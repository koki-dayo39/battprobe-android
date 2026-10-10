package com.kokidayo.batterystatus

import android.app.Application
import com.kokidayo.batterystatus.shizuku.ShizukuManager

class BatteryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.init(this)
    }
}
