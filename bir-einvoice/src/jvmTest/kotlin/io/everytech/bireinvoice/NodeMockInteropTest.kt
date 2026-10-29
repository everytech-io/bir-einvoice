package io.everytech.bireinvoice

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interop proof against an independent implementation: the Node.js mock EIS in the EveryTech
 * e-invoice app. Skipped unless BIR_EINVOICE_NODE_MOCK_CREDS names the credentials file that
 * `node tools/mock-eis.mjs <file>` wrote.
 */
class NodeMockInteropTest {
    private val credsFile: String? = System.getProperty("BIR_EINVOICE_NODE_MOCK_CREDS") ?: System.getenv("BIR_EINVOICE_NODE_MOCK_CREDS")

    @Test
    fun authenticatesSubmitsAndPollsAgainstTheNodeMock() {
        assumeTrue("BIR_EINVOICE_NODE_MOCK_CREDS not set", !credsFile.isNullOrBlank())
        val credentials = EisCredentials.fromJson(Files.readString(Path.of(credsFile!!)))
        val client = EisClient(credentials, sandboxSpacingMillis = 0)
        val session = client.authenticate()
        assertEquals(32, session.sessionKey.length)

        val issueDate = EisCrypto.manilaDatetime(java.time.Instant.now()).take(8)
        val original = Samples.vatable(EisIds.eisUniqueId(issueDate, credentials.eisCertNumber))
        val exempt = Samples.exempt(EisIds.eisUniqueId(issueDate, credentials.eisCertNumber))
        val first = submitAndWait(client, listOf(original, exempt))
        assertEquals(listOf("SUC001", "SUC001"), first.processedDocuments.map { it.resultStatusCode }, first.toString())
        assertEquals(listOf(original.eisUniqueId, exempt.eisUniqueId), first.processedDocuments.map { it.invoiceUid })

        val credit = Samples.creditMemo(EisIds.eisUniqueId(issueDate, credentials.eisCertNumber), prevUniqueId = original.eisUniqueId)
        val second = submitAndWait(client, listOf(credit, original))
        assertEquals(listOf("SUC001", "SYN003"), second.processedDocuments.map { it.resultStatusCode }, second.toString())
        println("Node mock interop: ${first.processedDocuments} then ${second.processedDocuments}")
    }

    private fun submitAndWait(client: EisClient, invoices: List<CasInvoice>): InquiryResult {
        val ack = client.submit(invoices.map { EisJson.encode(it) })
        assertTrue(Regex("BIR-\\d{14}-[0-9A-Z]{5}").matches(ack.ackId), ack.ackId)
        repeat(10) {
            val result = client.inquire(ack.submitId)
            if (result.processStatusCode != EisCodes.PROCESS_IN_PROGRESS) {
                assertEquals(EisCodes.PROCESS_DONE, result.processStatusCode)
                return result
            }
        }
        error("still processing after 10 polls")
    }
}
