package com.kokidayo.batterystatus

import android.app.Application
import com.kokidayo.batterystatus.background.ChargerEvents
import com.kokidayo.batterystatus.background.ChargingWork
import com.kokidayo.batterystatus.shell.PrivilegeManager

class BatteryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegeManager.init(this)
        ChargerEvents.registerRuntimeReceiver(this)
        ChargingWork.armChargingWatch(this)
    }
}
