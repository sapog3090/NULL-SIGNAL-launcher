package com.nullsignal.launcher.sys

import android.content.Context
import android.net.wifi.WifiManager
import com.nullsignal.core.net.DeviceKind
import com.nullsignal.core.net.HtmlTitle
import com.nullsignal.core.net.NetBios
import com.nullsignal.core.net.Ssdp
import com.nullsignal.core.net.Subnet
import com.nullsignal.core.net.Upnp
import com.nullsignal.core.net.UpnpDevice
import com.nullsignal.core.net.WakeOnLan
import com.nullsignal.core.net.WsDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** Сетевые обнаружения в СВОЕЙ локальной сети: кто есть кто, камеры, роутер. */
object Discover {

    /** Ответ HTTP: код, заголовки (ключи в нижнем регистре), тело (обрезанное). */
    data class Http(val code: Int, val headers: Map<String, String>, val body: String)

    private fun readLimited(stream: InputStream?, max: Int): String {
        if (stream == null) return ""
        val buf = ByteArray(max)
        var n = 0
        stream.use { s ->
            while (n < max) {
                val r = s.read(buf, n, max - n)
                if (r <= 0) break
                n += r
            }
        }
        return String(buf, 0, n, Charsets.UTF_8)
    }

    fun http(
        url: String,
        timeoutMs: Int = 3000,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        max: Int = 256 * 1024,
        followRedirects: Boolean = true,
    ): Http? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = followRedirects
        c.requestMethod = method
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NullSignal/0.3")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.joinToString("; ") }
            Http(code, h, readLimited(stream, max))
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun <T> withMulticast(ctx: Context, block: () -> T): T {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val lock = runCatching { wm?.createMulticastLock("nullsignal")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            return block()
        } finally {
            runCatching { lock?.release() }
        }
    }

    // ---------- SSDP / UPnP ----------

    data class SsdpHit(val ip: String, val location: String, val server: String?, val st: String?)

