package io.everytech.bireinvoice.mock

import io.everytech.bireinvoice.CasValidator
import io.everytech.bireinvoice.EisClient
import io.everytech.bireinvoice.EisCodes
import io.everytech.bireinvoice.EisCredentials
import io.everytech.bireinvoice.EisCrypto
import io.everytech.bireinvoice.EisException
import io.everytech.bireinvoice.EisJson
import io.everytech.bireinvoice.InquiryResult
import io.everytech.bireinvoice.JdkEisTransport
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MockEisServerTest {
    private val servers = mutableListOf<MockEisServer>()

    private fun server(polls: Int = 1, clock: Clock = Clock.systemUTC()): MockEisServer =
        MockEisServer(polls, clock).also { it.start(); servers += it }

    @AfterTest
    fun stopServers() = servers.forEach { it.close() }

    private fun client(credentials: EisCredentials, clock: Clock = Clock.systemUTC()) =
        EisClient(credentials, JdkEisTransport(Duration.ofSeconds(10)), clock, sandboxSpacingMillis = 0)

    private val today: String get() = EisCrypto.manilaDatetime(Instant.now()).take(8)

    private fun waitForResult(client: EisClient, submitId: String): InquiryResult {
        repeat(10) {
            val result = client.inquire(submitId)
            if (result.processStatusCode != EisCodes.PROCESS_IN_PROGRESS) return result
        }
        error("still processing")
    }

    @Test
    fun credentialsAreCompleteAfterStart() {
        val mock = server()
        val creds = mock.credentials
        assertEquals(emptyList(), creds.missingFields())
        assertTrue(creds.baseUrl.startsWith("http://127.0.0.1:"))
        assertTrue(Regex("20[0-9A-Z]{6}").matches(creds.eisCertNumber))
        assertEquals("Akeypair00", creds.signingKeyId)
    }

    @Test
    fun endToEndIssueInquireAndCredit() {
        val mock = server(polls = 1)
        val creds = mock.credentials
        val client = client(creds)
        val session = client.authenticate()
        assertEquals(32, session.authToken.length)

        val invoices = listOf(
            TestInvoices.build(creds.eisCertNumber, today, EisCodes.TRANS_VATABLE),
            TestInvoices.build(creds.eisCertNumber, today, EisCodes.TRANS_ZERO_RATED, qty = 100, unitCost = 50_000),
            TestInvoices.build(creds.eisCertNumber, today, EisCodes.TRANS_EXEMPT, qty = 250, unitCost = 10_000),
        )
        invoices.forEach { assertEquals(emptyList(), CasValidator.validate(EisJson.encode(it))) }
        val ack = client.submit(invoices.map(TestInvoices::json))
        assertTrue(Regex("${creds.eisCertNumber}-$today-[0-9a-f]{12}").matches(ack.submitId), ack.submitId)
        assertTrue(Regex("BIR-\\d{14}-[0-9A-Z]{5}").matches(ack.ackId), ack.ackId)
        assertTrue(ack.description.startsWith("Transmission has been successfully processed"))

        val processing = client.inquire(ack.submitId)
        assertEquals(EisCodes.PROCESS_IN_PROGRESS, processing.processStatusCode)
        assertEquals(ack.ackId, processing.ackId)
        val done = client.inquire(ack.submitId)
        assertEquals(EisCodes.PROCESS_DONE, done.processStatusCode)
        assertEquals(3, done.totalCountQuantity)
        assertEquals(3, done.successCountQuantity)
        assertEquals(0, done.failCountQuantity)
        assertEquals(invoices.map { it.eisUniqueId }, done.processedDocuments.map { it.invoiceUid })
        assertTrue(done.processedDocuments.all { it.resultStatusCode == "SUC001" && it.description == null })

        val credit = TestInvoices.build(creds.eisCertNumber, today, qty = 100, number = "CN-00000001", prevUniqueId = invoices[0].eisUniqueId)
        assertTrue(""""NetAmtPay":-1120.00""" in TestInvoices.json(credit))
        val unmanaged = TestInvoices.build(creds.eisCertNumber, today, qty = 100, number = "CN-00000002", prevUniqueId = EisCodes.UNMANAGED_ORIGINAL)
        val orphan = TestInvoices.build(creds.eisCertNumber, today, qty = 100, number = "CN-00000003", prevUniqueId = "20261029" + creds.eisCertNumber + "ZZZZZZZZ")
        val creditResult = waitForResult(client, client.submit(listOf(credit, unmanaged, orphan).map(TestInvoices::json)).submitId)
        assertEquals(listOf("SUC001", "SUC001", "ERR007"), creditResult.processedDocuments.map { it.resultStatusCode })
        assertEquals(1, creditResult.failCountQuantity)
        assertEquals(invoices.map { it.eisUniqueId }.toSet() + credit.eisUniqueId + unmanaged.eisUniqueId, mock.registered)
        assertEquals(1, mock.registered.count { it == credit.eisUniqueId })
    }

    @Test
    fun rejectsWhatBirRejects() {
        val mock = server(polls = 0)
        val creds = mock.credentials

        assertEquals("E23", assertFailsWith<EisException> { client(creds.copy(password = "nope")).authenticate() }.code)
        assertEquals("E23", assertFailsWith<EisException> { client(creds.copy(userId = "someone")).authenticate() }.code)
        val e04 = assertFailsWith<EisException> { client(creds.copy(applicationKey = "WRONG")).authenticate() }
        assertEquals("E04", e04.code)
        assertEquals(401, e04.httpStatus)
        val skewed = Clock.offset(Clock.systemUTC(), Duration.ofHours(1))
        assertEquals("E05", assertFailsWith<EisException> { client(creds, skewed).authenticate() }.code)
        assertEquals("E01", assertFailsWith<EisException> { client(creds.copy(eisCertNumber = "20XXXXXX")).authenticate() }.code)
        assertEquals("E02", assertFailsWith<EisException> { client(creds.copy(applicationId = "NOPE0000")).authenticate() }.code)
        val otherKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val wrongEisKey = creds.copy(eisPublicKey = Base64.getEncoder().encodeToString(otherKey.public.encoded))
        assertEquals("E21", assertFailsWith<EisException> { client(wrongEisKey).authenticate() }.code)

        val client = client(creds)
        val invoice = TestInvoices.build(creds.eisCertNumber, today)
        val first = waitForResult(client, client.submit(listOf(TestInvoices.json(invoice))).submitId)
        assertEquals("SUC001", first.processedDocuments.single().resultStatusCode)

        val duplicate = waitForResult(client, client.submit(listOf(TestInvoices.json(invoice))).submitId)
        assertEquals("SYN003", duplicate.processedDocuments.single().resultStatusCode)

        val bad = waitForResult(client, client.submit(listOf("""{"CompInvoiceId":"X"}""")).submitId)
        assertEquals("SYN004", bad.processedDocuments.single().resultStatusCode)

        val foreignCert = TestInvoices.build("20ZZZZZZ", today)
        assertEquals("ERR006", waitForResult(client, client.submit(listOf(TestInvoices.json(foreignCert))).submitId).processedDocuments.single().resultStatusCode)

        val zeroWithVat = TestInvoices.json(TestInvoices.build(creds.eisCertNumber, today, EisCodes.TRANS_ZERO_RATED, qty = 100, unitCost = 50_000))
            .replace(""""VATAmt":0.00""", """"VATAmt":60.00""").replace(""""NetAmtPay":500.00""", """"NetAmtPay":560.00""")
        val err004 = waitForResult(client, client.submit(listOf(zeroWithVat)).submitId).processedDocuments.single()
        assertEquals("ERR004", err004.resultStatusCode)
        assertEquals("VATAmt: must be 0 for zero-rated and exempt sales", err004.description)

        val wrongSigner = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val forger = client(creds.copy(signingPrivateKey = Base64.getEncoder().encodeToString(wrongSigner.private.encoded)))
        val forged = TestInvoices.json(TestInvoices.build(creds.eisCertNumber, today))
        assertEquals("SYN002", waitForResult(forger, forger.submit(listOf(forged)).submitId).processedDocuments.single().resultStatusCode)
        val wrongKid = client(creds.copy(signingKeyId = "Bkeypair01"))
        assertEquals("SYN002", waitForResult(wrongKid, wrongKid.submit(listOf(forged)).submitId).processedDocuments.single().resultStatusCode)

        val ack = client.submit(listOf(TestInvoices.json(TestInvoices.build(creds.eisCertNumber, today))))
        assertEquals("E14", assertFailsWith<EisException> { client.submit(listOf("{}"), ack.submitId) }.code)
        assertEquals("E11", assertFailsWith<EisException> { client.submit(listOf("{}"), "not-a-submit-id") }.code)
        val e15 = assertFailsWith<EisException> { client.inquire("${creds.eisCertNumber}-$today-000000000000") }
        assertEquals("E15", e15.code)
        assertEquals(404, e15.httpStatus)
    }

    @Test
    fun expiredTokenIsRenewedTransparently() {
        val start = Instant.now()
        var mockNow = start
        var clientNow = start
        val mockClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = mockNow
        }
        val clientClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = clientNow
        }
        val mock = server(polls = 0, clock = mockClock)
        val creds = mock.credentials
        val client = client(creds, clientClock)
        val firstToken = client.authenticate().authToken

        // The client still trusts its token (it renews 5 minutes before expiry) but EIS has
        // expired it: the call gets E03, the client signs in again and retries once.
        mockNow = start.plus(Duration.ofMinutes(6 * 60 + 5))
        clientNow = start.plus(Duration.ofMinutes(5 * 60 + 52))
        val date = EisCrypto.manilaDatetime(clientNow).take(8)
        val ack = client.submit(listOf(TestInvoices.json(TestInvoices.build(creds.eisCertNumber, date))))
        assertTrue(client.currentSession!!.authToken != firstToken)
        assertEquals("SUC001", client.inquire(ack.submitId).processedDocuments.single().resultStatusCode)
    }

    @Test
    fun unknownEndpointsAndMethods() {
        val mock = server()
        val transport = JdkEisTransport()
        val response = transport.execute("GET", "${mock.credentials.baseUrl}/api/nothing", emptyMap(), null)
        assertEquals(400, response.status)
        assertTrue(""""errorCode":"E11"""" in response.body, response.body)
        val noHeaders = transport.execute("POST", "${mock.credentials.baseUrl}/api/authentication", emptyMap(), "{}")
        assertEquals(404, noHeaders.status)
        assertTrue(""""errorCode":"E01"""" in noHeaders.body, noHeaders.body)
    }
}
