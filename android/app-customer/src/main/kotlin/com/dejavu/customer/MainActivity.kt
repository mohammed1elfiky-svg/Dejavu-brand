package com.dejavu.customer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dejavu.customer.ui.navigation.DejavuNavHost
import com.dejavu.core.ui.theme.DejavuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            DejavuTheme {
                // The store is Arabic-first: always lay out right-to-left.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    DejavuNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Opens the order a notification or the payment result page points at. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val order = intent.data?.takeIf { it.scheme == "dejavu" }?.getQueryParameter("order")
            // Our own notifications, and FCM's background ones (data keys become extras).
            ?: intent.getStringExtra(EXTRA_ORDER)
        val container = (application as DejavuApp).container
        container.requestOrder(order)
        // Top-up reviewed (approved or rejected): open the wallet.
        if (order.isNullOrBlank() && !intent.getStringExtra(EXTRA_TOP_UP).isNullOrBlank()) container.requestWallet()
    }

    companion object {
        const val EXTRA_ORDER = "order"
        const val EXTRA_TOP_UP = "topUp"
    }
}
