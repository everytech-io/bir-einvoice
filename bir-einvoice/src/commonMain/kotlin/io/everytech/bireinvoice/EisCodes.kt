package io.everytech.bireinvoice

import kotlin.jvm.JvmStatic

/** Code values from the BIR EIS API guide v2.3 and the e-invoice JSON format, with plain labels. */
public object EisCodes {
    public const val DOC_INVOICE: String = "01"
    public const val DOC_DEBIT_MEMO: String = "02"
    public const val DOC_CREDIT_MEMO: String = "03"

    public const val TRANS_VATABLE: String = "01"
    public const val TRANS_ZERO_RATED: String = "02"
    public const val TRANS_EXEMPT: String = "03"
    public const val TRANS_RBE_LOCAL: String = "04"

    public const val SELLER_VAT: String = "0"
    public const val SELLER_NON_VAT: String = "1"
    public const val SELLER_RBE: String = "2"

    /** The PrevUniqueId to use when a credit memo corrects a document that was never in EIS. */
    public const val UNMANAGED_ORIGINAL: String = "000000000000000000000000"

    /** The EIS certification sandbox. It accepts one call per 10 seconds (E19). */
    public const val SANDBOX_BASE_URL: String = "https://eis-cert.bir.gov.ph"

    public const val PROCESS_DONE: String = "01"
    public const val PROCESS_IN_PROGRESS: String = "02"
    public const val PROCESS_FAILED: String = "03"

    public const val RESULT_PASSED: String = "SUC001"

    @JvmStatic
    public val docTypes: Map<String, String> = mapOf(
        DOC_INVOICE to "Invoice",
        DOC_DEBIT_MEMO to "Debit Memo/Note",
        DOC_CREDIT_MEMO to "Credit Memo/Note",
    )

    @JvmStatic
    public val transClasses: Map<String, String> = mapOf(
        TRANS_VATABLE to "VATable",
        TRANS_ZERO_RATED to "Zero-rated",
        TRANS_EXEMPT to "VAT-exempt",
        TRANS_RBE_LOCAL to "RBE local sales",
    )

    @JvmStatic
    public val sellerTypes: Map<String, String> = mapOf(
        SELLER_VAT to "VAT-registered",
        SELLER_NON_VAT to "Non-VAT",
        SELLER_RBE to "Registered business enterprise (RBE)",
    )

    @JvmStatic
    public val correctionCodes: Map<String, String> = mapOf(
        "01" to "Error in a mandatory field",
        "02" to "Duplication",
        "03" to "Addition or reduction of volume or amount",
        "04" to "Cancellation",
        "05" to "Return",
        "09" to "Others",
    )

    @JvmStatic
    public val processStatuses: Map<String, String> = mapOf(
        PROCESS_DONE to "Done",
        PROCESS_IN_PROGRESS to "In progress",
        PROCESS_FAILED to "Cannot be processed",
    )

    /** Request errors, with the fix from BIR's API Error Solution Table v1.1 in plain words. */
    @JvmStatic
    public val requestErrors: Map<String, String> = mapOf(
        "E01" to "EIS Cert Number not recognised. Use the EIS Cert Number shown on the EIS Cert page of your application, and for production, check that it is registered for production.",
        "E02" to "Application ID not recognised for this EIS Cert Number. Copy it from the application's Setting page in the EIS Certification Portal.",
        "E03" to "Auth token expired or invalid. Tokens last 6 hours; authenticate again.",
        "E04" to "HMAC signature rejected. Sign datetime + METHOD + path with the application key for authentication and with the session key for every other call.",
        "E05" to "Clock is off. The datetime header must be the current Philippine time (UTC+8) as yyyyMMddHHmmss. Keep the clock within 10 minutes of real time.",
        "E06" to "EIS key pair expired. BIR says this does not occur.",
        "E07" to "This server's public IP is not whitelisted for production. Add it (up to 4 per application) in the IP whitelisting step of the portal.",
        "E08" to "Sandbox is locked because the EIS certificate was already requested. Create a new application to keep testing.",
        "E11" to "Request body JSON does not match the API guide.",
        "E12" to "Unknown eisKeyId. BIR says this does not occur.",
        "E13" to "Expired eisKeyId. BIR says this does not occur.",
        "E14" to "Duplicate submit ID. Generate a new submitId for each new transmission.",
        "E15" to "Submit ID unknown, expired, or not yours. Inquire with the submitId you sent.",
        "E16" to "No result document for this submit ID. BIR says this does not occur.",
        "E17" to "Callback URL is not responding. Check the callback endpoint set in the portal.",
        "E18" to "Callback returned the wrong response shape. Return the structure the API guide describes.",
        "E19" to "Sandbox allows one call per 10 seconds. Wait and try again.",
        "E21" to "EIS could not decrypt the data. Check the key (application key, session key or EIS public key) and the algorithm.",
        "E22" to "Decrypted data is not valid JSON.",
        "E23" to "Wrong EIS Certification Portal user ID or password.",
        "E24" to "authKey is not a 32-character string.",
        "E31" to "Invalid callback response body. BIR says this does not occur.",
        "E99" to "EIS internal error. Send the full request headers and body to the EIS team.",
    )

    /** Per-invoice result codes returned by the inquiry API. */
    @JvmStatic
    public val resultCodes: Map<String, String> = mapOf(
        "SUC001" to "Passed",
        "SYN002" to "Invalid digital signature",
        "SYN003" to "Duplicate EisUniqueId",
        "SYN004" to "Schema error: missing or extra fields, codes, types, or number format",
        "ERR001" to "Seller TIN not registered",
        "ERR002" to "Issue date invalid, after transmission, or different from the date inside EisUniqueId",
        "ERR003" to "Transmission date invalid",
        "ERR004" to "Sales and VAT have different signs, or VAT is not 0 on exempt or zero-rated sales",
        "ERR005" to "Classification code error",
        "ERR006" to "EIS Cert Number inside EisUniqueId differs from the sender's",
        "ERR007" to "PrevUniqueId missing or not the seller's, or present on a document that is not a correction",
        "ERR011" to "Invalid document type",
        "ERR012" to "Invalid seller type",
        "ERR013" to "RBE local sale from a seller that is not an RBE",
        "ERR014" to "Invalid transaction classification",
        "ERR015" to "TIN and branch code are not an RBE",
        "ERR999" to "Other error",
    )
}
