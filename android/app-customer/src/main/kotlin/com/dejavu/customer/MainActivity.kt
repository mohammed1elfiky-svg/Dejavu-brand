package com.dejavu.customer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.dejavu.customer.ui.SplashScreen
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
                    var splash by rememberSaveable { mutableStateOf(true) }
                    Box(Modifier.fillMaxSize()) {
                        DejavuNavHost()
                        AnimatedVisibility(visible = splash, enter = EnterTransition.None, exit = fadeOut(tween(durationMillis = 350))) {
                            SplashScreen(onFinished = { splash = false })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Analytics.onStart()
    }

    override fun onStop() {
        super.onStop()
        Analytics.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Opens the order (or product) a notification or the payment result page points at. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val order = intent.data?.takeIf { it.scheme == "dejavu" }?.getQueryParameter("order")
            // Our own notifications, and FCM's background ones (data keys become extras).
            ?: intent.getStringExtra(EXTRA_ORDER)?.takeIf { it.isNotBlank() }
            // "Shipped" notification (with the shipping receipt).
            ?: intent.getStringExtra(EXTRA_ORDER_NUMBER)
        val container = (application as DejavuApp).container
        // A shared product link opened from WhatsApp: dejavu://product/<slug>
        intent.data?.takeIf { it.scheme == "dejavu" && it.host == "product" }?.lastPathSegment?.let {
            container.requestProduct(it)
            return
        }
        container.requestOrder(order)
        if (!order.isNullOrBlank()) return
        // New-product or back-in-stock notification: open the product.
        val product = intent.getStringExtra(EXTRA_PRODUCT)?.takeIf { it.isNotBlank() } ?: intent.getStringExtra(EXTRA_PRODUCT_SLUG)
        if (!product.isNullOrBlank()) {
            container.requestProduct(product)
            return
        }
        // Cart reminder: open the cart.
        if (intent.getStringExtra(EXTRA_SCREEN) == SCREEN_CART) {
            container.requestCart()
            return
        }
        // Top-up reviewed (approved or rejected): open the wallet.
        if (!intent.getStringExtra(EXTRA_TOP_UP).isNullOrBlank()) container.requestWallet()
    }

    companion object {
        const val EXTRA_ORDER = "order"
        const val EXTRA_TOP_UP = "topUp"
        const val EXTRA_PRODUCT = "product"

        // Keys of newer notifications (FCM background messages pass data keys as extras as-is).
        const val EXTRA_ORDER_NUMBER = "orderNumber"
        const val EXTRA_PRODUCT_SLUG = "productSlug"
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_CART = "cart"
    }
}
