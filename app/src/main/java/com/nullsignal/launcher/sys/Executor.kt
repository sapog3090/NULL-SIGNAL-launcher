package com.nullsignal.launcher.sys

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.nullsignal.core.terminal.Action
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Настоящие команды терминала. Всё работает от имени приложения,
 * без root: то, что разрешено обычному приложению Android.
 */
class Executor(private val ctx: Context) {

    @Volatile
    private var process: Process? = null

    /** ^C: убить запущенный процесс. */
    fun interrupt() {
        process?.destroy()
    }

    suspend fun run(cmd: Action.Exec, out: suspend (String) -> Unit) {
        try {
            when (cmd.command) {
                "ping" -> ping(cmd.args[0], out)
                "nslookup" -> nslookup(cmd.args[0], out)
                "curl" -> curl(cmd.args[0], out)
                "nc" -> nc(cmd.args, out)
                "sh" -> shell(cmd.rawArgs, out)
                "sysinfo" -> sysinfo(out)
                else -> out("${cmd.command}: не реализовано")
            }
        } catch (e: Exception) {
            out("ошибка: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    private fun safeHost(h: String): Boolean = h.matches(Regex("[A-Za-z0-9.:\\-]{1,253}"))

    private suspend fun ping(host: String, out: suspend (String) -> Unit) {
        if (!safeHost(host)) return out("ping: неверный хост")
        process(listOf("ping", "-c", "4", "-W", "2", host), timeoutSec = 20, out = out)
    }

    private suspend fun shell(command: String, out: suspend (String) -> Unit) =
        process(listOf("sh", "-c", command), timeoutSec = 20, out = out)

    private suspend fun process(argv: List<String>, timeoutSec: Long, out: suspend (String) -> Unit) {
        val p = withContext(Dispatchers.IO) {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        }
        process = p
        try {
            val reader = p.inputStream.bufferedReader()
            var lines = 0
            val started = SystemClock.elapsedRealtime()
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (++lines <= 400) out(line) else if (lines == 401) out("… вывод обрезан")
                if (SystemClock.elapsedRealtime() - started > timeoutSec * 1000) {
                    out("… таймаут ${timeoutSec}с"); break
                }
            }
            val finished = withContext(Dispatchers.IO) { p.waitFor(2, TimeUnit.SECONDS) }
            if (finished) {
                val code = p.exitValue()
                if (code != 0) out("[код выхода $code]")
            }
        } finally {
            p.destroy()
            process = null
        }
    }

    private suspend fun nslookup(host: String, out: suspend (String) -> Unit) {
        if (!safeHost(host)) return out("nslookup: неверный домен")
        val t = SystemClock.elapsedRealtime()
        val addrs = withContext(Dispatchers.IO) { InetAddress.getAllByName(host) }
        out("имя: $host")
        addrs.forEach { out("адрес: ${it.hostAddress}") }
        out("(${SystemClock.elapsedRealtime() - t} мс)")
    }

    private suspend fun curl(raw: String, out: suspend (String) -> Unit) {
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val lines = withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.setRequestProperty("User-Agent", "nullsignal-shell/0.1")
            val t = SystemClock.elapsedRealtime()
            try {
                val code = c.responseCode
                val res = mutableListOf("HTTP $code ${c.responseMessage ?: ""}  (${SystemClock.elapsedRealtime() - t} мс)")
                c.headerFields.entries.filter { it.key != null }.take(25).forEach { (k, v) ->
                    res += "$k: ${v.joinToString(", ")}".take(160)
                }
                res
            } finally {
                c.disconnect()
            }
        }
        lines.forEach { out(it) }
    }

    private suspend fun nc(args: List<String>, out: suspend (String) -> Unit) {
        val host = args.getOrNull(0) ?: return out("использование: nc <хост> <порт>")
        val port = args.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return out("использование: nc <хост> <порт>")
        if (!safeHost(host)) return out("nc: неверный хост")
        val t = SystemClock.elapsedRealtime()
        val ok = withContext(Dispatchers.IO) {
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 3000) } }
        }
        val ms = SystemClock.elapsedRealtime() - t
        if (ok.isSuccess) out("$host:$port ОТКРЫТ ($ms мс)")
        else out("$host:$port закрыт/недоступен: ${ok.exceptionOrNull()?.message ?: ""}")
    }

    private suspend fun sysinfo(out: suspend (String) -> Unit) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val stat = StatFs(Environment.getDataDirectory().path)
        val upMin = SystemClock.elapsedRealtime() / 60000
        val gb = 1024.0 * 1024 * 1024
        listOf(
            "устройство : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "android    : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "патч       : ${Build.VERSION.SECURITY_PATCH}",
            "ядро       : ${System.getProperty("os.version")}",
            "cpu        : ${Runtime.getRuntime().availableProcessors()} ядер, ${Build.SUPPORTED_ABIS.joinToString()}",
            "soc        : ${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE}",
            "ram        : %.1f / %.1f ГБ свободно".format(mem.availMem / gb, mem.totalMem / gb),
            "диск       : %.1f / %.1f ГБ свободно".format(stat.availableBytes / gb, stat.totalBytes / gb),
            "батарея    : ${if (level >= 0) level * 100 / scale else "?"}%, $temp°C",
            "аптайм     : ${upMin / 60} ч ${upMin % 60} мин",
        ).forEach { out(it) }
    }
}
