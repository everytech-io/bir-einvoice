@file:JvmName("MockEisMain")

package io.everytech.bireinvoice.mock

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Runs the mock EIS on its own and writes matching credentials, for testing another EIS client
 * (in any language) against the protocol.
 *
 * Usage: `MockEisMain [credentials-file]`. Port from MOCK_EIS_PORT (default 8787), host from
 * MOCK_EIS_HOST (default 127.0.0.1), polls before a result from MOCK_EIS_POLLS (default 1).
 */
public fun main(args: Array<String>) {
    val port = System.getenv("MOCK_EIS_PORT")?.toInt() ?: 8787
    val host = System.getenv("MOCK_EIS_HOST") ?: "127.0.0.1"
    val polls = System.getenv("MOCK_EIS_POLLS")?.toInt() ?: 1
    val out = Path.of(args.firstOrNull() ?: "mock-eis-credentials.json")
    val mock = MockEisServer(processingPolls = polls)
    val url = mock.start(port, host)
    Files.writeString(out, mock.credentials.toJson() + "\n")
    try {
        Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rw-------"))
    } catch (e: UnsupportedOperationException) {
        // Not a POSIX file system; the file keeps the default permissions.
    }
    println("Mock EIS on $url. Credentials written to $out. These keys exist only in this process.")
    Runtime.getRuntime().addShutdownHook(Thread { mock.close() })
    Thread.currentThread().join()
}
