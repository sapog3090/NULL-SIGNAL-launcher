package com.nullsignal.core.apps

import java.io.File
import java.util.zip.ZipFile

/**
 * «Рентген» приложений: ищет код известных трекеров прямо внутри APK.
 * Читает таблицу строк каждого classes*.dex и сравнивает имена классов
 * с сигнатурами SDK слежки (по методике Exodus Privacy). Приложение при этом не запускается.
 */
object DexScan {

    enum class Kind(val title: String) {
        ADS("реклама"), ANALYTICS("аналитика"), CRASH("отчёты о сбоях"),
        LOCATION("продажа геолокации"), PROFILING("профилирование"), SOCIAL("соцсети"), RECORDING("запись экрана"),
    }

    data class Tracker(val name: String, val kind: Kind, val packages: List<String>)

    val TRACKERS: List<Tracker> = listOf(
        Tracker("Google Firebase Analytics", Kind.ANALYTICS, listOf("com.google.firebase.analytics")),
        Tracker("Google Analytics", Kind.ANALYTICS, listOf("com.google.android.gms.analytics")),
        Tracker("Google AdMob", Kind.ADS, listOf("com.google.android.gms.ads")),
        Tracker("Google Tag Manager", Kind.ANALYTICS, listOf("com.google.android.gms.tagmanager")),
        Tracker("Google Crashlytics", Kind.CRASH, listOf("com.crashlytics", "com.google.firebase.crashlytics")),
        Tracker("Facebook Analytics", Kind.ANALYTICS, listOf("com.facebook.appevents")),
        Tracker("Facebook Ads", Kind.ADS, listOf("com.facebook.ads")),
        Tracker("Facebook Login/Share", Kind.SOCIAL, listOf("com.facebook.login", "com.facebook.share")),
        Tracker("Яндекс AppMetrica", Kind.ANALYTICS, listOf("com.yandex.metrica", "io.appmetrica.analytics")),
        Tracker("Яндекс Реклама", Kind.ADS, listOf("com.yandex.mobile.ads")),
        Tracker("VK myTracker", Kind.ANALYTICS, listOf("com.my.tracker")),
        Tracker("VK myTarget", Kind.ADS, listOf("com.my.target")),
        Tracker("AppsFlyer", Kind.PROFILING, listOf("com.appsflyer")),
        Tracker("Adjust", Kind.PROFILING, listOf("com.adjust.sdk")),
        Tracker("Branch", Kind.PROFILING, listOf("io.branch")),
        Tracker("Kochava", Kind.PROFILING, listOf("com.kochava")),
        Tracker("Singular", Kind.PROFILING, listOf("com.singular.sdk")),
        Tracker("Tenjin", Kind.PROFILING, listOf("com.tenjin")),
        Tracker("Mixpanel", Kind.ANALYTICS, listOf("com.mixpanel")),
        Tracker("Amplitude", Kind.ANALYTICS, listOf("com.amplitude")),
        Tracker("Segment", Kind.ANALYTICS, listOf("com.segment.analytics")),
        Tracker("Flurry", Kind.ANALYTICS, listOf("com.flurry")),
        Tracker("Adobe Analytics", Kind.ANALYTICS, listOf("com.adobe.mobile", "com.adobe.marketing.mobile")),
        Tracker("Countly", Kind.ANALYTICS, listOf("ly.count.android")),
        Tracker("Heap", Kind.ANALYTICS, listOf("com.heapanalytics")),
        Tracker("comScore", Kind.ANALYTICS, listOf("com.comscore")),
        Tracker("Huawei Analytics", Kind.ANALYTICS, listOf("com.huawei.hms.analytics")),
        Tracker("Huawei Ads", Kind.ADS, listOf("com.huawei.hms.ads")),
        Tracker("Xiaomi Analytics", Kind.ANALYTICS, listOf("com.xiaomi.analytics", "com.miui.analytics")),
        Tracker("AppLovin", Kind.ADS, listOf("com.applovin")),
        Tracker("Unity Ads", Kind.ADS, listOf("com.unity3d.ads", "com.unity3d.services")),
        Tracker("ironSource", Kind.ADS, listOf("com.ironsource")),
        Tracker("Vungle", Kind.ADS, listOf("com.vungle")),
        Tracker("Chartboost", Kind.ADS, listOf("com.chartboost")),
        Tracker("InMobi", Kind.ADS, listOf("com.inmobi")),
        Tracker("Pangle (TikTok)", Kind.ADS, listOf("com.bytedance.sdk.openadsdk")),
        Tracker("Mintegral", Kind.ADS, listOf("com.mbridge.msdk")),
        Tracker("AdColony", Kind.ADS, listOf("com.adcolony")),
        Tracker("Tapjoy", Kind.ADS, listOf("com.tapjoy")),
        Tracker("Fyber", Kind.ADS, listOf("com.fyber")),
        Tracker("Smaato", Kind.ADS, listOf("com.smaato")),
        Tracker("StartApp", Kind.ADS, listOf("com.startapp")),
        Tracker("Criteo", Kind.ADS, listOf("com.criteo")),
        Tracker("Taboola", Kind.ADS, listOf("com.taboola")),
        Tracker("Outbrain", Kind.ADS, listOf("com.outbrain")),
        Tracker("Moat", Kind.ADS, listOf("com.moat.analytics")),
        Tracker("Integral Ad Science", Kind.ADS, listOf("com.integralads")),
        Tracker("OneSignal", Kind.PROFILING, listOf("com.onesignal")),
        Tracker("Braze", Kind.PROFILING, listOf("com.appboy", "com.braze")),
        Tracker("Sentry", Kind.CRASH, listOf("io.sentry")),
        Tracker("Bugsnag", Kind.CRASH, listOf("com.bugsnag")),
        Tracker("Instabug", Kind.CRASH, listOf("com.instabug")),
        Tracker("Microsoft App Center", Kind.CRASH, listOf("com.microsoft.appcenter")),
        Tracker("New Relic", Kind.CRASH, listOf("com.newrelic.agent")),
        Tracker("AppDynamics", Kind.CRASH, listOf("com.appdynamics")),
        Tracker("UXCam", Kind.RECORDING, listOf("com.uxcam")),
        Tracker("Smartlook", Kind.RECORDING, listOf("com.smartlook")),
        Tracker("Huq", Kind.LOCATION, listOf("io.huq")),
        Tracker("X-Mode / Outlogic", Kind.LOCATION, listOf("io.mysdk", "com.xmode")),
        Tracker("Cuebiq", Kind.LOCATION, listOf("com.cuebiq")),
        Tracker("Foursquare Pilgrim", Kind.LOCATION, listOf("com.foursquare.pilgrim")),
        Tracker("Teemo", Kind.LOCATION, listOf("com.databerries")),
        Tracker("Tutela", Kind.LOCATION, listOf("com.tutelatechnologies")),
        Tracker("Unacast", Kind.LOCATION, listOf("com.unacast")),
        Tracker("Pollfish", Kind.PROFILING, listOf("com.pollfish")),
    )

