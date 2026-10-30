package io.everytech.bireinvoice.mock

import io.everytech.bireinvoice.EisCodes
import io.everytech.bireinvoice.EisCrypto
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Optional interop test in the other direction: the client of an independent implementation of the
 * same protocol (Node.js), which is not published, against this Kotlin mock. Skipped unless
 * BIR_EINVOICE_NODE_DIR names that implementation's folder (the one holding src/eis/client.mjs)
 * and `node` is on the PATH.
 */
class NodeClientInteropTest {
    private val nodeDir: String? = System.getProperty("BIR_EINVOICE_NODE_DIR") ?: System.getenv("BIR_EINVOICE_NODE_DIR")

    @Test
    fun nodeClientAuthenticatesSubmitsAndGetsSuc001() {
        assumeTrue("BIR_EINVOICE_NODE_DIR not set", !nodeDir.isNullOrBlank())
        val clientModule = Path.of(nodeDir!!, "src", "eis", "client.mjs").toAbsolutePath()
        assumeTrue("no client.mjs in $nodeDir", Files.exists(clientModule))

        MockEisServer(processingPolls = 1).use { mock ->
            mock.start()
            val creds = mock.credentials
            val work = Files.createTempDirectory("bir-einvoice-node-interop")
            val credsFile = work.resolve("creds.json")
            Files.writeString(credsFile, creds.toJson())
            val today = EisCrypto.manilaDatetime(Instant.now()).take(8)
            val original = TestInvoices.build(creds.eisCertNumber, today)
            val credit = TestInvoices.build(creds.eisCertNumber, today, qty = 100, number = "CN-00000001", prevUniqueId = original.eisUniqueId)
            val invoicesFile = work.resolve("invoices.json")
            Files.writeString(invoicesFile, "[" + listOf(original, credit).joinToString(",") { quote(TestInvoices.json(it)) } + "]")
            val script = work.resolve("interop.mjs")
            Files.writeString(script, NODE_SCRIPT.replace("CLIENT_URL", clientModule.toUri().toString()))

            val process = ProcessBuilder("node", script.toString(), credsFile.toString(), invoicesFile.toString())
                .redirectErrorStream(true)
                .start()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "node did not finish")
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.exitValue(), output)
            println("Node client against Kotlin mock: $output")
            val last = output.trim().lines().last()
            assertTrue(""""processStatusCode":"01"""" in last, output)
            assertEquals(2, Regex("\"resultStatusCode\":\"SUC001\"").findAll(last).count(), output)
            assertEquals(setOf(original.eisUniqueId, credit.eisUniqueId), mock.registered)
            assertTrue(mock.registered.all { it.startsWith(today + creds.eisCertNumber) })
            assertEquals(EisCodes.DOC_CREDIT_MEMO, credit.docType)
        }
    }

    private fun quote(text: String): String = kotlinx.serialization.json.JsonPrimitive(text).toString()

    private companion object {
        val NODE_SCRIPT = """
            import fs from "node:fs";
            import { EisClient } from "CLIENT_URL";
            const creds = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
            const invoices = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
            const client = new EisClient(creds, { sandboxSpacingMs: 0 });
            const session = await client.authenticate();
            console.log("authenticated, token expires", session.tokenExpiry);
            const ack = await client.submit(invoices);
            console.log("submitted", ack.submitId, ack.ackId);
            let result;
            for (let i = 0; i < 10; i++) {
              result = await client.inquire(ack.submitId);
              if (result.processStatusCode !== "02") break;
              console.log("processing");
            }
            console.log(JSON.stringify(result));
        """.trimIndent()
    }
}
