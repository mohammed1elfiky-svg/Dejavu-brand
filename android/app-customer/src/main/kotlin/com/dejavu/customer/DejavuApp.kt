package com.dejavu.customer

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class DejavuApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
        Analytics.init(container)
    }

    /** Pictures load through [AppContainer.imageClient], so they follow a change of server. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        com.dejavu.core.ui.dejavuImageLoader(context) { container.imageClient }
}
