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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
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
import kotlinx.coroutines.async
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
    /** Аргумент для модуля, открытого из терминала (ssh user@host, osint ник). Модуль забирает его сам. */
    var moduleArg by mutableStateOf<String?>(null)

    /** Файл, которым поделились с лаунчером из другого приложения: его ждёт ПРОВЕРКА ФАЙЛА. */
    var sharedFile by mutableStateOf<android.net.Uri?>(null)
    var notice by mutableStateOf<String?>(null)
        private set

    /** Короткое уведомление сверху экрана. */
    fun toast(text: String) {
        notice = text
        viewModelScope.launch { delay(3500); if (notice == text) notice = null }
    }
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

    /** Ответы игрока в чате: id главы → номер выбранного варианта. */
    var replies by mutableStateOf(
        prefs.getStringSet("replies", emptySet())!!.mapNotNull { e -> e.substringAfterLast(':').toIntOrNull()?.let { e.substringBeforeLast(':') to it } }.toMap(),
    )
        private set

    fun reply(chapterId: String, index: Int) {
        if (chapterId in replies) return
        replies = replies + (chapterId to index)
        prefs.edit().putStringSet("replies", replies.map { "${it.key}:${it.value}" }.toSet()).apply()
    }

    // ---------- эффекты и игры ----------

    var matrix by mutableStateOf(prefs.getBoolean("matrix2", false))
        private set

    fun toggleMatrix() {
        matrix = !matrix
        prefs.edit().putBoolean("matrix2", matrix).apply()
    }

    var cipherLevel by mutableStateOf(prefs.getInt("cipher", 0))
        private set
    var vulnLevel by mutableStateOf(prefs.getInt("vuln", 0))
        private set
    var nodesWins by mutableStateOf(prefs.getInt("nodes", 0))
        private set

    fun saveCipherLevel(n: Int) { cipherLevel = n; prefs.edit().putInt("cipher", n).apply() }
    fun saveVulnLevel(n: Int) { vulnLevel = n; prefs.edit().putInt("vuln", n).apply() }
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

    var tasksDone by mutableStateOf(prefs.getStringSet("pytasks", emptySet())!!.toSet())
        private set

    /** Задача из практики решена: награда даётся один раз. */
    fun completeTask(task: com.nullsignal.core.academy.PracticeTask) {
        if (task.id in tasksDone) return
        tasksDone = tasksDone + task.id
        prefs.edit().putStringSet("pytasks", tasksDone).apply()
        save(progress.copy(followers = progress.followers + task.reward))
        if (tasksDone.size >= 3) fire(Trigger.PY_TASKS)
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
            loadIcons(apps)
        }
    }

    /** Иконки приложений: пакет+класс → картинка. Грузятся в фоне после списка. */
    val icons = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

    private fun loadIcons(list: List<AppEntry>) {
        viewModelScope.launch {
            val pm = ctx.packageManager
            list.filter { (it.pkg + "/" + it.cls) !in icons }.chunked(12).forEach { batch ->
                val loaded = withContext(Dispatchers.IO) {
                    batch.mapNotNull { a ->
                        runCatching {
                            val d = pm.getActivityIcon(ComponentName(a.pkg, a.cls))
                            (a.pkg + "/" + a.cls) to d.toBitmap(96, 96).asImageBitmap()
                        }.getOrNull()
                    }
                }
                loaded.forEach { (k, v) -> icons[k] = v }
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
        TermLine("NULL//SIGNAL shell v0.3 — введи help", LineKind.SYSTEM),
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
            is Action.OpenWith -> { moduleArg = a.arg.ifBlank { null }; screen = a.module }
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

    var newDevices by mutableStateOf(0)
        private set

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
        newDevices = 0
        scanState = "сканирую ${net.ip}/${maxOf(net.prefix, 24)} …"
        startMdns()
        val knownKey = "known_" + (net.gateway ?: net.ip.substringBeforeLast('.'))
        val known = prefs.getStringSet(knownKey, null)
        viewModelScope.launch {
            val t = System.currentTimeMillis()
            try {
                // UPnP-опрос идёт параллельно со сканом портов
                val upnp = async(Dispatchers.IO) { runCatching { com.nullsignal.launcher.sys.Discover.upnpByIp(ctx) }.getOrDefault(emptyMap()) }
                LanScanner.scan(
                    net,
                    onProgress = { d, total -> scanProgress = d.toFloat() / total * 0.85f },
                    onHost = { h0 ->
                        val h = h0.copy(isNew = known != null && h0.ip !in known)
                        // Вставляем по порядку адресов
                        val key = { x: Host -> x.ip.substringAfterLast('.').toInt() }
                        val idx = scanHosts.indexOfFirst { key(it) > key(h) }
                        if (idx < 0) scanHosts.add(h) else scanHosts.add(idx, h)
                    },
                )
                scanState = "опознаю устройства…"
                val upnpMap = upnp.await()
                val gate = kotlinx.coroutines.sync.Semaphore(12)
                scanHosts.toList().map { h ->
                    async {
                        gate.acquire()
                        try {
                            val rich = com.nullsignal.launcher.sys.Discover.enrich(h, upnpMap)
                            val i = scanHosts.indexOfFirst { it.ip == h.ip }
                            if (i >= 0) scanHosts[i] = rich
                        } finally {
                            gate.release()
                        }
                    }
                }.forEach { it.await() }
                newDevices = scanHosts.count { it.isNew }
                prefs.edit().putStringSet(knownKey, (known.orEmpty() + scanHosts.map { it.ip }).toSet()).apply()
                scanState = "готово: ${scanHosts.size} устройств за ${(System.currentTimeMillis() - t) / 1000} с" +
                    if (known == null) " · сеть запомнена, новые устройства будут подсвечены" else ""
                fire(Trigger.SCAN_DONE)
            } catch (e: Exception) {
                scanState = "ошибка: ${e.message}"
            } finally {
                scanning = false
                scanProgress = 1f
            }
        }
    }

    fun forgetNetwork() {
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith("known_") }.forEach { remove(it) } }.apply()
        for (i in scanHosts.indices) scanHosts[i] = scanHosts[i].copy(isNew = false)
        newDevices = 0
    }

    // ---------- роутер (UPnP) ----------

    var router by mutableStateOf<com.nullsignal.launcher.sys.Discover.RouterReport?>(null)
        private set
    var routerBusy by mutableStateOf(false)
        private set
    var routerExposure by mutableStateOf<String?>(null)
        private set

    fun auditRouter() {
        if (routerBusy) return
        routerBusy = true
        routerExposure = null
        viewModelScope.launch {
            try {
                val r = com.nullsignal.launcher.sys.Discover.routerAudit(ctx)
                router = r
                // Что интернет видит на внешнем адресе (только если адрес белый)
                val ext = r.externalIp
                if (ext != null && !com.nullsignal.launcher.sys.Discover.isCarrierNat(ext)) {
                    routerExposure = withContext(Dispatchers.IO) {
                        com.nullsignal.launcher.sys.Discover.http("https://internetdb.shodan.io/$ext", 6000)?.body
                            ?.let { com.nullsignal.core.osint.IpIntel.parseInternetDb(it) }
                            ?.let { e ->
                                if (e.ports.isEmpty()) "интернет-сканеры не видят открытых портов на $ext ✓"
                                else "интернет-сканеры видят на $ext порты: ${e.ports.joinToString()}" +
                                    if (e.vulns.isNotEmpty()) " · уязвимости: ${e.vulns.take(5).joinToString()}" else ""
                            } ?: "интернет-сканеры ничего не знают о $ext ✓"
                    }
                }
            } finally {
                routerBusy = false
            }
        }
    }

    // ---------- Wake-on-LAN ----------

    data class WolTarget(val name: String, val mac: String)

    var wolTargets by mutableStateOf(
        prefs.getStringSet("wol", emptySet())!!.mapNotNull { s ->
            s.split('|').takeIf { it.size == 2 }?.let { WolTarget(it[0], it[1]) }
        }.sortedBy { it.name },
    )
        private set

    fun addWol(name: String, mac: String): Boolean {
        val bytes = com.nullsignal.core.net.WakeOnLan.parseMac(mac) ?: return false
        val t = WolTarget(name.ifBlank { "компьютер" }.replace("|", "/"), com.nullsignal.core.net.WakeOnLan.formatMac(bytes))
        wolTargets = (wolTargets.filter { it.mac != t.mac } + t).sortedBy { it.name }
        prefs.edit().putStringSet("wol", wolTargets.map { "${it.name}|${it.mac}" }.toSet()).apply()
        return true
    }

    fun removeWol(t: WolTarget) {
        wolTargets = wolTargets - t
        prefs.edit().putStringSet("wol", wolTargets.map { "${it.name}|${it.mac}" }.toSet()).apply()
    }

    fun wake(t: WolTarget): String {
        val mac = com.nullsignal.core.net.WakeOnLan.parseMac(t.mac) ?: return "неверный MAC"
        val n = runCatching { com.nullsignal.launcher.sys.Discover.wake(mac, NetInfo.local(ctx)) }.getOrDefault(0)
        return if (n > 0) "волшебный пакет отправлен ($n шт.) → ${t.name}" else "не удалось отправить — проверь Wi-Fi"
    }

    // ---------- камеры ----------

    val cameras = mutableStateListOf<com.nullsignal.launcher.sys.Discover.CameraHit>()
    var camState by mutableStateOf("")
        private set
    var camProgress by mutableStateOf(0f)
        private set
    var camBusy by mutableStateOf(false)
        private set

    fun scanCameras() {
        if (camBusy) return
        val net = NetInfo.local(ctx)
        if (net == null || !com.nullsignal.core.net.Subnet.isPrivate(net.ip)) {
            camState = "подключись к Wi-Fi той сети, которую проверяешь"
            return
        }
        camBusy = true
        cameras.clear()
        camProgress = 0f
        camState = "прочёсываю ${net.ip.substringBeforeLast('.')}.0/24: RTSP, ONVIF, веб-интерфейсы…"
        viewModelScope.launch {
            try {
                val hits = com.nullsignal.launcher.sys.Discover.cameraSweep(ctx, net) { camProgress = it }
                cameras.addAll(hits)
                camState = if (hits.isEmpty()) "камер в сети не найдено ✓" else "найдено подозрительных устройств: ${hits.size}"
            } catch (e: Exception) {
                camState = "ошибка: ${e.message}"
            } finally {
                camBusy = false
                camProgress = 1f
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
