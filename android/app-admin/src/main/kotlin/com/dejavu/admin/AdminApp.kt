package com.dejavu.admin

import android.app.Application
import com.dejavu.admin.ui.sms.SmsGateway

class AdminApp : Application() {
    lateinit var container: AdminContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AdminContainer(this)
        container.start()
        // SMS gateway switched on for this phone: run it whenever a staff session exists.
        SmsGateway.watch(this)
    }
}
