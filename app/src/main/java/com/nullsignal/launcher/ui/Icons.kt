package com.nullsignal.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nullsignal.core.terminal.Module

/**
 * Единый набор линейных иконок модулей: сетка 24×24, одна толщина линии,
 * скруглённые концы. Рисуются кодом — одинаково на любом телефоне.
 */
@Composable
fun ModuleIcon(module: Module, color: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), st.width, StrokeCap.Round)
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) = drawCircle(color, r * u, p(cx, cy), style = if (fill) Fill else st)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 0f) =
            drawRoundRect(color, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = st)
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
            drawArc(color, start, sweep, false, p(cx - r, cy - r), Size(2 * r * u, 2 * r * u), style = st)
        fun path(vararg pts: Float, close: Boolean = false) {
            val pa = Path()
            pa.moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size - 1) { pa.lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
            if (close) pa.close()
            drawPath(pa, color, style = st)
        }

        when (module) {
            Module.INTERCEPT -> {
                circle(12f, 12f, 7f); circle(12f, 12f, 2f, fill = true)
                line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f); line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f)
            }
            Module.MITM -> {
                circle(4f, 12f, 2.2f); circle(20f, 12f, 2.2f)
                line(6.2f, 12f, 9f, 12f); line(15f, 12f, 17.8f, 12f)
                path(12f, 8.5f, 15f, 12f, 12f, 15.5f, 9f, 12f, close = true)
            }
            Module.STALKER -> { circle(12f, 12f, 8f); line(6.5f, 17.5f, 17.5f, 6.5f) }
            Module.XRAY -> { rect(7f, 3f, 10f, 18f, 2f); line(4f, 12f, 20f, 12f); line(10f, 18f, 14f, 18f) }
            Module.TRACES -> {
                drawOval(color, p(5f, 3.5f), Size(4.6f * u, 7.5f * u), style = st)
                drawOval(color, p(13.5f, 10f), Size(4.6f * u, 7.5f * u), style = st)
                circle(7.3f, 14.2f, 1.4f); circle(15.8f, 20.6f, 1.3f)
            }
            Module.NOTIFLOG -> {
                arc(12f, 11f, 6f, 180f, 180f); line(6f, 11f, 6f, 16.5f); line(18f, 11f, 18f, 16.5f)
                line(4.5f, 17f, 19.5f, 17f); circle(12f, 19.8f, 1.4f); line(12f, 3.2f, 12f, 5f)
            }
            Module.CAMS -> { rect(3f, 7f, 18f, 12f, 2f); circle(12f, 13f, 3.5f); path(8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f) }
            Module.AUDIT -> {
                path(12f, 3f, 19f, 6f, 18.5f, 13f, 12f, 21f, 5.5f, 13f, 5f, 6f, close = true)
                path(8.6f, 12f, 11f, 14.4f, 15.4f, 9.6f)
            }
            Module.NETSCAN -> {
                circle(5f, 6f, 2f); circle(19f, 7f, 2f); circle(12f, 18f, 2f)
                line(7f, 6.2f, 17f, 6.8f); line(6f, 7.8f, 11f, 16.2f); line(18f, 8.8f, 13f, 16.3f)
            }
            Module.MONITOR -> path(2f, 13f, 7f, 13f, 9.5f, 6f, 13f, 19f, 15.5f, 10f, 17f, 13f, 22f, 13f)
            Module.SPEED -> { arc(12f, 15f, 8.5f, 180f, 180f); line(12f, 15f, 16.5f, 9.5f); circle(12f, 15f, 1.4f, fill = true); line(3.5f, 19f, 20.5f, 19f) }
            Module.TERMINAL -> { rect(3f, 4f, 18f, 16f, 2f); path(7f, 9.5f, 10f, 12f, 7f, 14.5f); line(12f, 15f, 16.5f, 15f) }
            Module.SSH -> { circle(7.5f, 12f, 4f); line(11.5f, 12f, 21f, 12f); line(17f, 12f, 17f, 15f); line(20f, 12f, 20f, 14.5f) }
            Module.OSINT -> { circle(10.5f, 10.5f, 6.5f); line(15.3f, 15.3f, 21f, 21f) }
            Module.EXIF -> { rect(3f, 5f, 18f, 14f, 2f); path(5f, 17f, 10f, 11.5f, 13.5f, 15f, 16f, 12.5f, 19f, 17f); circle(16f, 9f, 1.6f) }
            Module.WIFI -> {
                arc(12f, 19f, 4f, 225f, 90f); arc(12f, 19f, 8.5f, 225f, 90f); arc(12f, 19f, 13f, 225f, 90f)
                circle(12f, 19f, 1.3f, fill = true)
            }
            Module.BLE -> path(7f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 16.5f)
            Module.NFC -> { rect(3f, 5f, 8f, 14f, 1.5f); arc(12f, 12f, 3f, -45f, 90f); arc(12f, 12f, 6f, -45f, 90f); arc(12f, 12f, 9f, -45f, 90f) }
            Module.IR -> {
                rect(8f, 8f, 8f, 14f, 2f); circle(12f, 12.5f, 1.3f, fill = true); line(10.5f, 17f, 13.5f, 17f)
                line(10f, 5.5f, 8.5f, 2.5f); line(12f, 5f, 12f, 2f); line(14f, 5.5f, 15.5f, 2.5f)
            }
            Module.SENSORS -> { circle(12f, 12f, 9f); path(12f, 5.5f, 14f, 12f, 12f, 18.5f, 10f, 12f, close = true) }
            Module.SPY -> { rect(3f, 3f, 12f, 12f, 1.5f); rect(9f, 9f, 12f, 12f, 1.5f) }
            Module.AUTH -> { circle(12f, 12f, 8.5f); line(12f, 12f, 12f, 7f); line(12f, 12f, 15.5f, 14f) }
            Module.TOOLS -> { rect(5f, 10.5f, 14f, 10.5f, 2f); arc(12f, 10.5f, 4.5f, 180f, 180f); circle(12f, 15.5f, 1.3f, fill = true) }
            Module.MESSAGES -> { rect(3f, 6f, 18f, 12f, 2f); path(3.5f, 7f, 12f, 13.5f, 20.5f, 7f) }
            Module.GAMES, Module.BREACH -> { line(9f, 3f, 7.5f, 21f); line(16.5f, 3f, 15f, 21f); line(3f, 9f, 21f, 9f); line(3f, 15f, 21f, 15f) }
            Module.ACADEMY -> { path(6f, 4f, 9f, 4f, 18f, 20f); line(12.2f, 10.8f, 6f, 20f) }
            Module.BOARDS -> {
                rect(6f, 6f, 12f, 12f, 1.5f); rect(9.5f, 9.5f, 5f, 5f, 0.5f)
                for (c in listOf(8.5f, 12f, 15.5f)) { line(c, 2.5f, c, 6f); line(c, 18f, c, 21.5f); line(2.5f, c, 6f, c); line(18f, c, 21.5f, c) }
            }
            Module.CELLS -> {
                line(12f, 9.5f, 8f, 21f); line(12f, 9.5f, 16f, 21f); line(9.6f, 16f, 14.4f, 16f); circle(12f, 7.5f, 1.6f, fill = true)
                arc(12f, 7.5f, 4.5f, -40f, 80f); arc(12f, 7.5f, 8f, -40f, 80f); arc(12f, 7.5f, 4.5f, 140f, 80f); arc(12f, 7.5f, 8f, 140f, 80f)
            }
            Module.GNSS -> {
                rect(9.5f, 9.5f, 5f, 5f, 1f); rect(2.5f, 10f, 5.5f, 4f, 0.5f); rect(16f, 10f, 5.5f, 4f, 0.5f)
                line(8f, 12f, 9.5f, 12f); line(14.5f, 12f, 16f, 12f); line(12f, 14.5f, 12f, 17f); arc(12f, 20f, 3f, 200f, 140f)
            }
            Module.SONIC -> { line(4f, 10f, 4f, 14f); line(8f, 7f, 8f, 17f); line(12f, 4f, 12f, 20f); line(16f, 8f, 16f, 16f); line(20f, 10.5f, 20f, 13.5f) }
            Module.PYTHON -> { path(4f, 8f, 8f, 12f, 4f, 16f); path(10f, 8f, 14f, 12f, 10f, 16f); path(16f, 8f, 20f, 12f, 16f, 16f) }
            Module.DECODER -> {
                line(4f, 8f, 20f, 8f); path(16f, 4f, 20f, 8f, 16f, 12f)
                line(4f, 16f, 20f, 16f); path(8f, 12f, 4f, 16f, 8f, 20f)
            }
            Module.FILECHECK -> {
                path(6f, 3f, 14f, 3f, 18f, 7f, 18f, 21f, 6f, 21f, close = true)
                path(14f, 3f, 14f, 7f, 18f, 7f); path(9f, 14f, 11f, 16.5f, 15f, 11.5f)
            }
            Module.SPECTRUM -> path(3f, 16f, 6f, 16f, 8f, 8f, 10f, 18f, 12f, 5f, 14f, 17f, 16f, 11f, 18f, 16f, 21f, 16f)
            Module.DROP -> {
                rect(4f, 14f, 16f, 6f, 1f)
                line(9f, 4f, 9f, 11f); path(6.5f, 6.5f, 9f, 4f, 11.5f, 6.5f)
                line(15f, 4f, 15f, 11f); path(12.5f, 8.5f, 15f, 11f, 17.5f, 8.5f)
            }
            Module.LANCHAT -> {
                path(4f, 5f, 20f, 5f, 20f, 16f, 11f, 16f, 7f, 20f, 7f, 16f, 4f, 16f, close = true)
                line(8f, 9f, 16f, 9f); line(8f, 12f, 13f, 12f)
            }
            Module.HOME -> { path(4f, 11f, 12f, 4f, 20f, 11f); rect(6.5f, 10f, 11f, 10f, 1f) }
        }
    }
}
