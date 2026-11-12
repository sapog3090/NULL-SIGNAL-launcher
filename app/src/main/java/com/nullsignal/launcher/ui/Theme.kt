package com.nullsignal.launcher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

object Ns {
    val Bg = Color(0xFF03060A)
    val Bg2 = Color(0xFF071318)
    val Panel = Color(0xCC0A161B)
    val Green = Color(0xFF39FF88)
    val Dim = Color(0xFF2E9C68)
    val Faint = Color(0xFF15412F)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF4D6D)
    val Cyan = Color(0xFF2EE6FF)
    val Violet = Color(0xFFB57BFF)
    val Text2 = Color(0xFF9FBFB0)

    val Mono = FontFamily.Monospace
    val text = TextStyle(fontFamily = Mono, color = Green, fontSize = 13.sp, lineHeight = 18.sp)
    val small = text.copy(fontSize = 11.sp, lineHeight = 15.sp, color = Text2)
    val title = text.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp, letterSpacing = 1.sp)
    val label = text.copy(fontSize = 10.sp, lineHeight = 12.sp, color = Dim, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)

    /** Скошенный угол — фирменная форма панелей. */
    val Hud: Shape = CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 0.dp, bottomStart = 12.dp)
    val HudSmall: Shape = CutCornerShape(topEnd = 7.dp, bottomStart = 7.dp)
    val Pill: Shape = RoundedCornerShape(50)
}

@Composable
fun NsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ns.Green, background = Ns.Bg, surface = Ns.Bg2,
            onPrimary = Ns.Bg, onBackground = Ns.Green, onSurface = Ns.Green,
        ),
        content = content,
    )
}

/** Фон: тёмный градиент + сетка + сканлайны. */
fun Modifier.cyberBackground(): Modifier = this
    .background(Brush.verticalGradient(listOf(Ns.Bg, Ns.Bg2, Ns.Bg)))
    .drawBehind {
        val grid = Ns.Green.copy(alpha = 0.035f)
        val step = 32.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(grid, Offset(x, 0f), Offset(x, size.height)); x += step }
        var y = 0f
        while (y < size.height) { drawLine(grid, Offset(0f, y), Offset(size.width, y)); y += step }
        drawRect(Brush.radialGradient(listOf(Ns.Cyan.copy(alpha = 0.06f), Color.Transparent), Offset(size.width * 0.8f, 0f), size.width))
    }

/** Сканлайны поверх всего экрана, как у старого ЭЛТ-монитора. */
fun Modifier.scanlines(): Modifier = drawWithContent {
    drawContent()
    var y = 0f
    val line = Color.Black.copy(alpha = 0.14f)
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += 3f
    }
}

/** Уголки-скобки по краям, как в прицеле: ⌜ ⌝ ⌞ ⌟ */
fun Modifier.brackets(color: Color, len: Dp = 10.dp, stroke: Dp = 1.5.dp): Modifier = drawWithContent {
    drawContent()
    val l = len.toPx(); val s = stroke.toPx(); val w = size.width; val h = size.height
    drawLine(color, Offset(0f, 0f), Offset(l, 0f), s); drawLine(color, Offset(0f, 0f), Offset(0f, l), s)
    drawLine(color, Offset(w, h), Offset(w - l, h), s); drawLine(color, Offset(w, h), Offset(w, h - l), s)
}

/** HUD-панель: полупрозрачный фон, скошенные углы, тонкая рамка и уголки. */
fun Modifier.hud(color: Color = Ns.Dim, fill: Color = Ns.Panel, shape: Shape = Ns.Hud): Modifier = this
    .clip(shape)
    .background(Brush.linearGradient(listOf(fill, fill.copy(alpha = 0.55f))))
    .border(1.dp, Brush.linearGradient(listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.25f))), shape)
    .brackets(color)

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = Ns.Dim, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().hud(color).padding(12.dp), content = content)
}

/** Заголовок секции: ▍ТЕКСТ ───── */
@Composable
fun SectionTitle(text: String, color: Color = Ns.Cyan, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), style = Ns.label.copy(color = color))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent))))
    }
}

