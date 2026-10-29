package io.everytech.bireinvoice.mock

import io.everytech.bireinvoice.EisCodes
import io.everytech.bireinvoice.EisException
import io.everytech.bireinvoice.EisIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Runs on the JVM and in Kotlin/Wasm: the processing behind MockEisServer, with no transport.
class MockEisTest {
    // 2026-10-29T03:00:00Z, 11:00 in Manila.
    private val now = 1_793_242_800_000L
    private val day = "2026-10-29"

    @Test
    fun acknowledgesThenReportsEachInvoice() {
        val eis = MockEis(eisCertNumber = "20AB12CD", processingPolls = 1, nowMillis = { now })
        val invoice = TestInvoices.build("20AB12CD", day)
        val wrongCert = TestInvoices.build("20ZZ99ZZ", day, number = "INV-00000002")
        val submitId = EisIds.submitId("20AB12CD", day)
        val ack = eis.submit(submitId, listOf(TestInvoices.json(invoice), TestInvoices.json(wrongCert)))
        assertEquals(submitId, ack.submitId)
        assertTrue(Regex("BIR-20261029110000-[0-9A-Z]{5}").matches(ack.ackId), ack.ackId)
        assertEquals("2026-10-29T11:00:00", ack.responseDtm)

        assertEquals(EisCodes.PROCESS_IN_PROGRESS, eis.inquire(submitId).processStatusCode)
        val result = eis.inquire(submitId)
        assertEquals(EisCodes.PROCESS_DONE, result.processStatusCode)
        assertEquals(listOf("SUC001", "ERR006"), result.processedDocuments.map { it.resultStatusCode })
        assertEquals(1, result.successCountQuantity)
        assertEquals(setOf(invoice.eisUniqueId), eis.registered)
    }

    @Test
    fun checksCreditMemosDuplicatesAndSubmitIds() {
        val eis = MockEis(eisCertNumber = "20AB12CD", processingPolls = 0, nowMillis = { now })
        val invoice = TestInvoices.build("20AB12CD", day)
        val orphan = TestInvoices.build("20AB12CD", day, number = "CN-00000001", qty = 100, prevUniqueId = "2026102920AB12CDNOTTHERE")
        assertEquals("ERR007", eis.check(TestInvoices.json(orphan)).resultStatusCode)
        assertEquals("SUC001", eis.check(TestInvoices.json(invoice)).resultStatusCode)
        assertEquals("SYN003", eis.check(TestInvoices.json(invoice)).resultStatusCode)
        assertEquals("SYN004", eis.check("{not json").resultStatusCode)
        val credit = TestInvoices.build("20AB12CD", day, number = "CN-00000002", qty = 100, prevUniqueId = invoice.eisUniqueId)
        assertEquals("SUC001", eis.check(TestInvoices.json(credit)).resultStatusCode)

        assertEquals("E11", assertFailsWith<EisException> { eis.submit("OTHER123-20261029-0123456789ab", listOf("{}")) }.code)
        val submitId = EisIds.submitId("20AB12CD", day)
        eis.submit(submitId, listOf("{}"))
        assertEquals("E14", assertFailsWith<EisException> { eis.submit(submitId, listOf("{}")) }.code)
        assertEquals("E15", assertFailsWith<EisException> { eis.inquire("20AB12CD-20261029-000000000000") }.code)
    }

    @Test
    fun manilaTimeIsUtcPlusEight() {
        assertEquals("20261029110000", ManilaTime.compact(now))
        assertEquals("2024-03-01T07:59:59", ManilaTime.iso(1_709_251_199_000L))
    }
}
