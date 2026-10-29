package com.dejavu.admin

import android.app.Application
import com.dejavu.admin.ui.sms.SmsGateway

class AdminApp : Application(), coil3.SingletonImageLoader.Factory {
    lateinit var container: AdminContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AdminContainer(this)
        container.start()
        com.dejavu.admin.ui.pos.OfflineSales.scheduleIfPending(this)
        // SMS gateway switched on for this phone: run it whenever a staff session exists.
        SmsGateway.watch(this)
        // Daily automatic backup switched on: keep its work scheduled.
        com.dejavu.admin.ui.system.AutoBackup.restoreSchedule(this)
    }

    /** Strong memory + disk cache for pictures, shared with the customer app's setup. */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        com.dejavu.core.ui.dejavuImageLoader(context) { okhttp3.OkHttpClient() }
}
