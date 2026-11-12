package com.nullsignal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
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
import com.nullsignal.launcher.ui.AcademyScreen
import com.nullsignal.launcher.ui.AuditScreen
import com.nullsignal.launcher.ui.GamesScreen
import com.nullsignal.launcher.ui.MonitorScreen
import com.nullsignal.launcher.ui.ToolsScreen
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
import com.nullsignal.launcher.ui.cyberBackground
import com.nullsignal.launcher.ui.hud
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column

class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NsTheme {
                val home = { vm.screen = Module.HOME }
                BackHandler(enabled = vm.screen != Module.HOME, onBack = home)
                Box(Modifier.fillMaxSize().cyberBackground().scanlines().safeDrawingPadding()) {
                    AnimatedContent(
                        targetState = vm.screen,
                        transitionSpec = {
                            (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(120))
                        },
                        label = "screen",
                    ) { screen ->
                    when (screen) {
                        Module.HOME -> HomeScreen(vm)
                        Module.TERMINAL -> TerminalScreen(vm, home)
                        Module.NETSCAN -> NetScanScreen(vm, home)
                        Module.MESSAGES -> MessagesScreen(vm, home)
                        Module.BREACH -> BreachScreen(vm) { vm.screen = Module.GAMES }
                        Module.WIFI -> WifiScreen(home)
                        Module.BLE -> BleScreen(home)
                        Module.ACADEMY -> AcademyScreen(vm, home)
                        Module.AUDIT -> AuditScreen(vm, home)
                        Module.TOOLS -> ToolsScreen(home)
                        Module.MONITOR -> MonitorScreen(home)
                        Module.GAMES -> GamesScreen(vm, home)
                    }
                    }
                    AnimatedVisibility(
                        vm.notice != null,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut(),
                    ) {
                        Column(
                            Modifier.padding(10.dp).fillMaxWidth()
                                .hud(Ns.Green, fill = Ns.Bg2.copy(alpha = 0.95f))
                                .padding(12.dp),
                        ) {
                            Text("ВХОДЯЩИЙ СИГНАЛ", style = Ns.label.copy(color = Ns.Cyan))
                            Text(vm.notice.orEmpty(), style = Ns.text)
                        }
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
