package com.nullsignal.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Сосуд: ломаная линия от края глаза к радужке (в долях размера). */
private class Vein(val points: List<Offset>, val width: Float)

private fun makeVeins(seed: Int): List<Vein> {
    val r = Random(seed)
    return List(26) {
        val fromLeft = r.nextBoolean()
        var x = if (fromLeft) r.nextFloat() * 0.12f else 1f - r.nextFloat() * 0.12f
        var y = 0.3f + r.nextFloat() * 0.4f
        val pts = mutableListOf(Offset(x, y))
        val steps = 4 + r.nextInt(5)
        repeat(steps) {
            x += (if (fromLeft) 1 else -1) * (0.025f + r.nextFloat() * 0.04f)
            y += (r.nextFloat() - 0.5f) * 0.09f
            pts += Offset(x, y)
        }
        Vein(pts, 0.6f + r.nextFloat() * 1.6f)
    }
}

/**
 * Живой глаз: моргает, оглядывается, сужает зрачок.
 * Нажатие — глаз резко смотрит в точку касания.
 */
@Composable
fun WatchingEye(modifier: Modifier = Modifier) {
    val veins = remember { makeVeins(1337) }
    var lookX by remember { mutableFloatStateOf(0f) }
    var lookY by remember { mutableFloatStateOf(0f) }
    var open by remember { mutableFloatStateOf(1f) }
    var pupil by remember { mutableFloatStateOf(0.4f) }
    var time by remember { mutableFloatStateOf(0f) }
    var touchX by remember { mutableFloatStateOf(Float.NaN) }
    var touchY by remember { mutableFloatStateOf(0f) }
    var touchAt by remember { mutableFloatStateOf(-10f) }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var targetX = 0f; var targetY = 0f
        var nextLook = 1.5f
        var nextBlink = 2.5f
        var blinkStart = -1f
        var last = 0f
        while (true) {
            withFrameNanos { now ->
                val t = (now - start) / 1e9f
                val dt = (t - last).coerceIn(0f, 0.1f); last = t
                time = t
                if (t - touchAt < 1.6f && !touchX.isNaN()) {
                    targetX = touchX; targetY = touchY
                } else if (t > nextLook) {
                    // иногда смотрит прямо на тебя, иногда в сторону
                    if (Random.nextFloat() < 0.35f) { targetX = 0f; targetY = 0f } else {
                        targetX = Random.nextFloat() * 2 - 1; targetY = (Random.nextFloat() * 2 - 1) * 0.6f
                    }
                    nextLook = t + 0.8f + Random.nextFloat() * 3f
                }
                val speed = if (t - touchAt < 0.3f) 22f else 9f // резкий саккадный рывок
                lookX += (targetX - lookX) * (speed * dt).coerceAtMost(1f)
                lookY += (targetY - lookY) * (speed * dt).coerceAtMost(1f)
                // моргание
                if (t > nextBlink && blinkStart < 0) blinkStart = t
                if (blinkStart >= 0) {
                    val p = (t - blinkStart) / 0.22f
                    open = if (p < 0.5f) 1f - p * 2 else (p - 0.5f) * 2
                    if (p >= 1f) {
                        open = 1f; blinkStart = -1f
                        nextBlink = t + if (Random.nextFloat() < 0.2f) 0.25f else 2.5f + Random.nextFloat() * 5f
                    }
                }
                // зрачок «дышит», а при касании сужается
                val base = 0.42f + 0.06f * sin(t * 0.9f)
                val target = if (t - touchAt < 1.2f) 0.22f else base
                pupil += (target - pupil) * (6f * dt).coerceAtMost(1f)
            }
        }
    }

    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { p ->
                touchX = ((p.x / size.width) * 2 - 1).coerceIn(-1f, 1f)
                touchY = ((p.y / size.height) * 2 - 1).coerceIn(-1f, 1f)
                touchAt = time
            }
        },
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val lidH = h * 0.48f * open.coerceIn(0f, 1f)

        val almond = Path().apply {
            moveTo(w * 0.04f, cy)
            cubicTo(w * 0.28f, cy - lidH * 1.25f, w * 0.72f, cy - lidH * 1.25f, w * 0.96f, cy)
            cubicTo(w * 0.72f, cy + lidH * 1.1f, w * 0.28f, cy + lidH * 1.1f, w * 0.04f, cy)
            close()
        }

        // тень вокруг глазницы
        drawOval(
            Brush.radialGradient(listOf(Ns.Red.copy(alpha = 0.18f), Color.Transparent), Offset(cx, cy), w * 0.55f),
            topLeft = Offset(0f, cy - h * 0.7f), size = androidx.compose.ui.geometry.Size(w, h * 1.4f),
        )
        if (open > 0.04f) {
            clipPath(almond) {
                // белок: жёлто-розоватый, темнее к краям
                drawRect(Brush.radialGradient(
                    listOf(Color(0xFFEDE3D3), Color(0xFFD9B9A4), Color(0xFF8E4B44)), Offset(cx, cy), w * 0.48f,
                ))
                // сосуды
                veins.forEach { v ->
                    val path = Path()
                    v.points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x * w, o.y * h) else path.lineTo(o.x * w, o.y * h) }
                    drawPath(path, Color(0xFF9A1020).copy(alpha = 0.55f), style = Stroke(v.width, cap = StrokeCap.Round))
                }
                val r = h * 0.34f
                val ix = cx + lookX * w * 0.2f
                val iy = cy + lookY * h * 0.14f
                // радужка
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF7A1A12), Color(0xFF3A0B08), Color(0xFF0B0202)), Offset(ix, iy), r),
                    r, Offset(ix, iy),
                )
                for (k in 0 until 48) {
                    val a = k / 48f * 2 * PI.toFloat()
                    val inner = r * (pupil + 0.08f)
                    drawLine(
                        Color(0xFFB0382A).copy(alpha = 0.35f),
                        Offset(ix + cos(a) * inner, iy + sin(a) * inner),
                        Offset(ix + cos(a) * r * 0.92f, iy + sin(a) * r * 0.92f),
                        strokeWidth = 1.2f,
                    )
                }
                drawCircle(Color.Black, r, Offset(ix, iy), style = Stroke(r * 0.12f))
                // зрачок
                drawCircle(Color.Black, r * pupil, Offset(ix, iy))
                // блик
                drawCircle(Color.White.copy(alpha = 0.85f), r * 0.11f, Offset(ix - r * 0.35f, iy - r * 0.38f))
                drawCircle(Color.White.copy(alpha = 0.35f), r * 0.05f, Offset(ix + r * 0.3f, iy + r * 0.25f))
                // тень верхнего века
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent), cy - lidH * 1.2f, cy - lidH * 0.2f))
            }
        }
        // контур век
        drawPath(almond, Color(0xFF1A0505), style = Stroke(w * 0.012f))
        // ресницы-штрихи сверху
        for (k in 1..13) {
            val f = k / 14f
            val x = w * (0.04f + 0.92f * f)
            val bez = 1 - (2 * f - 1) * (2 * f - 1)
            val y = cy - lidH * 0.94f * bez
            drawLine(Ns.Green.copy(alpha = 0.25f), Offset(x, y), Offset(x + (f - 0.5f) * w * 0.05f, y - h * 0.08f * (0.3f + bez)), 1.5f)
        }
    }
}
