package com.astra.wakeup

import android.app.Application

class AstraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AstraCrashStore.install(this)
        AppUpgradeManager.runStartupMaintenance(this)
    }
}
