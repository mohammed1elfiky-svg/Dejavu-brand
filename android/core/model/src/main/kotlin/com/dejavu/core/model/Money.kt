package com.dejavu.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Formatting and arithmetic for the API's decimal-string amounts. */
object Money {
    private const val CURRENCY = "ج.م"

    fun parse(amount: String?): BigDecimal = amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** "1140" → "1,140 ج.م", "99.5" → "99.50 ج.م" (Western digits, as most Egyptian shops use). */
    fun format(amount: String?): String = format(parse(amount))

    fun format(amount: BigDecimal): String {
        val scaled = amount.setScale(2, RoundingMode.HALF_UP)
        val pattern = if (scaled.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00"
        return "${DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(scaled)} $CURRENCY"
    }

    fun isZero(amount: String?): Boolean = parse(amount).signum() == 0

    /** Percentage saved versus the strike-through price, e.g. 16 for 1850 vs 2200. */
    fun discountPercent(price: String, compareAt: String?): Int? {
        val was = parse(compareAt)
        val now = parse(price)
        if (was <= now || was.signum() == 0) return null
        return was.subtract(now).multiply(BigDecimal(100)).divide(was, 0, RoundingMode.HALF_UP).toInt()
    }
}
