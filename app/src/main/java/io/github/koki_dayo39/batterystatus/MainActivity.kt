package io.github.koki_dayo39.batterystatus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.koki_dayo39.batterystatus.shizuku.ShizukuManager
import io.github.koki_dayo39.batterystatus.ui.BatteryApp
import io.github.koki_dayo39.batterystatus.ui.theme.BatteryStatusTheme

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
