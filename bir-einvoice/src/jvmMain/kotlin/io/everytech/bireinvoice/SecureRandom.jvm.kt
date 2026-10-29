package io.everytech.bireinvoice

internal actual fun secureRandomString(length: Int, alphabet: String): String = EisCrypto.randomString(length, alphabet)
