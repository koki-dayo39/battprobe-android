package com.kokidayo.batterystatus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kokidayo.batterystatus.shizuku.ShizukuManager
import com.kokidayo.batterystatus.ui.BatteryApp
import com.kokidayo.batterystatus.ui.theme.BatteryStatusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BatteryStatusTheme {
                BatteryApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have started Shizuku or granted permission while we were in the background.
        ShizukuManager.refresh()
    }
}
