package io.everytech.bireinvoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CasValidatorTest {
    private fun problems(json: String) = CasValidator.validate(json)

    private fun codes(json: String) = problems(json).map { it.code }

    private fun vatable() = Samples.VATABLE_JSON

    private fun assertProblem(json: String, code: String, field: String) {
        val found = problems(json)
        assertTrue(found.any { it.code == code && it.field == field }, "expected $code at $field, got $found")
    }

    @Test
    fun samplesPass() {
        for (invoice in listOf(Samples.vatable(), Samples.zeroRated(), Samples.exempt(), Samples.creditMemo())) {
            assertEquals(emptyList(), problems(EisJson.encode(invoice)), invoice.compInvoiceId)
        }
        val unmanaged = Samples.creditMemo(prevUniqueId = EisCodes.UNMANAGED_ORIGINAL)
        assertEquals(emptyList(), problems(EisJson.encode(unmanaged)))
    }

    @Test
    fun catchesFormulaErrors() {
        assertProblem(vatable().replace(""""SalesAmt":3000.00""", """"SalesAmt":3002.00"""), "SYN004", "ItemList[0].SalesAmt")
        assertProblem(vatable().replace(""""NetSales":3000.00""", """"NetSales":2999.00"""), "SYN004", "ItemList[0].NetSales")
        assertProblem(vatable().replace(""""TotNetItemSales":3000.00""", """"TotNetItemSales":3001.00"""), "SYN004", "TotNetItemSales")
        assertProblem(vatable().replace(""""OtherTaxRev":0.00""", """"OtherTaxRev":1.00"""), "SYN004", "TotNetSalesAftDisct")
        assertProblem(vatable().replace(""""NetAmtPay":3360.00""", """"NetAmtPay":3360.01"""), "SYN004", "NetAmtPay")
        assertProblem(vatable().replace(""""WithholdIncome":0.00""", """"WithholdIncome":30.00"""), "SYN004", "NetAmtPay")
    }

    @Test
    fun salesAmountAllowsOneCentavoOfRounding() {
        assertEquals(emptyList(), problems(vatable().replace(""""SalesAmt":3000.00""", """"SalesAmt":3000.01""")
            .replace(""""NetSales":3000.00""", """"NetSales":3000.01""")
            .replace(""""TotNetItemSales":3000.00""", """"TotNetItemSales":3000.01""")
            .replace(""""TotNetSalesAftDisct":3000.00""", """"TotNetSalesAftDisct":3000.01""")
            .replace(""""NetAmtPay":3360.00""", """"NetAmtPay":3360.01""")))
    }

    @Test
    fun vatOnZeroRatedIsErr004() {
        val zero = EisJson.encode(Samples.zeroRated())
            .replace(""""VATAmt":0.00""", """"VATAmt":60.00""")
            .replace(""""NetAmtPay":500.00""", """"NetAmtPay":560.00""")
        assertEquals(listOf(SchemaProblem("ERR004", "VATAmt", "must be 0 for zero-rated and exempt sales")), problems(zero))
    }

    @Test
    fun vatSignDifferentFromSalesIsErr004() {
        val text = vatable().replace(""""VATAmt":360.00""", """"VATAmt":-360.00""").replace(""""NetAmtPay":3360.00""", """"NetAmtPay":2640.00""")
        assertEquals(listOf("ERR004"), codes(text))
    }

    @Test
    fun unknownFieldsAreSyn004() {
        val top = vatable().replace("""{"CompInvoiceId"""", """{"Extra":1,"CompInvoiceId"""")
        assertEquals(listOf(SchemaProblem("SYN004", "Extra", "field is not in the spec")), problems(top))
        val nested = vatable().replace(""""Nm":"Widget"""", """"Nm":"Widget","Color":"blue"""")
        assertEquals(listOf(SchemaProblem("SYN004", "ItemList[0].Color", "field is not in the spec")), problems(nested))
        // Schema problems hide code checks, as in EIS: DocType 05 is not reported while an extra field is present.
        assertEquals(listOf("SYN004"), codes(top.replace(""""DocType":"01"""", """"DocType":"05"""")))
    }

    @Test
    fun rawNumbersWithThreeDecimalsAreRejected() {
        assertProblem(vatable().replace(""""OtherTaxRev":0.00""", """"OtherTaxRev":0.001"""), "SYN004", "OtherTaxRev")
        val trailingZero = problems(vatable().replace(""""VATAmt":360.00""", """"VATAmt":360.000"""))
        assertEquals(listOf(SchemaProblem("SYN004", "VATAmt", "more than 2 decimal places")), trailingZero)
    }

    @Test
    fun wholeAndOneDecimalNumbersAreAccepted() {
        assertEquals(emptyList(), problems(vatable().replace(""""VATAmt":360.00""", """"VATAmt":360""").replace(""""Qty":3.00""", """"Qty":3.0""")))
    }

    @Test
    fun typesAndPresence() {
        assertProblem(vatable().replace(""""VATAmt":360.00""", """"VATAmt":"360.00""""), "SYN004", "VATAmt")
        assertProblem(vatable().replace(""""VATAmt":360.00""", """"VATAmt":null"""), "SYN004", "VATAmt")
        assertProblem(vatable().replace(""""PtuNum":"AC-0001"""", """"PtuNum":"""""), "SYN004", "PtuNum")
        assertProblem(vatable().replace(""""CompInvoiceId":"INV-00000001",""", ""), "SYN004", "CompInvoiceId")
        assertProblem(vatable().replace(""""Tin":"123456789"""", """"Tin":"123-456-789""""), "SYN004", "SellerInfo.Tin")
        assertProblem(vatable().replace(""""Tin":"123456789"""", """"Tin":123456789"""), "SYN004", "SellerInfo.Tin")
        assertProblem(vatable().replace(""""BranchCd":"00000","Type":"0"""", """"BranchCd":"00000","Type":"7""""), "SYN004", "SellerInfo.Type")
        assertProblem(vatable().replace(""""Rmk1":""""", """"Rmk1":"${"x".repeat(501)}""""), "SYN004", "Rmk1")
        assertEquals(listOf(SchemaProblem("SYN004", "", "not valid JSON")), problems("{"))
        assertEquals(listOf(SchemaProblem("SYN004", "", "must be an object")), problems("[]"))
        assertProblem("""{"CompInvoiceId":"X"}""", "SYN004", "IssueDtm")
        val emptyItems = vatable().replace(Regex("\"ItemList\":\\[.*?]"), "\"ItemList\":[]")
        assertProblem(emptyItems, "SYN004", "ItemList")
    }

    @Test
    fun codesAndDates() {
        assertEquals(listOf("ERR011"), codes(vatable().replace(""""DocType":"01"""", """"DocType":"05"""")))
        assertEquals(listOf("ERR014"), codes(vatable().replace(""""TransClass":"01"""", """"TransClass":"07"""")))
        assertTrue("ERR013" in codes(vatable().replace(""""TransClass":"01"""", """"TransClass":"04"""")))
        val badDate = vatable().replace("20261029", "20260230")
        assertEquals(listOf(SchemaProblem("ERR002", "IssueDtm", "not a real date")), problems(badDate))
        assertEquals(listOf("ERR002"), codes(vatable().replace(""""IssueDtm":"20261029"""", """"IssueDtm":"20261028"""")))
    }

    @Test
    fun correctionRules() {
        val credit = EisJson.encode(Samples.creditMemo())
        assertEquals(listOf("ERR005"), codes(credit.replace(""""CorrectionCd":"05"""", """"CorrectionCd":"06"""")))
        assertEquals(listOf("ERR007"), codes(credit.replace(""""PrevUniqueId":"${Samples.UID_VATABLE}"""", """"PrevUniqueId":""""")))
        assertEquals(listOf("ERR005"), codes(vatable().replace(""""CorrectionCd":""""", """"CorrectionCd":"01"""")))
        assertEquals(listOf("ERR007"), codes(vatable().replace(""""PrevUniqueId":""""", """"PrevUniqueId":"${Samples.UID_ZERO}"""")))
    }

    @Test
    fun specialDiscountCountsOutsideVatableSales() {
        // On VATable lines the special discount is not taken off NetSales; on other classes it is.
        val exempt = EisJson.encode(Samples.exempt())
            .replace(""""SpeDscntAmt":0.00,"NetSales":250.00""", """"SpeDscntAmt":50.00,"NetSales":200.00""")
            .replace(""""TotNetItemSales":250.00""", """"TotNetItemSales":200.00""")
            .replace(""""TotNetSalesAftDisct":250.00""", """"TotNetSalesAftDisct":200.00""")
            .replace(""""NetAmtPay":250.00""", """"NetAmtPay":200.00""")
        assertEquals(emptyList(), problems(exempt))
    }
}
