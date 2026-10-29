package io.everytech.bireinvoice

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The default transport, on `java.net.http.HttpClient`. It uses HTTP/1.1, which every EIS sample uses. */
public class JdkEisTransport @JvmOverloads constructor(
    private val timeout: Duration = Duration.ofSeconds(60),
) : EisTransport {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): EisHttpResponse {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .method(method, publisher)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return EisHttpResponse(response.statusCode(), response.body() ?: "")
    }
}
