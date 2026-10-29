package io.everytech.bireinvoice

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmStatic
import kotlin.math.abs

/**
 * An amount or quantity in integer hundredths: `Amount(85000)` is 850.00.
 *
 * Money never goes through floating point. In EIS JSON it is written as an unquoted number with
 * exactly two decimals (`850.00`, `-0.05`), which is what the BIR JSON format asks for.
 */
@JvmInline
@Serializable(with = AmountSerializer::class)
public value class Amount(public val hundredths: Long) : Comparable<Amount> {
    public operator fun plus(other: Amount): Amount = Amount(hundredths + other.hundredths)

    public operator fun minus(other: Amount): Amount = Amount(hundredths - other.hundredths)

    public operator fun unaryMinus(): Amount = Amount(-hundredths)

    override fun compareTo(other: Amount): Int = hundredths.compareTo(other.hundredths)

    /** The EIS form: `850.00`, `-0.05`. */
    override fun toString(): String = format(hundredths)

    public companion object {
        public val ZERO: Amount = Amount(0)

        /** Parses `850`, `850.5`, `850.50` or `-0.05`. More than two decimals is an error, not a rounding. */
        @JvmStatic
        public fun parse(text: String): Amount {
            val parsed = parseDecimal(text.trim()) ?: throw IllegalArgumentException("\"$text\" is not a number")
            if (parsed.tooLarge) throw IllegalArgumentException("\"$text\" is too large")
            val hundredths = parsed.hundredths
                ?: throw IllegalArgumentException("\"$text\" has more than 2 decimal places")
            return Amount(hundredths)
        }

        /** 123456 to `1234.56`, -5 to `-0.05`. */
        @JvmStatic
        public fun format(hundredths: Long): String {
            val sign = if (hundredths < 0) "-" else ""
            val whole = abs(hundredths / 100)
            val frac = abs(hundredths % 100)
            return "$sign$whole.${frac.toString().padStart(2, '0')}"
        }
    }
}

/** Writes [Amount] as a raw JSON number with two decimals and reads 850, 850.0 or 850.00. */
public object AmountSerializer : KSerializer<Amount> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.everytech.bireinvoice.Amount", PrimitiveKind.STRING)

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Amount) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(value.toString()))
        } else {
            encoder.encodeString(value.toString())
        }
    }

    override fun deserialize(decoder: Decoder): Amount {
        val text = if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element !is JsonPrimitive || element is JsonNull) throw SerializationException("expected a number, got $element")
            element.content
        } else {
            decoder.decodeString()
        }
        val parsed = parseDecimal(text) ?: throw SerializationException("\"$text\" is not a number")
        if (parsed.tooLarge) throw SerializationException("\"$text\" is too large")
        return Amount(parsed.hundredths ?: throw SerializationException("\"$text\" has more than 2 decimal places"))
    }
}

/**
 * A JSON number read exactly. [decimals] counts the digits after the point as written, so
 * `1.230` has 3 even though its value fits in hundredths. [hundredths] is null when the value
 * itself needs more than two decimals.
 */
internal class ParsedDecimal(val hundredths: Long?, val decimals: Int, val tooLarge: Boolean)

private val DECIMAL = Regex("(-)?(\\d+)(?:\\.(\\d+))?(?:[eE]([+-]?\\d+))?")

// Larger than any real invoice amount, small enough that sums of a thousand lines cannot overflow.
private const val MAX_HUNDREDTHS = 100_000_000_000_000_000L

internal fun parseDecimal(raw: String): ParsedDecimal? {
    val match = DECIMAL.matchEntire(raw) ?: return null
    val negative = match.groupValues[1] == "-"
    val intPart = match.groupValues[2]
    val fracPart = match.groupValues[3]
    val expText = match.groupValues[4]
    val exp = if (expText.isEmpty()) 0 else expText.toIntOrNull() ?: return ParsedDecimal(null, 0, true)
    val scale = fracPart.length - exp
    val decimals = if (scale > 0) scale else 0
    var digits = (intPart + fracPart).trimStart('0')
    if (digits.isEmpty()) return ParsedDecimal(0, decimals, false)
    val shift = 2 - scale
    if (shift < 0) {
        val dropped = digits.takeLast(minOf(-shift, digits.length))
        if (dropped.any { it != '0' } || -shift > digits.length) return ParsedDecimal(null, decimals, false)
        digits = digits.dropLast(-shift)
        if (digits.isEmpty()) return ParsedDecimal(0, decimals, false)
    } else {
        if (digits.length + shift > 18) return ParsedDecimal(null, decimals, true)
        digits += "0".repeat(shift)
    }
    if (digits.length > 18) return ParsedDecimal(null, decimals, true)
    val value = digits.toLong()
    if (value > MAX_HUNDREDTHS) return ParsedDecimal(null, decimals, true)
    return ParsedDecimal(if (negative) -value else value, decimals, false)
}

/** Integer division rounded half away from zero, the rounding a person does by hand. */
internal fun divRound(numerator: Long, denominator: Long): Long {
    require(denominator != 0L) { "division by zero" }
    val negative = (numerator < 0) != (denominator < 0)
    val n = abs(numerator)
    val d = abs(denominator)
    val q = n / d
    val r = n - q * d
    val rounded = if (r * 2 >= d) q + 1 else q
    return if (negative) -rounded else rounded
}

/** Null when the product does not fit in a Long. */
internal fun multiplyOrNull(a: Long, b: Long): Long? {
    if (a == 0L || b == 0L) return 0
    val r = a * b
    if (r / b != a || (a == -1L && b == Long.MIN_VALUE) || (b == -1L && a == Long.MIN_VALUE)) return null
    return r
}
