package io.everytech.bireinvoice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The EIS transport crypto, exactly as the BIR API Development Guide v2.3 and its Java, Python
 * and C# samples do it. Some choices (an IV taken from the key, RSA PKCS#1 v1.5) are BIR's
 * protocol, not a recommendation, and these keys should be used for nothing else.
 */
public object EisCrypto {
    private const val AUTH_KEY_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz@#$%^&*()"
    private val random = SecureRandom()
    private val MANILA: ZoneOffset = ZoneOffset.ofHours(8)
    private val MANILA_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(MANILA)

    internal fun randomString(length: Int, alphabet: String): String =
        buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    /** The 32-character temporary secret the authentication response is encrypted with. */
    @JvmStatic
    public fun newAuthKey(): String = randomString(32, AUTH_KEY_ALPHABET)

    private fun aesCipher(mode: Int, key: String): Cipher {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        require(keyBytes.size == 32) { "EIS AES key must be 32 bytes, got ${keyBytes.size}" }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes.copyOf(16)))
        }
    }

    /** AES-256-CBC, PKCS#5/7 padding, IV = the first 16 bytes of the key, base64 out. */
    @JvmStatic
    public fun aesEncrypt(key: String, plaintext: String): String =
        Base64.getEncoder().encodeToString(aesCipher(Cipher.ENCRYPT_MODE, key).doFinal(plaintext.toByteArray(Charsets.UTF_8)))

    @JvmStatic
    public fun aesDecrypt(key: String, base64: String): String =
        String(aesCipher(Cipher.DECRYPT_MODE, key).doFinal(Base64.getMimeDecoder().decode(base64)), Charsets.UTF_8)

    private fun derOf(key: String): ByteArray {
        val body = key.lines().filterNot { it.trim().startsWith("-----") }.joinToString("")
        return Base64.getMimeDecoder().decode(body.filterNot { it.isWhitespace() })
    }

    /** Reads a PEM `PUBLIC KEY` or the bare base64 SPKI DER the EIS portal hands out. */
    @JvmStatic
    public fun publicKey(key: String): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(derOf(key)))

    /** Reads a PEM `PRIVATE KEY` or bare base64 PKCS#8 DER. */
    @JvmStatic
    public fun privateKey(key: String): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(derOf(key)))

    /** RSA/ECB/PKCS1Padding, what the guide's `Cipher.getInstance("RSA")` means. */
    @JvmStatic
    public fun rsaEncrypt(publicKey: String, plaintext: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, publicKey(publicKey)) }
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    @JvmStatic
    public fun rsaDecrypt(privateKey: String, base64: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, privateKey(privateKey)) }
        return String(cipher.doFinal(Base64.getMimeDecoder().decode(base64)), Charsets.UTF_8)
    }

    /** The authorization header: `Bearer ` + base64(HmacSHA256(key, datetime + METHOD + path)). */
    @JvmStatic
    public fun hmacAuthorization(secretKey: String, datetime: String, method: String, path: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        val raw = mac.doFinal("$datetime${method.uppercase()}$path".toByteArray(Charsets.UTF_8))
        return "Bearer " + Base64.getEncoder().encodeToString(raw)
    }

    /** yyyyMMddHHmmss in Philippine time (UTC+8, no daylight saving), whatever the local zone. */
    @JvmStatic
    public fun manilaDatetime(instant: Instant): String = MANILA_FORMAT.format(instant)

    private val b64url = Base64.getUrlEncoder().withoutPadding()

    /** JWS compact serialization, RS256, header `{"kid":..,"alg":"RS256"}` as the guide's Nimbus sample emits it. */
    @JvmStatic
    public fun jwsSign(keyId: String, privateKey: String, payload: String): String {
        val header = buildJsonObject {
            put("kid", keyId)
            put("alg", "RS256")
        }.toString()
        val signingInput = b64url.encodeToString(header.toByteArray(Charsets.UTF_8)) + "." +
            b64url.encodeToString(payload.toByteArray(Charsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey(privateKey))
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return signingInput + "." + b64url.encodeToString(signature)
    }

    /** Checks an RS256 compact JWS. [JwsVerification.kid] and payload are filled in even when the signature fails. */
    @JvmStatic
    public fun jwsVerify(jws: String, publicKey: String): JwsVerification = jwsVerify(jws, publicKey(publicKey))

    @JvmStatic
    public fun jwsVerify(jws: String, publicKey: PublicKey): JwsVerification {
        val parts = jws.split(".")
        if (parts.size != 3) return JwsVerification(false, null, null)
        val (header, body, signature) = parts
        val decoder = Base64.getUrlDecoder()
        val headerJson = try {
            responseJson.parseToJsonElement(String(decoder.decode(header), Charsets.UTF_8)) as JsonObject
        } catch (e: Exception) {
            return JwsVerification(false, null, null)
        }
        val kid = (headerJson["kid"] as? JsonPrimitive)?.content
        val payload = try {
            String(decoder.decode(body), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return JwsVerification(false, kid, null)
        }
        if ((headerJson["alg"] as? JsonPrimitive)?.content != "RS256") return JwsVerification(false, kid, payload)
        val ok = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("$header.$body".toByteArray(Charsets.US_ASCII))
                verify(decoder.decode(signature))
            }
        } catch (e: Exception) {
            false
        }
        return JwsVerification(ok, kid, payload)
    }
}
