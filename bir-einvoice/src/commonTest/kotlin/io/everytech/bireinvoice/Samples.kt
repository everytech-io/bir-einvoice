package io.everytech.bireinvoice

/** Valid CAS invoices for the three transaction classes, plus a credit memo, all checked by hand. */
object Samples {
    const val UID_VATABLE = "2026102920AB12CDABCD1234"
    const val UID_ZERO = "2026102920AB12CDABCD1235"
    const val UID_EXEMPT = "2026102920AB12CDABCD1236"
    const val UID_CREDIT = "2026102920AB12CDABCD1237"

    val seller = SellerInfo(
        tin = "123456789",
        branchCd = "00000",
        type = EisCodes.SELLER_VAT,
        regNm = "SAMPLE TRADING CORP.",
        businessNm = "Sample Trading",
        email = "billing@sample.test",
        regAddr = "1 Ayala Ave, Makati City",
    )

    val buyer = BuyerInfo(tin = "987654321", branchCd = "00000", regNm = "BUYER INC.", businessNm = "BUYER INC.", regAddr = "Pasig City")

    private fun zeroDiscount() = Discount(Amount.ZERO, Amount.ZERO, Amount.ZERO, Amount.ZERO)

    private fun item(name: String, qty: Long, unitCost: Long, unit: String = "", desc: String = name): CasItem {
        val sales = qty * unitCost / 100
        return CasItem(name, desc, Amount(qty), unit, Amount(unitCost), Amount(sales), Amount.ZERO, Amount.ZERO, Amount(sales))
    }

    private fun invoice(
        uid: String,
        transClass: String,
        items: List<CasItem>,
        vat: Long,
        number: String = "INV-00000001",
        docType: String = EisCodes.DOC_INVOICE,
        corrYN: String = "N",
        correctionCd: String = "",
        prevUniqueId: String = "",
        rmk1: String = "",
    ): CasInvoice {
        val net = items.sumOf { it.netSales.hundredths }
        return CasInvoice(
            compInvoiceId = number,
            issueDtm = uid.take(8),
            eisUniqueId = uid,
            docType = docType,
            transClass = transClass,
            corrYN = corrYN,
            correctionCd = correctionCd,
            prevUniqueId = prevUniqueId,
            rmk1 = rmk1,
            sellerInfo = seller,
            buyerInfo = buyer,
            itemList = items,
            totNetItemSales = Amount(net),
            discount = zeroDiscount(),
            otherTaxRev = Amount.ZERO,
            totNetSalesAftDisct = Amount(net),
            vatAmt = Amount(vat),
            withholdIncome = Amount.ZERO,
            withholdBusVat = Amount.ZERO,
            withholdBusPt = Amount.ZERO,
            otherNonTaxCharge = Amount.ZERO,
            netAmtPay = Amount(net + vat),
            ptuNum = "AC-0001",
        )
    }

    /** 3 x 1,000.00 VATable, VAT 360.00. */
    fun vatable(uid: String = UID_VATABLE) = invoice(uid, EisCodes.TRANS_VATABLE, listOf(item("Widget", 300, 100000, "pc", "Blue")), 36000)

    /** 1 x 500.00 zero-rated. */
    fun zeroRated(uid: String = UID_ZERO) = invoice(uid, EisCodes.TRANS_ZERO_RATED, listOf(item("Export crate", 100, 50000)), 0)

    /** 2.5 x 100.00 VAT-exempt. */
    fun exempt(uid: String = UID_EXEMPT) = invoice(uid, EisCodes.TRANS_EXEMPT, listOf(item("Book", 250, 10000)), 0)

    /** Returns one of the three widgets: -1 x 1,000.00, VAT -120.00. */
    fun creditMemo(uid: String = UID_CREDIT, prevUniqueId: String = UID_VATABLE) = invoice(
        uid,
        EisCodes.TRANS_VATABLE,
        listOf(item("Widget", -100, 100000, "pc", "Blue")),
        -12000,
        number = "CN-00000001",
        docType = EisCodes.DOC_CREDIT_MEMO,
        corrYN = "Y",
        correctionCd = "05",
        prevUniqueId = prevUniqueId,
        rmk1 = "Damaged on arrival (corrects INV-00000001)",
    )

    const val VATABLE_JSON =
        """{"CompInvoiceId":"INV-00000001","IssueDtm":"20261029","EisUniqueId":"2026102920AB12CDABCD1234","DocType":"01",""" +
            """"TransClass":"01","CorrYN":"N","CorrectionCd":"","PrevUniqueId":"","Rmk1":"",""" +
            """"SellerInfo":{"Tin":"123456789","BranchCd":"00000","Type":"0","RegNm":"SAMPLE TRADING CORP.","BusinessNm":"Sample Trading",""" +
            """"Email":"billing@sample.test","RegAddr":"1 Ayala Ave, Makati City"},""" +
            """"BuyerInfo":{"Tin":"987654321","BranchCd":"00000","RegNm":"BUYER INC.","BusinessNm":"BUYER INC.","Email":"",""" +
            """"RegAddr":"Pasig City","DevAddr":"","AirNum":"","AirNumDt":"","LadNum":"","LadNumDt":""},""" +
            """"ItemList":[{"Nm":"Widget","Desc":"Blue","Qty":3.00,"Unit":"pc","UnitCost":1000.00,"SalesAmt":3000.00,""" +
            """"RegDscntAmt":0.00,"SpeDscntAmt":0.00,"NetSales":3000.00}],"TotNetItemSales":3000.00,""" +
            """"Discount":{"ScAmt":0.00,"PwdAmt":0.00,"RegAmt":0.00,"SpeAmt":0.00,"Rmk2":""},"OtherTaxRev":0.00,""" +
            """"TotNetSalesAftDisct":3000.00,"VATAmt":360.00,"WithholdIncome":0.00,"WithholdBusVAT":0.00,"WithholdBusPT":0.00,""" +
            """"OtherNonTaxCharge":0.00,"NetAmtPay":3360.00,"PtuNum":"AC-0001"}"""
}
