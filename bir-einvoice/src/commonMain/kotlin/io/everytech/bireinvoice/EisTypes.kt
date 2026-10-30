package io.everytech.bireinvoice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Everything one seller needs to call EIS, from the EIS Certification Portal: the EIS Cert
 * Number (accreditationId), the application ID and key, the portal user, the EIS public key, and
 * the seller's own JWS signing key pair (private key as PKCS#8, base64 DER or PEM).
 *
 * [toString] never prints a secret.
 */
@Serializable
public data class EisCredentials @JvmOverloads constructor(
    val eisCertNumber: String,
    val applicationId: String,
    val applicationKey: String,
    val userId: String,
    val password: String,
    val eisPublicKey: String,
    val signingKeyId: String,
    val signingPrivateKey: String,
    val baseUrl: String = EisCodes.SANDBOX_BASE_URL,
) {
    /** Names of the fields that are blank. Empty when the credentials are complete. */
    public fun missingFields(): List<String> = listOf(
        "eisCertNumber" to eisCertNumber,
        "applicationId" to applicationId,
        "applicationKey" to applicationKey,
        "userId" to userId,
        "password" to password,
        "eisPublicKey" to eisPublicKey,
        "signingKeyId" to signingKeyId,
        "signingPrivateKey" to signingPrivateKey,
        "baseUrl" to baseUrl,
    ).filter { it.second.isBlank() }.map { it.first }

    override fun toString(): String =
        "EisCredentials(eisCertNumber=$eisCertNumber, applicationId=$applicationId, applicationKey=${mask(applicationKey)}, " +
            "userId=$userId, password=${mask(password)}, eisPublicKey=${if (eisPublicKey.isBlank()) "" else "(set)"}, " +
            "signingKeyId=$signingKeyId, signingPrivateKey=${mask(signingPrivateKey)}, baseUrl=$baseUrl)"

    public fun toJson(): String = credentialsJson.encodeToString(serializer(), this)

    public companion object {
        private fun mask(secret: String): String = if (secret.isEmpty()) "" else "****"

        /** Reads the JSON shape [toJson] writes. Unknown keys are ignored. */
        @JvmStatic
        public fun fromJson(text: String): EisCredentials = credentialsJson.decodeFromString(serializer(), text)
    }
}

private val credentialsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/**
 * A failed EIS call. [code] is BIR's code (E01 to E99, see [EisCodes.requestErrors]) when EIS
 * returned one, `HTTP` or `HTTP<status>` for a response that is not EIS JSON, `NETWORK` when no
 * response arrived, and `RESPONSE` when a response could not be decrypted or read.
 */
public class EisException @JvmOverloads constructor(
    public val code: String,
    message: String,
    public val httpStatus: Int? = null,
) : Exception("$code: $message")

/** An authenticated session. Tokens last 6 hours; the client renews 5 minutes early. */
public data class EisSession(
    val authToken: String,
    val sessionKey: String,
    val tokenExpiry: String,
    val expiresAtEpochMillis: Long,
) {
    override fun toString(): String = "EisSession(tokenExpiry=$tokenExpiry, expiresAtEpochMillis=$expiresAtEpochMillis)"
}

/** The acknowledgement EIS returns for a transmission. Final results come from inquiry. */
public data class SubmitAck(
    val submitId: String,
    val ackId: String,
    val responseDtm: String,
    val description: String,
)

@Serializable
public data class ProcessedDocument(
    val invoiceUid: String,
    val resultStatusCode: String,
    val description: String? = null,
)

/** The inquiry response. [processStatusCode] is 01 done, 02 in progress, 03 cannot process. */
@Serializable
public data class InquiryResult(
    val accreditationId: String = "",
    val userId: String = "",
    val refSubmitId: String = "",
    val ackId: String = "",
    val responseDtm: String = "",
    val processStatusCode: String,
    val failReasonStatusCode: String? = null,
    val totalCountQuantity: Int? = null,
    val successCountQuantity: Int? = null,
    val failCountQuantity: Int? = null,
    val processedDocuments: List<ProcessedDocument> = emptyList(),
)

public data class JwsVerification(val ok: Boolean, val kid: String?, val payload: String?)

public data class EisHttpResponse(val status: Int, val body: String)

/** One HTTP exchange. Swap it to add logging, a proxy, or a fake in tests. */
public fun interface EisTransport {
    public fun execute(method: String, url: String, headers: Map<String, String>, body: String?): EisHttpResponse
}
