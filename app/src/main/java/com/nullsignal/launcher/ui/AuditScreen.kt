package com.nullsignal.launcher.ui

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullsignal.core.security.Audit
import com.nullsignal.core.security.AuditInput
import com.nullsignal.core.security.Finding
import com.nullsignal.core.story.Trigger
import com.nullsignal.launcher.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private data class AppAccess(val label: String, val pkg: String, val perms: List<String>)

private fun collectAudit(ctx: Context): AuditInput {
    val cr = ctx.contentResolver
    val km = ctx.getSystemService(KeyguardManager::class.java)
    val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
    val enc = dpm?.storageEncryptionStatus
    val patchDays = runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)!!
        ((System.currentTimeMillis() - d.time) / 86_400_000).toInt()
    }.getOrNull()
    val root = buildList {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/adb/magisk")
            .filter { runCatching { File(it).exists() }.getOrDefault(false) }
            .forEach { add(it) }
        if (Build.TAGS?.contains("test-keys") == true) add("test-keys прошивка")
    }
    return AuditInput(
        screenLock = km?.isDeviceSecure,
        encrypted = enc?.let {
            it == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
                it == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
                it == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY
        },
        devOptions = runCatching { Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrNull(),
        adb = runCatching { Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrNull(),
        patchAgeDays = patchDays,
        sdk = Build.VERSION.SDK_INT,
        rootIndicators = root,
        installUnknownApps = null,
    )
}

/** Какие НЕсистемные приложения получили опасные разрешения. */
private fun collectAccess(ctx: Context): List<AppAccess> {
    val pm = ctx.packageManager
    val pkgs: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    }
    return pkgs.mapNotNull { p ->
        val ai = p.applicationInfo ?: return@mapNotNull null
        if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 || p.packageName == ctx.packageName) return@mapNotNull null
        val req = p.requestedPermissions ?: return@mapNotNull null
        val flags = p.requestedPermissionsFlags ?: return@mapNotNull null
        val granted = req.indices
            .filter { flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
            .mapNotNull { Audit.SENSITIVE[req[it]] }
            .distinct()
        if (granted.isEmpty()) null else AppAccess(ai.loadLabel(pm).toString(), p.packageName, granted)
    }.sortedByDescending { it.perms.size }
}

@Composable
fun AuditScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var findings by remember { mutableStateOf<List<Finding>?>(null) }
    var apps by remember { mutableStateOf<List<AppAccess>>(emptyList()) }
    LaunchedEffect(Unit) {
        val (f, a) = withContext(Dispatchers.IO) { Audit.run(collectAudit(ctx)) to collectAccess(ctx) }
        findings = f
        apps = a
        vm.fire(Trigger.AUDIT_DONE)
    }
    ModuleScreen("AUDIT", onBack) {
        val f = findings
        if (f == null) {
            Text("сканирую систему…", style = Ns.text.copy(color = Ns.Amber))
            return@ModuleScreen
        }
        val score = Audit.score(f)
        val color = when {
            score >= 75 -> Ns.Green
            score >= 50 -> Ns.Amber
            else -> Ns.Red
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Column(Modifier.fillMaxWidth().hud(color).padding(12.dp)) {
                    Text("ЗАЩИЩЁННОСТЬ: $score/100", style = Ns.title.copy(color = color, fontSize = 22.sp))
                    Text(asciiBar(score / 100f, 28), style = Ns.text.copy(color = color))
                    Text("оценка ${Audit.grade(score)}", style = Ns.text.copy(color = color))
                }
            }
            items(f) { x ->
                Column(Modifier.fillMaxWidth().hud(if (x.ok) Ns.Faint else Ns.Red).padding(8.dp)) {
                    Text("${if (x.ok) "[✓]" else "[✗]"} ${x.title}", style = Ns.text.copy(color = if (x.ok) Ns.Green else Ns.Red))
                    Text(x.detail, style = Ns.small.copy(color = if (x.ok) Ns.Dim else Ns.Amber))
                }
            }
            item {
                Text(
                    "открыть настройки безопасности →",
                    style = Ns.text.copy(color = Ns.Cyan),
                    modifier = Modifier.padding(vertical = 8.dp).clickable {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                )
                Text("— доступы приложений (${apps.size}) —", style = Ns.text.copy(color = Ns.Cyan), modifier = Modifier.padding(top = 8.dp))
                Text("нажми на приложение, чтобы забрать лишние разрешения", style = Ns.small)
            }
            items(apps, key = { it.pkg }) { a ->
                Column(
                    Modifier.fillMaxWidth().hud(Ns.Faint).clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }.padding(8.dp),
                ) {
                    Text(a.label, style = Ns.text)
                    Text(a.perms.joinToString(" · "), style = Ns.small.copy(color = if (a.perms.size >= 3) Ns.Amber else Ns.Dim))
                }
            }
        }
    }
}
