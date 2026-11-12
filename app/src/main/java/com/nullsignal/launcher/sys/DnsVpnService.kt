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

/** Одна строчка ленты перехвата. */
data class DnsEvent(val time: Long, val uid: Int, val app: String, val domain: String, val type: String, val blocked: Boolean, val tracker: String?)

/** Сводка по приложению. */
data class AppDns(val uid: Int, val app: String, val queries: Int, val trackers: Int, val domains: Set<String>)

/** Состояние перехватчика для интерфейса (обновляется в главном потоке). */
object DnsMonitor {
    var running by mutableStateOf(false)
    var total by mutableIntStateOf(0)
    var blocked by mutableIntStateOf(0)
    val feed = mutableStateListOf<DnsEvent>()
    val apps = mutableStateMapOf<Int, AppDns>()
    var error by mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())

    fun post(e: DnsEvent) = main.post {
        total++
        if (e.blocked) blocked++
        feed.add(0, e)
        if (feed.size > 400) feed.removeAt(feed.lastIndex)
        val a = apps[e.uid] ?: AppDns(e.uid, e.app, 0, 0, emptySet())
        apps[e.uid] = a.copy(
            queries = a.queries + 1,
            trackers = a.trackers + if (e.tracker != null) 1 else 0,
            domains = if (a.domains.size < 300) a.domains + e.domain else a.domains,
        )
    }

    fun clear() = main.post { feed.clear(); apps.clear(); total = 0; blocked = 0 }

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
        val uid = ownerUid(q)
        val app = appName(uid)
        val tracker = Trackers.match(q.name)
        val cutOff = uid in DnsMonitor.blockedUids(this)
        val block = cutOff || (tracker != null && DnsMonitor.blockTrackers(this))
        val response = if (block) DnsPacket.nxdomain(q.dns) else forward(q.dns)
        DnsMonitor.post(
            DnsEvent(System.currentTimeMillis(), uid, app, q.name, DnsPacket.QTYPES[q.qtype] ?: q.qtype.toString(), block,
                tracker ?: if (cutOff) "отрезано от сети" else null),
        )
        if (response != null) {
            val pkt = DnsPacket.wrapResponse(q, response)
            synchronized(output) { runCatching { output.write(pkt) } }
        }
    }

    /** Настоящий запрос к DNS через защищённый (мимо туннеля) сокет. */
    private fun forward(query: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { s ->
            protect(s)
            s.soTimeout = 4000
            s.send(DatagramPacket(query, query.size, InetAddress.getByName(UPSTREAM), 53))
            val resp = ByteArray(4096)
            val p = DatagramPacket(resp, resp.size)
            s.receive(p)
            resp.copyOf(p.length)
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
        const val UPSTREAM = "1.1.1.1"

        fun start(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
    }
}
