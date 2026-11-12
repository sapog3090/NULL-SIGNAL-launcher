package com.nullsignal.launcher.ui

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.provider.DocumentsContract
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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

private data class ExifInfo(
    val lat: Double?,
    val lon: Double?,
    val tags: List<Pair<String, String>>,
    val preview: ImageBitmap?,
)

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

    val mediaLocation = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else -> emptyArray()
    }
    val (locGranted, requestLoc) = rememberPermissions(mediaLocation)

    fun load(uri: Uri) {
        source = uri
        msg = null
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
                    ExifInfo(ll?.getOrNull(0), ll?.getOrNull(1), tags, bmp?.asImageBitmap())
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
            }
        }
    }
}
