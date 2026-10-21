package com.dejavu.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun `formats whole and fractional amounts`() {
        assertEquals("1,140 ج.م", Money.format("1140"))
        assertEquals("1,140 ج.م", Money.format("1140.00"))
        assertEquals("99.50 ج.م", Money.format("99.5"))
        assertEquals("0 ج.م", Money.format(null as String?))
    }

    @Test
    fun `computes the saving against the compare-at price`() {
        assertEquals(16, Money.discountPercent("1850", "2200"))
        assertNull(Money.discountPercent("1850", null))
        assertNull(Money.discountPercent("1850", "1500"))
    }
}
