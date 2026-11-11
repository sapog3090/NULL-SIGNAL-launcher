package com.nullsignal.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

object Ns {
    val Bg = Color(0xFF040704)
    val Panel = Color(0xFF07120A)
    val Green = Color(0xFF00FF66)
    val Dim = Color(0xFF1F8A4C)
    val Faint = Color(0xFF0F3D22)
    val Amber = Color(0xFFFFB000)
    val Red = Color(0xFFFF3355)
    val Cyan = Color(0xFF00E5FF)

    val Mono = FontFamily.Monospace
    val text = TextStyle(fontFamily = Mono, color = Green, fontSize = 13.sp, lineHeight = 17.sp)
    val small = text.copy(fontSize = 11.sp, lineHeight = 14.sp, color = Dim)
    val title = text.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
}

@Composable
fun NsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ns.Green, background = Ns.Bg, surface = Ns.Panel,
            onPrimary = Ns.Bg, onBackground = Ns.Green, onSurface = Ns.Green,
        ),
        content = content,
    )
}

/** Сканлайны поверх всего экрана, как у старого ЭЛТ-монитора. */
fun Modifier.scanlines(): Modifier = drawWithContent {
    drawContent()
    var y = 0f
    val line = Color.Black.copy(alpha = 0.22f)
    while (y < size.height) {
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += 3f
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
            delay(Random.nextLong(1800, 4200))
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
    Text(text.take(n) + if (n < text.length) "█" else "", style = style)
}

/** Кнопка в терминальном стиле: [ ТЕКСТ ]. */
@Composable
fun TermButton(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Ns.Green,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = if (enabled) color else Ns.Faint
    Box(
        modifier
            .border(1.dp, c)
            .background(c.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("[ $label ]", style = Ns.text.copy(color = c))
    }
}

/** Каркас экрана модуля: заголовок + кнопка назад. */
@Composable
fun ModuleScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("< BACK", style = Ns.text.copy(color = Ns.Dim), modifier = Modifier.clickable(onClick = onBack).padding(4.dp))
            Spacer(Modifier.width(12.dp))
            GlitchText("// $title")
        }
        Text("─".repeat(60), style = Ns.small, maxLines = 1)
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
