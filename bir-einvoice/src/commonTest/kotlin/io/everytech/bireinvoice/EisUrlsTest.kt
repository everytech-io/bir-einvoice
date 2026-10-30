package io.everytech.bireinvoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EisUrlsTest {
    private fun accepted(url: String) = assertNull(EisUrls.checkBaseUrl(url), url)

    private fun rejected(url: String): String = assertNotNull(EisUrls.checkBaseUrl(url), url)

    @Test
    fun acceptsHttps() {
        accepted(EisCodes.SANDBOX_BASE_URL)
        accepted("https://eis-cert.bir.gov.ph/")
        accepted("HTTPS://EIS.example.test:8443/prefix")
        accepted("https://203.0.113.10")
        accepted("https://[2001:db8::1]:443/")
    }

    @Test
    fun acceptsPlainHttpOnlyOnLoopback() {
        accepted("http://localhost:8787")
        accepted("http://LOCALHOST/")
        accepted("http://127.0.0.1:41234")
        accepted("http://[::1]:8787")
    }

    @Test
    fun rejectsPlainHttpToAnyOtherHost() {
        for (url in listOf(
            "http://eis-cert.bir.gov.ph",
            "http://example.test:80/",
            "http://192.168.1.10:8787",
            "http://0.0.0.0:8787",
            "http://localhost.example.test",
            "http://127.0.0.1.example.test",
        )) {
            val message = rejected(url)
            assertTrue(message.startsWith("The EIS base URL must start with https://"), message)
            assertTrue(url in message, message)
        }
    }

    @Test
    fun rejectsOtherSchemesAndAMissingScheme() {
        for (url in listOf("ftp://localhost", "file:///tmp/eis", "ws://127.0.0.1:8787", "eis-cert.bir.gov.ph", "//eis-cert.bir.gov.ph", "")) {
            assertTrue(rejected(url).startsWith("The EIS base URL must start with https://"), url)
        }
    }

    @Test
    fun rejectsMalformedUrls() {
        for (url in listOf(
            "https://",
            "https:///api",
            "https://:443",
            "https://eis .example.test",
            " https://eis.example.test",
            "https://eis.example.test/\n",
            "https://eis.example.test:",
            "https://eis.example.test:0",
            "https://eis.example.test:65536",
            "https://eis.example.test:84a3",
            "https://-eis.example.test",
            "https://eis..example.test",
            "https://[::1",
            "https://[::1]x",
            "https://[zz::1]",
            "https://eis.example.test?x=1",
            "https://eis.example.test/path#top",
        )) {
            rejected(url)
        }
    }

    @Test
    fun neverEchoesAUserNameOrPassword() {
        val message = rejected("https://user:secret@eis.example.test")
        assertFalse("secret" in message, message)
        // The loopback rule reads the real host, not what comes before the @.
        val trick = rejected("http://localhost@evil.example.test")
        assertEquals("The EIS base URL must not contain a user name or password.", trick)
    }
}
