package io.everytech.bireinvoice

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AmountTest {
    @Serializable
    private data class Holder(val a: Amount)

    private val json = EisJson.json

    @Test
    fun encodesAsUnquotedNumberWithTwoDecimals() {
        assertEquals("850.00", json.encodeToString(Amount.serializer(), Amount(85000)))
        assertEquals("-0.05", json.encodeToString(Amount.serializer(), Amount(-5)))
        assertEquals("0.00", json.encodeToString(Amount.serializer(), Amount.ZERO))
        assertEquals("1234567.89", json.encodeToString(Amount.serializer(), Amount(123456789)))
        assertEquals("-1120.50", json.encodeToString(Amount.serializer(), Amount(-112050)))
        assertEquals("""{"a":850.00}""", json.encodeToString(Holder.serializer(), Holder(Amount(85000))))
        assertEquals("[0.10,-0.01]", json.encodeToString(ListSerializer(Amount.serializer()), listOf(Amount(10), Amount(-1))))
    }

    @Test
    fun decodesWholeOneAndTwoDecimalNumbers() {
        for (text in listOf("850", "850.0", "850.00", "8.5e2", "850.000")) {
            assertEquals(Amount(85000), json.decodeFromString(Holder.serializer(), """{"a":$text}""").a, text)
        }
        assertEquals(Amount(-5), json.decodeFromString(Holder.serializer(), """{"a":-0.05}""").a)
        assertEquals(Amount(0), json.decodeFromString(Holder.serializer(), """{"a":-0}""").a)
    }

    @Test
    fun refusesToRoundOnDecode() {
        assertFailsWith<SerializationException> { json.decodeFromString(Holder.serializer(), """{"a":1.234}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(Holder.serializer(), """{"a":true}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(Holder.serializer(), """{"a":null}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(Holder.serializer(), """{"a":1e30}""") }
    }

    @Test
    fun parseFormatAndArithmetic() {
        assertEquals(Amount(123450), Amount.parse("1234.5"))
        assertEquals(Amount(-5), Amount.parse(" -0.05 "))
        assertEquals("-0.05", Amount(-5).toString())
        assertEquals("1234.56", Amount.format(123456))
        assertEquals(Amount(150), Amount(100) + Amount(50))
        assertEquals(Amount(50), Amount(100) - Amount(50))
        assertEquals(Amount(-100), -Amount(100))
        assertEquals(true, Amount(1) > Amount.ZERO)
        assertFailsWith<IllegalArgumentException> { Amount.parse("1.234") }
        assertFailsWith<IllegalArgumentException> { Amount.parse("abc") }
        assertFailsWith<IllegalArgumentException> { Amount.parse("1,000") }
    }

    @Test
    fun formatHandlesLongExtremes() {
        assertEquals("-92233720368547758.08", Amount.format(Long.MIN_VALUE))
        assertEquals("92233720368547758.07", Amount.format(Long.MAX_VALUE))
    }
}
