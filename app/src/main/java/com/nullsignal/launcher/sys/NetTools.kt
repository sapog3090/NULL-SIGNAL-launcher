package com.nullsignal.launcher.sys

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.nullsignal.core.net.Subnet
import com.nullsignal.core.security.WebAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Сетевые утилиты терминала. Все — обычные запросы, как у браузера или системных утилит. */
object NetTools {

    private val HOST = Regex("[A-Za-z0-9.\\-]{1,253}")
    private fun badHost(h: String) = !HOST.matches(h)

    suspend fun traceroute(host: String, out: suspend (String) -> Unit) {
        if (badHost(host)) return out("traceroute: неверный хост")
        out("маршрут до $host (до 20 прыжков):")
        for (ttl in 1..20) {
            val t = SystemClock.elapsedRealtime()
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    val p = ProcessBuilder("ping", "-c", "1", "-W", "1", "-t", ttl.toString(), host)
                        .redirectErrorStream(true).start()
                    val s = p.inputStream.bufferedReader().readText()
                    p.waitFor(3, TimeUnit.SECONDS)
                    p.destroy()
                    s
                }.getOrDefault("")
            }
            val ms = SystemClock.elapsedRealtime() - t
            val hop = Regex("[Ff]rom ([0-9a-fA-F.:]+)").find(text)?.groupValues?.get(1)
            val reached = Regex("bytes from ([0-9a-fA-F.:]+)").find(text)?.groupValues?.get(1)
            when {
                reached != null -> { out("${ttl.toString().padStart(2)}  $reached  ${ms} мс  ◀ цель"); return }
                hop != null -> out("${ttl.toString().padStart(2)}  $hop  ${ms} мс")
                else -> out("${ttl.toString().padStart(2)}  * * *")
            }
        }
        out("цель не ответила за 20 прыжков")
    }

    /** DNS через DNS-over-HTTPS (Cloudflare). */
    suspend fun dig(domain: String, type: String, out: suspend (String) -> Unit) {
        if (badHost(domain)) return out("dig: неверный домен")
        val t = type.uppercase()
        val json = withContext(Dispatchers.IO) {
            val c = URL("https://cloudflare-dns.com/dns-query?name=${URLEncoder.encode(domain, "UTF-8")}&type=$t")
                .openConnection() as HttpURLConnection
            c.setRequestProperty("accept", "application/dns-json")
            c.connectTimeout = 6000; c.readTimeout = 6000
            try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        }
        val o = JSONObject(json)
        val answers = o.optJSONArray("Answer")
        out(";; $domain $t  статус: ${if (o.optInt("Status") == 0) "OK" else "ошибка ${o.optInt("Status")}"}")
        if (answers == null || answers.length() == 0) return out("записей нет")
        for (i in 0 until answers.length()) {
            val a = answers.getJSONObject(i)
            val typeName = DNS_TYPES[a.optInt("type")] ?: a.optInt("type").toString()
            out("${a.optString("name")}  ${a.optInt("TTL")}  $typeName  ${a.optString("data")}")
        }
    }

    private val DNS_TYPES = mapOf(1 to "A", 2 to "NS", 5 to "CNAME", 6 to "SOA", 15 to "MX", 16 to "TXT", 28 to "AAAA", 257 to "CAA")

    suspend fun ssl(host: String, out: suspend (String) -> Unit) {
        val h = host.removePrefix("https://").substringBefore('/')
        if (badHost(h)) return out("ssl: неверный домен")
        val lines = withContext(Dispatchers.IO) {
            val s = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            try {
                s.connect(InetSocketAddress(h, 443), 6000)
                s.soTimeout = 6000
                s.startHandshake()
                val session = s.session
                val cert = session.peerCertificates.first() as X509Certificate
                val daysLeft = (cert.notAfter.time - System.currentTimeMillis()) / 86_400_000
                val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.US)
                val san = runCatching { cert.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() } }.getOrNull().orEmpty()
                listOf(
                    "протокол : ${session.protocol}",
                    "шифр     : ${session.cipherSuite}",
                    "владелец : ${cert.subjectX500Principal.name}",
                    "выдал    : ${cert.issuerX500Principal.name}",
                    "действует: ${fmt.format(cert.notBefore)} — ${fmt.format(cert.notAfter)} (осталось $daysLeft дн.)",
                    "имя      : ${if (HttpsURLConnection.getDefaultHostnameVerifier().verify(h, session)) "совпадает ✓" else "НЕ совпадает ⚠"}",
                    "домены   : ${san.take(8).joinToString()}${if (san.size > 8) " … +${san.size - 8}" else ""}",
                    "цепочка  : ${session.peerCertificates.size} сертификата",
                )
            } finally {
                s.close()
            }
        }
        lines.forEach { out(it) }
    }

    suspend fun sechead(raw: String, out: suspend (String) -> Unit) {
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        val headers = withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "nullsignal-shell/0.2")
            try {
                c.responseCode
                c.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") }
            } finally { c.disconnect() }
        }
        val checks = WebAudit.check(headers)
        out("оценка: ${WebAudit.grade(checks)}")
        checks.forEach { out("${if (it.present) "✓" else "✗"} ${it.header} — ${it.about}") }
        WebAudit.leaks(headers).forEach { out("⚠ $it") }
    }

    suspend fun whois(domain: String, out: suspend (String) -> Unit) {
        if (badHost(domain)) return out("whois: неверный домен")
        val iana = query("whois.iana.org", domain)
        val server = Regex("(?m)^refer:\\s*(\\S+)").find(iana)?.groupValues?.get(1)
        val text = if (server != null) query(server, domain) else iana
        out("сервер: ${server ?: "whois.iana.org"}")
        text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("%") && !it.startsWith("#") && !it.startsWith(">>>") }
            .take(40).forEach { out(it) }
    }

    private suspend fun query(server: String, q: String): String = withContext(Dispatchers.IO) {
        Socket().use { s ->
            s.connect(InetSocketAddress(server, 43), 6000)
            s.soTimeout = 8000
            s.getOutputStream().write("$q\r\n".toByteArray())
            s.getInputStream().bufferedReader().readText()
        }
    }

    suspend fun myip(ctx: Context, out: suspend (String) -> Unit) {
        NetInfo.local(ctx)?.let { out("локальный : ${it.ip}/${it.prefix}  шлюз ${it.gateway ?: "?"}") }
        val ip = withContext(Dispatchers.IO) {
            val c = URL("https://api.ipify.org").openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 6000
            try { c.inputStream.bufferedReader().readText().trim() } finally { c.disconnect() }
        }
        out("внешний   : $ip  (так тебя видят сайты)")
    }

    @Suppress("DEPRECATION")
    suspend fun wifiinfo(ctx: Context, out: suspend (String) -> Unit) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val info = wm?.connectionInfo ?: return out("Wi-Fi недоступен")
        if (info.networkId == -1) return out("не подключено к Wi-Fi")
        val ssid = info.ssid.trim('"').let { if (it == "<unknown ssid>") "скрыто (нужна геолокация)" else it }
        out("сеть     : $ssid")
        out("bssid    : ${info.bssid ?: "?"}")
        out("сигнал   : ${info.rssi} dBm")
        out("скорость : ${info.linkSpeed} Мбит/с")
        out("частота  : ${info.frequency} МГц")
        NetInfo.local(ctx)?.let { out("ip       : ${it.ip}/${it.prefix}  шлюз ${it.gateway}") }
    }

    /** Порты одного устройства СВОЕЙ сети (проверка частного адреса — в ядре терминала). */
    suspend fun portscan(ip: String, out: suspend (String) -> Unit) {
        if (!Subnet.isPrivate(ip)) return out("только локальная сеть")
        out("сканирую ${Subnet.COMMON_PORTS.size} известных портов на $ip …")
        val open = coroutineScope {
            Subnet.COMMON_PORTS.keys.map { port ->
                async(Dispatchers.IO) {
                    val t = SystemClock.elapsedRealtime()
                    val ok = runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 600) } }.isSuccess
                    if (ok) port to (SystemClock.elapsedRealtime() - t) else null
                }
            }.awaitAll().filterNotNull()
        }
        if (open.isEmpty()) return out("открытых портов не найдено")
        open.forEach { (port, ms) ->
            out("${port.toString().padStart(5)}/tcp  ОТКРЫТ  ${Subnet.COMMON_PORTS[port]}  (${ms} мс)")
            Subnet.ADVICE[port]?.let { out("        совет: $it") }
        }
    }
}
