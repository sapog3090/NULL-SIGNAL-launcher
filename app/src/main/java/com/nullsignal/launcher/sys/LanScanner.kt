package com.nullsignal.launcher.sys

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.nullsignal.core.net.Subnet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

data class Host(val ip: String, val name: String?, val ports: List<Int>, val gateway: Boolean)

/**
 * Сканер СВОЕЙ локальной сети: только частные адреса (192.168.x.x, 10.x.x.x, 172.16–31.x.x)
 * и только подсеть, к которой телефон подключён.
 */
object LanScanner {

    private val PROBE_PORTS = listOf(80, 443, 22, 445, 139, 554, 8080, 62078, 5000, 8008)

    /** Хост «живой», если что-то ответило — даже отказом в соединении (RST). */
    private fun alive(ip: String): Boolean {
        if (runCatching { InetAddress.getByName(ip).isReachable(350) }.getOrDefault(false)) return true
        for (port in PROBE_PORTS.take(4)) {
            when (tcp(ip, port, 200)) {
                PortState.OPEN, PortState.REFUSED -> return true
                PortState.SILENT -> Unit
            }
        }
        return false
    }

    private enum class PortState { OPEN, REFUSED, SILENT }

    private fun tcp(ip: String, port: Int, timeout: Int): PortState = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }
        PortState.OPEN
    } catch (e: ConnectException) {
        if (e.message?.contains("ECONNREFUSED") == true || e.message?.contains("refused", true) == true) PortState.REFUSED
        else PortState.SILENT
    } catch (e: Exception) {
        PortState.SILENT
    }

    suspend fun scan(
        net: LocalNet,
        onProgress: suspend (done: Int, total: Int) -> Unit,
        onHost: suspend (Host) -> Unit,
    ) = coroutineScope {
        require(Subnet.isPrivate(net.ip)) { "сканер работает только в частных сетях" }
        val targets = Subnet.hosts(net.ip, net.prefix)
        val done = AtomicInteger()
        val gate = Semaphore(64)
        targets.map { ip ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val isAlive = alive(ip)
                    val host = if (isAlive) inspect(ip, ip == net.gateway) else null
                    val d = done.incrementAndGet()
                    withContext(Dispatchers.Main) {
                        if (host != null) onHost(host)
                        if (d % 8 == 0 || d == targets.size) onProgress(d, targets.size)
                    }
                }
            }
        }.awaitAll()
    }

    /** Открытые порты из списка известных + обратный DNS. */
    private suspend fun inspect(ip: String, gateway: Boolean): Host = coroutineScope {
        val open = Subnet.COMMON_PORTS.keys.map { port ->
            async(Dispatchers.IO) { if (tcp(ip, port, 400) == PortState.OPEN) port else null }
        }.awaitAll().filterNotNull()
        val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip }
        Host(ip, name, open, gateway)
    }
}

/** Сервис, который устройство само объявило в сети через mDNS (Bonjour / DNS-SD). */
data class MdnsService(val name: String, val type: String)

/** Слушает mDNS: телевизоры, колонки, принтеры, умный дом сами говорят, кто они. */
class MdnsWatcher(ctx: Context, private val onFound: (MdnsService) -> Unit) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun start() {
        val m = nsd ?: return
        for (type in TYPES.keys) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    val t = info.serviceType.trim('.').removeSuffix(".local")
                    onFound(MdnsService(info.serviceName, TYPES.entries.firstOrNull { t.startsWith(it.key) }?.value ?: t))
                }
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            runCatching { m.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }.onSuccess { listeners += l }
        }
    }

    fun stop() {
        listeners.forEach { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listeners.clear()
    }

    companion object {
        val TYPES = linkedMapOf(
            "_googlecast._tcp" to "Chromecast/ТВ",
            "_airplay._tcp" to "AirPlay",
            "_raop._tcp" to "AirPlay-аудио",
            "_spotify-connect._tcp" to "Spotify Connect",
            "_ipp._tcp" to "принтер",
            "_printer._tcp" to "принтер",
            "_hap._tcp" to "HomeKit",
            "_smb._tcp" to "сетевая папка",
            "_http._tcp" to "веб-интерфейс",
            "_androidtvremote2._tcp" to "Android TV",
            "_companion-link._tcp" to "Apple-устройство",
        )
    }
}
