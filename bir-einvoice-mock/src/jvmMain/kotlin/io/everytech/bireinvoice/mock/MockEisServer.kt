package io.everytech.bireinvoice.mock

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.everytech.bireinvoice.EisCredentials
import io.everytech.bireinvoice.EisCrypto
import io.everytech.bireinvoice.EisException
import io.everytech.bireinvoice.InquiryResult
import io.everytech.bireinvoice.ProcessedDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A local stand-in for the BIR EIS API, built from the v2.3 guide. It checks what the real one
 * checks (headers, clock, HMAC, the RSA and AES layers, JWS signatures, schema, formulas,
 * duplicate IDs) so a client can be tested before anyone has sandbox credentials. Passing here is
 * necessary, not sufficient: the real sandbox has the final word.
 *
 * The keys and credentials are generated per instance and exist only in memory. The processing
 * behind the transport (registration, results, the checks after the signature) is [MockEis].
 *
 * @param processingPolls how many inquiries answer "in progress" (02) before the result is ready.
 */
public class MockEisServer @JvmOverloads constructor(
    private val processingPolls: Int = 1,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val eisKeys: KeyPair = rsaKeyPair()
    private val signingKeys: KeyPair = rsaKeyPair()
    private val lock = Any()
    private val sessions = HashMap<String, Session>()
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null
    private var baseUrl: String = ""

    private val eis = MockEis(processingPolls = processingPolls, nowMillis = clock::millis)

    private val baseCredentials = EisCredentials(
        eisCertNumber = eis.eisCertNumber,
        applicationId = randomString(8, UPPER_DIGITS),
        applicationKey = randomString(12, UPPER_DIGITS),
        userId = eis.userId,
        password = randomString(16, KEY_CHARS),
        eisPublicKey = Base64.getEncoder().encodeToString(eisKeys.public.encoded),
        signingKeyId = "Akeypair00",
        signingPrivateKey = Base64.getEncoder().encodeToString(signingKeys.private.encoded),
        baseUrl = "",
    )

    /** Credentials that work against this server. [EisCredentials.baseUrl] is set once [start] returns. */
    public val credentials: EisCredentials
        get() = baseCredentials.copy(baseUrl = baseUrl)

    /** EisUniqueIds accepted so far. */
    public val registered: Set<String>
        get() = synchronized(lock) { eis.registered }

    /** Starts listening on 127.0.0.1 and returns the base URL, like `http://127.0.0.1:41234`. */
    @JvmOverloads
    public fun start(port: Int = 0): String = start(port, "127.0.0.1")

    public fun start(port: Int, host: String): String {
        check(server == null) { "already started" }
        val http = HttpServer.create(InetSocketAddress(host, port), 0)
        val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "mock-eis").apply { isDaemon = true } }
        http.executor = pool
        http.createContext("/") { exchange -> exchange.use { handle(it) } }
        http.start()
        server = http
        executor = pool
        baseUrl = "http://$host:${http.address.port}"
        return baseUrl
    }

    override fun close() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    private class Session(val sessionKey: String, val expiresAt: Long)

    private class Reply(val status: Int, val body: JsonObject)

    private fun fail(code: String, message: String): Reply = Reply(
        STATUS_FOR[code] ?: 400,
        buildJsonObject {
            put("status", "0")
            putJsonObject("errorDetails") {
                put("errorCode", code)
                put("errorMessage", message)
            }
        },
    )

    private fun ok(data: JsonElement): Reply = Reply(200, buildJsonObject { put("status", "1"); put("data", data) })

    private fun ok(data: String): Reply = ok(JsonPrimitive(data))

    private fun handle(exchange: HttpExchange) {
        val reply = try {
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val method = exchange.requestMethod
            val path = exchange.requestURI.rawPath
            synchronized(lock) {
                val match = INQUIRY_PATH.matchEntire(path)
                when {
                    method == "POST" && path == "/api/authentication" -> authenticate(exchange, body)
                    method == "POST" && path == "/api/invoices" -> issue(exchange, body)
                    method == "GET" && match != null ->
                        inquire(exchange, URLDecoder.decode(match.groupValues[1].replace("+", "%2B"), Charsets.UTF_8), path)
                    else -> fail("E11", "Unknown endpoint")
                }
            }
        } catch (e: Exception) {
            fail("E99", e.message ?: e.toString())
        }
        val bytes = reply.body.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun header(exchange: HttpExchange, name: String): String? = exchange.requestHeaders.getFirst(name)

    private fun checkHeaders(exchange: HttpExchange, path: String, hmacKey: String): Reply? {
        if (header(exchange, "accreditationId") != baseCredentials.eisCertNumber) return fail("E01", "Invalid accreditationId")
        if (header(exchange, "applicationId") != baseCredentials.applicationId) return fail("E02", "Invalid applicationId")
        val datetime = header(exchange, "datetime")
        val at = parseManila(datetime)
        if (at == null || abs(at - clock.millis()) > SKEW_MINUTES * 60_000) return fail("E05", "Invalid Datetime")
        val expected = EisCrypto.hmacAuthorization(hmacKey, datetime!!, exchange.requestMethod, path)
        if (!constantTimeEquals(header(exchange, "authorization"), expected)) return fail("E04", "Invalid HMAC Signature")
        return null
    }

    private fun parseJsonObject(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun authenticate(exchange: HttpExchange, body: String): Reply {
        checkHeaders(exchange, "/api/authentication", baseCredentials.applicationKey)?.let { return it }
        val request = parseJsonObject(body) ?: return fail("E11", "Invalid Request Body JSON")
        val plain = try {
            EisCrypto.rsaDecrypt(Base64.getEncoder().encodeToString(eisKeys.private.encoded), request.string("data")!!)
        } catch (e: Exception) {
            return fail("E21", "It could not decrypt the \"data\" field")
        }
        val creds = parseJsonObject(plain) ?: return fail("E22", "Invalid JSON data in the \"data\" field")
        val userId = creds.string("userId")
        if (userId != baseCredentials.userId || !constantTimeEquals(creds.string("password"), baseCredentials.password)) {
            return fail("E23", "Invalid User ID or Password")
        }
        val authKey = creds.string("authKey")
        if (authKey == null || authKey.toByteArray(Charsets.UTF_8).size != 32) return fail("E24", "Invalid authKey")
        val authToken = randomString(32, UPPER_DIGITS)
        val sessionKey = randomString(32, KEY_CHARS)
        val expiry = clock.millis() + 6 * 3_600_000
        sessions[authToken] = Session(sessionKey, expiry)
        val result = buildJsonObject {
            put("accreditationId", baseCredentials.eisCertNumber)
            put("userId", userId)
            put("authToken", authToken)
            put("sessionKey", sessionKey)
            put("tokenExpiry", MANILA_ISO.format(Instant.ofEpochMilli(expiry)))
        }
        return ok(EisCrypto.aesEncrypt(authKey, result.toString()))
    }

    private fun session(exchange: HttpExchange, path: String): Pair<Session?, Reply?> {
        val s = sessions[header(exchange, "authToken")]
        if (s == null || s.expiresAt < clock.millis()) return null to fail("E03", "Invalid authToken")
        checkHeaders(exchange, path, s.sessionKey)?.let { return null to it }
        return s to null
    }

    private fun issue(exchange: HttpExchange, body: String): Reply {
        val (s, error) = session(exchange, "/api/invoices")
        if (s == null) return error!!
        val request = parseJsonObject(body) ?: return fail("E11", "Invalid Request Body JSON")
        val submitId = request.string("submitId") ?: return fail("E11", "Invalid submitId")
        try {
            eis.checkSubmitId(submitId)
        } catch (e: EisException) {
            return fail(e.code, e.message.orEmpty().substringAfter(": "))
        }
        val plain = try {
            EisCrypto.aesDecrypt(s.sessionKey, request.string("data")!!)
        } catch (e: Exception) {
            return fail("E21", "It could not decrypt the \"data\" field")
        }
        val jwsList = plain.split(",")
        val ack = try {
            eis.accept(submitId, jwsList.size) { jwsList.map { check(it) } }
        } catch (e: EisException) {
            return fail(e.code, e.message.orEmpty().substringAfter(": "))
        }
        val result = buildJsonObject {
            put("accreditationId", baseCredentials.eisCertNumber)
            put("userId", baseCredentials.userId)
            put("refSubmitId", ack.submitId)
            put("ackId", ack.ackId)
            put("responseDtm", ack.responseDtm)
            put("description", ack.description)
        }
        return ok(EisCrypto.aesEncrypt(s.sessionKey, result.toString()))
    }

    // The signature layer; everything after it is MockEis.check.
    private fun check(jws: String): ProcessedDocument {
        val verified = EisCrypto.jwsVerify(jws, signingKeys.public)
        val payloadText = verified.payload ?: "{}"
        val payload = try {
            Json.parseToJsonElement(payloadText)
        } catch (e: Exception) {
            return ProcessedDocument("", "SYN004", "payload is not JSON")
        }
        val uid = (payload as? JsonObject)?.string("EisUniqueId") ?: ""
        if (!verified.ok || verified.kid != baseCredentials.signingKeyId) return ProcessedDocument(uid, "SYN002", "Invalid signature")
        return eis.check(payloadText, uid)
    }

    private fun inquire(exchange: HttpExchange, submitId: String, path: String): Reply {
        val (s, error) = session(exchange, path)
        if (s == null) return error!!
        val result = try {
            eis.inquire(submitId)
        } catch (e: EisException) {
            return fail(e.code, e.message.orEmpty().substringAfter(": "))
        }
        return ok(inquiryJson.encodeToJsonElement(InquiryResult.serializer(), result))
    }

    private companion object {
        const val UPPER_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val KEY_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz@#$%^&*()"
        const val SKEW_MINUTES = 15
        val STATUS_FOR = mapOf("E01" to 404, "E02" to 404, "E03" to 401, "E04" to 401, "E15" to 404)
        val INQUIRY_PATH = Regex("/api/invoice_result/([^/]+)")
        val inquiryJson = Json { explicitNulls = false }
        val MANILA: ZoneOffset = ZoneOffset.ofHours(8)
        val MANILA_ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(MANILA)
        val DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val random = SecureRandom()

        fun randomString(length: Int, alphabet: String): String =
            buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

        // For secrets (the HMAC, the password). MessageDigest.isEqual takes the same time wherever
        // the first difference is, so response times do not reveal the expected value byte by byte.
        fun constantTimeEquals(actual: String?, expected: String): Boolean =
            actual != null && MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

        fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        fun parseManila(datetime: String?): Long? {
            if (datetime == null || !Regex("\\d{14}").matches(datetime)) return null
            return try {
                LocalDateTime.parse(datetime, DATETIME).toInstant(MANILA).toEpochMilli()
            } catch (e: Exception) {
                null
            }
        }
    }
}
