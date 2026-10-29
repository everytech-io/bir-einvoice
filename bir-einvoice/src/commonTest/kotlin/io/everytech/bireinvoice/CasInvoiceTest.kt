package io.everytech.bireinvoice

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CasInvoiceTest {
    @Test
    fun encodesExactBirFieldNamesAndOrder() {
        assertEquals(Samples.VATABLE_JSON, EisJson.encode(Samples.vatable()))
    }

    @Test
    fun roundTripsThroughJson() {
        for (invoice in listOf(Samples.vatable(), Samples.zeroRated(), Samples.exempt(), Samples.creditMemo())) {
            val text = EisJson.encode(invoice)
            assertEquals(invoice, EisJson.decode(text))
            assertEquals(text, EisJson.encode(EisJson.decode(text)))
        }
    }

    @Test
    fun creditMemoCarriesNegativeAmounts() {
        val text = EisJson.encode(Samples.creditMemo())
        assertTrue(""""Qty":-1.00""" in text, text)
        assertTrue(""""VATAmt":-120.00""" in text, text)
        assertTrue(""""NetAmtPay":-1120.00""" in text, text)
        assertTrue(""""DocType":"03"""" in text && """"CorrYN":"Y"""" in text && """"CorrectionCd":"05"""" in text, text)
    }

    @Test
    fun forCurIsOmittedWhenNullAndPlacedBeforePtuNumWhenSet() {
        assertFalse("ForCur" in EisJson.encode(Samples.vatable()))
        val withForex = Samples.vatable().copy(forCur = ForCur("USD", Amount(5650), Amount(5310)))
        val text = EisJson.encode(withForex)
        assertTrue(text.endsWith(""""NetAmtPay":3360.00,"ForCur":{"Currency":"USD","ConvRate":56.50,"ForexAmt":53.10},"PtuNum":"AC-0001"}"""), text)
        assertEquals(withForex, EisJson.decode(text))
        assertEquals(emptyList(), CasValidator.validate(text))
    }

    @Test
    fun decodeReadsNullStringsAsEmptyAndWholeNumbers() {
        val text = Samples.VATABLE_JSON
            .replace(""""Rmk1":""""", """"Rmk1":null""")
            .replace(""""VATAmt":360.00""", """"VATAmt":360""")
        val decoded = EisJson.decode(text)
        assertEquals("", decoded.rmk1)
        assertEquals(Amount(36000), decoded.vatAmt)
    }

    @Test
    fun decodeRejectsUnknownFields() {
        assertFailsWith<SerializationException> { EisJson.decode(Samples.VATABLE_JSON.replace("""{"CompInvoiceId"""", """{"Extra":1,"CompInvoiceId"""")) }
    }
}