    /** Мультикаст-поиск: устройства отвечают своими адресами описаний. */
    fun ssdpSearch(ctx: Context, st: String = "ssdp:all", timeoutMs: Long = 2500): List<SsdpHit> = withMulticast(ctx) {
        val hits = LinkedHashMap<String, SsdpHit>()
        runCatching {
            DatagramSocket().use { s ->
                s.soTimeout = 250
                val req = Ssdp.searchRequest(st).toByteArray()
                val group = InetAddress.getByName(Ssdp.ADDRESS)
                repeat(2) { s.send(DatagramPacket(req, req.size, group, Ssdp.PORT)) }
                val buf = ByteArray(4096)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val h = Ssdp.parseHeaders(String(buf, 0, p.length))
                        val loc = h["location"] ?: continue
                        hits.getOrPut(loc) { SsdpHit(p.address.hostAddress ?: "", loc, h["server"], h["st"]) }
                    } catch (e: SocketTimeoutException) {
                    }
                }
            }
        }
        hits.values.toList()
    }

    fun upnpDescription(location: String): UpnpDevice? =
        http(location, 2500)?.takeIf { it.code in 200..299 }?.let { runCatching { Upnp.parseDescription(it.body, location) }.getOrNull() }

    /** ip → описание UPnP-устройства (имя, производитель, модель). */
    suspend fun upnpByIp(ctx: Context): Map<String, UpnpDevice> = withContext(Dispatchers.IO) {
        val hits = ssdpSearch(ctx)
        coroutineScope {
            hits.distinctBy { it.location }.map { h -> async { upnpDescription(h.location)?.let { h.ip to it } } }.awaitAll()
        }.filterNotNull().groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.maxByOrNull { (it.friendlyName?.length ?: 0) + (it.modelName?.length ?: 0) }!! }
    }

    // ---------- NetBIOS ----------

    fun netbios(ip: String, timeoutMs: Int = 700): NetBios.Info? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val req = NetBios.nodeStatusRequest()
            s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            NetBios.parseNodeStatus(buf, p.length)
        }
    }.getOrNull()

    // ---------- веб-интерфейс ----------

    /** Заголовок страницы и Server на порту. */
    fun webInfo(ip: String, port: Int): Pair<String?, String?>? {
        val scheme = if (port == 443 || port == 8443) "https" else "http"
        val r = http("$scheme://$ip:$port/", 1800, max = 64 * 1024) ?: return null
        return HtmlTitle.extract(r.body) to r.headers["server"]
    }

    // ---------- обогащение найденного хоста ----------

    suspend fun enrich(h: Host, upnp: Map<String, UpnpDevice>): Host = coroutineScope {
        val nb = async(Dispatchers.IO) { netbios(h.ip) }
        val web = async(Dispatchers.IO) {
            listOf(80, 8080, 443, 8443, 8000).firstOrNull { it in h.ports }?.let { webInfo(h.ip, it) }
        }
        val n = nb.await()
        val w = web.await()
        val u = upnp[h.ip]
        val kind = DeviceKind.guess(h.gateway, h.ports, u?.title, u?.deviceType, w?.first, w?.second, n?.name, h.name)
        h.copy(netbios = n?.name, mac = n?.mac, upnp = u?.title, upnpType = u?.deviceType, web = w?.first, server = w?.second, kind = kind)
    }

    // ---------- камеры ----------

    /** ONVIF WS-Discovery: камеры сами отвечают на зов. ip → описание. */
    fun onvifProbe(ctx: Context, timeoutMs: Long = 3000): Map<String, WsDiscovery.Camera> = withMulticast(ctx) {
        val found = LinkedHashMap<String, WsDiscovery.Camera>()
        runCatching {
            DatagramSocket().use { s ->
                s.soTimeout = 300
                val req = WsDiscovery.probe(UUID.randomUUID().toString()).toByteArray()
                val group = InetAddress.getByName(WsDiscovery.ADDRESS)
                repeat(2) { s.send(DatagramPacket(req, req.size, group, WsDiscovery.PORT)) }
                val buf = ByteArray(16 * 1024)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        WsDiscovery.parse(String(buf, 0, p.length))?.let { found[p.address.hostAddress ?: ""] = it }
                    } catch (e: SocketTimeoutException) {
                    }
                }
            }
        }
        found
    }

    data class CameraHit(val ip: String, val reasons: List<String>, val title: String?, val confidence: Int)

    private fun open(ip: String, port: Int, timeout: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), timeout) } }.isSuccess

    private val CAM_WORDS = listOf("camera", "ipcam", "ip cam", "webcam", "hikvision", "dahua", "nvr", "dvr", "netsurveillance", "onvif", "ezviz", "imou", "reolink", "xmeye", "vstarcam", "yoosee", "uniview", "tp-link tapo", "tapo")

    /** Поиск камер в своей подсети: RTSP, характерные порты, ONVIF, вид веб-интерфейса. */
    suspend fun cameraSweep(ctx: Context, net: LocalNet, onProgress: suspend (Float) -> Unit): List<CameraHit> = coroutineScope {
        require(Subnet.isPrivate(net.ip)) { "только частные сети" }
        val onvif = async(Dispatchers.IO) { onvifProbe(ctx) }
        val targets = Subnet.hosts(net.ip, net.prefix)
        val gate = Semaphore(64)
        var done = 0
        val sweep = targets.map { ip ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val reasons = mutableListOf<String>()
                    var score = 0
                    if (open(ip, 554, 350)) { reasons += "RTSP-видеопоток (порт 554)"; score += 3 }
                    if (open(ip, 8554, 250)) { reasons += "RTSP-видеопоток (порт 8554)"; score += 3 }
                    if (open(ip, 37777, 250)) { reasons += "порт Dahua (37777)"; score += 3 }
                    if (open(ip, 34567, 250)) { reasons += "порт XMEye/китайских DVR (34567)"; score += 3 }
                    var title: String? = null
                    val webPort = listOf(80, 8000, 8080).firstOrNull { open(ip, it, 250) }
                    if (webPort != null) {
                        val w = webInfo(ip, webPort)
                        title = w?.first
                        val text = listOfNotNull(w?.first, w?.second).joinToString(" ").lowercase()
                        CAM_WORDS.firstOrNull { it in text }?.let { reasons += "веб-интерфейс камеры: «${w?.first ?: w?.second}»"; score += 3 }
                    }
                    withContext(Dispatchers.Main) { done++; if (done % 8 == 0) onProgress(done.toFloat() / targets.size) }
                    if (score > 0) CameraHit(ip, reasons, title, score) else null
                }
            }
        }.awaitAll().filterNotNull().associateBy { it.ip }.toMutableMap()
        onvif.await().forEach { (ip, cam) ->
            val reason = "ответила как ONVIF-камера: ${listOfNotNull(cam.name, cam.hardware).joinToString(" ").ifEmpty { cam.addresses.first() }}"
            val old = sweep[ip]
            sweep[ip] = if (old == null) CameraHit(ip, listOf(reason), cam.name, 5) else old.copy(reasons = old.reasons + reason, confidence = old.confidence + 5)
        }
        onProgress(1f)
        sweep.values.sortedByDescending { it.confidence }
    }

    // ---------- роутер: UPnP-аудит ----------

    data class RouterReport(
        val device: UpnpDevice?,
        val location: String?,
        val externalIp: String?,
        val mappings: List<Upnp.PortMapping>,
        val error: String?,
    )

    private fun soap(control: String, service: String, action: String, args: Map<String, String> = emptyMap()): Http? = http(
        control, 3000, "POST", Upnp.soapEnvelope(service, action, args),
        mapOf("Content-Type" to "text/xml; charset=\"utf-8\"", "SOAPAction" to "\"$service#$action\""),
    )

    suspend fun routerAudit(ctx: Context): RouterReport = withContext(Dispatchers.IO) {
        val hits = ssdpSearch(ctx, "urn:schemas-upnp-org:device:InternetGatewayDevice:1", 2000) +
            ssdpSearch(ctx, "urn:schemas-upnp-org:service:WANIPConnection:1", 1500)
        val described = hits.distinctBy { it.location }.mapNotNull { h -> upnpDescription(h.location)?.let { h.location to it } }
        val (loc, dev) = described.firstOrNull { it.second.wanControlUrl != null }
            ?: return@withContext RouterReport(described.firstOrNull()?.second, described.firstOrNull()?.first, null, emptyList(),
                if (hits.isEmpty()) "роутер не ответил по UPnP — скорее всего, UPnP выключен (это хорошо)" else "UPnP есть, но сервис подключения не найден")
        val control = dev.wanControlUrl!!
        val service = dev.wanServiceType ?: "urn:schemas-upnp-org:service:WANIPConnection:1"
        val ext = soap(control, service, "GetExternalIPAddress")?.body?.let { Upnp.parseExternalIp(it) }
        val maps = mutableListOf<Upnp.PortMapping>()
        for (i in 0 until 128) {
            val r = soap(control, service, "GetGenericPortMappingEntry", mapOf("NewPortMappingIndex" to "$i")) ?: break
            if (r.code != 200) break
            maps += Upnp.parseMapping(r.body) ?: break
        }
        RouterReport(dev, loc, ext, maps, null)
    }

    /** Серый (не из интернета) внешний адрес: частный или CGNAT 100.64/10. */
    fun isCarrierNat(ip: String): Boolean = runCatching {
        Subnet.isPrivate(ip) || (Subnet.parse(ip) ushr 22) == (Subnet.parse("100.64.0.0") ushr 22)
    }.getOrDefault(false)

    // ---------- Wake-on-LAN ----------

    fun wake(mac: ByteArray, net: LocalNet?): Int {
        val packet = WakeOnLan.magicPacket(mac)
        val targets = listOfNotNull(net?.let { WakeOnLan.broadcast(it.ip, it.prefix) }, "255.255.255.255").distinct()
        var sent = 0
        DatagramSocket().use { s ->
            s.broadcast = true
            for (t in targets) for (port in listOf(9, 7)) {
                runCatching { s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port)); sent++ }
            }
        }
        return sent
    }
}