/** Пилюля-статус: ● текст */
@Composable
fun Chip(text: String, color: Color = Ns.Green, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(Ns.Pill).background(color.copy(alpha = 0.1f)).border(1.dp, color.copy(alpha = 0.4f), Ns.Pill)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(Ns.Pill).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = Ns.small.copy(color = color, fontSize = 10.sp))
    }
}

/** Пульсирующая точка «онлайн». */
@Composable
fun PulseDot(color: Color = Ns.Green, size: Dp = 8.dp) {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a",
    )
    Box(Modifier.size(size).alpha(a).clip(Ns.Pill).background(color))
}

/** Полоса прогресса в HUD-стиле. */
@Composable
fun HudBar(progress: Float, color: Color = Ns.Green, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(Ns.HudSmall).background(color.copy(alpha = 0.12f))) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))),
        )
    }
}

private const val GLITCH_CHARS = "!<>-_\\/[]{}=+*^?#░▒▓█"

/** Текст, который время от времени «сбоит»: символы мешаются, слои расходятся по цветам. */
@Composable
fun GlitchText(text: String, style: TextStyle = Ns.title, modifier: Modifier = Modifier) {
    var shown by remember(text) { mutableStateOf(text) }
    var glitching by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        while (true) {
            delay(Random.nextLong(2200, 5200))
            glitching = true
            repeat(4) {
                shown = text.map { c ->
                    if (c != ' ' && Random.nextFloat() < 0.25f) GLITCH_CHARS.random() else c
                }.joinToString("")
                delay(55)
            }
            shown = text
            glitching = false
        }
    }
    Box(modifier) {
        if (glitching) {
            Text(shown, style = style.copy(color = Ns.Cyan.copy(alpha = 0.7f)), modifier = Modifier.offset(x = (-2).dp))
            Text(shown, style = style.copy(color = Ns.Red.copy(alpha = 0.7f)), modifier = Modifier.offset(x = 2.dp))
        }
        Text(shown, style = style)
    }
}

/** Печатает текст посимвольно. */
@Composable
fun TypewriterText(text: String, style: TextStyle = Ns.text, charDelayMs: Long = 12, onDone: () -> Unit = {}) {
    var n by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        while (n < text.length) { delay(charDelayMs); n++ }
        onDone()
    }
    Text(text.take(n) + if (n < text.length) "▌" else "", style = style)
}

/** Кнопка: скошенные углы, градиент, подсветка при нажатии. */
@Composable
fun TermButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Ns.Green,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = if (enabled) color else Ns.Faint
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .clip(Ns.HudSmall)
            .background(Brush.verticalGradient(listOf(c.copy(alpha = if (pressed) 0.35f else 0.16f), c.copy(alpha = if (pressed) 0.2f else 0.05f))))
            .border(1.dp, c.copy(alpha = if (enabled) 0.8f else 0.4f), Ns.HudSmall)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = Ns.text.copy(color = c, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
    }
}

/** Каркас экрана модуля: шапка с кнопкой назад, заголовком и линией. */
@Composable
fun ModuleScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(38.dp).clip(Ns.HudSmall).background(Ns.Green.copy(alpha = 0.08f))
                    .border(1.dp, Ns.Dim, Ns.HudSmall).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", style = Ns.title.copy(fontSize = 24.sp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NULL//SIGNAL", style = Ns.label)
                GlitchText(title.uppercase(), style = Ns.title.copy(fontSize = 20.sp))
            }
            PulseDot()
        }
        Box(
            Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Ns.Cyan, Ns.Green.copy(alpha = 0.4f), Color.Transparent))),
        )
        content()
    }
}

/** Полоска силы сигнала из блоков: ▂▃▅▇ */
fun signalBars(dbm: Int): String {
    val level = when {
        dbm >= -55 -> 4
        dbm >= -67 -> 3
        dbm >= -78 -> 2
        dbm >= -88 -> 1
        else -> 0
    }
    return "▂▃▅▇".mapIndexed { i, c -> if (i < level) c else '·' }.joinToString("")
}

/** Лёгкое «дыхание» свечения для акцентов. */
@Composable
fun glowAlpha(): Float {
    val a by rememberInfiniteTransition(label = "glow").animateFloat(
        0.45f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "g",
    )
    return a
}

@Suppress("unused")
private fun Modifier.ring(color: Color) = drawBehind { drawCircle(color, style = Stroke(2f)) }
