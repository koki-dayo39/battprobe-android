package io.github.koki_dayo39.batterystatus

import android.app.Application
import io.github.koki_dayo39.batterystatus.background.ChargerEvents
import io.github.koki_dayo39.batterystatus.background.ChargingWork
import io.github.koki_dayo39.batterystatus.shell.PrivilegeManager

class BatteryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegeManager.init(this)
        ChargerEvents.registerRuntimeReceiver(this)
        ChargingWork.armChargingWatch(this)
    }
}
