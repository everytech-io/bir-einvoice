package io.everytech.bireinvoice.mock

import io.everytech.bireinvoice.CasValidator
import io.everytech.bireinvoice.EisCodes
import io.everytech.bireinvoice.EisException
import io.everytech.bireinvoice.InquiryResult
import io.everytech.bireinvoice.ProcessedDocument
import io.everytech.bireinvoice.SubmitAck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmOverloads
import kotlin.random.Random

/**
 * The EIS's processing, without the transport: it registers e-invoices, answers submissions with
 * an acknowledgement and inquiries with the per-invoice results, and checks what the real one
 * checks once a payload is decrypted and its signature verified (submit IDs, schema, formulas,
 * the EIS Cert Number inside the ID, a credit memo's original, duplicates).
 *
 * [MockEisServer] puts the HTTP, crypto and signature layers in front of it on the JVM. On its
 * own it runs anywhere, a browser included, for practice runs that never leave the process.
 * Like the server, it is a stand-in: passing here is necessary, not sufficient.
 *
 * Not thread-safe; [MockEisServer] holds its own lock around every call.
 *
 * @param processingPolls how many inquiries answer "in progress" (02) before the result is ready.
 * @param nowMillis the clock, in epoch milliseconds.
 */
public class MockEis @JvmOverloads constructor(
    public val eisCertNumber: String = "20" + randomString(6, UPPER_DIGITS),
    public val userId: String = "mockuser",
    private val processingPolls: Int = 1,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) {
    private class Submission(val ackId: String, val documents: List<ProcessedDocument>, var pollsLeft: Int)

    private val submissions = HashMap<String, Submission>()
    private val registeredIds = LinkedHashSet<String>()

    /** EisUniqueIds accepted so far. */
    public val registered: Set<String>
        get() = registeredIds.toSet()

    /**
     * Takes one transmission of decoded CAS invoice payloads (JSON text, no JWS around them) and
     * returns the acknowledgement. Throws [EisException] E11 for a bad submit ID or more than 100
     * invoices, and E14 for a submit ID used before.
     */
    public fun submit(submitId: String, invoiceJsons: List<String>): SubmitAck =
        accept(submitId, invoiceJsons.size) { invoiceJsons.map(::check) }

    /** Like [submit], for payloads already checked one by one (the server checks signatures first). */
    internal fun accept(submitId: String, count: Int, check: () -> List<ProcessedDocument>): SubmitAck {
        checkSubmitId(submitId)
        if (count > 100) throw EisException("E11", "1 to 100 invoices per submission")
        val documents = check()
        val now = nowMillis()
        val ackId = "BIR-${ManilaTime.compact(now)}-${randomString(5, UPPER_DIGITS)}"
        submissions[submitId] = Submission(ackId, documents, processingPolls)
        return SubmitAck(
            submitId = submitId,
            ackId = ackId,
            responseDtm = ManilaTime.iso(now),
            description = "Transmission has been successfully processed. EIS will process the final registration within the next business day.",
        )
    }

    /** E11 for a submit ID in the wrong format, E14 for one used before. */
    internal fun checkSubmitId(submitId: String) {
        if (!submitId.startsWith("$eisCertNumber-") || !SUBMIT_SUFFIX.containsMatchIn(submitId)) {
            throw EisException("E11", "Invalid submitId")
        }
        if (submitId in submissions) throw EisException("E14", "Duplicated submitId")
    }

    /** The processing result of one transmission. Throws [EisException] E15 for an unknown submit ID. */
    public fun inquire(submitId: String): InquiryResult {
        val submission = submissions[submitId] ?: throw EisException("E15", "Invalid submitId")
        val base = InquiryResult(
            accreditationId = eisCertNumber,
            userId = userId,
            refSubmitId = submitId,
            ackId = submission.ackId,
            responseDtm = ManilaTime.iso(nowMillis()),
            processStatusCode = EisCodes.PROCESS_IN_PROGRESS,
        )
        if (submission.pollsLeft > 0) {
            submission.pollsLeft -= 1
            return base
        }
        val success = submission.documents.count { it.resultStatusCode == EisCodes.RESULT_PASSED }
        return base.copy(
            processStatusCode = EisCodes.PROCESS_DONE,
            totalCountQuantity = submission.documents.size,
            successCountQuantity = success,
            failCountQuantity = submission.documents.size - success,
            processedDocuments = submission.documents,
        )
    }

    /** Checks one decoded payload and registers it when it passes. */
    public fun check(payloadText: String): ProcessedDocument {
        val payload = try {
            Json.parseToJsonElement(payloadText)
        } catch (e: Exception) {
            return ProcessedDocument("", "SYN004", "payload is not JSON")
        }
        val uid = (payload as? JsonObject)?.string("EisUniqueId") ?: ""
        return check(payloadText, uid)
    }

    internal fun check(payloadText: String, uid: String): ProcessedDocument {
        val problems = CasValidator.validate(payloadText)
        if (problems.isNotEmpty()) {
            val first = problems.first()
            return ProcessedDocument(uid, first.code, "${first.field}: ${first.message}")
        }
        val p = Json.parseToJsonElement(payloadText) as JsonObject
        if (uid.length < 16 || uid.substring(8, 16) != eisCertNumber) return ProcessedDocument(uid, "ERR006", "EIS Cert Number mismatch")
        val prev = p.string("PrevUniqueId") ?: ""
        if (p.string("CorrYN") == "Y" && !ZEROS_24.matches(prev) && prev !in registeredIds) {
            return ProcessedDocument(uid, "ERR007", "PrevUniqueId is not registered")
        }
        if (uid in registeredIds) return ProcessedDocument(uid, "SYN003", "Duplicated EisUniqueId")
        registeredIds += uid
        return ProcessedDocument(uid, EisCodes.RESULT_PASSED)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal companion object {
        const val UPPER_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val SUBMIT_SUFFIX = Regex("-\\d{8}-[0-9a-f]{12}$")
        val ZEROS_24 = Regex("0{24}")

        // Identifiers for a stand-in, not secrets: an ordinary random source is enough.
        fun randomString(length: Int, alphabet: String): String =
            buildString(length) { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }
}

internal expect fun currentTimeMillis(): Long

/** Philippine time (UTC+8, no daylight saving) as the EIS writes it. */
internal object ManilaTime {
    private const val OFFSET_MILLIS = 8 * 3_600_000L

    /** yyyy-MM-ddTHH:mm:ss */
    fun iso(epochMillis: Long): String = fields(epochMillis).let { (y, mo, d, h, mi, s) ->
        "${pad(y, 4)}-${pad(mo)}-${pad(d)}T${pad(h)}:${pad(mi)}:${pad(s)}"
    }

    /** yyyyMMddHHmmss */
    fun compact(epochMillis: Long): String = fields(epochMillis).let { (y, mo, d, h, mi, s) ->
        "${pad(y, 4)}${pad(mo)}${pad(d)}${pad(h)}${pad(mi)}${pad(s)}"
    }

    private data class Fields(val y: Int, val mo: Int, val d: Int, val h: Int, val mi: Int, val s: Int)

    private fun fields(epochMillis: Long): Fields {
        val local = epochMillis + OFFSET_MILLIS
        val days = floorDiv(local, 86_400_000L)
        val secondOfDay = ((local - days * 86_400_000L) / 1000).toInt()
        val (y, mo, d) = civilFromDays(days)
        return Fields(y, mo, d, secondOfDay / 3600, secondOfDay / 60 % 60, secondOfDay % 60)
    }

    private fun pad(n: Int, width: Int = 2) = n.toString().padStart(width, '0')

    private fun floorDiv(a: Long, b: Long): Long = (a / b).let { if (a % b != 0L && (a < 0) != (b < 0)) it - 1 else it }

    // Howard Hinnant's civil_from_days (proleptic Gregorian calendar).
    private fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719_468
        val era = floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }
}
