package com.nullsignal.launcher.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.random.Random

private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄ0123456789ABCDEF<>/\\|=+*#"

/** «Цифровой дождь» на фоне. Дешёвый: ~20 кадров в секунду. */
@Composable
fun MatrixRain(modifier: Modifier = Modifier, alpha: Float = 0.35f) {
    var frame by remember { mutableLongStateOf(0L) }
    val drops = remember { mutableMapOf<Int, Float>() }
    val paint = remember {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            textSize = 34f
        }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { t ->
                if (t - last >= 50) { last = t; frame++ }
            }
        }
    }
    Canvas(modifier) {
        frame // подписка на кадры
        val cell = paint.textSize
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        drawIntoCanvas { canvas ->
            for (c in 0 until cols) {
                val head = drops.getOrPut(c) { Random.nextFloat() * -rows }
                val newHead = head + 0.5f
                drops[c] = if (newHead - 18 > rows && Random.nextFloat() < 0.05f) Random.nextFloat() * -10 else newHead
                for (k in 0 until 18) {
                    val r = (newHead - k).toInt()
                    if (r < 0 || r > rows) continue
                    val a = alpha * (1f - k / 18f)
                    paint.color = (if (k == 0) Ns.Cyan else Ns.Green).copy(alpha = a).toArgb()
                    val ch = GLYPHS[(c * 31 + r * 17 + (frame / 6).toInt()) % GLYPHS.length]
                    canvas.nativeCanvas.drawText(ch.toString(), c * cell, r * cell, paint)
                }
            }
        }
    }
}
