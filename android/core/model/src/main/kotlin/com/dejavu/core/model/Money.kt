package com.dejavu.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A currency the store sells or holds money in. SAR is the base: every amount the API
 * sends is in SAR. Others (YER, USD, …) come from the server and can be added by staff,
 * so this is a registry keyed by code rather than an enum; it travels as its code.
 */
@Serializable(with = CurrencySerializer::class)
class Currency private constructor(val code: String, val symbol: String, val nameAr: String, val decimals: Int) {
    val isBase: Boolean get() = code == SAR.code

    override fun equals(other: Any?) = other is Currency && other.code == code
    override fun hashCode() = code.hashCode()
    override fun toString() = code

    companion object {
        private val registry = linkedMapOf<String, Currency>()

        private fun put(c: Currency): Currency = c.also { registry[c.code] = c }

        val SAR = put(Currency("SAR", "ر.س", "ريال سعودي", 2))
        val YER = put(Currency("YER", "ر.ي", "ريال يمني", 0))

        /** The currency with [code]; unknown codes show their code until the server describes them. */
        fun of(code: String): Currency = synchronized(registry) { registry[code] ?: put(Currency(code, code, code, 2)) }

        /** Records how the server describes a currency (symbol, Arabic name, decimals). */
        fun register(code: String, symbol: String, nameAr: String, decimals: Int): Currency =
            synchronized(registry) { put(Currency(code, symbol, nameAr, decimals)) }

        /** Every currency seen so far, base first. */
        fun known(): List<Currency> = synchronized(registry) { registry.values.toList() }
    }
}

object CurrencySerializer : KSerializer<Currency> {
    override val descriptor = PrimitiveSerialDescriptor("Currency", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Currency) = encoder.encodeString(value.code)
    override fun deserialize(decoder: Decoder): Currency = Currency.of(decoder.decodeString())
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

    fun parse(amount: String?): BigDecimal = amount?.let { Numerals.latin(it).toBigDecimalOrNull() } ?: BigDecimal.ZERO

    /**
     * A SAR amount in [currency] at [rate]. Same rule as the server: YER is rounded
     * to whole riyals, and every figure (line, total, balance) is converted on its own.
     */
    fun convert(amountSar: BigDecimal, currency: Currency, rate: BigDecimal): BigDecimal =
        if (currency.isBase) {
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

    /** SAR value of [amount] handed over in [currency] at [rate] (units per 1 SAR). */
    fun toBase(amount: BigDecimal, currency: Currency, rate: BigDecimal): BigDecimal =
        if (currency.isBase || rate.signum() == 0) amount.setScale(2, RoundingMode.HALF_UP) else amount.divide(rate, 2, RoundingMode.HALF_UP)

    /** An amount already in [currency] (e.g. what was paid in YER). */
    fun plain(amount: BigDecimal, currency: Currency): String {
        val scaled = amount.setScale(currency.decimals, RoundingMode.HALF_UP)
        val pattern = if (scaled.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00"
        return Numerals.of("${DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(scaled)} ${currency.symbol}")
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
