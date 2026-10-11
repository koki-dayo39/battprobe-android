package io.github.koki_dayo39.battprobe

import android.app.Application
import io.github.koki_dayo39.battprobe.background.ChargerEvents
import io.github.koki_dayo39.battprobe.background.ChargingWork
import io.github.koki_dayo39.battprobe.shell.PrivilegeManager

class BatteryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivilegeManager.init(this)
        ChargerEvents.registerRuntimeReceiver(this)
        ChargingWork.armChargingWatch(this)
    }
}
