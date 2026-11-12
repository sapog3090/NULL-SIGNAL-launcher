package com.nullsignal.launcher.sys

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nullsignal.core.dns.Blocklist
import com.nullsignal.core.dns.BlocklistSources
import com.nullsignal.core.dns.Upstreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Правила перехватчика: скачанные блок-листы, свои правила блокировки, исключения,
 * выбор DNS-сервера. Читаются сервисом на каждом запросе, поэтому лежат в памяти.
 */
object DnsRules {
    private const val PREFS = "dns"

    @Volatile
    var lists: Blocklist = Blocklist()
        private set

    @Volatile
    var custom: Blocklist = Blocklist()
        private set

    @Volatile
    var allow: Blocklist = Blocklist()
        private set

    /** Сколько доменов в каждом скачанном списке (для экрана). */
    val listSizes = mutableStateMapOf<String, Int>()
    var totalListed by mutableIntStateOf(0)
    var customRules by mutableStateOf<List<String>>(emptyList())
    var allowRules by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, 0)
    private fun file(ctx: Context, id: String) = File(ctx.filesDir, "bl_$id.txt")

    fun enabled(ctx: Context): Set<String> = prefs(ctx).getStringSet("lists", emptySet())!!

    fun upstreamId(ctx: Context): String = prefs(ctx).getString("upstream", "cloudflare")!!
    fun setUpstream(ctx: Context, id: String) = prefs(ctx).edit().putString("upstream", id).apply()
    fun useDoh(ctx: Context): Boolean = prefs(ctx).getBoolean("doh", true)
    fun setDoh(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("doh", v).apply()
    fun upstream(ctx: Context) = Upstreams.byId(upstreamId(ctx))

    /** Пересобрать правила из файлов и настроек. Вызывать не в главном потоке. */
    fun rebuildBlocking(ctx: Context) {
        val on = enabled(ctx)
        val sizes = HashMap<String, Int>()
        val combined = Blocklist()
        BlocklistSources.ALL.forEach { s ->
            val f = file(ctx, s.id)
            if (f.exists()) {
                var n = 0
                if (s.id in on) f.forEachLine { if (combined.add(it)) n++ } else f.forEachLine { n++ }
                sizes[s.id] = n
            }
        }
        val c = prefs(ctx).getStringSet("custom", emptySet())!!.sorted()
        val a = prefs(ctx).getStringSet("allow", emptySet())!!.sorted()
        lists = combined
        custom = Blocklist(c)
        allow = Blocklist(a)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            listSizes.clear(); listSizes.putAll(sizes)
            totalListed = combined.size
            customRules = c
            allowRules = a
        }
    }

    suspend fun rebuild(ctx: Context) = withContext(Dispatchers.IO) { rebuildBlocking(ctx) }

    /** Скачать список: построчно, сразу в файл (списки бывают по 5+ МБ). */
    suspend fun download(ctx: Context, id: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val src = BlocklistSources.ALL.first { it.id == id }
            val c = URL(src.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "NullSignal/0.3")
            val tmp = File(ctx.filesDir, "bl_$id.tmp")
            var count = 0
            try {
                require(c.responseCode == 200) { "сервер ответил ${c.responseCode}" }
                val seen = HashSet<String>()
                tmp.bufferedWriter().use { w ->
                    c.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val d = Blocklist.normalize(line)
                            if (d != null && seen.add(d)) { w.write(d); w.newLine(); count++ }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            require(count > 0) { "список пустой" }
            tmp.renameTo(file(ctx, id))
            prefs(ctx).edit().putStringSet("lists", enabled(ctx) + id).apply()
            rebuildBlocking(ctx)
            count
        }
    }

    suspend fun setListEnabled(ctx: Context, id: String, on: Boolean) {
        prefs(ctx).edit().putStringSet("lists", if (on) enabled(ctx) + id else enabled(ctx) - id).apply()
        rebuild(ctx)
    }

    suspend fun deleteList(ctx: Context, id: String) {
        file(ctx, id).delete()
        setListEnabled(ctx, id, false)
    }

    private suspend fun editSet(ctx: Context, key: String, rule: String, add: Boolean): Boolean {
        val d = Blocklist.normalize(rule) ?: return false
        val cur = prefs(ctx).getStringSet(key, emptySet())!!
        prefs(ctx).edit().putStringSet(key, if (add) cur + d else cur - d).apply()
        rebuild(ctx)
        return true
    }

    suspend fun addCustom(ctx: Context, rule: String) = editSet(ctx, "custom", rule, true)
    suspend fun removeCustom(ctx: Context, rule: String) = editSet(ctx, "custom", rule, false)
    suspend fun addAllow(ctx: Context, rule: String) = editSet(ctx, "allow", rule, true)
    suspend fun removeAllow(ctx: Context, rule: String) = editSet(ctx, "allow", rule, false)
}
