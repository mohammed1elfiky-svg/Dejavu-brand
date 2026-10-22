package com.dejavu.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dejavu.admin.ui.AdminRoot
import com.dejavu.admin.ui.theme.LuxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            LuxTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AdminRoot() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A tapped notification (ours, or FCM's background one whose data keys become extras). */
    private fun handle(intent: Intent?) {
        (application as AdminApp).container.requestOrder(intent?.getStringExtra(EXTRA_ORDER_ID))
    }

    companion object {
        const val EXTRA_ORDER_ID = "orderId"
    }
}
