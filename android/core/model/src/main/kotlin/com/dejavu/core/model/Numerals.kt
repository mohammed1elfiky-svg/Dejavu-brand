package com.dejavu.core.model

/**
 * Which digits amounts and dates are written in: Latin (0-9, the default) or Arabic-Indic
 * (٠-٩). Set once at start-up from the user's preference; screens pick it up when recreated.
 */
object Numerals {
    @Volatile
    var arabic: Boolean = false

    /** [text] with its Latin digits (and decimal point) written the chosen way. */
    fun of(text: String): String {
        if (!arabic) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            out.append(
                when (c) {
                    in '0'..'9' -> '٠' + (c - '0')
                    '.' -> '٫'
                    ',' -> '٬'
                    else -> c
                },
            )
        }
        return out.toString()
    }

    /** [text] with Arabic-Indic digits (e.g. typed on an Arabic keyboard) turned back into Latin ones. */
    fun latin(text: String): String {
        if (text.none { it in '٠'..'٩' || it == '٫' || it == '٬' }) return text
        return buildString(text.length) {
            for (c in text) {
                when (c) {
                    in '٠'..'٩' -> append('0' + (c - '٠'))
                    '٫' -> append('.')
                    '٬' -> {}
                    else -> append(c)
                }
            }
        }
    }
}
