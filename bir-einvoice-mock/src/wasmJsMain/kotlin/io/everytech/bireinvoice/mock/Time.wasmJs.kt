package io.everytech.bireinvoice.mock

@JsFun("() => Date.now()")
private external fun dateNow(): Double

internal actual fun currentTimeMillis(): Long = dateNow().toLong()
