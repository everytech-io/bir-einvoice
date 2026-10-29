package io.everytech.bireinvoice.mock

import io.everytech.bireinvoice.Amount
import io.everytech.bireinvoice.BuyerInfo
import io.everytech.bireinvoice.CasInvoice
import io.everytech.bireinvoice.CasItem
import io.everytech.bireinvoice.CasValidator
import io.everytech.bireinvoice.Discount
import io.everytech.bireinvoice.EisClient
import io.everytech.bireinvoice.EisCodes
import io.everytech.bireinvoice.EisCrypto
import io.everytech.bireinvoice.EisIds
import io.everytech.bireinvoice.EisJson
import io.everytech.bireinvoice.SellerInfo
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The README's Kotlin example, against the mock, so the documented code keeps working. */
class ReadmeExampleTest {
    @Test
    fun readmeInvoicePassesValidationAndTheMock() {
        MockEisServer(processingPolls = 1).use { mock ->
            mock.start()
            val credentials = mock.credentials
            val client = EisClient(credentials, sandboxSpacingMillis = 0)
            val today = EisCrypto.manilaDatetime(Instant.now()).take(8)
            val issueDate = "${today.take(4)}-${today.substring(4, 6)}-${today.takeLast(2)}"
            val invoice = CasInvoice(
                compInvoiceId = "INV-00000001",
                issueDtm = today,
                eisUniqueId = EisIds.eisUniqueId(issueDate, credentials.eisCertNumber),
                docType = EisCodes.DOC_INVOICE,
                transClass = EisCodes.TRANS_VATABLE,
                corrYN = "N",
                sellerInfo = SellerInfo(
                    tin = "123456789", branchCd = "00000", type = EisCodes.SELLER_VAT,
                    regNm = "SAMPLE TRADING CORP.", regAddr = "1 Ayala Ave, Makati City",
                ),
                buyerInfo = BuyerInfo(tin = "987654321", branchCd = "00000", regNm = "BUYER INC."),
                itemList = listOf(
                    CasItem(
                        nm = "Widget", desc = "Blue", qty = Amount.parse("3"), unit = "pc",
                        unitCost = Amount.parse("1000.00"), salesAmt = Amount.parse("3000.00"),
                        regDscntAmt = Amount.ZERO, speDscntAmt = Amount.ZERO, netSales = Amount.parse("3000.00"),
                    ),
                ),
                totNetItemSales = Amount.parse("3000.00"),
                discount = Discount(Amount.ZERO, Amount.ZERO, Amount.ZERO, Amount.ZERO),
                otherTaxRev = Amount.ZERO,
                totNetSalesAftDisct = Amount.parse("3000.00"),
                vatAmt = Amount.parse("360.00"),
                withholdIncome = Amount.ZERO,
                withholdBusVat = Amount.ZERO,
                withholdBusPt = Amount.ZERO,
                otherNonTaxCharge = Amount.ZERO,
                netAmtPay = Amount.parse("3360.00"),
                ptuNum = "AC-0001",
            )
            val json = EisJson.encode(invoice)
            assertEquals(emptyList(), CasValidator.validate(json))
            val ack = client.submit(listOf(json))
            var result = client.inquire(ack.submitId)
            while (result.processStatusCode == EisCodes.PROCESS_IN_PROGRESS) result = client.inquire(ack.submitId)
            assertEquals(listOf("SUC001"), result.processedDocuments.map { it.resultStatusCode })
        }
    }
}
