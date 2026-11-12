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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.RectangleShape
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
    // Строгая «жуткая» палитра: чёрный, кость, кровь.
    val Bg = Color(0xFF000000)
    val Bg2 = Color(0xFF0A0909)
    val Panel = Color(0xE60E0D0D)
    val Green = Color(0xFFE8E4DC)   // основной: цвет кости
    val Dim = Color(0xFF6F6A66)
    val Faint = Color(0xFF2A2726)
    val Amber = Color(0xFFFF6A2B)   // предупреждение
    val Red = Color(0xFFE5112E)     // кровь — главный акцент
    val Cyan = Color(0xFFB9B4AE)    // холодный серый для вторичного
    val Violet = Color(0xFFE5112E)
    val Text2 = Color(0xFF8E8883)

    val Mono = FontFamily.Monospace
    val text = TextStyle(fontFamily = Mono, color = Green, fontSize = 13.sp, lineHeight = 18.sp)
    val small = text.copy(fontSize = 11.sp, lineHeight = 15.sp, color = Text2)
    val Condensed = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))
    val title = text.copy(fontFamily = Condensed, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, letterSpacing = 2.sp)
    val label = text.copy(fontSize = 10.sp, lineHeight = 12.sp, color = Dim, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)

    /** Скошенный угол — фирменная форма панелей. */
    val Hud: Shape = RectangleShape
    val HudSmall: Shape = RectangleShape
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

private val GRAIN: List<Offset> = List(1400) { Offset(Random.nextFloat(), Random.nextFloat()) }

/** Фон: чёрный, плёночное зерно, красная виньетка снизу. */
fun Modifier.cyberBackground(): Modifier = this
    .background(Ns.Bg)
    .drawBehind {
        drawRect(Brush.radialGradient(listOf(Ns.Red.copy(alpha = 0.10f), Color.Transparent), Offset(size.width / 2, size.height * 1.05f), size.width))
        val dot = Ns.Green.copy(alpha = 0.05f)
        GRAIN.forEach { drawCircle(dot, 1.1f, Offset(it.x * size.width, it.y * size.height)) }
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

/**
 * Время в секундах от первого кадра. Работает через кадры Compose,
 * поэтому не замирает, даже если системные анимации выключены (энергосбережение).
 */
@Composable
fun rememberSeconds(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t.floatValue = (now - start) / 1_000_000_000f }
    }
    return t
}

/** Пульсирующая точка. */
@Composable
fun PulseDot(color: Color = Ns.Red, size: Dp = 8.dp) {
    val t by rememberSeconds()
    val a = 0.25f + 0.75f * ((kotlin.math.sin(t * 3.5f) + 1f) / 2f)
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
            Text(shown, style = style.copy(color = Color.White.copy(alpha = 0.5f)), modifier = Modifier.offset(x = (-2).dp))
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
            .background(if (pressed) c else c.copy(alpha = 0.04f))
            .border(1.dp, c.copy(alpha = if (enabled) 0.8f else 0.4f), Ns.HudSmall)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = Ns.text.copy(color = if (pressed) Ns.Bg else c, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
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
            ) { Text("←", style = Ns.title.copy(fontSize = 18.sp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NULL//SIGNAL", style = Ns.label.copy(color = Ns.Red))
                GlitchText(title.uppercase(), style = Ns.title.copy(fontSize = 20.sp))
            }
            PulseDot()
        }
        Box(
            Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Ns.Red, Ns.Red.copy(alpha = 0.3f), Color.Transparent))),
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

/** Лёгкое «дыхание» для акцентов. */
@Composable
fun glowAlpha(): Float {
    val t by rememberSeconds()
    return 0.45f + 0.55f * ((kotlin.math.sin(t * 2f) + 1f) / 2f)
}

@Suppress("unused")
private fun Modifier.ring(color: Color) = drawBehind { drawCircle(color, style = Stroke(2f)) }
