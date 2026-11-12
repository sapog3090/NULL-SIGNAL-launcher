package com.nullsignal.launcher.sys

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nullsignal.core.apps.DexScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Результаты «рентгена» приложений (живут, пока жив процесс). */
object Xray {
    data class AppScan(
        val pkg: String,
        val label: String,
        val system: Boolean,
        val trackers: List<DexScan.Tracker>,
        val dexFiles: Int,
        val sizeMb: Double,
        val error: String? = null,
    )

    val results = mutableStateMapOf<String, AppScan>()
    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var current by mutableStateOf("")
        private set
    var total by mutableStateOf(0)
        private set

    private fun scanOne(ctx: Context, ai: ApplicationInfo): AppScan {
        val pm = ctx.packageManager
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName)
        val system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val files = (listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).map { File(it) }.filter { it.exists() }
        return try {
            val found = linkedSetOf<DexScan.Tracker>()
            var dex = 0
            files.forEach { f ->
                val r = DexScan.scanApk(f)
                found += r.trackers
                dex += r.dexFiles
            }
            AppScan(ai.packageName, label, system, found.sortedBy { it.kind.ordinal }, dex, files.sumOf { it.length() } / 1048576.0)
        } catch (e: Throwable) {
            AppScan(ai.packageName, label, system, emptyList(), 0, 0.0, e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun scanAll(ctx: Context, includeSystem: Boolean) {
        if (busy) return
        busy = true
        progress = 0f
        try {
            val apps = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                ctx.packageManager.getInstalledApplications(0)
                    .filter { it.packageName != ctx.packageName }
                    .filter { includeSystem || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
            }
            total = apps.size
            results.clear()
            var done = 0
            val gate = Semaphore(2) // DEX бывают по 10+ МБ — не больше двух одновременно
            coroutineScope {
                apps.map { ai ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val r = scanOne(ctx, ai)
                            withContext(Dispatchers.Main) {
                                results[r.pkg] = r
                                done++
                                progress = done.toFloat() / apps.size
                                current = r.label
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            busy = false
            current = ""
            progress = 1f
        }
    }

    fun explain(kind: DexScan.Kind): String = when (kind) {
        DexScan.Kind.ADS -> "показывает рекламу и собирает данные для её подбора"
        DexScan.Kind.ANALYTICS -> "сообщает разработчику, что и когда ты делаешь в приложении"
        DexScan.Kind.CRASH -> "отчёты о сбоях — обычно безобидно"
        DexScan.Kind.LOCATION -> "собирает геолокацию и продаёт её брокерам данных"
        DexScan.Kind.PROFILING -> "связывает твои действия между приложениями и рекламой"
        DexScan.Kind.SOCIAL -> "передаёт данные соцсети, даже если не входишь через неё"
        DexScan.Kind.RECORDING -> "записывает экран и нажатия — разработчик смотрит твои действия как видео"
    }
}
