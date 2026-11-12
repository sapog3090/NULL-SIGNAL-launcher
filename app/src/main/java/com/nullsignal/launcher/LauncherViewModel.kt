package com.nullsignal.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nullsignal.core.story.Progress
import com.nullsignal.core.story.QuestEngine
import com.nullsignal.core.story.Trigger
import com.nullsignal.core.terminal.Action
import com.nullsignal.core.terminal.Module
import com.nullsignal.core.terminal.Terminal
import com.nullsignal.core.terminal.TerminalEnv
import com.nullsignal.launcher.sys.Executor
import com.nullsignal.launcher.sys.Host
import com.nullsignal.launcher.sys.LanScanner
import com.nullsignal.launcher.sys.MdnsService
import com.nullsignal.launcher.sys.MdnsWatcher
import com.nullsignal.launcher.sys.NetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppEntry(val label: String, val pkg: String, val cls: String)

enum class LineKind { INPUT, OUTPUT, SYSTEM, ERROR }
data class TermLine(val text: String, val kind: LineKind = LineKind.OUTPUT)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("nullsignal", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    val engine = QuestEngine()

    val operator: String = prefs.getString("operator", null) ?: run {
        val id = "op" + (1000..9999).random()
        prefs.edit().putString("operator", id).apply()
        id
    }

    var progress by mutableStateOf(
        Progress(
            chapter = prefs.getInt("chapter", 0),
            followers = prefs.getInt("followers", 0),
            readMessages = prefs.getInt("read", 0),
        ),
    )
        private set

    var screen by mutableStateOf(Module.HOME)
    var notice by mutableStateOf<String?>(null)
        private set
    var bootDone by mutableStateOf(false)

    // ---------- сюжет ----------

    fun fire(trigger: Trigger) {
        val before = progress
        val after = engine.on(before, trigger)
        if (after == before) return
        save(after)
        notice = "+${after.followers - before.followers} узлов  ▸  новое сообщение в MESSAGES"
        viewModelScope.launch { delay(4500); notice = null }
    }

    fun markRead() = save(engine.markRead(progress))

    // ---------- эффекты и игры ----------

    var matrix by mutableStateOf(prefs.getBoolean("matrix", true))
        private set

    fun toggleMatrix() {
        matrix = !matrix
        prefs.edit().putBoolean("matrix", matrix).apply()
    }

    var cipherLevel by mutableStateOf(prefs.getInt("cipher", 0))
        private set
    var vulnLevel by mutableStateOf(prefs.getInt("vuln", 0))
        private set
    var nodesWins by mutableStateOf(prefs.getInt("nodes", 0))
        private set

    fun setCipherLevel(n: Int) { cipherLevel = n; prefs.edit().putInt("cipher", n).apply() }
    fun setVulnLevel(n: Int) { vulnLevel = n; prefs.edit().putInt("vuln", n).apply() }
    fun addNodesWin() { nodesWins++; prefs.edit().putInt("nodes", nodesWins).apply() }

    // ---------- ACADEMY ----------

    var lessonsDone by mutableStateOf(prefs.getStringSet("lessons", emptySet())!!.toSet())
        private set

    fun completeLesson(lesson: com.nullsignal.core.academy.Lesson) {
        if (lesson.id in lessonsDone) return
        lessonsDone = lessonsDone + lesson.id
        prefs.edit().putStringSet("lessons", lessonsDone).apply()
        save(progress.copy(followers = progress.followers + lesson.reward))
    }

    private fun save(p: Progress) {
        progress = p
        prefs.edit().putInt("chapter", p.chapter).putInt("followers", p.followers).putInt("read", p.readMessages).apply()
    }

    // ---------- приложения ----------

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    fun refreshApps() {
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val list = if (Build.VERSION.SDK_INT >= 33) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
                list.filter { it.activityInfo.packageName != ctx.packageName }
                    .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
                    .distinctBy { it.pkg + it.cls }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    fun launch(app: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.pkg, app.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { ctx.startActivity(i) }
    }

    // ---------- терминал ----------

    val termLines = mutableStateListOf(
        TermLine("NULL//SIGNAL shell v0.1 — введи help", LineKind.SYSTEM),
    )
    var termBusy by mutableStateOf(false)
        private set
    private val executor = Executor(app)
    private var execJob: Job? = null

    private val env = object : TerminalEnv {
        override val operator get() = this@LauncherViewModel.operator
        override val device get() = "${Build.MANUFACTURER} ${Build.MODEL}"
        override val ip get() = NetInfo.local(ctx)?.ip
        override val objective get() = engine.current(progress).objective
        override val followers get() = progress.followers
        override val apps get() = this@LauncherViewModel.apps.map { it.label }
        override fun now(): String = SimpleDateFormat("EEE dd.MM.yyyy HH:mm:ss zzz", Locale("ru")).format(Date())
    }
    private val terminal = Terminal(env)
    val prompt: String get() = terminal.prompt()
    val termHistory: List<String> get() = terminal.history

    fun submit(input: String) {
        if (termBusy) return
        termLines += TermLine("${terminal.prompt()} $input", LineKind.INPUT)
        val out = terminal.execute(input)
        if (out.action == Action.Clear) termLines.clear()
        out.lines.forEach { termLines += TermLine(it) }
        out.trigger?.let(::fire)
        when (val a = out.action) {
            is Action.Open -> screen = a.module
            is Action.LaunchApp -> apps.firstOrNull { it.label == a.label }?.let(::launch)
            is Action.Exec -> {
                termBusy = true
                execJob = viewModelScope.launch {
                    try {
                        executor.run(a) { line -> termLines += TermLine(line) }
                    } finally {
                        termBusy = false
                    }
                }
            }
            Action.ToggleMatrix -> toggleMatrix()
            Action.Clear, null -> Unit
        }
        while (termLines.size > 1500) termLines.removeAt(0)
    }

    fun interrupt() {
        executor.interrupt()
        execJob?.cancel()
        termLines += TermLine("^C", LineKind.ERROR)
        termBusy = false
    }

    // ---------- NETSCAN ----------

    val scanHosts = mutableStateListOf<Host>()
    val mdns = mutableStateListOf<MdnsService>()
    var scanState by mutableStateOf("готов")
        private set
    var scanProgress by mutableStateOf(0f)
        private set
    var scanning by mutableStateOf(false)
        private set
    private var mdnsWatcher: MdnsWatcher? = null

    fun startScan() {
        if (scanning) return
        val net = NetInfo.local(ctx)
        if (net == null) { scanState = "нет сети: подключись к Wi-Fi"; return }
        if (!com.nullsignal.core.net.Subnet.isPrivate(net.ip)) {
            scanState = "${net.ip} — не домашняя сеть. сканер работает только в частных сетях"
            return
        }
        scanHosts.clear(); mdns.clear()
        scanning = true
        scanProgress = 0f
        scanState = "сканирую ${net.ip}/${maxOf(net.prefix, 24)} …"
        startMdns()
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            try {
                LanScanner.scan(
                    net,
                    onProgress = { d, total -> scanProgress = d.toFloat() / total },
                    onHost = { h ->
                        // Вставляем по порядку адресов
                        val key = { x: Host -> x.ip.substringAfterLast('.').toInt() }
                        val idx = scanHosts.indexOfFirst { key(it) > key(h) }
                        if (idx < 0) scanHosts.add(h) else scanHosts.add(idx, h)
                    },
                )
                scanState = "готово: ${scanHosts.size} устройств за ${(System.currentTimeMillis() - t) / 1000} с"
                fire(Trigger.SCAN_DONE)
            } catch (e: Exception) {
                scanState = "ошибка: ${e.message}"
            } finally {
                scanning = false
                scanProgress = 1f
            }
        }
    }

    private fun startMdns() {
        mdnsWatcher?.stop()
        mdnsWatcher = MdnsWatcher(ctx) { s ->
            main.post { if (mdns.none { it == s }) mdns += s }
        }.also { it.start() }
        viewModelScope.launch { delay(20_000); mdnsWatcher?.stop(); mdnsWatcher = null }
    }

    override fun onCleared() {
        mdnsWatcher?.stop()
        executor.interrupt()
    }
}
