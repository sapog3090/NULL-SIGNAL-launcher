package com.nullsignal.launcher.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.DocumentsContract
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nullsignal.core.photo.JpegForensics
import java.io.ByteArrayOutputStream

private data class ExifInfo(
    val lat: Double?,
    val lon: Double?,
    val tags: List<Pair<String, String>>,
    val preview: ImageBitmap?,
    val previewBmp: Bitmap? = null,
    val thumb: Bitmap? = null,
)

private class Forensics(
    val report: JpegForensics.Report?,
    val ela: ImageBitmap?,
    val alerts: List<Triple<Int, String, String>>,
)

/** Чёрный → кровь → кость: палитра лаунчера для тепловой карты. */
private fun heat(t: Float): Int {
    val r: Int
    val g: Int
    val b: Int
    if (t < 0.5f) {
        val k = t / 0.5f
        r = (229 * k).toInt(); g = (17 * k).toInt(); b = (46 * k).toInt()
    } else {
        val k = (t - 0.5f) / 0.5f
        r = (229 + 3 * k).toInt(); g = (17 + 211 * k).toInt(); b = (46 + 174 * k).toInt()
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * ELA (анализ уровня ошибок): пересжимаем фото с известным качеством и смотрим разницу.
 * Однажды сжатые области «устоялись» и меняются мало; вставленное или перерисованное — светится иначе.
 * Идём полосами по 128 строк (кратно блокам JPEG 16×16), чтобы не держать 12 Мп в памяти целиком.
 */
private fun elaMap(ctx: android.content.Context, uri: Uri, onProgress: (Float) -> Unit, quality: Int = 90, maxOut: Int = 900): ImageBitmap? {
    val dec = ctx.contentResolver.openInputStream(uri)?.use {
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(it)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(it, false)
    } ?: return null
    try {
        val w = dec.width
        val h = dec.height
        val scale = maxOf(1, (maxOf(w, h) + maxOut - 1) / maxOut)
        val ow = (w + scale - 1) / scale
        val oh = (h + scale - 1) / scale
        val sum = FloatArray(ow * oh)
        val cnt = IntArray(ow * oh)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val strip = 128
        val pad = 16
        val a = IntArray(w * (strip + 2 * pad))
        val b = IntArray(w * (strip + 2 * pad))
        var y = 0
        while (y < h) {
            val sh = minOf(strip, h - y)
            val top = maxOf(0, y - pad)
            val bottom = minOf(h, y + sh + pad)
            val rh = bottom - top
            val part = dec.decodeRegion(Rect(0, top, w, bottom), opts) ?: break
            val bos = ByteArrayOutputStream(w * rh / 3)
            part.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            val re = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size(), opts)
            if (re == null) { part.recycle(); break }
            part.getPixels(a, 0, w, 0, 0, w, rh)
            re.getPixels(b, 0, w, 0, 0, w, rh)
            part.recycle(); re.recycle()
            for (r in 0 until sh) {
                val row = (y - top + r) * w
                val o0 = ((y + r) / scale) * ow
                for (x in 0 until w) {
                    val p = a[row + x]
                    val q = b[row + x]
                    val d = maxOf(
                        kotlin.math.abs((p shr 16 and 255) - (q shr 16 and 255)),
                        kotlin.math.abs((p shr 8 and 255) - (q shr 8 and 255)),
                        kotlin.math.abs((p and 255) - (q and 255)),
                    )
                    val o = o0 + x / scale
                    sum[o] += d.toFloat(); cnt[o]++
                }
            }
            y += sh
            onProgress(y.toFloat() / h)
        }
        val vals = FloatArray(ow * oh) { if (cnt[it] > 0) sum[it] / cnt[it] else 0f }
        val sorted = vals.copyOf().also { it.sort() }
        val topVal = sorted[(sorted.size * 0.995).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(1f)
        val px = IntArray(ow * oh) { heat((vals[it] / topVal).coerceIn(0f, 1f)) }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888).asImageBitmap()
    } finally {
        dec.recycle()
    }
}

/** Яркость 16×16 — для сравнения миниатюры с самим фото. */
private fun gray16(b: Bitmap): FloatArray {
    val s = Bitmap.createScaledBitmap(b, 16, 16, true)
    val px = IntArray(256)
    s.getPixels(px, 0, 16, 0, 0, 16, 16)
    return FloatArray(256) { val p = px[it]; (p shr 16 and 255) * 0.299f + (p shr 8 and 255) * 0.587f + (p and 255) * 0.114f }
}

private fun ratioName(w: Int, h: Int): String {
    val r = w.toFloat() / h
    return listOf(4f / 3 to "4:3", 3f / 4 to "3:4", 16f / 9 to "16:9", 9f / 16 to "9:16", 1f to "1:1", 3f / 2 to "3:2", 2f / 3 to "2:3")
        .minByOrNull { kotlin.math.abs(it.first - r) }!!.second
}

private fun forensics(ctx: android.content.Context, uri: Uri, info: ExifInfo, onProgress: (Float) -> Unit): Forensics {
    val head = ctx.contentResolver.openInputStream(uri)?.use { s ->
        val buf = ByteArray(2 * 1024 * 1024)
        var n = 0
        while (n < buf.size) { val k = s.read(buf, n, buf.size - n); if (k <= 0) break; n += k }
        buf.copyOf(n)
    } ?: ByteArray(0)
    val report = JpegForensics.analyze(head)
    val alerts = mutableListOf<Triple<Int, String, String>>()
    if (report == null) {
        alerts += Triple(1, "Это не JPEG", "ELA и таблицы сжатия работают только для JPEG. Скриншоты и PNG так не проверить.")
        return Forensics(null, null, alerts)
    }
    if (report.editors.isNotEmpty()) {
        alerts += Triple(2, "Следы редактора: ${report.editors.joinToString()}", "файл сохраняли в программе-редакторе — его открывали и могли менять")
    }
    val camera = info.tags.any { it.first == "модель" }
    if (report.standardTables && !camera) {
        alerts += Triple(1, "Фото пересохранено", "у снимков прямо с камеры свои таблицы сжатия, а тут стандартные libjpeg и нет данных камеры — фото прошло через редактор, мессенджер или сайт")
    } else if (report.standardTables) {
        alerts += Triple(1, "Стандартные таблицы сжатия при данных камеры", "так бывает, когда фото открыли и пересохранили, не трогая EXIF")
    }
    val pv = info.previewBmp
    val th = info.thumb
    if (pv != null && th != null && th.width > 8 && th.height > 8) {
        val rp = pv.width.toFloat() / pv.height
        val rt = th.width.toFloat() / th.height
        if (kotlin.math.abs(rp - rt) / rp > 0.04f) {
            alerts += Triple(1, "Пропорции миниатюры другие: ${ratioName(th.width, th.height)} против ${ratioName(pv.width, pv.height)}", "фото могли обрезать после съёмки — сравни миниатюру ниже глазами")
        } else {
            val ga = gray16(pv)
            val gb = gray16(th)
            val diff = ga.indices.sumOf { kotlin.math.abs(ga[it] - gb[it]).toDouble() } / ga.size
            alerts += if (diff > 28) Triple(2, "Миниатюра не совпадает с фото", "внутри файла осталась старая превьюшка (расхождение ${diff.toInt()}) — на ней видно, как было до правки")
            else Triple(0, "Миниатюра совпадает с фото", "встроенная превьюшка такая же, как кадр")
        }
    }
    report.quality?.let { q ->
        alerts += Triple(0, "Качество сжатия ≈ $q%", (if (report.progressive) "прогрессивный JPEG · " else "") + "блоки: " + report.segments.take(8).joinToString(" · "))
    }
    if (alerts.none { it.first > 0 }) alerts.add(0, Triple(0, "Явных следов монтажа нет", "но это не гарантия: проверь тепловую карту ниже"))
    val ela = runCatching { elaMap(ctx, uri, onProgress) }.getOrNull()
    return Forensics(report, ela, alerts.sortedByDescending { it.first })
}

private val TAGS = listOf(
    ExifInterface.TAG_MAKE to "производитель",
    ExifInterface.TAG_MODEL to "модель",
    ExifInterface.TAG_DATETIME_ORIGINAL to "снято",
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "часовой пояс",
    ExifInterface.TAG_SOFTWARE to "программа",
    ExifInterface.TAG_GPS_ALTITUDE to "высота GPS",
    ExifInterface.TAG_GPS_DATESTAMP to "дата GPS",
    ExifInterface.TAG_IMAGE_WIDTH to "ширина",
    ExifInterface.TAG_IMAGE_LENGTH to "высота",
    ExifInterface.TAG_F_NUMBER to "диафрагма",
    ExifInterface.TAG_EXPOSURE_TIME to "выдержка",
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "ISO",
    ExifInterface.TAG_FOCAL_LENGTH to "фокусное",
    ExifInterface.TAG_LENS_MODEL to "объектив",
    ExifInterface.TAG_ARTIST to "автор",
    ExifInterface.TAG_IMAGE_UNIQUE_ID to "уникальный ID",
    ExifInterface.TAG_CAMERA_OWNER_NAME to "владелец камеры",
    ExifInterface.TAG_BODY_SERIAL_NUMBER to "серийный номер",
)

@Composable
fun ExifScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ExifInfo?>(null) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var forensic by remember { mutableStateOf<Forensics?>(null) }
    var fProgress by remember { mutableStateOf(-1f) }
    var showEla by remember { mutableStateOf(true) }

    val mediaLocation = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> emptyArray()
    }
    val (locGranted, requestLoc) = rememberPermissions(mediaLocation)

    fun load(uri: Uri) {
        source = uri
        msg = null
        forensic = null
        scope.launch {
            info = withContext(Dispatchers.IO) {
                runCatching {
                    // Android прячет GPS в фото от приложений; с разрешением ACCESS_MEDIA_LOCATION — отдаёт оригинал
                    val real = runCatching {
                        if (Build.VERSION.SDK_INT >= 29 && locGranted && uri.authority == "com.android.providers.media.documents") {
                            val id = DocumentsContract.getDocumentId(uri).substringAfter(':').toLong()
                            val media = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            MediaStore.setRequireOriginal(media).also { ctx.contentResolver.openInputStream(it)!!.close() }
                        } else uri
                    }.getOrDefault(uri)
                    val exif = ctx.contentResolver.openInputStream(real)!!.use { ExifInterface(it) }
                    val ll = exif.latLong
                    val tags = TAGS.mapNotNull { (tag, name) -> exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { name to it } }
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    val thumb = runCatching { exif.thumbnailBitmap }.getOrNull()
                    ExifInfo(ll?.getOrNull(0), ll?.getOrNull(1), tags, bmp?.asImageBitmap(), bmp, thumb)
                }.getOrElse { ExifInfo(null, null, listOf("ошибка" to (it.message ?: "?")), null) }
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) load(uri) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { out ->
        val src = source ?: return@rememberLauncherForActivityResult
        if (out == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = withContext(Dispatchers.IO) {
                runCatching {
                    // перекодирование в новый JPEG: метаданные не переносятся вообще
                    val bmp = ctx.contentResolver.openInputStream(src)!!.use { BitmapFactory.decodeStream(it) }
                    ctx.contentResolver.openOutputStream(out)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    "✓ сохранено без метаданных"
                }.getOrElse { "ошибка: ${it.message}" }
            }
        }
    }

    ModuleScreen("EXIF", onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "Фото хранит скрытые данные: где оно сделано (до метров), когда, на какой телефон, иногда серийный номер. " +
                    "Отправил «просто фотку» — мог отправить свой адрес.",
                style = Ns.small,
            )
            if (mediaLocation.isNotEmpty() && !locGranted) {
                Text(
                    "[выдать доступ к геоданным фото — иначе Android их спрячет]",
                    style = Ns.small.copy(color = Ns.Red),
                    modifier = Modifier.clickable { requestLoc() }.padding(vertical = 6.dp),
                )
            }
            TermButton("ВЫБРАТЬ ФОТО", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Ns.Red) { pick.launch(arrayOf("image/*")) }
            val i = info
            if (i != null) {
                i.preview?.let {
                    Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp))
                }
                Spacer(Modifier.height(8.dp))
                if (i.lat != null && i.lon != null) {
                    Panel(color = Ns.Red) {
                        Text("⚠ МЕСТО СЪЁМКИ НАЙДЕНО", style = Ns.title.copy(color = Ns.Red))
                        Text("%.6f, %.6f".format(i.lat, i.lon), style = Ns.text)
                        Text(
                            "[открыть на карте]",
                            style = Ns.text.copy(color = Ns.Red),
                            modifier = Modifier.clickable {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=${i.lat}&mlon=${i.lon}#map=17/${i.lat}/${i.lon}"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }.padding(vertical = 6.dp),
                        )
                    }
                } else {
                    Panel { Text("GPS-координат нет", style = Ns.text) }
                }
                SectionTitle("метаданные · ${i.tags.size}")
                i.tags.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(k.padEnd(16), style = Ns.small.copy(color = Ns.Dim))
                        Text(v, style = Ns.small.copy(color = Ns.Green))
                    }
                }
                if (i.tags.isEmpty()) Text("метаданных нет — фото чистое", style = Ns.small)
                TermButton("СОХРАНИТЬ ЧИСТУЮ КОПИЮ", Modifier.fillMaxWidth().padding(vertical = 10.dp)) { save.launch("clean_${System.currentTimeMillis()}.jpg") }
                msg?.let { Text(it, style = Ns.text.copy(color = if (it.startsWith("✓")) Ns.Green else Ns.Red)) }

                SectionTitle("подделка? · экспертиза", Ns.Red)
                val f = forensic
                if (f == null) {
                    Text("Ищет следы монтажа: программы-редакторы в файле, пересжатие, подменённую миниатюру и тепловую карту ошибок сжатия (ELA).", style = Ns.small)
                    if (fProgress >= 0f) {
                        HudBar(fProgress, Ns.Red, Modifier.padding(top = 8.dp))
                        Text("пересжимаю по полосам… ${(fProgress * 100).toInt()}%", style = Ns.small.copy(color = Ns.Red), modifier = Modifier.padding(top = 4.dp))
                    } else {
                        TermButton("ПРОВЕРИТЬ НА МОНТАЖ", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Ns.Red) {
                            val src = source ?: return@TermButton
                            fProgress = 0f
                            scope.launch {
                                forensic = withContext(Dispatchers.Default) { forensics(ctx, src, i) { p -> fProgress = p } }
                                fProgress = -1f
                            }
                        }
                    }
                } else {
                    f.alerts.forEach { (lvl, t, d) -> AlertRow(lvl, t, d) }
                    i.thumb?.let { th ->
                        SectionTitle("миниатюра внутри файла")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Image(th.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp))
                                Text("превью из EXIF", style = Ns.label)
                            }
                            i.preview?.let { pv ->
                                Column(Modifier.weight(1f)) {
                                    Image(pv, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp))
                                    Text("само фото", style = Ns.label)
                                }
                            }
                        }
                    }
                    f.ela?.let { ela ->
                        SectionTitle("тепловая карта ELA")
                        val shown = if (showEla) ela else i.preview ?: ela
                        Image(
                            shown, null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clickable { showEla = !showEla },
                        )
                        Text(
                            (if (showEla) "нажми — покажу оригинал" else "нажми — покажу карту") +
                                "\nОднородное зерно по всему кадру — норма; края и текст светятся всегда. Подозрительно, когда кусок светится " +
                                "заметно ярче или темнее похожих участков вокруг (лицо, надпись, предмет) — его сжимали другое число раз.",
                            style = Ns.small,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
