package com.dejavu.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dejavu.admin.ui.AdminRoot
import com.dejavu.core.ui.theme.DejavuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DejavuTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AdminRoot() }
            }
        }
    }
}
