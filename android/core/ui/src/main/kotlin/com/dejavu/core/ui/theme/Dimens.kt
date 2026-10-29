package com.dejavu.core.ui.theme

import androidx.compose.ui.unit.dp

/** The spacing both apps share, so screens line up: use these instead of new numbers. */
object Dimens {
    /** Left / right margin of a screen. */
    val screen = 16.dp

    /** Gap between cards in a list. */
    val listGap = 12.dp

    /** Gap between fields and rows inside a card. */
    val inner = 8.dp

    /** Room under a list so the floating button never covers the last card. */
    val fabClearance = 96.dp

    /** Height of the main action buttons. */
    val button = 52.dp
}
