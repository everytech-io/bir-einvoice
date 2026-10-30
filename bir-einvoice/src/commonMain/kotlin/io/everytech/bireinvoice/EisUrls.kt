package io.everytech.bireinvoice

import kotlin.jvm.JvmStatic

/**
 * The rule for the EIS base URL. [EisClient] applies it, and an application that lets a user type
 * the URL can apply the same rule before it saves the value.
 */
public object EisUrls {
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    private val HOST_NAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*")
    private val IPV6_LITERAL = Regex("\\[[0-9A-Fa-f:.]+]")
    private val PORT = Regex("[0-9]{1,5}")

    /**
     * Returns why [baseUrl] cannot be used as the EIS base URL, or null when it can.
     *
     * The URL must start with `https://`. Plain `http://` is allowed only for a test server on this
     * computer, such as `MockEisServer`: the host must be `localhost`, `127.0.0.1` or `[::1]`. The
     * URL needs a host, may have a port and a path, and must not contain a user name or password, a
     * query, a fragment or whitespace. The scheme and the host are compared ignoring case.
     *
     * The message names the URL, except when the URL contains a user name or password.
     */
    @JvmStatic
    public fun checkBaseUrl(baseUrl: String): String? {
        val insecure = "The EIS base URL must start with https:// (plain http is only allowed for a test " +
            "server on this computer: localhost, 127.0.0.1 or [::1]): $baseUrl"
        val invalid = "The EIS base URL is not a valid URL: $baseUrl"

        val separator = baseUrl.indexOf("://")
        if (separator < 0) return insecure
        val scheme = baseUrl.substring(0, separator).lowercase()
        if (scheme != "https" && scheme != "http") return insecure

        val rest = baseUrl.substring(separator + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if ('@' in authority) return "The EIS base URL must not contain a user name or password."
        if (baseUrl.any { it.isWhitespace() || it.isISOControl() }) return invalid
        val tail = rest.substring(authorityEnd)
        if ('?' in tail || '#' in tail) return "The EIS base URL must not contain a query (?) or a fragment (#): $baseUrl"

        val host: String
        val port: String?
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return invalid
            host = authority.substring(0, close + 1)
            val afterHost = authority.substring(close + 1)
            port = when {
                afterHost.isEmpty() -> null
                afterHost.startsWith(":") -> afterHost.substring(1)
                else -> return invalid
            }
            if (!IPV6_LITERAL.matches(host)) return invalid
        } else {
            val colon = authority.indexOf(':')
            host = if (colon < 0) authority else authority.substring(0, colon)
            port = if (colon < 0) null else authority.substring(colon + 1)
            if (!HOST_NAME.matches(host)) return invalid
        }
        if (port != null && (!PORT.matches(port) || port.toInt() !in 1..65535)) return invalid

        if (scheme == "http" && host.lowercase() !in LOOPBACK_HOSTS) return insecure
        return null
    }
}
