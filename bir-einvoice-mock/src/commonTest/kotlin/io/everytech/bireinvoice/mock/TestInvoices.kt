package io.everytech.bireinvoice.mock

import io.everytech.bireinvoice.Amount
import io.everytech.bireinvoice.BuyerInfo
import io.everytech.bireinvoice.CasInvoice
import io.everytech.bireinvoice.CasItem
import io.everytech.bireinvoice.Discount
import io.everytech.bireinvoice.EisCodes
import io.everytech.bireinvoice.EisIds
import io.everytech.bireinvoice.EisJson
import io.everytech.bireinvoice.SellerInfo

/** Valid CAS invoices for tests. Quantities and unit costs in hundredths. */
object TestInvoices {
    fun build(
        eisCertNumber: String,
        issueDate: String,
        transClass: String = EisCodes.TRANS_VATABLE,
        qty: Long = 300,
        unitCost: Long = 100_000,
        number: String = "INV-00000001",
        prevUniqueId: String? = null,
    ): CasInvoice {
        val credit = prevUniqueId != null
        val sign = if (credit) -1 else 1
        val sales = sign * qty * unitCost / 100
        val vat = if (transClass == EisCodes.TRANS_VATABLE) sign * ((qty * unitCost / 100) * 12 + 50) / 100 else 0
        return CasInvoice(
            compInvoiceId = number,
            issueDtm = issueDate.replace("-", ""),
            eisUniqueId = EisIds.eisUniqueId(issueDate, eisCertNumber),
            docType = if (credit) EisCodes.DOC_CREDIT_MEMO else EisCodes.DOC_INVOICE,
            transClass = transClass,
            corrYN = if (credit) "Y" else "N",
            correctionCd = if (credit) "05" else "",
            prevUniqueId = prevUniqueId ?: "",
            rmk1 = if (credit) "Returned (corrects INV-00000001)" else "",
            sellerInfo = SellerInfo("123456789", "00000", EisCodes.SELLER_VAT, "SAMPLE TRADING CORP.", "Sample Trading", "billing@sample.test", "1 Ayala Ave, Makati City"),
            buyerInfo = BuyerInfo("987654321", "00000", "BUYER INC.", "BUYER INC.", regAddr = "Pasig City"),
            itemList = listOf(
                CasItem("Widget", "Blue", Amount(sign * qty), "pc", Amount(unitCost), Amount(sales), Amount.ZERO, Amount.ZERO, Amount(sales)),
            ),
            totNetItemSales = Amount(sales),
            discount = Discount(Amount.ZERO, Amount.ZERO, Amount.ZERO, Amount.ZERO),
            otherTaxRev = Amount.ZERO,
            totNetSalesAftDisct = Amount(sales),
            vatAmt = Amount(vat),
            withholdIncome = Amount.ZERO,
            withholdBusVat = Amount.ZERO,
            withholdBusPt = Amount.ZERO,
            otherNonTaxCharge = Amount.ZERO,
            netAmtPay = Amount(sales + vat),
            ptuNum = "AC-0001",
        )
    }

    fun json(invoice: CasInvoice): String = EisJson.encode(invoice)
}
