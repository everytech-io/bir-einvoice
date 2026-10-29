package io.everytech.bireinvoice

import kotlin.jvm.JvmStatic

/** The identifier formats EIS expects. */
public object EisIds {
    private const val CONTROL_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val HEX = "0123456789abcdef"
    private val DATE8 = Regex("\\d{8}")
    private val CERT = Regex("[0-9A-Za-z]{8}")

    /**
     * 24 characters: issue date YYYYMMDD + EIS Cert Number (8) + 8 control characters. The JSON
     * spec allows numeric, uppercase, or alphanumeric control values; uppercase alphanumeric fits all.
     *
     * @param issueDate `2026-10-29` or `20261029`
     */
    @JvmStatic
    public fun eisUniqueId(issueDate: String, eisCertNumber: String): String {
        val date = issueDate.replace("-", "")
        require(DATE8.matches(date)) { "issue date \"$issueDate\" is not YYYY-MM-DD" }
        require(CERT.matches(eisCertNumber)) { "EIS Cert Number \"$eisCertNumber\" must be 8 letters or digits" }
        return date + eisCertNumber + secureRandomString(8, CONTROL_ALPHABET)
    }

    /** `<EIS Cert Number>-<YYYYMMDD>-<12 lowercase hex>`, with the date in Manila time. */
    @JvmStatic
    public fun submitId(eisCertNumber: String, manilaDate: String): String =
        "$eisCertNumber-${manilaDate.replace("-", "")}-${secureRandomString(12, HEX)}"
}
