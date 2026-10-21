package com.dejavu.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantPlannerTest {
    @Test
    fun `one variant per size and color, with readable SKUs and swatches`() {
        val planned = VariantPlanner.plan("linen-shirt", listOf("M", "XL"), listOf("أسود", "أبيض"))
        assertEquals(
            listOf("LINEN-SHIRT-M-BLK", "LINEN-SHIRT-M-WHT", "LINEN-SHIRT-XL-BLK", "LINEN-SHIRT-XL-WHT"),
            planned.map { it.sku },
        )
        assertEquals("#000000", planned.first().colorHex)
        assertEquals("M · أسود", planned.first().title)
        assertTrue(planned.all { VariantPlanner.isValidSku(it.sku) })
    }

    @Test
    fun `sizes only, colors only, or neither`() {
        assertEquals(listOf("DRESS-38", "DRESS-40"), VariantPlanner.plan("dress", listOf("38", "40"), emptyList()).map { it.sku })
        assertEquals(listOf("BAG-RED"), VariantPlanner.plan("bag", emptyList(), listOf("أحمر")).map { it.sku })
        val single = VariantPlanner.plan("scarf", emptyList(), emptyList())
        assertEquals(listOf("SCARF"), single.map { it.sku })
        assertEquals("مقاس واحد", single.single().title)
    }

    @Test
    fun `unknown colors get a code from Latin letters or their position`() {
        val planned = VariantPlanner.plan("tee", listOf("L"), listOf("Mint", "تركواز"))
        assertEquals(listOf("TEE-L-MIN", "TEE-L-C2"), planned.map { it.sku })
        assertEquals(null, planned[1].colorHex)
    }

    @Test
    fun `splits Arabic and Latin separators and builds slugs`() {
        assertEquals(listOf("S", "M", "L"), VariantPlanner.split("S، M , L ,M"))
        assertEquals(listOf("أسود", "أبيض"), VariantPlanner.split("أسود - أبيض"))
        assertEquals("linen-shirt-2026", VariantPlanner.slugOf("  Linen Shirt (2026)! "))
        assertEquals("ITEM", VariantPlanner.skuBase(""))
    }
}
