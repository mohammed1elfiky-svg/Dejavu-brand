package com.dejavu.admin.ui.customerapp

import androidx.compose.runtime.Composable

/** The store page ("عن المتجر"), sign-in before browsing, Google ads. */
@Composable
fun CustomerAppSettingsScreen(onBack: () -> Unit) {
    // TODO (agent)
}

/** The shopping assistant: instructions, welcome, limits, and customers' conversations. */
@Composable
fun AssistantScreen(onBack: () -> Unit, openCustomer: (String) -> Unit) {
    // TODO (agent)
}

/** Notifications / SMS to a group of customers, with a picture and a link; past campaigns. */
@Composable
fun CampaignsScreen(onBack: () -> Unit) {
    // TODO (agent)
}

/** "سلوك العملاء": time in the app, most viewed products, searches. */
@Composable
fun BehaviorScreen(onBack: () -> Unit, openCustomer: (String) -> Unit, openProduct: (String) -> Unit) {
    // TODO (agent)
}

/** Time this customer spent in the app (for the customer page). */
@Composable
fun CustomerUsageCard(customerId: String) {
    // TODO (agent)
}
