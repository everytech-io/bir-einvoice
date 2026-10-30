package io.everytech.bireinvoice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The client against a scripted transport, to pin down headers, retries and error mapping. */
class EisClientTest {
    private val eisKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val signingKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val eisPrivate = Base64.getEncoder().encodeToString(eisKeys.private.encoded)
    private val clock = Clock.fixed(Instant.parse("2026-10-29T03:00:00Z"), ZoneOffset.UTC)

    private val credentials = EisCredentials(
        eisCertNumber = "20AB12CD",
        applicationId = "APP00001",
        applicationKey = "APPKEY000001",
        userId = "user",
        password = "secret-password",
        eisPublicKey = Base64.getEncoder().encodeToString(eisKeys.public.encoded),
        signingKeyId = "Akeypair00",
        signingPrivateKey = Base64.getEncoder().encodeToString(signingKeys.private.encoded),
        baseUrl = "https://eis.example.test/",
    )

    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    /** Answers authentication properly and hands everything else to [other]. */
    private inner class FakeEis(val other: (Call, String) -> EisHttpResponse) : EisTransport {
        val calls = mutableListOf<Call>()
        var sessions = 0

        override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): EisHttpResponse {
            val call = Call(method, url, headers, body)
            calls += call
            if (url.endsWith("/api/authentication")) {
                sessions += 1
                val data = Json.parseToJsonElement(body!!).jsonObject["data"]!!.jsonPrimitive.content
                val secret = Json.parseToJsonElement(EisCrypto.rsaDecrypt(eisPrivate, data)).jsonObject
                val authKey = secret["authKey"]!!.jsonPrimitive.content
                val result = """{"authToken":"TOKEN$sessions","sessionKey":"${"k".repeat(31)}$sessions","tokenExpiry":"2026-10-29T17:00:00"}"""
                return EisHttpResponse(200, """{"status":"1","data":"${EisCrypto.aesEncrypt(authKey, result)}"}""")
            }
            return other(call, "${"k".repeat(31)}$sessions")
        }
    }

    private fun ok(data: String) = EisHttpResponse(200, """{"status":"1","data":"$data"}""")

    private fun error(status: Int, code: String, message: String = "") =
        EisHttpResponse(status, """{"status":"0","errorDetails":{"errorCode":"$code","errorMessage":"$message"}}""")

    @Test
    fun authenticationSendsTheGuideHeadersAndBody() {
        val fake = FakeEis { _, _ -> error(500, "E99") }
        val session = EisClient(credentials, fake, clock).authenticate()
        assertEquals("TOKEN1", session.authToken)
        assertEquals("2026-10-29T17:00:00", session.tokenExpiry)
        assertEquals(Instant.parse("2026-10-29T09:00:00Z").toEpochMilli(), session.expiresAtEpochMillis)

        val call = fake.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://eis.example.test/api/authentication", call.url)
        assertEquals("20AB12CD", call.headers["accreditationId"])
        assertEquals("APP00001", call.headers["applicationId"])
        assertEquals("20261029110000", call.headers["datetime"])
        assertEquals(EisCrypto.hmacAuthorization("APPKEY000001", "20261029110000", "POST", "/api/authentication"), call.headers["authorization"])
        assertEquals("application/json; charset=utf-8", call.headers["Content-Type"])
        assertFalse("authToken" in call.headers)

        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("false", body["forceRefreshToken"]!!.jsonPrimitive.content)
        val secret = Json.parseToJsonElement(EisCrypto.rsaDecrypt(eisPrivate, body["data"]!!.jsonPrimitive.content)).jsonObject
        assertEquals(listOf("userId", "password", "authKey"), secret.keys.toList())
        assertEquals("secret-password", secret["password"]!!.jsonPrimitive.content)
        assertEquals(32, secret["authKey"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun sessionIsReusedUntilFiveMinutesBeforeExpiry() {
        val fake = FakeEis { _, _ -> error(500, "E99") }
        val client = EisClient(credentials, fake, clock)
        val first = client.authenticate()
        assertSame(first, client.authenticate())
        assertEquals(1, fake.sessions)
        client.authenticate(force = true)
        assertEquals(2, fake.sessions)

        val late = EisClient(credentials, fake, Clock.fixed(Instant.parse("2026-10-29T08:56:00Z"), ZoneOffset.UTC))
        late.authenticate()
        late.authenticate()
        assertEquals(4, fake.sessions)
    }

    @Test
    fun submitEncryptsSignedInvoicesAndReturnsTheAck() {
        val invoice = EisJson.encode(Samples.vatable())
        val fake = FakeEis { call, sessionKey ->
            assertEquals("TOKEN1", call.headers["authToken"])
            assertEquals(EisCrypto.hmacAuthorization(sessionKey, "20261029110000", "POST", "/api/invoices"), call.headers["authorization"])
            val body = Json.parseToJsonElement(call.body!!).jsonObject
            val jwsList = EisCrypto.aesDecrypt(sessionKey, body["data"]!!.jsonPrimitive.content).split(",")
            assertEquals(2, jwsList.size)
            val verified = EisCrypto.jwsVerify(jwsList[0], Base64.getEncoder().encodeToString(signingKeys.public.encoded))
            assertEquals(JwsVerification(true, "Akeypair00", invoice), verified)
            val ack = """{"refSubmitId":"${body["submitId"]!!.jsonPrimitive.content}","ackId":"BIR-1","responseDtm":"2026-10-29T11:00:00","description":"ok","extra":1}"""
            ok(EisCrypto.aesEncrypt(sessionKey, ack))
        }
        val ack = EisClient(credentials, fake, clock).submit(listOf(invoice, invoice))
        assertTrue(Regex("20AB12CD-20261029-[0-9a-f]{12}").matches(ack.submitId), ack.submitId)
        assertEquals(SubmitAck(ack.submitId, "BIR-1", "2026-10-29T11:00:00", "ok"), ack)
    }

    @Test
    fun submitChecksTheBatchSize() {
        val client = EisClient(credentials, FakeEis { _, _ -> error(500, "E99") }, clock)
        assertFailsWith<IllegalArgumentException> { client.submit(emptyList()) }
        assertFailsWith<IllegalArgumentException> { client.submit(List(101) { "{}" }) }
    }

    @Test
    fun expiredTokenTriggersOneReauthenticationWithTheSameSubmitId() {
        val submitIds = mutableListOf<String>()
        val fake = FakeEis { call, sessionKey ->
            submitIds += Json.parseToJsonElement(call.body!!).jsonObject["submitId"]!!.jsonPrimitive.content
            if (call.headers["authToken"] == "TOKEN1") error(401, "E03", "Invalid authToken")
            else ok(EisCrypto.aesEncrypt(sessionKey, """{"ackId":"BIR-2","responseDtm":"","description":""}"""))
        }
        val ack = EisClient(credentials, fake, clock).submit(listOf("{}"))
        assertEquals("BIR-2", ack.ackId)
        assertEquals(2, fake.sessions)
        assertEquals(2, submitIds.size)
        assertEquals(submitIds[0], submitIds[1])
        assertEquals(ack.submitId, submitIds[0])
    }

    @Test
    fun inquiryAcceptsPlainAndEncryptedData() {
        val result = """{"refSubmitId":"S","processStatusCode":"01","totalCountQuantity":1,"successCountQuantity":1,"failCountQuantity":0,""" +
            """"processedDocuments":[{"invoiceUid":"U","resultStatusCode":"SUC001"}],"somethingNew":true}"""
        var encrypted = false
        val fake = FakeEis { call, sessionKey ->
            assertEquals("GET", call.method)
            assertEquals("https://eis.example.test/api/invoice_result/20AB12CD-20261029-abc%20def", call.url)
            assertEquals(null, call.body)
            val path = "/api/invoice_result/20AB12CD-20261029-abc%20def"
            assertEquals(EisCrypto.hmacAuthorization(sessionKey, "20261029110000", "GET", path), call.headers["authorization"])
            if (encrypted) ok(EisCrypto.aesEncrypt(sessionKey, result)) else EisHttpResponse(200, """{"status":"1","data":$result}""")
        }
        val client = EisClient(credentials, fake, clock)
        val plain = client.inquire("20AB12CD-20261029-abc def")
        encrypted = true
        val decrypted = client.inquire("20AB12CD-20261029-abc def")
        assertEquals(plain, decrypted)
        assertEquals("01", plain.processStatusCode)
        assertEquals(listOf(ProcessedDocument("U", "SUC001")), plain.processedDocuments)
    }

    @Test
    fun errorsAreMappedToEisException() {
        fun failing(response: EisHttpResponse) = EisClient(credentials, { _, _, _, _ -> response }, clock)

        val e23 = assertFailsWith<EisException> { failing(error(400, "E23", "Invalid User ID or Password")).authenticate() }
        assertEquals("E23", e23.code)
        assertEquals(400, e23.httpStatus)
        assertEquals("E23: Invalid User ID or Password", e23.message)

        val noMessage = assertFailsWith<EisException> { failing(error(400, "E19")).authenticate() }
        assertEquals("E19: ${EisCodes.requestErrors["E19"]}", noMessage.message)

        val html = assertFailsWith<EisException> { failing(EisHttpResponse(502, "<html>Bad gateway</html>")).authenticate() }
        assertEquals("HTTP", html.code)
        assertEquals(502, html.httpStatus)

        val bare = assertFailsWith<EisException> { failing(EisHttpResponse(500, """{"status":"0"}""")).authenticate() }
        assertEquals("HTTP500", bare.code)

        val network = assertFailsWith<EisException> {
            EisClient(credentials, { _, _, _, _ -> throw IOException("connection refused") }, clock).authenticate()
        }
        assertEquals("NETWORK", network.code)
    }

    @Test
    fun sandboxCallsAreSpacedOut() {
        val fake = FakeEis { _, _ -> error(400, "E15") }
        val client = EisClient(credentials, fake, clock, sandboxSpacingMillis = 300)
        val started = System.nanoTime()
        client.authenticate()
        assertFailsWith<EisException> { client.inquire("x") }
        assertFailsWith<EisException> { client.inquire("x") }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMillis >= 600, "took $elapsedMillis ms")
    }

    @Test
    fun credentialsHideSecretsAndReportBlanks() {
        val text = credentials.toString()
        for (secret in listOf(credentials.password, credentials.applicationKey, credentials.signingPrivateKey, credentials.eisPublicKey)) {
            assertFalse(secret in text, "secret leaked in $text")
        }
        assertTrue("20AB12CD" in text)
        assertEquals(emptyList(), credentials.missingFields())
        assertEquals(listOf("password", "signingKeyId"), credentials.copy(password = "", signingKeyId = " ").missingFields())
        assertFailsWith<IllegalArgumentException> { EisClient(credentials.copy(password = "")) }
        assertEquals(credentials, EisCredentials.fromJson(credentials.toJson()))
        val defaults = EisCredentials("a", "b", "c", "d", "e", "f", "g", "h")
        assertEquals(EisCodes.SANDBOX_BASE_URL, defaults.baseUrl)
        assertNotNull(EisCredentials.fromJson("""{"eisCertNumber":"a","applicationId":"b","applicationKey":"c","userId":"d","password":"e",""" +
            """"eisPublicKey":"f","signingKeyId":"g","signingPrivateKey":"h","extra":"ignored"}"""))
    }

    @Test
    fun refusesAPlainHttpBaseUrlExceptOnLoopback() {
        val fake = FakeEis { _, _ -> error(500, "E99") }
        val e = assertFailsWith<IllegalArgumentException> { EisClient(credentials.copy(baseUrl = "http://eis.example.test"), fake, clock) }
        assertTrue(e.message!!.startsWith("The EIS base URL must start with https://"), e.message)
        assertFailsWith<IllegalArgumentException> { EisClient(credentials.copy(baseUrl = "ftp://127.0.0.1"), fake, clock) }
        assertFailsWith<IllegalArgumentException> { EisClient(credentials.copy(baseUrl = "https://user:pw@eis.example.test"), fake, clock) }
        for (url in listOf("http://127.0.0.1:8787", "http://localhost:8787/", "http://[::1]:8787")) {
            EisClient(credentials.copy(baseUrl = url), fake, clock, sandboxSpacingMillis = 0).authenticate()
            assertTrue(fake.calls.last().url.startsWith(url.trimEnd('/') + "/api/authentication"), fake.calls.last().url)
        }
    }

    @Test
    fun eisCodesCoverTheTables() {
        for (code in listOf("E01", "E03", "E04", "E05", "E08", "E14", "E19", "E21", "E23", "E24", "E31", "E99")) {
            assertNotNull(EisCodes.requestErrors[code], code)
        }
        for (code in listOf("SUC001", "SYN002", "SYN003", "SYN004", "ERR004", "ERR007", "ERR011", "ERR015", "ERR999")) {
            assertNotNull(EisCodes.resultCodes[code], code)
        }
        assertEquals(setOf("01", "02", "03", "04", "05", "09"), EisCodes.correctionCodes.keys)
        assertEquals(24, EisCodes.UNMANAGED_ORIGINAL.length)
    }

    @Suppress("unused")
    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
}
