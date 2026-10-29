package io.everytech.bireinvoice

/**
 * [length] characters drawn from [alphabet] by the platform's cryptographic random source
 * (SecureRandom on the JVM, crypto.getRandomValues in a browser).
 */
internal expect fun secureRandomString(length: Int, alphabet: String): String
