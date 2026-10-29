package io.everytech.bireinvoice

import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EisCryptoTest {
    private val key = "abcdefghijklmnopqrstuvwxyz012345"

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicDer = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val privateDer = Base64.getEncoder().encodeToString(keyPair.private.encoded)

    private fun pem(label: String, der: String) =
        "-----BEGIN $label-----\n" + der.chunked(64).joinToString("\n") + "\n-----END $label-----\n"

    @Test
    fun aesMatchesAManualJavaxComputation() {
        // The guide's construction: AES/CBC/PKCS5Padding, key = 32 UTF-8 bytes, IV = first 16 characters of the key.
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(key.substring(0, 16).toByteArray()))
        val manual = Base64.getEncoder().encodeToString(cipher.doFinal("hello".toByteArray()))
        assertEquals(manual, EisCrypto.aesEncrypt(key, "hello"))
        assertEquals("hello", EisCrypto.aesDecrypt(key, manual))
    }

    @Test
    fun aesMatchesVectorsFromNodeAndOpenSsl() {
        // Produced independently by Node's crypto (and, for the first, the openssl command line).
        assertEquals("w4VnNEj90e+VFcRRxl0NKg==", EisCrypto.aesEncrypt(key, "hello"))
        val text = """{"userId":"u","x":"₱1,234.50"}"""
        assertEquals("Mm6WH4rmV8/W+dizcYKeRiQvmlxzbR2TQtODZo9qt/rwlDEAXsYN3n6Ra/Epwhgo", EisCrypto.aesEncrypt(key, text))
        assertEquals(text, EisCrypto.aesDecrypt(key, "Mm6WH4rmV8/W+dizcYKeRiQvmlxzbR2TQtODZo9qt/rwlDEAXsYN3n6Ra/Epwhgo"))
    }

    @Test
    fun aesKeyMustBe32Bytes() {
        assertFailsWith<IllegalArgumentException> { EisCrypto.aesEncrypt("short", "x") }
        // 31 ASCII characters plus one 2-byte character is 33 bytes.
        assertFailsWith<IllegalArgumentException> { EisCrypto.aesEncrypt("abcdefghijklmnopqrstuvwxyz01234é", "x") }
    }

    @Test
    fun hmacMatchesTheGuideConstruction() {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("X8TD8UUEDTK7".toByteArray(), "HmacSHA256"))
        val raw = Base64.getEncoder().encodeToString(mac.doFinal("20220217093015POST/api/authentication".toByteArray()))
        val header = EisCrypto.hmacAuthorization("X8TD8UUEDTK7", "20220217093015", "post", "/api/authentication")
        assertEquals("Bearer $raw", header)
        assertEquals("Bearer xRp3k5Bg94iRomz+bvE3+HpMl+sEIi9rq8crJK3MZJg=", header)
    }

    @Test
    fun rsaRoundTripWithDerAndPem() {
        assertEquals("""{"a":1}""", EisCrypto.rsaDecrypt(privateDer, EisCrypto.rsaEncrypt(publicDer, """{"a":1}""")))
        val pemPublic = pem("PUBLIC KEY", publicDer)
        val pemPrivate = pem("PRIVATE KEY", privateDer)
        assertEquals("₱ ok", EisCrypto.rsaDecrypt(pemPrivate, EisCrypto.rsaEncrypt(pemPublic, "₱ ok")))
        // PKCS#1 v1.5 is randomised: the same plaintext encrypts differently each time.
        assertTrue(EisCrypto.rsaEncrypt(publicDer, "x") != EisCrypto.rsaEncrypt(publicDer, "x"))
    }

    @Test
    fun readsTheBirSandboxPublicKey() {
        val bir = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAgMbSxoPRLi4P98qbfdFvwYCEf6l2QcKHhyE+m7Fh8OSqKqQFWud0+SSqydzYZzQYZIQ0hwZ/" +
            "Vvd6StsEY80O7XC6ELVZ052s91PjAlh38TSzmJGy8ZZUYLsg8S2DzKaCpQ0ZmvphYf0ZB8ZoOXBTVPpg4cGBVbMZLdTtnXYxSegXhog6XBsIkAXmAWHwzJ0t6x0" +
            "NbMnsfbHvFlqtUrsbwBc4BD+0rO3lJHPbDO4HEiMmrlM/bD/hL4uKzXv3jeXCkDbQdYsZZgI7tglu2Al/jB8VdMDJRJjsQf0Z5Ye3FdOsqp1v3SF3ENns8F/0A8x" +
            "rrB/SuKcwO7Rvm2fjogoqqwIDAQAB"
        assertEquals(344, EisCrypto.rsaEncrypt(bir, "x").length)
    }

    @Test
    fun jwsSignAndVerify() {
        val payload = """{"x":"₱1"}"""
        val jws = EisCrypto.jwsSign("Akeypair00", privateDer, payload)
        val (header, body, _) = jws.split(".")
        assertEquals("""{"kid":"Akeypair00","alg":"RS256"}""", String(Base64.getUrlDecoder().decode(header)))
        assertFalse('=' in jws || '+' in jws || '/' in jws)
        assertEquals(payload, String(Base64.getUrlDecoder().decode(body)))
        assertEquals(JwsVerification(true, "Akeypair00", payload), EisCrypto.jwsVerify(jws, publicDer))
        // RS256 is deterministic, so the same key and payload always give the same token.
        assertEquals(jws, EisCrypto.jwsSign("Akeypair00", pem("PRIVATE KEY", privateDer), payload))
    }

    @Test
    fun jwsTamperingIsDetected() {
        val jws = EisCrypto.jwsSign("Akeypair00", privateDer, """{"x":"P1"}""")
        val (h, _, s) = jws.split(".")
        val forged = "$h.${Base64.getUrlEncoder().withoutPadding().encodeToString("""{"x":"P2"}""".toByteArray())}.$s"
        val result = EisCrypto.jwsVerify(forged, publicDer)
        assertFalse(result.ok)
        assertEquals("""{"x":"P2"}""", result.payload)

        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        assertFalse(EisCrypto.jwsVerify(jws, Base64.getEncoder().encodeToString(other.public.encoded)).ok)

        val noneAlg = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"kid":"Akeypair00","alg":"none"}""".toByteArray())
        assertFalse(EisCrypto.jwsVerify("$noneAlg.${jws.substringAfter('.')}", publicDer).ok)

        val notJws = EisCrypto.jwsVerify("abc", publicDer)
        assertFalse(notJws.ok)
        assertNull(notJws.payload)
        assertFalse(EisCrypto.jwsVerify("$h.${jws.split(".")[1]}.!!!", publicDer).ok)
    }

    @Test
    fun manilaDatetimeIsUtcPlus8() {
        assertEquals("20261030000509", EisCrypto.manilaDatetime(Instant.parse("2026-10-29T16:05:09Z")))
        assertEquals("20261029110000", EisCrypto.manilaDatetime(Instant.parse("2026-10-29T03:00:00Z")))
    }

    @Test
    fun authKeysAre32CharactersFromTheGuideAlphabet() {
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz@#$%^&*()"
        val keys = List(50) { EisCrypto.newAuthKey() }
        keys.forEach { k ->
            assertEquals(32, k.length)
            assertTrue(k.all { it in alphabet }, k)
        }
        assertEquals(50, keys.toSet().size)
    }
}
