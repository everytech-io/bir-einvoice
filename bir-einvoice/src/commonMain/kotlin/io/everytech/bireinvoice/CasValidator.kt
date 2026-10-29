package io.everytech.bireinvoice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmStatic
import kotlin.math.abs
import kotlin.math.sign

/** One reason a payload would fail BIR's checks. [code] is the BIR result code it maps to. */
public data class SchemaProblem(val code: String, val field: String, val message: String)

/**
 * Checks a CAS e-invoice JSON document the way EIS does: unknown and missing fields, lengths,
 * formats, numbers written with at most two decimals, code values, and the field-table formulas
 * to the centavo. It reads the JSON text itself, so `1.234` is caught even where a parsed double
 * would hide it.
 *
 * Passing here is necessary, not sufficient. The BIR sandbox has the final word.
 */
public object CasValidator {
    private sealed interface Rule
    private class Str(val max: Int, val required: Boolean = true, val pattern: Regex? = null) : Rule
    private object Num : Rule
    private class Obj(val fields: Map<String, Rule>, val optional: Boolean = false) : Rule
    private class Arr(val min: Int, val max: Int, val fields: Map<String, Rule>) : Rule

    private val SELLER: Map<String, Rule> = linkedMapOf(
        "Tin" to Str(9, true, Regex("\\d{9}")),
        "BranchCd" to Str(5, true, Regex("\\d{5}")),
        "Type" to Str(1, true, Regex("[012]")),
        "RegNm" to Str(200),
        "BusinessNm" to Str(200, false),
        "Email" to Str(100, false),
        "RegAddr" to Str(300),
    )
    private val BUYER: Map<String, Rule> = linkedMapOf(
        "Tin" to Str(9, true, Regex("\\d{9}")),
        "BranchCd" to Str(5, true, Regex("\\d{5}")),
        "RegNm" to Str(200),
        "BusinessNm" to Str(200, false),
        "Email" to Str(100, false),
        "RegAddr" to Str(300, false),
        "DevAddr" to Str(300, false),
        "AirNum" to Str(50, false),
        "AirNumDt" to Str(8, false),
        "LadNum" to Str(50, false),
        "LadNumDt" to Str(8, false),
    )
    private val ITEM: Map<String, Rule> = linkedMapOf(
        "Nm" to Str(100),
        "Desc" to Str(100),
        "Qty" to Num,
        "Unit" to Str(50, false),
        "UnitCost" to Num,
        "SalesAmt" to Num,
        "RegDscntAmt" to Num,
        "SpeDscntAmt" to Num,
        "NetSales" to Num,
    )
    private val DISCOUNT: Map<String, Rule> = linkedMapOf(
        "ScAmt" to Num,
        "PwdAmt" to Num,
        "RegAmt" to Num,
        "SpeAmt" to Num,
        "Rmk2" to Str(500, false),
    )
    private val FORCUR: Map<String, Rule> = linkedMapOf(
        "Currency" to Str(3, true, Regex("[A-Z]{3}")),
        "ConvRate" to Num,
        "ForexAmt" to Num,
    )
    private val TOP: Map<String, Rule> = linkedMapOf(
        "CompInvoiceId" to Str(50),
        "IssueDtm" to Str(8, true, Regex("\\d{8}")),
        "EisUniqueId" to Str(24, true, Regex("\\d{8}[0-9A-Za-z]{8}[0-9A-Za-z]{8}")),
        "DocType" to Str(2, true),
        "TransClass" to Str(2, true),
        "CorrYN" to Str(1, true, Regex("[YN]")),
        "CorrectionCd" to Str(2, false),
        "PrevUniqueId" to Str(24, false),
        "Rmk1" to Str(500, false),
        "SellerInfo" to Obj(SELLER),
        "BuyerInfo" to Obj(BUYER),
        "ItemList" to Arr(1, 1000, ITEM),
        "TotNetItemSales" to Num,
        "Discount" to Obj(DISCOUNT),
        "OtherTaxRev" to Num,
        "TotNetSalesAftDisct" to Num,
        "VATAmt" to Num,
        "WithholdIncome" to Num,
        "WithholdBusVAT" to Num,
        "WithholdBusPT" to Num,
        "OtherNonTaxCharge" to Num,
        "NetAmtPay" to Num,
        "ForCur" to Obj(FORCUR, optional = true),
        "PtuNum" to Str(50),
    )

