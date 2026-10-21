package com.dejavu.core.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Sales currencies. SAR is the base: every amount the API sends is in SAR. */
@Serializable
enum class Currency(val symbol: String, val nameAr: String, val decimals: Int) {
    SAR("ر.س", "ريال سعودي", 2),
    YER("ر.ي", "ريال يمني", 0),
}

/** Formatting, conversion and arithmetic for the API's decimal-string amounts. */
object Money {
    /** How amounts are shown: [currency] at [rate] units per 1 SAR. */
    data class Display(val currency: Currency, val rate: BigDecimal) {
        companion object {
            val BASE = Display(Currency.SAR, BigDecimal.ONE)
        }
    }

    /**
     * Currency the app shows prices in. The admin app keeps the SAR base; the
     * customer app switches it when the shopper picks YER.
     */
    @Volatile
    var display: Display = Display.BASE

    fun parse(amount: String?): BigDecimal = amount?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    /**
     * A SAR amount in [currency] at [rate]. Same rule as the server: YER is rounded
     * to whole riyals, and every figure (line, total, balance) is converted on its own.
     */
    fun convert(amountSar: BigDecimal, currency: Currency, rate: BigDecimal): BigDecimal =
        if (currency == Currency.SAR) {
            amountSar.setScale(2, RoundingMode.HALF_UP)
        } else {
            amountSar.multiply(rate).setScale(currency.decimals, RoundingMode.HALF_UP)
        }

    /** A SAR amount in the [display] currency: "1140" → "1,140 ر.س" or "159,600 ر.ي". */
    fun format(amount: String?): String = format(parse(amount))

    fun format(amount: BigDecimal): String = format(amount, display.currency, display.rate)

    /** A SAR amount in [currency] at a given (e.g. an order's locked) [rate]. */
    fun format(amountSar: String?, currency: Currency, rate: String?): String =
        format(parse(amountSar), currency, rate?.toBigDecimalOrNull() ?: BigDecimal.ONE)

    fun format(amountSar: BigDecimal, currency: Currency, rate: BigDecimal): String = plain(convert(amountSar, currency, rate), currency)

    /** Always SAR, whatever the display currency (costs, accounting). */
    fun formatBase(amount: String?): String = plain(parse(amount).setScale(2, RoundingMode.HALF_UP), Currency.SAR)

    /** An amount already in [currency] (e.g. what was paid in YER). */
    fun plain(amount: BigDecimal, currency: Currency): String {
        val scaled = amount.setScale(currency.decimals, RoundingMode.HALF_UP)
        val pattern = if (scaled.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00"
        return "${DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(scaled)} ${currency.symbol}"
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
