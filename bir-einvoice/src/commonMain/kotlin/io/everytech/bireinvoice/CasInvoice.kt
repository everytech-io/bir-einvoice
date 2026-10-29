package io.everytech.bireinvoice

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmStatic

/**
 * One e-invoice in the CAS variant of the BIR e-invoice JSON format (CAS sheet v2.2 of JSON
 * File Format v2.1). Property order is the spec's field order, and [EisJson] writes it that way.
 *
 * Amounts are [Amount]s. A credit memo carries negative amounts and quantities, as in BIR's
 * correction scenarios.
 */
@Serializable
public data class CasInvoice(
    @SerialName("CompInvoiceId") val compInvoiceId: String,
    @SerialName("IssueDtm") val issueDtm: String,
    @SerialName("EisUniqueId") val eisUniqueId: String,
    @SerialName("DocType") val docType: String,
    @SerialName("TransClass") val transClass: String,
    @SerialName("CorrYN") val corrYN: String,
    @SerialName("CorrectionCd") val correctionCd: String = "",
    @SerialName("PrevUniqueId") val prevUniqueId: String = "",
    @SerialName("Rmk1") val rmk1: String = "",
    @SerialName("SellerInfo") val sellerInfo: SellerInfo,
    @SerialName("BuyerInfo") val buyerInfo: BuyerInfo,
    @SerialName("ItemList") val itemList: List<CasItem>,
    @SerialName("TotNetItemSales") val totNetItemSales: Amount,
    @SerialName("Discount") val discount: Discount,
    @SerialName("OtherTaxRev") val otherTaxRev: Amount,
    @SerialName("TotNetSalesAftDisct") val totNetSalesAftDisct: Amount,
    @SerialName("VATAmt") val vatAmt: Amount,
    @SerialName("WithholdIncome") val withholdIncome: Amount,
    @SerialName("WithholdBusVAT") val withholdBusVat: Amount,
    @SerialName("WithholdBusPT") val withholdBusPt: Amount,
    @SerialName("OtherNonTaxCharge") val otherNonTaxCharge: Amount,
    @SerialName("NetAmtPay") val netAmtPay: Amount,
    /** Omitted from the JSON when null. */
    @SerialName("ForCur") val forCur: ForCur? = null,
    @SerialName("PtuNum") val ptuNum: String,
)

@Serializable
public data class SellerInfo(
    @SerialName("Tin") val tin: String,
    @SerialName("BranchCd") val branchCd: String,
    /** [EisCodes.SELLER_VAT], [EisCodes.SELLER_NON_VAT] or [EisCodes.SELLER_RBE]. */
    @SerialName("Type") val type: String,
    @SerialName("RegNm") val regNm: String,
    @SerialName("BusinessNm") val businessNm: String = "",
    @SerialName("Email") val email: String = "",
    @SerialName("RegAddr") val regAddr: String,
)

@Serializable
public data class BuyerInfo(
    @SerialName("Tin") val tin: String,
    @SerialName("BranchCd") val branchCd: String,
    @SerialName("RegNm") val regNm: String,
    @SerialName("BusinessNm") val businessNm: String = "",
    @SerialName("Email") val email: String = "",
    @SerialName("RegAddr") val regAddr: String = "",
    @SerialName("DevAddr") val devAddr: String = "",
    @SerialName("AirNum") val airNum: String = "",
    @SerialName("AirNumDt") val airNumDt: String = "",
    @SerialName("LadNum") val ladNum: String = "",
    @SerialName("LadNumDt") val ladNumDt: String = "",
)

@Serializable
public data class CasItem(
    @SerialName("Nm") val nm: String,
    @SerialName("Desc") val desc: String,
    @SerialName("Qty") val qty: Amount,
    @SerialName("Unit") val unit: String = "",
    @SerialName("UnitCost") val unitCost: Amount,
    @SerialName("SalesAmt") val salesAmt: Amount,
    @SerialName("RegDscntAmt") val regDscntAmt: Amount,
    @SerialName("SpeDscntAmt") val speDscntAmt: Amount,
    @SerialName("NetSales") val netSales: Amount,
)

@Serializable
public data class Discount(
    @SerialName("ScAmt") val scAmt: Amount,
    @SerialName("PwdAmt") val pwdAmt: Amount,
    @SerialName("RegAmt") val regAmt: Amount,
    @SerialName("SpeAmt") val speAmt: Amount,
    @SerialName("Rmk2") val rmk2: String = "",
)

@Serializable
public data class ForCur(
    @SerialName("Currency") val currency: String,
    @SerialName("ConvRate") val convRate: Amount,
    @SerialName("ForexAmt") val forexAmt: Amount,
)

/** The JSON settings EIS payloads need: defaults written out, null optional objects left out. */
public object EisJson {
    @OptIn(ExperimentalSerializationApi::class)
    public val json: Json = Json {
        explicitNulls = false
        encodeDefaults = true
        // The spec allows null for an empty string; read it as the field's default.
        coerceInputValues = true
    }

    /** Compact JSON in spec field order, amounts with exactly two decimals. */
    @JvmStatic
    public fun encode(invoice: CasInvoice): String = json.encodeToString(CasInvoice.serializer(), invoice)

    @JvmStatic
    public fun decode(text: String): CasInvoice = json.decodeFromString(CasInvoice.serializer(), text)
}

// Responses from EIS may grow new fields; reading them should not break.
internal val responseJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}
