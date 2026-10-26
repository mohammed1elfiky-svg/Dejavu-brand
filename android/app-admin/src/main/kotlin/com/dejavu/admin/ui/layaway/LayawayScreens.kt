package com.dejavu.admin.ui.layaway

import androidx.compose.runtime.Composable
import com.dejavu.core.admin.StaffSession

/** حجز بعربون: the list (open / done / cancelled). */
@Composable
fun LayawaysScreen(session: StaffSession.LoggedIn, onBack: () -> Unit, open: (String) -> Unit, newLayaway: () -> Unit) {
    // TODO (agent)
}

/** One reservation: items, instalments, add payment, complete the sale, cancel. */
@Composable
fun LayawayScreen(id: String, session: StaffSession.LoggedIn, onBack: () -> Unit) {
    // TODO (agent)
}

/** Pick the customer and pieces, take the first deposit. */
@Composable
fun NewLayawayScreen(onBack: () -> Unit, created: (String) -> Unit) {
    // TODO (agent)
}
