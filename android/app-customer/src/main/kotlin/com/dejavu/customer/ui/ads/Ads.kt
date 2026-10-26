package com.dejavu.customer.ui.ads

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dejavu.core.model.AdsConfig
import com.dejavu.customer.DejavuApp
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import androidx.compose.ui.platform.LocalContext

/** Where a banner may show; the owner switches each on or off from the admin app. */
enum class AdPlacement { HOME, PRODUCT }

/**
 * Google ads (AdMob), only when the owner turned them on. Without the owner's own ad unit
 * ids, Google's test ads are shown.
 */
object Ads {
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/6300978111"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"

    private var started = false
    private var productViews = 0
    private var interstitial: InterstitialAd? = null

    private fun ensureStarted(context: Context) {
        if (started) return
        started = true
        runCatching { MobileAds.initialize(context.applicationContext) }
    }

    fun bannerUnit(c: AdsConfig) = c.bannerUnitId.ifBlank { TEST_BANNER }

    /** Counts product pages; every [AdsConfig.interstitialEvery] pages a full-screen ad shows. */
    fun onProductOpened(activity: Activity, c: AdsConfig) {
        if (!c.enabled || c.interstitialEvery <= 0) return
        ensureStarted(activity)
        productViews++
        val ad = interstitial
        if (productViews % c.interstitialEvery == 0 && ad != null) {
            interstitial = null
            runCatching { ad.show(activity) }
        }
        if (interstitial == null) load(activity, c)
    }

    private fun load(activity: Activity, c: AdsConfig) {
        runCatching {
            InterstitialAd.load(
                activity,
                c.interstitialUnitId.ifBlank { TEST_INTERSTITIAL },
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitial = ad
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitial = null
                    }
                },
            )
        }
    }

    internal fun start(context: Context) = ensureStarted(context)
}

/** A banner ad at [placement], or nothing when ads are off there. */
@Composable
fun AdBanner(placement: AdPlacement, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val extras = (context.applicationContext as DejavuApp).container.extras
    val config by extras.config.collectAsStateWithLifecycle()
    val ads = config.ads
    val on = ads.enabled && when (placement) {
        AdPlacement.HOME -> ads.homeBanner
        AdPlacement.PRODUCT -> ads.productBanner
    }
    if (!on) return
    Ads.start(context)
    val unit = Ads.bannerUnit(ads)
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = unit
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}
