package com.kokidayo.batterystatus

import android.app.Application
import com.kokidayo.batterystatus.shell.PrivilegeManager

class BatteryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegeManager.init(this)
    }
}
