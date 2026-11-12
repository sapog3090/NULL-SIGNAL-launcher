package com.nullsignal.launcher.ui

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.security.Audit
import com.nullsignal.core.security.SpywareCheck
import com.nullsignal.core.security.TrackerDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class TagSeen(val address: String, val kind: String, val rssi: Int, val first: Long, val last: Long, val hits: Int)

@Composable
fun StalkerScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ModuleScreen("АНТИСЛЕЖКА", onBack) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("МЕТКИ РЯДОМ", "ШПИОНЫ В ТЕЛЕФОНЕ").forEachIndexed { i, t ->
                Text(
                    t,
                    style = Ns.small.copy(color = if (i == tab) Ns.Bg else Ns.Green, textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f).background(if (i == tab) Ns.Red else Ns.Panel).clickable { tab = i }.padding(vertical = 9.dp),
                )
            }
        }
        if (tab == 0) TagsTab() else SpywareTab()
    }
}

// ---------------- метки слежения ----------------

private fun blePerms(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

@SuppressLint("MissingPermission")
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TagsTab() {
    val ctx = LocalContext.current
    val (granted, request) = rememberPermissions(blePerms())
    val tags = remember { mutableStateMapOf<String, TagSeen>() }
    var started by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var status by remember { mutableStateOf("") }

    if (!granted) {
        PermissionGate("Нужен доступ к Bluetooth, чтобы искать AirTag, SmartTag и Tile рядом с тобой.", request)
        return
    }
    DisposableEffect(Unit) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        status = if (scanner == null) "включи Bluetooth" else "сканирую эфир…"
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val rec = r.scanRecord ?: return
                val man = HashMap<Int, ByteArray>()
                val sparse = rec.manufacturerSpecificData
                if (sparse != null) for (i in 0 until sparse.size()) man[sparse.keyAt(i)] = sparse.valueAt(i)
                val services = HashSet<Int>()
                rec.serviceUuids?.forEach { services += (it.uuid.mostSignificantBits ushr 32).toInt() and 0xffff }
                rec.serviceData?.keys?.forEach { services += (it.uuid.mostSignificantBits ushr 32).toInt() and 0xffff }
                val kind = TrackerDetector.classify(man, services) ?: return
                val t = System.currentTimeMillis()
                val old = tags[r.device.address]
                tags[r.device.address] = TagSeen(r.device.address, kind, r.rssi, old?.first ?: t, t, (old?.hits ?: 0) + 1)
            }
        }
        runCatching {
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }.onFailure { status = "ошибка: ${it.message}" }
        onDispose { runCatching { scanner?.stopScan(cb) } }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val list = tags.values.sortedByDescending { it.last - it.first }
    val mins = (now - started) / 60000
    Text("$status  ·  слушаю ${mins} мин ${(now - started) / 1000 % 60} с", style = Ns.small)
    Text(
        "Метка, которая держится рядом 10+ минут, пока ты перемещаешься, — повод насторожиться. " +
            "Держи экран открытым в дороге: время рядом считается само.",
        style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 6.dp),
    )
    TermButton("СБРОСИТЬ", Modifier.fillMaxWidth()) { tags.clear(); started = System.currentTimeMillis() }
    if (list.isEmpty()) {
        Text("меток слежения рядом не найдено", style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(top = 14.dp))
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.address }) { t ->
            val follow = TrackerDetector.following(t.first, t.last)
            val near = now - t.last < 30_000
            Column(Modifier.fillMaxWidth().hud(if (follow) Ns.Red else Ns.Faint).padding(10.dp)) {
                Text(if (follow) "⚠ СЛЕДУЕТ ЗА ТОБОЙ" else if (near) "РЯДОМ" else "ПРОПАЛА", style = Ns.label.copy(color = if (follow) Ns.Red else Ns.Dim))
                Text(t.kind, style = Ns.text.copy(color = if (follow) Ns.Red else Ns.Green))
                Text(
                    "${signalBars(t.rssi)} ${t.rssi} dBm · рядом ${(t.last - t.first) / 60000} мин · ${t.address}",
                    style = Ns.small,
                )
                if (follow) {
                    Text(
                        "Проверь сумку, карманы, куртку, машину. Найденную метку можно отключить, вынув батарейку. " +
                            "Если боишься — обратись к взрослым или в полицию, метку не выбрасывай: это улика.",
                        style = Ns.small.copy(color = Ns.Amber),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ---------------- шпионские программы ----------------

private fun scanSpyware(ctx: Context): List<SpywareCheck.Verdict> {
    val pm = ctx.packageManager
    val cr = ctx.contentResolver
    val accessibility = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
        .split(':').map { it.substringBefore('/') }.filter { it.isNotEmpty() }.toSet()
    val notif = (Settings.Secure.getString(cr, "enabled_notification_listeners") ?: "")
        .split(':').map { it.substringBefore('/') }.filter { it.isNotEmpty() }.toSet()
    val admins = ctx.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().map { it.packageName }.toSet()
    val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.toSet()
    val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    }
    return pkgs.mapNotNull { p ->
        val ai = p.applicationInfo ?: return@mapNotNull null
        if (p.packageName == ctx.packageName) return@mapNotNull null
        val system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val req = p.requestedPermissions.orEmpty()
        val flags = p.requestedPermissionsFlags ?: IntArray(0)
        val dangerous = req.indices
            .filter { it < flags.size && flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
            .mapNotNull { Audit.SENSITIVE[req[it]] }.distinct()
        SpywareCheck.judge(
            SpywareCheck.AppFacts(
                pkg = p.packageName,
                label = ai.loadLabel(pm).toString(),
                system = system,
                hasLauncherIcon = p.packageName in launchable,
                dangerous = dangerous,
                accessibility = p.packageName in accessibility,
                notificationAccess = p.packageName in notif,
                deviceAdmin = p.packageName in admins,
            ),
        ).takeIf { it.level > 0 }
    }.sortedByDescending { it.level }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SpywareTab() {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<List<SpywareCheck.Verdict>?>(null) }
    LaunchedEffect(Unit) { result = withContext(Dispatchers.IO) { scanSpyware(ctx) } }
    val r = result
    if (r == null) {
        Text("проверяю все установленные приложения…", style = Ns.text.copy(color = Ns.Amber))
        return
    }
    val danger = r.count { it.level >= 2 }
    Panel(color = if (danger > 0) Ns.Red else Ns.Dim) {
        Text(
            if (danger > 0) "НАЙДЕНО ОПАСНЫХ: $danger" else "ЯВНЫХ ШПИОНОВ НЕТ",
            style = Ns.title.copy(color = if (danger > 0) Ns.Red else Ns.Green),
        )
        Text("требуют внимания: ${r.size - danger}. Проверены скрытые приложения, спец. возможности, доступ к уведомлениям, администраторы устройства.", style = Ns.small)
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(r, key = { it.app.pkg }) { v ->
            Column(
                Modifier.fillMaxWidth().hud(if (v.level >= 2) Ns.Red else Ns.Faint).clickable {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${v.app.pkg}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }.padding(10.dp),
            ) {
                Text(v.app.label, style = Ns.text.copy(color = if (v.level >= 2) Ns.Red else Ns.Green))
                Text(v.app.pkg, style = Ns.small.copy(fontSize = 10.sp))
                v.reasons.forEach { Text("· $it", style = Ns.small.copy(color = if (v.level >= 2) Ns.Amber else Ns.Text2)) }
            }
        }
        item {
            Text(
                "Нажми на приложение → «Удалить» или забери разрешения. Если не удаляется — сначала сними его с администраторов " +
                    "устройства (Настройки → Безопасность). Свои приложения (менеджер паролей, автоответчик) — это нормально.",
                style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}
