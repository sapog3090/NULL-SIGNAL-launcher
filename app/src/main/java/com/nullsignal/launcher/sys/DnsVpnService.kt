package com.nullsignal.launcher.sys

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nullsignal.core.dns.DnsPacket
import com.nullsignal.core.dns.Trackers
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одна строчка ленты перехвата.
 * [tracker] — чей это трекер (если известен), [reason] — почему заблокировано.
 */
data class DnsEvent(
    val time: Long,
    val uid: Int,
    val app: String,
    val domain: String,
    val type: String,
    val blocked: Boolean,
    val tracker: String?,
    val reason: String? = null,
    val ms: Long = 0,
)

/** Сводка по приложению. */
data class AppDns(val uid: Int, val app: String, val queries: Int, val trackers: Int, val domains: Set<String>, val blocked: Int = 0)

/** Состояние перехватчика для интерфейса (обновляется в главном потоке). */
object DnsMonitor {
    var running by mutableStateOf(false)
    var total by mutableIntStateOf(0)
    var blocked by mutableIntStateOf(0)
    val feed = mutableStateListOf<DnsEvent>()
    val apps = mutableStateMapOf<Int, AppDns>()
    /** Сколько раз запрашивали каждый домен. */
    val topDomains = mutableStateMapOf<String, Int>()
    /** Сколько раз срабатывал каждый трекер. */
    val topTrackers = mutableStateMapOf<String, Int>()
    var error by mutableStateOf<String?>(null)
    /** Полный журнал для экспорта (до 5000 строк). */
    val log = ArrayDeque<DnsEvent>()

    private val main = Handler(Looper.getMainLooper())

    fun post(e: DnsEvent) = main.post {
        total++
        if (e.blocked) blocked++
        feed.add(0, e)
        if (feed.size > 400) feed.removeAt(feed.lastIndex)
        log.addLast(e)
        if (log.size > 5000) log.removeFirst()
        val a = apps[e.uid] ?: AppDns(e.uid, e.app, 0, 0, emptySet())
        apps[e.uid] = a.copy(
            queries = a.queries + 1,
            trackers = a.trackers + if (e.tracker != null) 1 else 0,
            blocked = a.blocked + if (e.blocked) 1 else 0,
            domains = if (a.domains.size < 400) a.domains + e.domain else a.domains,
        )
        if (topDomains.size < 3000 || e.domain in topDomains) topDomains[e.domain] = (topDomains[e.domain] ?: 0) + 1
        e.tracker?.let { topTrackers[it] = (topTrackers[it] ?: 0) + 1 }
    }

    fun clear() = main.post {
        feed.clear(); apps.clear(); topDomains.clear(); topTrackers.clear(); log.clear(); total = 0; blocked = 0
    }

    /** Журнал в CSV для экспорта. */
    fun csv(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        return buildString {
            appendLine("time,app,uid,domain,type,blocked,tracker,reason,ms")
            log.forEach { e ->
                appendLine(listOf(q(fmt.format(java.util.Date(e.time))), q(e.app), e.uid, q(e.domain), e.type, e.blocked, q(e.tracker), q(e.reason), e.ms).joinToString(","))
            }
        }
    }

    // ---- настройки (читаются сервисом при каждом запросе) ----
    private const val PREFS = "dns"
    fun blockTrackers(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("trackers", true)
    fun setBlockTrackers(ctx: Context, v: Boolean) = ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("trackers", v).apply()
    fun blockedUids(ctx: Context): Set<Int> =
        ctx.getSharedPreferences(PREFS, 0).getStringSet("uids", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
    fun setUidBlocked(ctx: Context, uid: Int, blocked: Boolean) {
        val cur = blockedUids(ctx).toMutableSet()
        if (blocked) cur += uid else cur -= uid
        ctx.getSharedPreferences(PREFS, 0).edit().putStringSet("uids", cur.map { it.toString() }.toSet()).apply()
    }
}

/**
 * DNS-перехватчик на базе VpnService.
 *
 * Телефону выдаётся «DNS-сервер» 10.111.222.1, и в туннель маршрутизируется только он.
 * Весь остальной трафик идёт мимо, как обычно. Каждый DNS-запрос мы читаем, узнаём,
 * какое приложение спрашивает, и либо пересылаем настоящему DNS (1.1.1.1), либо
 * отвечаем «домена нет» — так режутся трекеры и приложения, отрезанные от сети.
 */
class DnsVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(6)
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(); stopSelf(); return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY
        try {
            val b = Builder()
                .setSession("NULL//SIGNAL DNS")
                .addAddress(TUN_IP, 32)
                .addDnsServer(DNS_IP)
                .addRoute(DNS_IP, 32)
                .setBlocking(true)
            runCatching { b.addDisallowedApplication(packageName) }
            tun = b.establish() ?: throw IllegalStateException("нет разрешения на VPN")
            // Блок-листы могут быть большими — грузим их в фоне, перехват начинается сразу
            Thread({ runCatching { DnsRules.rebuildBlocking(this) } }, "dns-rules").start()
            running.set(true)
            DnsMonitor.running = true
            DnsMonitor.error = null
            reader = Thread(::loop, "dns-tun").also { it.start() }
        } catch (e: Exception) {
            DnsMonitor.error = e.message
            stopVpn()
            stopSelf()
        }
        return START_STICKY
    }

