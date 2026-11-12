package com.nullsignal.launcher.sys

import android.content.Context
import com.nullsignal.core.net.Subnet
import com.nullsignal.core.net.WakeOnLan
import com.nullsignal.core.osint.Breaches
import com.nullsignal.core.osint.DomainIntel
import com.nullsignal.core.osint.MailSecurity
import com.nullsignal.core.osint.UsernameSearch

/** OSINT и сетевые команды терминала: те же движки, что и в модулях, но вывод строками. */
object TermOsint {

    suspend fun ipinfo(target: String, out: suspend (String) -> Unit) {
        out("пробиваю $target …")
        val r = OsintNet.ip(target)
        r.error?.let { out("ошибка: $it") }
        val ip = r.ip ?: return
        out("ip          $ip" + (r.rdns?.let { "  ($it)" } ?: ""))
        r.geo?.let { g ->
            out("страна      ${g.country ?: "?"} ${g.countryCode ?: ""}")
            out("город       ${listOfNotNull(g.region, g.city).joinToString(", ")}")
            out("провайдер   ${g.isp ?: "?"}" + (g.asn?.let { " · $it" } ?: ""))
            if (g.lat != null && g.lon != null) out("координаты  %.4f, %.4f (по городу)".format(g.lat, g.lon))
        }
        val e = r.exposure
        if (e == null) out("shodan      открытых сервисов не видно")
        else {
            out("shodan      порты: ${e.ports.joinToString().ifEmpty { "—" }}")
            if (e.hostnames.isNotEmpty()) out("            имена: ${e.hostnames.take(5).joinToString()}")
            if (e.vulns.isNotEmpty()) out("⚠ уязвимости (${e.vulns.size}): ${e.vulns.take(10).joinToString()}")
        }
    }

    suspend fun subdomains(raw: String, out: suspend (String) -> Unit) {
        val d = DomainIntel.clean(raw)
        if (!DomainIntel.validDomain(d)) return out("subdomains: неверный домен")
        out("журналы сертификатов (crt.sh) для $d … это может занять до 30 с")
        val list = OsintNet.subdomains(d)
        if (list.isEmpty()) return out("ничего не найдено или crt.sh не ответил")
        list.take(200).forEach { out("  $it") }
        out("всего: ${list.size}")
    }

    suspend fun tech(raw: String, out: suspend (String) -> Unit) {
        val r = OsintNet.tech(raw.trim())
        r.error?.let { return out("tech: $it") }
        out("${r.url} · HTTP ${r.code}" + (r.title?.let { " · «$it»" } ?: ""))
        if (r.tech.isEmpty()) out("ничего характерного не найдено")
        r.tech.groupBy { it.category }.forEach { (cat, list) -> out("${cat.padEnd(14)} ${list.joinToString { it.name }}") }
    }

    suspend fun mailsec(raw: String, out: suspend (String) -> Unit) {
        val d = DomainIntel.clean(raw)
        if (!DomainIntel.validDomain(d)) return out("mailsec: неверный домен")
        val mx = OsintNet.dns(d, "MX").map { it.substringAfter(' ').trimEnd('.') }
        val txt = OsintNet.dns(d, "TXT")
        val dmarc = OsintNet.dns("_dmarc.$d", "TXT")
        val f = MailSecurity.analyze(mx, txt, dmarc)
        f.forEach { out((if (it.ok) "✓ " else "✗ ") + it.text) }
        out(if (MailSecurity.spoofable(f)) "ВЫВОД: письмо от имени $d подделать можно" else "ВЫВОД: подделка писем от $d будет отклонена")
    }

    suspend fun wayback(raw: String, out: suspend (String) -> Unit) {
        val d = DomainIntel.clean(raw)
        val first = Discover.http("https://web.archive.org/cdx/search/cdx?url=$d&output=json&limit=1&fl=timestamp,original", 12000)?.body
            ?.let { DomainIntel.parseCdxFirst(it) }
        val last = Discover.http("https://archive.org/wayback/available?url=$d", 10000)?.body?.let { DomainIntel.parseWaybackClosest(it) }
        if (first == null && last == null) return out("в веб-архиве нет копий $d")
        first?.let { out("первая копия: ${DomainIntel.formatTimestamp(it.timestamp)}  ${it.url}") }
        last?.let { out("последняя:    ${DomainIntel.formatTimestamp(it.timestamp)}  ${it.url}") }
    }

    suspend fun user(raw: String, out: suspend (String) -> Unit) {
        val nick = raw.trim().removePrefix("@")
        if (!UsernameSearch.valid(nick)) return out("user: ник из латиницы, цифр, . _ - (2–40 символов)")
        out("ищу «$nick» на ${UsernameSearch.SITES.size} сайтах…")
        var found = 0
        OsintNet.username(nick) { r ->
            when (r.verdict) {
                UsernameSearch.Verdict.FOUND -> { found++; out("◉ ${r.site.name.padEnd(13)} ${UsernameSearch.fill(r.site.profile, nick)}") }
                UsernameSearch.Verdict.UNKNOWN -> out("? ${r.site.name.padEnd(13)} не ответил (${r.code})")
                UsernameSearch.Verdict.NOT_FOUND -> Unit
            }
        }
        out("найден на $found сайтах")
    }

    suspend fun leak(raw: String, out: suspend (String) -> Unit) {
        if (!Breaches.validEmail(raw)) return out("leak: это не похоже на почту")
        val results = OsintNet.leaks(raw)
        if (results.isEmpty()) return out("сервисы не ответили, попробуй позже")
        results.forEach { r ->
            if (!r.found) out("✓ ${r.service}: в утечках не найдена")
            else {
                out("⚠ ${r.service}: найдена в ${r.sources.size} утечках")
                if (r.fields.isNotEmpty()) out("  утекло: ${r.fields.joinToString()}")
                r.sources.take(25).forEach { out("  · $it") }
            }
        }
    }

    suspend fun upnp(ctx: Context, out: suspend (String) -> Unit) {
        out("ищу роутер по UPnP…")
        val r = Discover.routerAudit(ctx)
        r.device?.let { out("роутер: ${it.title}") }
        r.error?.let { out(it) }
        r.externalIp?.let { out("внешний IP: $it" + if (Discover.isCarrierNat(it)) " (серый — снаружи к тебе не попасть)" else "") }
        if (r.error == null) out("проброшено портов: ${r.mappings.size}")
        r.mappings.forEach { m ->
            out("  ${m.external}/${m.protocol} → ${m.internalClient}:${m.internalPort}  ${m.description}")
            Subnet.ADVICE[m.internalPort]?.let { out("    ⚠ $it") }
        }
    }

    suspend fun wol(ctx: Context, mac: String, out: suspend (String) -> Unit) {
        val bytes = WakeOnLan.parseMac(mac) ?: return out("wol: MAC в виде AA:BB:CC:DD:EE:FF")
        val n = Discover.wake(bytes, NetInfo.local(ctx))
        out(if (n > 0) "волшебный пакет → ${WakeOnLan.formatMac(bytes)} (отправлено $n)" else "не удалось отправить")
    }
}
