package io.everytech.bireinvoice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The EIS API client: authentication, invoice issuance, and inquiry. Calls block, so run them
 * off any UI thread. One client serialises its own calls, which also keeps it within the
 * sandbox's one-call-per-10-seconds rule (E19).
 *
 * It talks to nobody but [EisCredentials.baseUrl]. That URL must use https, except for a test
 * server on this computer (see [EisUrls.checkBaseUrl]).
 *
 * @param sandboxSpacingMillis minimum gap between calls. Null means 10,500 ms when the base URL
 *   is [EisCodes.SANDBOX_BASE_URL] and 0 otherwise.
 * @throws IllegalArgumentException when a credential is blank, or the base URL breaks the rule of
 *   [EisUrls.checkBaseUrl].
 */
public class EisClient @JvmOverloads constructor(
    private val credentials: EisCredentials,
    private val transport: EisTransport = JdkEisTransport(),
    private val clock: Clock = Clock.systemUTC(),
    sandboxSpacingMillis: Long? = null,
) {
    private val baseUrl: String = credentials.baseUrl.trimEnd('/')
    private val spacingMillis: Long = sandboxSpacingMillis ?: if (baseUrl == EisCodes.SANDBOX_BASE_URL) 10_500 else 0
    private var lastCallNanos: Long? = null
    private var session: EisSession? = null

    init {
        val missing = credentials.missingFields()
        require(missing.isEmpty()) { "EIS credentials are missing ${missing.joinToString { "\"$it\"" }}" }
        EisUrls.checkBaseUrl(credentials.baseUrl)?.let { throw IllegalArgumentException(it) }
    }

    /** The session in use, if any. */
    public val currentSession: EisSession?
        @Synchronized get() = session

    private fun pace() {
        val last = lastCallNanos
        if (last != null && spacingMillis > 0) {
            val waitMillis = spacingMillis - (System.nanoTime() - last) / 1_000_000
            if (waitMillis > 0) Thread.sleep(waitMillis)
        }
        lastCallNanos = System.nanoTime()
    }

    private fun headers(method: String, path: String, hmacKey: String, authToken: String?): Map<String, String> {
        val datetime = EisCrypto.manilaDatetime(clock.instant())
        val headers = linkedMapOf(
            "accreditationId" to credentials.eisCertNumber,
            "applicationId" to credentials.applicationId,
            "datetime" to datetime,
            "authorization" to EisCrypto.hmacAuthorization(hmacKey, datetime, method, path),
            "Content-Type" to "application/json; charset=utf-8",
        )
        if (authToken != null) headers["authToken"] = authToken
        return headers
    }

    private fun call(method: String, path: String, hmacKey: String, authToken: String?, body: JsonObject?): JsonObject {
        try {
            pace()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EisException("NETWORK", "interrupted")
        }
        val response = try {
            transport.execute(method, baseUrl + path, headers(method, path, hmacKey, authToken), body?.toString())
        } catch (e: IOException) {
            throw EisException("NETWORK", e.message ?: e.toString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw EisException("NETWORK", "interrupted")
        }
        val json = try {
            responseJson.parseToJsonElement(response.body) as JsonObject
        } catch (e: Exception) {
            throw EisException("HTTP", "${response.status} ${response.body.take(200)}", response.status)
        }
        if ((json["status"] as? JsonPrimitive)?.content != "1") {
            val details = json["errorDetails"] as? JsonObject
            val code = (details?.get("errorCode") as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: "HTTP${response.status}"
            val message = (details?.get("errorMessage") as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
                ?: EisCodes.requestErrors[code] ?: "request failed"
            throw EisException(code, message, response.status)
        }
        return json
    }

    private fun decrypt(key: String, data: String?, what: String): JsonObject {
        if (data == null) throw EisException("RESPONSE", "$what response has no data")
        return try {
            responseJson.parseToJsonElement(EisCrypto.aesDecrypt(key, data)) as JsonObject
        } catch (e: Exception) {
            throw EisException("RESPONSE", "could not read the $what response: ${e.message}")
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun sessionValid(): Boolean {
        val s = session ?: return false
        // Renew five minutes early so a long batch never runs on a token that dies mid-call.
        return s.expiresAtEpochMillis - 5 * 60_000 > clock.millis()
    }

    /** Returns the current session, or signs in. A session is reused until 5 minutes before it expires. */
    @Synchronized
    @JvmOverloads
    @Throws(EisException::class)
    public fun authenticate(force: Boolean = false): EisSession {
        if (!force && sessionValid()) return session!!
        val authKey = EisCrypto.newAuthKey()
        val secret = buildJsonObject {
            put("userId", credentials.userId)
            put("password", credentials.password)
            put("authKey", authKey)
        }.toString()
        val data = try {
            EisCrypto.rsaEncrypt(credentials.eisPublicKey, secret)
        } catch (e: Exception) {
            throw EisException("KEY", "the EIS public key could not be used: ${e.message}")
        }
        val json = call(
            "POST",
            "/api/authentication",
            credentials.applicationKey,
            null,
            buildJsonObject {
                put("data", data)
                put("forceRefreshToken", force)
            },
        )
        val result = decrypt(authKey, json.string("data"), "authentication")
        val tokenExpiry = result.string("tokenExpiry") ?: ""
        // tokenExpiry is yyyy-MM-ddTHH:mm:ss with no zone; EIS runs on Philippine time.
        val expiresAt = try {
            LocalDateTime.parse(tokenExpiry).toInstant(ZoneOffset.ofHours(8)).toEpochMilli()
        } catch (e: Exception) {
            clock.millis() + 6 * 3_600_000
        }
        val authToken = result.string("authToken") ?: throw EisException("RESPONSE", "authentication response has no authToken")
        val sessionKey = result.string("sessionKey") ?: throw EisException("RESPONSE", "authentication response has no sessionKey")
        return EisSession(authToken, sessionKey, tokenExpiry, expiresAt).also { session = it }
    }

    /** Signs one serialized CAS invoice as an RS256 JWS with the seller's signing key. */
    public fun signInvoice(invoiceJson: String): String =
        EisCrypto.jwsSign(credentials.signingKeyId, credentials.signingPrivateKey, invoiceJson)

    /**
     * Sends 1 to 100 serialized CAS invoices in one transmission and returns the acknowledgement.
     * The submit ID is fixed before the call, so a retry after an expired token (E03) resends
     * the same submission. Pass your own [submitId] to make a retry of a whole call idempotent.
     */
    @Synchronized
    @JvmOverloads
    @Throws(EisException::class)
    public fun submit(invoiceJsons: List<String>, submitId: String? = null): SubmitAck {
        require(invoiceJsons.size in 1..100) { "submit takes 1 to 100 invoices" }
        val id = submitId ?: EisIds.submitId(credentials.eisCertNumber, EisCrypto.manilaDatetime(clock.instant()).take(8))
        val jwsList = invoiceJsons.joinToString(",") { signInvoice(it) }
        return withSession { s ->
            val json = call(
                "POST",
                "/api/invoices",
                s.sessionKey,
                s.authToken,
                buildJsonObject {
                    put("submitId", id)
                    put("data", EisCrypto.aesEncrypt(s.sessionKey, jwsList))
                },
            )
            val ack = decrypt(s.sessionKey, json.string("data"), "issuance")
            SubmitAck(
                submitId = id,
                ackId = ack.string("ackId") ?: "",
                responseDtm = ack.string("responseDtm") ?: "",
                description = ack.string("description") ?: "",
            )
        }
    }

    /** Asks for the processing result of a transmission. Poll until processStatusCode is not 02. */
    @Synchronized
    @Throws(EisException::class)
    public fun inquire(submitId: String): InquiryResult = withSession { s ->
        val path = "/api/invoice_result/${encodePathSegment(submitId)}"
        val json = call("GET", path, s.sessionKey, s.authToken, null)
        // The guide and the official sample return this one unencrypted; accept either.
        val data = when (val element = json["data"]) {
            is JsonObject -> element
            is JsonPrimitive -> decrypt(s.sessionKey, element.content, "inquiry")
            else -> throw EisException("RESPONSE", "inquiry response has no data")
        }
        try {
            responseJson.decodeFromJsonElement(InquiryResult.serializer(), data)
        } catch (e: Exception) {
            throw EisException("RESPONSE", "could not read the inquiry response: ${e.message}")
        }
    }

    private fun <T> withSession(work: (EisSession) -> T): T {
        val s = authenticate()
        return try {
            work(s)
        } catch (e: EisException) {
            if (e.code != "E03") throw e
            work(authenticate(force = true))
        }
    }

    private companion object {
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

        // encodeURIComponent, so the signed path matches what other EIS clients sign.
        fun encodePathSegment(text: String): String = buildString {
            for (byte in text.toByteArray(StandardCharsets.UTF_8)) {
                val c = byte.toInt() and 0xff
                if (c < 0x80 && c.toChar() in UNRESERVED) append(c.toChar()) else append("%%%02X".format(c))
            }
        }
    }
}
