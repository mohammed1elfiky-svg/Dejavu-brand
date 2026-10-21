package com.dejavu.admin

import android.app.Application

class AdminApp : Application() {
    lateinit var container: AdminContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AdminContainer(this)
        container.start()
    }
}
