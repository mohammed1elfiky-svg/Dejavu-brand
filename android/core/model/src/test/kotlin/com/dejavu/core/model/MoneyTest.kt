package com.dejavu.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {
    @After fun reset() {
        Money.display = Money.Display.BASE
    }

    @Test
    fun `formats whole and fractional SAR amounts`() {
        assertEquals("1,140 ر.س", Money.format("1140"))
        assertEquals("1,140 ر.س", Money.format("1140.00"))
        assertEquals("99.50 ر.س", Money.format("99.5"))
        assertEquals("0 ر.س", Money.format(null as String?))
    }

    @Test
    fun `shows prices in YER at the display rate, rounded to whole riyals`() {
        Money.display = Money.Display(Currency.YER, BigDecimal("140.5"))
        assertEquals("16,018 ر.ي", Money.format("114.01")) // 16,018.405
        assertEquals("70 ر.ي", Money.format("0.5")) // 70.25
        // Costs stay in SAR.
        assertEquals("99.50 ر.س", Money.formatBase("99.5"))
    }

    @Test
    fun `formats an order at its own locked rate, as the server does`() {
        assertEquals("18,000 ر.ي", Money.format("120", Currency.YER, "150"))
        assertEquals("120 ر.س", Money.format("120", Currency.SAR, "1"))
        assertEquals(BigDecimal("6667"), Money.convert(BigDecimal("46.67"), Currency.YER, BigDecimal("142.85")))
    }

    @Test
    fun `computes the saving against the compare-at price`() {
        assertEquals(16, Money.discountPercent("1850", "2200"))
        assertNull(Money.discountPercent("1850", null))
        assertNull(Money.discountPercent("1850", "1500"))
    }
}
