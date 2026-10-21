package com.dejavu.core.admin

/**
 * Builds a product's variants from the sizes and colors it comes in: one SKU
 * per size × color, e.g. "LINEN-SHIRT-M-BLK". Pure logic, shared by the product
 * editor and its tests.
 */
object VariantPlanner {
    data class Planned(val size: String?, val color: String?, val colorHex: String?, val sku: String) {
        val title: String get() = listOfNotNull(size, color).joinToString(" · ").ifBlank { "مقاس واحد" }
    }

    /** Common Arabic color names → SKU code and swatch. */
    val COLORS: Map<String, Pair<String, String>> = linkedMapOf(
        "أسود" to ("BLK" to "#000000"),
        "أبيض" to ("WHT" to "#FFFFFF"),
        "أحمر" to ("RED" to "#C62828"),
        "أزرق" to ("BLU" to "#1565C0"),
        "كحلي" to ("NVY" to "#1A237E"),
        "سماوي" to ("SKY" to "#4FC3F7"),
        "أخضر" to ("GRN" to "#2E7D32"),
        "زيتي" to ("OLV" to "#556B2F"),
        "أصفر" to ("YLW" to "#F9A825"),
        "برتقالي" to ("ORG" to "#EF6C00"),
        "وردي" to ("PNK" to "#EC407A"),
        "بنفسجي" to ("PRP" to "#6A1B9A"),
        "عنابي" to ("MRN" to "#7B1E3A"),
        "خمري" to ("WIN" to "#722F37"),
        "بني" to ("BRN" to "#6D4C41"),
        "بيج" to ("BEG" to "#D7C4A3"),
        "كريمي" to ("CRM" to "#F3E5C8"),
        "رمادي" to ("GRY" to "#9E9E9E"),
        "فضي" to ("SLV" to "#C0C0C0"),
        "ذهبي" to ("GLD" to "#C9A227"),
    )

    val SIZE_PRESETS: Map<String, List<String>> = linkedMapOf(
        "S – XXL" to listOf("S", "M", "L", "XL", "XXL"),
        "36 – 44" to listOf("36", "38", "40", "42", "44"),
        "مقاس واحد" to emptyList(),
    )

    /** "S, M ، L" / "أسود - أبيض" → distinct trimmed entries. */
    fun split(text: String): List<String> =
        text.split(',', '،', '\n', '-', '/', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** URL slug from the English name: "Linen Shirt!" → "linen-shirt". */
    fun slugOf(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80)

    /** SKU prefix from the slug: "linen-shirt" → "LINEN-SHIRT" (at most 24 characters). */
    fun skuBase(slug: String): String =
        slug.uppercase().replace(Regex("[^A-Z0-9]+"), "-").trim('-').take(24).trim('-').ifEmpty { "ITEM" }

    fun colorCode(color: String, index: Int): String {
        COLORS[color.trim()]?.let { return it.first }
        val latin = color.uppercase().replace(Regex("[^A-Z0-9]"), "")
        return if (latin.length >= 2) latin.take(3) else "C${index + 1}"
    }

    fun colorHex(color: String): String? = COLORS[color.trim()]?.second

    private fun sizeCode(size: String) = size.uppercase().replace(Regex("[^A-Z0-9]+"), "").ifEmpty { "OS" }

    /** One variant per size × color; empty lists mean "no option" (e.g. one size). */
    fun plan(slug: String, sizes: List<String>, colors: List<String>): List<Planned> {
        val base = skuBase(slug)
        val s = sizes.ifEmpty { listOf(null) }
        val c = colors.ifEmpty { listOf(null) }
        return s.flatMap { size ->
            c.mapIndexed { i, color ->
                val parts = listOfNotNull(base, size?.let(::sizeCode), color?.let { colorCode(it, i) })
                Planned(size, color, color?.let(::colorHex), parts.joinToString("-").take(50).trimEnd('-'))
            }
        }
    }

    /** Valid per the API: upper-case letters, digits and dashes, 2–50 characters. */
    fun isValidSku(sku: String) = Regex("^[A-Z0-9][A-Z0-9-]{1,49}$").matches(sku)
}
