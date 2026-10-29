package io.everytech.bireinvoice

// A uniform index below bound, from the Web Crypto API. Rejection sampling keeps it unbiased.
@JsFun(
    """(bound) => {
        const limit = Math.floor(0x100000000 / bound) * bound;
        const word = new Uint32Array(1);
        do { crypto.getRandomValues(word); } while (word[0] >= limit);
        return word[0] % bound;
    }""",
)
private external fun cryptoRandomIndex(bound: Int): Int

internal actual fun secureRandomString(length: Int, alphabet: String): String =
    buildString(length) { repeat(length) { append(alphabet[cryptoRandomIndex(alphabet.length)]) } }