    private fun loop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running.get()) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            val q = DnsPacket.parse(buf, n) ?: continue
            if (q.dstPort != 53) continue
            pool.execute { handle(q, output) }
        }
    }

    private fun handle(q: DnsPacket.Query, output: FileOutputStream) {
        val started = System.currentTimeMillis()
        val uid = ownerUid(q)
        val app = appName(uid)
        val tracker = Trackers.match(q.name)
        val allowed = DnsRules.allow.match(q.name) != null
        val cutOff = uid in DnsMonitor.blockedUids(this)
        val custom = if (allowed) null else DnsRules.custom.match(q.name)
        val listed = if (allowed) null else DnsRules.lists.match(q.name)
        val reason = when {
            allowed -> null
            cutOff -> "приложение отрезано от сети"
            custom != null -> "моё правило: $custom"
            tracker != null && DnsMonitor.blockTrackers(this) -> "трекер"
            listed != null -> "блок-лист: $listed"
            else -> null
        }
        val block = reason != null
        val response = if (block) DnsPacket.nxdomain(q.dns) else forward(q.dns)
        DnsMonitor.post(
            DnsEvent(
                System.currentTimeMillis(), uid, app, q.name, DnsPacket.QTYPES[q.qtype] ?: q.qtype.toString(), block,
                tracker ?: if (listed != null) "блок-лист" else null, reason, System.currentTimeMillis() - started,
            ),
        )
        if (response != null) {
            val pkt = DnsPacket.wrapResponse(q, response)
            synchronized(output) { runCatching { output.write(pkt) } }
        }
    }

    /** Ответ от настоящего DNS: через DNS-over-HTTPS (зашифровано) или обычным UDP. */
    private fun forward(query: ByteArray): ByteArray? {
        val up = DnsRules.upstream(this)
        if (up.doh != null && DnsRules.useDoh(this)) doh(up.doh!!, query)?.let { return it }
        return udp(up.ip, query)
    }

    /** UDP-запрос через защищённый (мимо туннеля) сокет. */
    private fun udp(server: String, query: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { s ->
            protect(s)
            s.soTimeout = 4000
            s.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val resp = ByteArray(4096)
            val p = DatagramPacket(resp, resp.size)
            s.receive(p)
            resp.copyOf(p.length)
        }
    }.getOrNull()

    /** RFC 8484: POST application/dns-message. Наше приложение исключено из туннеля, так что петли нет. */
    private fun doh(url: String, query: ByteArray): ByteArray? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/dns-message")
        c.setRequestProperty("Accept", "application/dns-message")
        try {
            c.outputStream.use { it.write(query) }
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Какое приложение отправило запрос (Android 10+). */
    private fun ownerUid(q: DnsPacket.Query): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return runCatching {
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(q.srcIp), q.srcPort),
                InetSocketAddress(InetAddress.getByAddress(q.dstIp), q.dstPort),
            )
        }.getOrDefault(-1)
    }

    private val names = HashMap<Int, String>()

    private fun appName(uid: Int): String = synchronized(names) {
        names.getOrPut(uid) {
            when {
                uid < 0 -> "неизвестно"
                uid == 0 -> "система (root)"
                uid == 1000 -> "система Android"
                else -> {
                    val pkgs = packageManager.getPackagesForUid(uid)
                    val pkg = pkgs?.firstOrNull()
                    if (pkg == null) "uid $uid" else runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() +
                            if ((pkgs.size) > 1) " +${pkgs.size - 1}" else ""
                    }.getOrDefault(pkg)
                }
            }
        }
    }

    private fun stopVpn() {
        running.set(false)
        runCatching { tun?.close() }
        tun = null
        DnsMonitor.running = false
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        pool.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val TUN_IP = "10.111.222.2"
        const val DNS_IP = "10.111.222.1"

        fun start(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
    }
}
