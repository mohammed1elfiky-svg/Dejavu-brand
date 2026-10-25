package com.dejavu.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.admin.ui.ThemeMode
import com.dejavu.core.ui.theme.LiquidMode
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
        val prefs = (application as AdminApp).container.uiPrefs
        setContent {
            val mode by prefs.theme.collectAsStateWithLifecycle()
            val fontScale by prefs.fontScale.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            if (LiquidMode.dark != dark) LiquidMode.dark = dark
            LaunchedEffect(dark) {
                // Status / navigation bar icons readable on the chosen background.
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            LuxTheme(fontScale) {
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
        val container = (application as AdminApp).container
        container.requestOrder(intent?.getStringExtra(EXTRA_ORDER_ID))
        container.requestTopUp(intent?.getStringExtra(EXTRA_TOP_UP_ID))
    }

    companion object {
        const val EXTRA_ORDER_ID = "orderId"
        const val EXTRA_TOP_UP_ID = "topUpId"
    }
}
