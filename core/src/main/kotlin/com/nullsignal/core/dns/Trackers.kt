package com.nullsignal.core.dns

/** Известные домены рекламы, аналитики и слежки. Совпадение — по самому домену и всем поддоменам. */
object Trackers {
    private val DOMAINS: Map<String, String> = mapOf(
        // Google
        "doubleclick.net" to "Google Ads", "googleadservices.com" to "Google Ads", "googlesyndication.com" to "Google Ads",
        "google-analytics.com" to "Google Analytics", "app-measurement.com" to "Firebase Analytics",
        "firebase-settings.crashlytics.com" to "Crashlytics", "crashlytics.com" to "Crashlytics", "admob.com" to "AdMob",
        // Meta
        "graph.facebook.com" to "Facebook SDK", "an.facebook.com" to "Facebook Audience",
        // Яндекс / Mail.ru / VK
        "mc.yandex.ru" to "Яндекс.Метрика", "appmetrica.yandex.net" to "AppMetrica", "an.yandex.ru" to "Яндекс.Реклама",
        "adfox.ru" to "AdFox", "ads.adfox.ru" to "AdFox", "ad.mail.ru" to "VK Реклама", "top-fwz1.mail.ru" to "Рейтинг Mail.ru",
        "tracker-api.my.com" to "myTracker", "ad.mail.ru" to "myTarget",
        // мобильная атрибуция
        "appsflyer.com" to "AppsFlyer", "appsflyersdk.com" to "AppsFlyer", "adjust.com" to "Adjust", "adjust.io" to "Adjust",
        "branch.io" to "Branch", "kochava.com" to "Kochava", "singular.net" to "Singular",
        // рекламные сети
        "applovin.com" to "AppLovin", "applvn.com" to "AppLovin", "unityads.unity3d.com" to "Unity Ads",
        "supersonicads.com" to "ironSource", "is.com" to "ironSource", "vungle.com" to "Vungle", "chartboost.com" to "Chartboost",
        "mopub.com" to "MoPub", "inmobi.com" to "InMobi", "pangle.io" to "Pangle (TikTok)", "byteoversea.com" to "ByteDance",
        "bytedance.com" to "ByteDance", "tiktokv.com" to "TikTok", "ads-api.tiktok.com" to "TikTok Ads",
        // аналитика
        "amplitude.com" to "Amplitude", "mixpanel.com" to "Mixpanel", "segment.io" to "Segment", "segment.com" to "Segment",
        "onesignal.com" to "OneSignal", "braze.com" to "Braze", "sentry.io" to "Sentry", "bugsnag.com" to "Bugsnag",
        "hotjar.com" to "Hotjar", "scorecardresearch.com" to "comScore", "criteo.com" to "Criteo", "taboola.com" to "Taboola",
        "outbrain.com" to "Outbrain", "xiaomi.com" to "", "tracking.miui.com" to "Xiaomi аналитика",
        "data.mistat.xiaomi.com" to "Xiaomi аналитика", "samsungads.com" to "Samsung Ads", "metrics.samsung.com" to "Samsung",
    ).filterValues { it.isNotEmpty() }

    /** Название трекера или null. */
    fun match(domain: String): String? {
        var d = domain.lowercase().trimEnd('.')
        while (true) {
            DOMAINS[d]?.let { return it }
            val dot = d.indexOf('.')
            if (dot < 0) return null
            d = d.substring(dot + 1)
        }
    }

    val size get() = DOMAINS.size
}
