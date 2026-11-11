package com.nullsignal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nullsignal.core.terminal.Module
import com.nullsignal.launcher.ui.BleScreen
import com.nullsignal.launcher.ui.BreachScreen
import com.nullsignal.launcher.ui.HomeScreen
import com.nullsignal.launcher.ui.MessagesScreen
import com.nullsignal.launcher.ui.NetScanScreen
import com.nullsignal.launcher.ui.Ns
import com.nullsignal.launcher.ui.NsTheme
import com.nullsignal.launcher.ui.TerminalScreen
import com.nullsignal.launcher.ui.WifiScreen
import com.nullsignal.launcher.ui.scanlines

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NsTheme {
                val home = { vm.screen = Module.HOME }
                BackHandler(enabled = vm.screen != Module.HOME, onBack = home)
                Box(Modifier.fillMaxSize().background(Ns.Bg).scanlines().safeDrawingPadding()) {
                    when (vm.screen) {
                        Module.HOME -> HomeScreen(vm)
                        Module.TERMINAL -> TerminalScreen(vm, home)
                        Module.NETSCAN -> NetScanScreen(vm, home)
                        Module.MESSAGES -> MessagesScreen(vm, home)
                        Module.BREACH -> BreachScreen(vm, home)
                        Module.WIFI -> WifiScreen(home)
                        Module.BLE -> BleScreen(home)
                    }
                    AnimatedVisibility(vm.notice != null, modifier = Modifier.align(Alignment.TopCenter)) {
                        Text(
                            vm.notice.orEmpty(),
                            style = Ns.text.copy(color = Ns.Bg),
                            modifier = Modifier
                                .padding(8.dp)
                                .fillMaxWidth()
                                .background(Ns.Green)
                                .border(1.dp, Ns.Cyan)
                                .padding(10.dp),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshApps() // приложения могли установить или удалить
    }

    // Нажатие «Домой», когда лаунчер уже открыт, возвращает на рабочий стол
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) vm.screen = Module.HOME
    }
}
