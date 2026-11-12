package com.nullsignal.core.osint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

internal fun parseJson(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/**
 * Поиск ника по сайтам (открытые данные). Для каждого сайта — проверенный способ
 * отличить «профиль есть» от «нет»: через API или признак на странице.
 */
object UsernameSearch {
    enum class Verdict { FOUND, NOT_FOUND, UNKNOWN }

    sealed interface Rule {
        /** 2xx — есть, 404/410/204 — нет. */
        data object Status : Rule
        /** Ответ содержит маркер — есть. */
        data class Contains(val marker: String) : Rule
        /** Ответ 2xx и не содержит ни одного маркера — есть. */
        data class Lacks(val markers: List<String>) : Rule
    }

    data class Site(val name: String, val api: String, val profile: String, val rule: Rule, val postBody: String? = null)

    val SITES: List<Site> = listOf(
        Site("GitHub", "https://api.github.com/users/{u}", "https://github.com/{u}", Rule.Status),
        Site("GitLab", "https://gitlab.com/api/v4/users?username={u}", "https://gitlab.com/{u}", Rule.Contains("\"id\"")),
        Site("Reddit", "https://www.reddit.com/user/{u}/about.json", "https://www.reddit.com/user/{u}", Rule.Status),
        Site("YouTube", "https://www.youtube.com/@{u}", "https://www.youtube.com/@{u}", Rule.Status),
        Site("Telegram", "https://t.me/{u}", "https://t.me/{u}", Rule.Lacks(listOf("you can contact <a class=\"tgme_username_link\"", "<title>Telegram Messenger</title>"))),
        Site("Steam", "https://steamcommunity.com/id/{u}?xml=1", "https://steamcommunity.com/id/{u}", Rule.Contains("<steamID64>")),
        Site("Roblox", "https://users.roblox.com/v1/usernames/users", "https://www.roblox.com/search/users?keyword={u}", Rule.Contains("\"id\""),
            postBody = "{\"usernames\":[\"{u}\"],\"excludeBannedUsers\":false}"),
        Site("Minecraft", "https://api.mojang.com/users/profiles/minecraft/{u}", "https://namemc.com/profile/{u}", Rule.Contains("\"id\"")),
        Site("Chess.com", "https://api.chess.com/pub/player/{u}", "https://www.chess.com/member/{u}", Rule.Status),
        Site("Lichess", "https://lichess.org/api/user/{u}", "https://lichess.org/@/{u}", Rule.Status),
        Site("osu!", "https://osu.ppy.sh/users/{u}", "https://osu.ppy.sh/users/{u}", Rule.Status),
        Site("Speedrun.com", "https://www.speedrun.com/api/v1/users/{u}", "https://www.speedrun.com/users/{u}", Rule.Status),
        Site("Duolingo", "https://www.duolingo.com/2017-06-30/users?username={u}", "https://www.duolingo.com/profile/{u}", Rule.Contains("\"username\"")),
        Site("Codeforces", "https://codeforces.com/api/user.info?handles={u}", "https://codeforces.com/profile/{u}", Rule.Contains("\"status\":\"OK\"")),
        Site("Docker Hub", "https://hub.docker.com/v2/users/{u}/", "https://hub.docker.com/u/{u}", Rule.Status),
        Site("npm", "https://registry.npmjs.org/-/user/org.couchdb.user:{u}", "https://www.npmjs.com/~{u}", Rule.Status),
        Site("PyPI", "https://pypi.org/user/{u}/", "https://pypi.org/user/{u}/", Rule.Status),
        Site("Keybase", "https://keybase.io/_/api/1.0/user/lookup.json?usernames={u}", "https://keybase.io/{u}", Rule.Contains("\"basics\"")),
        Site("Hacker News", "https://hacker-news.firebaseio.com/v0/user/{u}.json", "https://news.ycombinator.com/user?id={u}", Rule.Contains("\"id\"")),
        Site("Wikipedia", "https://en.wikipedia.org/w/api.php?action=query&list=users&ususers={u}&format=json", "https://en.wikipedia.org/wiki/User:{u}", Rule.Contains("\"userid\"")),
        Site("Habr", "https://habr.com/ru/users/{u}/", "https://habr.com/ru/users/{u}/", Rule.Status),
        Site("Medium", "https://medium.com/@{u}", "https://medium.com/@{u}", Rule.Status),
        Site("SoundCloud", "https://soundcloud.com/{u}", "https://soundcloud.com/{u}", Rule.Status),
        Site("DeviantArt", "https://www.deviantart.com/{u}", "https://www.deviantart.com/{u}", Rule.Status),
        Site("Dribbble", "https://dribbble.com/{u}", "https://dribbble.com/{u}", Rule.Status),
        Site("Gravatar", "https://en.gravatar.com/{u}.json", "https://gravatar.com/{u}", Rule.Status),
        Site("Linktree", "https://linktr.ee/{u}", "https://linktr.ee/{u}", Rule.Status),
        Site("Letterboxd", "https://letterboxd.com/{u}/", "https://letterboxd.com/{u}/", Rule.Status),
        Site("Pastebin", "https://pastebin.com/u/{u}", "https://pastebin.com/u/{u}", Rule.Status),
    )

    fun valid(u: String): Boolean = Regex("[A-Za-z0-9._-]{2,40}").matches(u)

    fun fill(template: String, u: String, encode: Boolean = true): String =
        template.replace("{u}", if (encode) URLEncoder.encode(u, "UTF-8") else u)

    fun judge(site: Site, code: Int, body: String): Verdict = when (val r = site.rule) {
        Rule.Status -> when (code) {
            204, 404, 410 -> Verdict.NOT_FOUND
            in 200..299 -> Verdict.FOUND
            else -> Verdict.UNKNOWN
        }
        is Rule.Contains -> when {
            code in 200..299 && r.marker in body -> Verdict.FOUND
            code in 200..299 || code in 400..404 || code == 410 -> Verdict.NOT_FOUND
            else -> Verdict.UNKNOWN
        }
        is Rule.Lacks -> when {
            code == 404 -> Verdict.NOT_FOUND
            code in 200..299 -> if (r.markers.none { it in body }) Verdict.FOUND else Verdict.NOT_FOUND
            else -> Verdict.UNKNOWN
        }
    }
}

/** Разведка по IP: где находится, чей, что открыто (публичные данные). */
object IpIntel {
    data class Geo(
        val ip: String, val country: String?, val countryCode: String?, val region: String?, val city: String?,
        val lat: Double?, val lon: Double?, val isp: String?, val org: String?, val asn: String?, val timezone: String?, val type: String?,
    )

    /** Ответ https://ipwho.is/<ip> */
    fun parseIpWho(text: String): Geo? {
        val j = parseJson(text) ?: return null
        if (j["success"].str() == "false") return null
        val ip = j["ip"].str() ?: return null
        val c = j["connection"]
        return Geo(
            ip = ip, country = j["country"].str(), countryCode = j["country_code"].str(), region = j["region"].str(),
            city = j["city"].str(), lat = j["latitude"].str()?.toDoubleOrNull(), lon = j["longitude"].str()?.toDoubleOrNull(),
            isp = c["isp"].str(), org = c["org"].str(), asn = c["asn"].str()?.let { "AS$it" },
            timezone = j["timezone"]["id"].str(), type = j["type"].str(),
        )
    }

    fun ipwhoError(text: String): String? = parseJson(text)?.let { if (it["success"].str() == "false") it["message"].str() else null }

    data class Exposure(val ports: List<Int>, val hostnames: List<String>, val vulns: List<String>, val tags: List<String>, val cpes: List<String>)

    /** Ответ https://internetdb.shodan.io/<ip>: что интернет-сканеры видят на этом адресе. */
    fun parseInternetDb(text: String): Exposure? {
        val j = parseJson(text) ?: return null
        if (j["detail"] != null) return null
        fun list(k: String) = j[k].arr()?.mapNotNull { it.str() }.orEmpty()
        return Exposure(list("ports").mapNotNull { it.toIntOrNull() }.sorted(), list("hostnames"), list("vulns").sorted(), list("tags"), list("cpes"))
    }
}

/** Разведка по домену: поддомены, архив, технологии, защита почты. */
object DomainIntel {
    fun validDomain(d: String): Boolean = Regex("(?i)([a-z0-9-]+\\.)+[a-z]{2,}|(?i)([a-z0-9-]+\\.)+xn--[a-z0-9-]+").matches(d)

    fun clean(input: String): String = input.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').removePrefix("www.")

    /** Поддомены из журналов прозрачности сертификатов (crt.sh). */
    fun parseCrtSh(text: String, domain: String): List<String> {
        val arr = parseJson(text).arr() ?: return emptyList()
        val d = domain.lowercase()
        return arr.flatMap { e ->
            (e["name_value"].str().orEmpty().split('\n') + listOfNotNull(e["common_name"].str()))
        }.map { it.trim().lowercase().removePrefix("*.") }
            .filter { it == d || it.endsWith(".$d") }
            .distinct().sorted()
    }

    data class Snapshot(val url: String, val timestamp: String)

    fun parseWaybackClosest(text: String): Snapshot? {
        val c = parseJson(text)["archived_snapshots"]["closest"] ?: return null
        val url = c["url"].str() ?: return null
        return Snapshot(url, c["timestamp"].str() ?: "")
    }

    /** CDX: [["timestamp","original"],["19990125…","http://…"]] */
    fun parseCdxFirst(text: String): Snapshot? {
        val rows = parseJson(text).arr() ?: return null
        val row = rows.getOrNull(1).arr() ?: return null
        val ts = row.getOrNull(0).str() ?: return null
        return Snapshot("https://web.archive.org/web/$ts/" + (row.getOrNull(1).str() ?: ""), ts)
    }

    fun formatTimestamp(ts: String): String =
        if (ts.length >= 8) "${ts.substring(6, 8)}.${ts.substring(4, 6)}.${ts.substring(0, 4)}" else ts
}

/** Технологии сайта по заголовкам и HTML — как Wappalyzer, без внешних сервисов. */
object TechDetect {
    data class Tech(val name: String, val category: String)

    private class Sig(val name: String, val category: String, val header: Pair<String, String>? = null, val html: String? = null, val cookie: String? = null)

    private val SIGS = listOf(
        Sig("nginx", "веб-сервер", header = "server" to "nginx"),
        Sig("Apache", "веб-сервер", header = "server" to "apache"),
        Sig("LiteSpeed", "веб-сервер", header = "server" to "litespeed"),
        Sig("Microsoft IIS", "веб-сервер", header = "server" to "microsoft-iis"),
        Sig("OpenResty", "веб-сервер", header = "server" to "openresty"),
        Sig("Caddy", "веб-сервер", header = "server" to "caddy"),
        Sig("Cloudflare", "CDN / защита", header = "server" to "cloudflare"),
        Sig("Cloudflare", "CDN / защита", header = "cf-ray" to ""),
        Sig("DDoS-Guard", "CDN / защита", header = "server" to "ddos-guard"),
        Sig("Qrator", "CDN / защита", header = "server" to "qrator"),
        Sig("Vercel", "хостинг", header = "x-vercel-id" to ""),
        Sig("Netlify", "хостинг", header = "x-nf-request-id" to ""),
        Sig("GitHub Pages", "хостинг", header = "x-github-request-id" to ""),
        Sig("AWS CloudFront", "CDN / защита", header = "x-amz-cf-id" to ""),
        Sig("Fastly", "CDN / защита", header = "x-served-by" to "cache-"),
        Sig("PHP", "язык", header = "x-powered-by" to "php"),
        Sig("ASP.NET", "язык", header = "x-powered-by" to "asp.net"),
        Sig("Express (Node.js)", "фреймворк", header = "x-powered-by" to "express"),
        Sig("Next.js", "фреймворк", header = "x-powered-by" to "next.js"),
        Sig("PHP", "язык", cookie = "phpsessid"),
        Sig("Laravel", "фреймворк", cookie = "laravel_session"),
        Sig("Django", "фреймворк", cookie = "csrftoken"),
        Sig("Java", "язык", cookie = "jsessionid"),
        Sig("ASP.NET", "язык", cookie = "asp.net_sessionid"),
        Sig("1С-Битрикс", "CMS", cookie = "bitrix_sm"),
        Sig("1С-Битрикс", "CMS", html = "/bitrix/"),
        Sig("WordPress", "CMS", html = "wp-content/"),
        Sig("WordPress", "CMS", cookie = "wordpress_"),
        Sig("Joomla", "CMS", html = "/media/jui/"),
        Sig("Drupal", "CMS", html = "drupal-settings-json"),
        Sig("OpenCart", "CMS", html = "catalog/view/theme"),
        Sig("MODX", "CMS", html = "modx"),
        Sig("Tilda", "конструктор", html = "tildacdn"),
        Sig("Wix", "конструктор", html = "static.wixstatic.com"),
        Sig("Shopify", "магазин", html = "cdn.shopify.com"),
        Sig("Squarespace", "конструктор", html = "squarespace.com"),
        Sig("Webflow", "конструктор", html = "webflow.com"),
        Sig("Ghost", "CMS", html = "content=\"ghost"),
        Sig("Next.js", "фреймворк", html = "__next_data__"),
        Sig("Nuxt", "фреймворк", html = "__nuxt__"),
        Sig("Gatsby", "фреймворк", html = "___gatsby"),
        Sig("React", "JS-библиотека", html = "data-reactroot"),
        Sig("Angular", "фреймворк", html = "ng-version="),
        Sig("Vue.js", "фреймворк", html = "data-v-app"),
        Sig("jQuery", "JS-библиотека", html = "jquery"),
        Sig("Bootstrap", "CSS", html = "bootstrap.min.css"),
        Sig("Tailwind CSS", "CSS", html = "tailwind"),
        Sig("Google Analytics", "слежка", html = "googletagmanager.com/gtag/js"),
        Sig("Google Analytics", "слежка", html = "google-analytics.com"),
        Sig("Google Tag Manager", "слежка", html = "googletagmanager.com/gtm.js"),
        Sig("Яндекс.Метрика", "слежка", html = "mc.yandex.ru"),
        Sig("Facebook Pixel", "слежка", html = "connect.facebook.net"),
        Sig("VK Pixel", "слежка", html = "vk.com/js/api/openapi"),
        Sig("Top.Mail.Ru", "слежка", html = "top-fwz1.mail.ru"),
        Sig("Hotjar", "слежка", html = "static.hotjar.com"),
        Sig("Google reCAPTCHA", "защита", html = "recaptcha"),
        Sig("hCaptcha", "защита", html = "hcaptcha.com"),
        Sig("Яндекс SmartCaptcha", "защита", html = "smartcaptcha"),
        Sig("Google Fonts", "шрифты", html = "fonts.googleapis.com"),
    )

    fun detect(headers: Map<String, String>, html: String): List<Tech> {
        val h = headers.entries.associate { it.key.lowercase() to it.value.lowercase() }
        val cookies = h["set-cookie"].orEmpty()
        val body = html.lowercase()
        val found = linkedSetOf<Tech>()
        for (s in SIGS) {
            val hit = when {
                s.header != null -> h[s.header.first]?.contains(s.header.second) == true
                s.cookie != null -> s.cookie in cookies
                s.html != null -> s.html in body
                else -> false
            }
            if (hit) found += Tech(s.name, s.category)
        }
        Regex("<meta[^>]+name=[\"']generator[\"'][^>]+content=[\"']([^\"']+)", RegexOption.IGNORE_CASE).find(html)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { g ->
                if (found.none { g.lowercase().startsWith(it.name.lowercase()) }) found += Tech(g.take(40), "генератор")
            }
        return found.toList().sortedBy { it.category }
    }
}

/** Можно ли подделать письмо от имени домена: SPF, DMARC, MX. */
object MailSecurity {
    data class Finding(val ok: Boolean, val text: String)

    fun analyze(mx: List<String>, txt: List<String>, dmarcTxt: List<String>): List<Finding> {
        val out = mutableListOf<Finding>()
        out += if (mx.isEmpty()) Finding(false, "нет MX — домен не принимает почту")
        else Finding(true, "почтовые серверы: ${mx.joinToString()}")
        val spf = txt.map { it.trim('"') }.filter { it.startsWith("v=spf1", ignoreCase = true) }
        out += when {
            spf.isEmpty() -> Finding(false, "нет SPF: любой сервер может слать письма от имени домена")
            spf.size > 1 -> Finding(false, "несколько SPF-записей — ошибка, проверка сломана")
            spf[0].trimEnd().endsWith("-all") -> Finding(true, "SPF строгий (-all): чужие серверы отклоняются")
            spf[0].trimEnd().endsWith("~all") -> Finding(true, "SPF мягкий (~all): чужие письма помечаются, но доходят")
            else -> Finding(false, "SPF разрешает всем (${spf[0].takeLast(5)}) — защиты нет")
        }
        val dmarc = dmarcTxt.map { it.trim('"') }.firstOrNull { it.startsWith("v=DMARC1", ignoreCase = true) }
        val policy = dmarc?.let { Regex("(?i)\\bp=\\s*(\\w+)").find(it)?.groupValues?.get(1)?.lowercase() }
        out += when (policy) {
            null -> Finding(false, "нет DMARC: поддельные письма от этого домена никто не отклонит")
            "reject" -> Finding(true, "DMARC p=reject: подделки отклоняются")
            "quarantine" -> Finding(true, "DMARC p=quarantine: подделки уходят в спам")
            else -> Finding(false, "DMARC p=$policy: только наблюдение, подделки доходят")
        }
        return out
    }

    fun spoofable(f: List<Finding>): Boolean = f.drop(1).count { !it.ok } >= 1
}

/** Проверка почты по базам утечек (публичные бесплатные сервисы). */
object Breaches {
    data class Result(val service: String, val found: Boolean, val sources: List<String>, val fields: List<String> = emptyList())

    fun validEmail(e: String): Boolean = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matches(e.trim())

    /** XposedOrNot: {"breaches":[["Adobe","Canva"]]} или {"Error":"Not found"} */
    fun parseXposedOrNot(text: String): Result? {
        val j = parseJson(text) ?: return null
        if (j["Error"] != null) return Result("XposedOrNot", false, emptyList())
        val list = j["breaches"].arr()?.flatMap { inner -> inner.arr()?.mapNotNull { it.str() } ?: listOfNotNull(inner.str()) } ?: return null
        return Result("XposedOrNot", list.isNotEmpty(), list)
    }

    /** LeakCheck public: {"success":true,"found":2,"fields":[..],"sources":[{"name":"..","date":".."}]} */
    fun parseLeakCheck(text: String): Result? {
        val j = parseJson(text) ?: return null
        if (j["success"].str() == "false") {
            return if (j["error"].str()?.contains("not found", ignoreCase = true) == true) Result("LeakCheck", false, emptyList()) else null
        }
        val sources = j["sources"].arr()?.mapNotNull { s ->
            val name = s["name"].str() ?: return@mapNotNull null
            val date = s["date"].str().orEmpty()
            if (date.isNotEmpty()) "$name ($date)" else name
        }.orEmpty()
        val count = j["found"].str()?.toIntOrNull() ?: sources.size
        return Result("LeakCheck", count > 0, sources, j["fields"].arr()?.mapNotNull { it.str() }.orEmpty())
    }
}
