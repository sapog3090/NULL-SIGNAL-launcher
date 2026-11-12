package com.nullsignal.launcher.sys

import com.nullsignal.core.osint.Breaches
import com.nullsignal.core.osint.DomainIntel
import com.nullsignal.core.osint.IpIntel
import com.nullsignal.core.osint.MailSecurity
import com.nullsignal.core.osint.TechDetect
import com.nullsignal.core.osint.UsernameSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder

/** Сетевая часть OSINT: всё — открытые публичные источники. */
object OsintNet {

    // ---------- ник ----------

    data class SiteResult(val site: UsernameSearch.Site, val verdict: UsernameSearch.Verdict, val code: Int)

    suspend fun username(nick: String, onResult: suspend (SiteResult) -> Unit) = coroutineScope {
        val gate = Semaphore(8)
        UsernameSearch.SITES.map { site ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val post = site.postBody?.let { UsernameSearch.fill(it, nick, encode = false) }
                    val r = Discover.http(
                        UsernameSearch.fill(site.api, nick), 9000,
                        method = if (post != null) "POST" else "GET", body = post,
                        headers = if (post != null) mapOf("Content-Type" to "application/json", "Accept" to "application/json") else mapOf("Accept" to "*/*"),
                        max = 256 * 1024,
                    )
                    val res = if (r == null) SiteResult(site, UsernameSearch.Verdict.UNKNOWN, -1)
                    else SiteResult(site, UsernameSearch.judge(site, r.code, r.body), r.code)
                    withContext(Dispatchers.Main) { onResult(res) }
                }
            }
        }.awaitAll()
    }

    // ---------- IP ----------

    data class IpReport(val target: String, val ip: String?, val geo: IpIntel.Geo?, val exposure: IpIntel.Exposure?, val rdns: String?, val error: String?)

    suspend fun ip(target: String): IpReport = withContext(Dispatchers.IO) {
        val t = target.trim()
        val ip = runCatching { InetAddress.getByName(t).hostAddress }.getOrNull()
            ?: return@withContext IpReport(t, null, null, null, null, "не удалось найти адрес для «$t»")
        val geoJob = async { Discover.http("https://ipwho.is/$ip", 8000)?.body }
        val expJob = async { Discover.http("https://internetdb.shodan.io/$ip", 8000)?.body }
        val rdnsJob = async { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip } }
        val geoBody = geoJob.await()
        IpReport(
            t, ip,
            geoBody?.let { IpIntel.parseIpWho(it) },
            expJob.await()?.let { IpIntel.parseInternetDb(it) },
            rdnsJob.await(),
            geoBody?.let { IpIntel.ipwhoError(it) },
        )
    }

    suspend fun myIp(): String? = withContext(Dispatchers.IO) { Discover.http("https://api.ipify.org", 6000)?.body?.trim() }

    // ---------- домен ----------

    /** DNS через DoH (JSON): список значений записей. */
    fun dns(name: String, type: String): List<String> {
        val r = Discover.http(
            "https://cloudflare-dns.com/dns-query?name=${URLEncoder.encode(name, "UTF-8")}&type=$type", 6000,
            headers = mapOf("accept" to "application/dns-json"),
        ) ?: return emptyList()
        return runCatching {
            val a = JSONObject(r.body).optJSONArray("Answer") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it).optString("data") }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    data class DomainReport(
        val domain: String,
        val a: List<String>,
        val aaaa: List<String>,
        val ns: List<String>,
        val mx: List<String>,
        val txt: List<String>,
        val mail: List<MailSecurity.Finding>,
        val whois: List<String>,
        val firstSnapshot: DomainIntel.Snapshot?,
        val lastSnapshot: DomainIntel.Snapshot?,
    )

    suspend fun domain(d: String): DomainReport = withContext(Dispatchers.IO) {
        val a = async { dns(d, "A") }
        val aaaa = async { dns(d, "AAAA") }
        val ns = async { dns(d, "NS") }
        val mx = async { dns(d, "MX").map { it.substringAfter(' ').trimEnd('.') } }
        val txt = async { dns(d, "TXT") }
        val dmarc = async { dns("_dmarc.$d", "TXT") }
        val whois = async { runCatching { whoisSummary(d) }.getOrDefault(emptyList()) }
        val first = async { Discover.http("https://web.archive.org/cdx/search/cdx?url=$d&output=json&limit=1&fl=timestamp,original", 12000)?.body?.let { DomainIntel.parseCdxFirst(it) } }
        val last = async { Discover.http("https://archive.org/wayback/available?url=$d", 10000)?.body?.let { DomainIntel.parseWaybackClosest(it) } }
        val mxList = mx.await()
        val txtList = txt.await()
        DomainReport(
            d, a.await(), aaaa.await(), ns.await().map { it.trimEnd('.') }, mxList, txtList,
            MailSecurity.analyze(mxList, txtList, dmarc.await()), whois.await(), first.await(), last.await(),
        )
    }

    /** Главное из WHOIS: регистратор, даты, владелец. */
    fun whoisSummary(domain: String): List<String> {
        fun query(server: String, q: String): String = java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(server, 43), 6000)
            s.soTimeout = 8000
            s.getOutputStream().write("$q\r\n".toByteArray())
            s.getInputStream().bufferedReader().readText()
        }
        val iana = query("whois.iana.org", domain)
        val server = Regex("(?m)^refer:\\s*(\\S+)").find(iana)?.groupValues?.get(1) ?: return emptyList()
        val text = query(server, domain)
        val keys = listOf("registrar:", "creation date:", "created:", "registry expiry date:", "paid-till:", "free-date:",
            "registrant organization:", "org:", "registrant country:", "state:", "updated date:")
        return text.lines().map { it.trim() }.filter { line -> keys.any { line.lowercase().startsWith(it) } }.distinct().take(10)
    }

    suspend fun subdomains(d: String): List<String> = withContext(Dispatchers.IO) {
        val r = Discover.http("https://crt.sh/?q=%25.$d&output=json", 30000, max = 8 * 1024 * 1024) ?: return@withContext emptyList()
        DomainIntel.parseCrtSh(r.body, d)
    }

    data class TechReport(val url: String, val code: Int, val title: String?, val tech: List<TechDetect.Tech>, val error: String?)

    suspend fun tech(input: String): TechReport = withContext(Dispatchers.IO) {
        val url = if (input.startsWith("http")) input else "https://$input"
        val r = Discover.http(url, 9000, max = 512 * 1024) ?: return@withContext TechReport(url, -1, null, emptyList(), "сайт не ответил")
        TechReport(url, r.code, com.nullsignal.core.net.HtmlTitle.extract(r.body), TechDetect.detect(r.headers, r.body), null)
    }

    // ---------- почта ----------

    suspend fun leaks(email: String): List<Breaches.Result> = withContext(Dispatchers.IO) {
        val e = URLEncoder.encode(email.trim(), "UTF-8")
        val x = async { Discover.http("https://api.xposedornot.com/v1/check-email/$e", 10000)?.body?.let { Breaches.parseXposedOrNot(it) } }
        val l = async { Discover.http("https://leakcheck.io/api/public?check=$e", 10000)?.body?.let { Breaches.parseLeakCheck(it) } }
        listOfNotNull(x.await(), l.await())
    }
}