    /** "com.appsflyer" → "Lcom/appsflyer/" : трекер */
    private val PREFIXES: Map<String, Tracker> = buildMap {
        TRACKERS.forEach { t -> t.packages.forEach { put("L" + it.replace('.', '/') + "/", t) } }
    }
    private val MAX_DEPTH = PREFIXES.keys.maxOf { k -> k.count { it == '/' } }

    private fun u32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    /** Проверяет один DEX и добавляет найденные трекеры в [found]. Возвращает число строк. */
    fun scanDex(dex: ByteArray, found: MutableSet<Tracker>): Int {
        if (dex.size < 0x70 || dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte()) return 0
        val count = u32(dex, 0x38)
        val idsOff = u32(dex, 0x3C)
        if (count <= 0 || idsOff <= 0 || idsOff.toLong() + count * 4L > dex.size) return 0
        for (i in 0 until count) {
            var p = u32(dex, idsOff + i * 4)
            if (p <= 0 || p >= dex.size) continue
            // uleb128: длина в UTF-16 единицах — пропускаем
            while (p < dex.size && dex[p].toInt() and 0x80 != 0) p++
            p++
            if (p >= dex.size || dex[p] != 'L'.code.toByte()) continue
            // Имя класса: L com / appsflyer / ... ; Берём префиксы глубиной 2..MAX_DEPTH
            var slashes = 0
            var end = p
            while (end < dex.size && dex[end] != 0.toByte() && dex[end] != ';'.code.toByte()) {
                if (dex[end] == '/'.code.toByte()) {
                    slashes++
                    if (slashes >= 2) {
                        val prefix = String(dex, p, end - p + 1, Charsets.ISO_8859_1)
                        PREFIXES[prefix]?.let { found += it }
                    }
                    if (slashes >= MAX_DEPTH) break
                }
                end++
            }
        }
        return count
    }

    data class Report(val trackers: Set<Tracker>, val dexFiles: Int, val strings: Int)

    /** Сканирует все classes*.dex внутри APK. */
    fun scanApk(apk: File): Report {
        val found = mutableSetOf<Tracker>()
        var dexes = 0
        var strings = 0
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.name.matches(Regex("classes\\d*\\.dex"))) continue
                if (e.size > 64L * 1024 * 1024) continue
                val bytes = zip.getInputStream(e).use { it.readBytes() }
                strings += scanDex(bytes, found)
                dexes++
            }
        }
        return Report(found, dexes, strings)
    }
}