    private val CORRECTION_CODES = setOf("01", "02", "03", "04", "05", "09")
    private val ALNUM_24 = Regex("[0-9A-Za-z]{24}")

    /** Returns an empty list when the payload passes. */
    @JvmStatic
    public fun validate(json: String): List<SchemaProblem> {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return listOf(SchemaProblem("SYN004", "", "not valid JSON"))
        }
        return validate(root)
    }

    /** The same checks on an already parsed document. */
    @JvmStatic
    public fun validate(root: JsonElement): List<SchemaProblem> {
        val problems = mutableListOf<SchemaProblem>()
        checkFields(root, TOP, "", problems)
        if (problems.isNotEmpty()) return problems
        checkRules(root.jsonObject, problems)
        return problems
    }

    private fun checkFields(value: JsonElement, fields: Map<String, Rule>, path: String, problems: MutableList<SchemaProblem>) {
        if (value !is JsonObject) {
            problems += SchemaProblem("SYN004", path, "must be an object")
            return
        }
        for (key in value.keys) {
            if (key !in fields) problems += SchemaProblem("SYN004", "$path$key", "field is not in the spec")
        }
        for ((key, rule) in fields) {
            val v = value[key]
            val at = "$path$key"
            when (rule) {
                is Str -> {
                    if (v == null) {
                        if (rule.required) problems += SchemaProblem("SYN004", at, "missing")
                        continue
                    }
                    if (v !is JsonNull && !(v is JsonPrimitive && v.isString)) {
                        problems += SchemaProblem("SYN004", at, "must be a string")
                    }
                    val text = when (v) {
                        is JsonNull -> ""
                        is JsonPrimitive -> v.content
                        else -> v.toString()
                    }
                    if (rule.required && text.isBlank()) problems += SchemaProblem("SYN004", at, "is empty")
                    if (text.length > rule.max) problems += SchemaProblem("SYN004", at, "longer than ${rule.max}")
                    if (rule.pattern != null && text.isNotEmpty() && !rule.pattern.matches(text)) {
                        problems += SchemaProblem("SYN004", at, "bad format")
                    }
                }
                Num -> {
                    val parsed = (v as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.let { parseDecimal(it.content) }
                    when {
                        parsed == null -> problems += SchemaProblem("SYN004", at, "must be a number (use 0.00 when empty)")
                        parsed.tooLarge -> problems += SchemaProblem("SYN004", at, "is too large")
                        parsed.decimals > 2 -> problems += SchemaProblem("SYN004", at, "more than 2 decimal places")
                    }
                }
                is Obj -> {
                    if (v == null) {
                        if (!rule.optional) problems += SchemaProblem("SYN004", at, "missing")
                        continue
                    }
                    checkFields(v, rule.fields, "$at.", problems)
                }
                is Arr -> {
                    if (v !is JsonArray) {
                        problems += SchemaProblem("SYN004", at, "must be an array")
                        continue
                    }
                    if (v.size < rule.min || v.size > rule.max) {
                        problems += SchemaProblem("SYN004", at, "needs ${rule.min} to ${rule.max} entries")
                    }
                    v.forEachIndexed { i, item -> checkFields(item, rule.fields, "$at[$i].", problems) }
                }
            }
        }
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""

    // Only called after checkFields passed, so every number is present with at most 2 decimals.
    private fun JsonObject.cents(key: String): Long = parseDecimal((this[key] as JsonPrimitive).content)!!.hundredths!!

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject

    private fun checkRules(p: JsonObject, problems: MutableList<SchemaProblem>) {
        fun add(code: String, field: String, message: String) {
            problems += SchemaProblem(code, field, message)
        }

        val date = p.text("IssueDtm")
        if (!isRealDate(date)) add("ERR002", "IssueDtm", "not a real date")
        if (p.text("EisUniqueId").take(8) != date) add("ERR002", "EisUniqueId", "date part differs from IssueDtm")

        val docType = p.text("DocType")
        val transClass = p.text("TransClass")
        if (docType !in setOf("01", "02", "03")) add("ERR011", "DocType", "must be 01 (Invoice) or 03 (Credit Memo)")
        if (transClass !in setOf("01", "02", "03", "04")) add("ERR014", "TransClass", "must be 01, 02, 03 or 04")
        if (transClass == "04" && p.obj("SellerInfo").text("Type") != "2") {
            add("ERR013", "TransClass", "RBE local sales need seller Type 2")
        }

        val correctionCd = p.text("CorrectionCd")
        val prevUniqueId = p.text("PrevUniqueId")
        if (p.text("CorrYN") == "Y") {
            if (correctionCd !in CORRECTION_CODES) add("ERR005", "CorrectionCd", "required when CorrYN is Y")
            if (!ALNUM_24.matches(prevUniqueId)) add("ERR007", "PrevUniqueId", "24 characters required when CorrYN is Y")
        } else {
            if (correctionCd.isNotEmpty()) add("ERR005", "CorrectionCd", "must be empty when CorrYN is N")
            if (prevUniqueId.isNotEmpty()) add("ERR007", "PrevUniqueId", "must be empty on a general invoice")
        }

        // Formulas from the field table, to the centavo.
        var sumNet = 0L
        (p["ItemList"] as JsonArray).forEachIndexed { i, element ->
            val item = element as JsonObject
            val at = "ItemList[$i]."
            val sales = item.cents("SalesAmt")
            val product = multiplyOrNull(item.cents("Qty"), item.cents("UnitCost"))
            if (product == null || abs(sales - divRound(product, 100)) > 1) add("SYN004", "${at}SalesAmt", "is not Qty x UnitCost")
            val special = if (transClass == "01") 0 else item.cents("SpeDscntAmt")
            if (item.cents("NetSales") != sales - item.cents("RegDscntAmt") - special) {
                add("SYN004", "${at}NetSales", "is not SalesAmt minus discounts")
            }
            sumNet += item.cents("NetSales")
        }
        if (p.cents("TotNetItemSales") != sumNet) add("SYN004", "TotNetItemSales", "is not the sum of NetSales")
        val d = p.obj("Discount")
        val afterDisc = p.cents("TotNetItemSales") + p.cents("OtherTaxRev") -
            d.cents("ScAmt") - d.cents("PwdAmt") - d.cents("RegAmt") - d.cents("SpeAmt")
        if (p.cents("TotNetSalesAftDisct") != afterDisc) add("SYN004", "TotNetSalesAftDisct", "does not match the formula")
        val vat = p.cents("VATAmt")
        if (transClass in setOf("02", "03") && vat != 0L) add("ERR004", "VATAmt", "must be 0 for zero-rated and exempt sales")
        if (vat != 0L && vat.sign != p.cents("TotNetSalesAftDisct").sign) add("ERR004", "VATAmt", "sign differs from total sales")
        val pay = p.cents("TotNetSalesAftDisct") + vat + p.cents("OtherNonTaxCharge") -
            p.cents("WithholdIncome") - p.cents("WithholdBusVAT") - p.cents("WithholdBusPT")
        if (p.cents("NetAmtPay") != pay) add("SYN004", "NetAmtPay", "does not match the formula")
    }

    private fun isRealDate(yyyymmdd: String): Boolean {
        if (yyyymmdd.length != 8 || !yyyymmdd.all { it in '0'..'9' }) return false
        val year = yyyymmdd.substring(0, 4).toInt()
        val month = yyyymmdd.substring(4, 6).toInt()
        val day = yyyymmdd.substring(6, 8).toInt()
        if (month !in 1..12 || day < 1) return false
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= days
    }
}
