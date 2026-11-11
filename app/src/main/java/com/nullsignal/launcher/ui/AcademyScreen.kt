package com.nullsignal.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nullsignal.core.academy.Academy
import com.nullsignal.core.academy.Lesson
import com.nullsignal.core.academy.Task
import com.nullsignal.launcher.LauncherViewModel

@Composable
fun AcademyScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    var open by remember { mutableStateOf<Lesson?>(null) }
    val lesson = open
    if (lesson != null) {
        BackHandler { open = null }
        LessonView(lesson, onExit = { open = null }, onDone = { vm.completeLesson(lesson) })
        return
    }
    ModuleScreen("ACADEMY", onBack) {
        Text("курс Килобайта: Python для хакеров. от print до своего порт-сканера.", style = Ns.small)
        Text(
            "пройдено: ${vm.lessonsDone.size} / ${Academy.lessons.size}",
            style = Ns.text.copy(color = Ns.Amber),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(Academy.lessons) { i, l ->
                val done = l.id in vm.lessonsDone
                val unlocked = Academy.unlocked(i, vm.lessonsDone)
                val color = when {
                    done -> Ns.Green
                    unlocked -> Ns.Cyan
                    else -> Ns.Faint
                }
                Text(
                    "${if (done) "[✓]" else if (unlocked) "[ ]" else "[×]"} ${(i + 1).toString().padStart(2, '0')}  ${l.title}" +
                        if (unlocked && !done) "   +${l.reward}" else "",
                    style = Ns.text.copy(color = color),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, color.copy(alpha = 0.5f))
                        .clickable(enabled = unlocked) { open = l }
                        .padding(10.dp),
                )
            }
            item {
                Text(
                    "совет: код из уроков можно запускать на телефоне в Pydroid 3 или Termux.",
                    style = Ns.small,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Text(
        code,
        style = Ns.text.copy(color = Ns.Cyan),
        softWrap = false,
        modifier = Modifier
            .fillMaxWidth()
            .background(Ns.Panel)
            .border(1.dp, Ns.Faint)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    )
}

@Composable
private fun LessonView(lesson: Lesson, onExit: () -> Unit, onDone: () -> Unit) {
    // шаг -1 — теория, 0..n-1 — задания, n — финиш
    var step by remember { mutableIntStateOf(-1) }
    var answered by remember { mutableStateOf<Boolean?>(null) }
    var picked by remember { mutableIntStateOf(-1) }
    var text by remember { mutableStateOf("") }
    var mistakes by remember { mutableIntStateOf(0) }

    fun next() {
        step++
        answered = null; picked = -1; text = ""
        if (step == lesson.tasks.size) onDone()
    }

    ModuleScreen(lesson.title.uppercase(), onExit) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            when {
                step < 0 -> {
                    Text("<КИЛОБАЙТ>", style = Ns.text.copy(color = Ns.Cyan))
                    Text(lesson.theory, style = Ns.text, modifier = Modifier.padding(vertical = 8.dp))
                    Text("пример:", style = Ns.small)
                    CodeBlock(lesson.example)
                    Spacer(Modifier.height(16.dp))
                    TermButton("К ЗАДАНИЯМ (${lesson.tasks.size})", Modifier.fillMaxWidth()) { next() }
                }
                step < lesson.tasks.size -> {
                    val task = lesson.tasks[step]
                    Text("задание ${step + 1}/${lesson.tasks.size}", style = Ns.small)
                    Text(task.question, style = Ns.text, modifier = Modifier.padding(vertical = 8.dp))
                    task.code?.let { CodeBlock(it) }
                    Spacer(Modifier.height(10.dp))
                    when (task) {
                        is Task.Choice -> task.options.forEachIndexed { i, opt ->
                            val c = when {
                                answered != null && i == task.correct -> Ns.Green
                                answered == false && i == picked -> Ns.Red
                                else -> Ns.Dim
                            }
                            Text(
                                "${'A' + i}) $opt",
                                style = Ns.text.copy(color = c),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .border(1.dp, c)
                                    .clickable(enabled = answered == null) {
                                        picked = i
                                        answered = Academy.check(task, i.toString())
                                        if (answered == false) mistakes++
                                    }
                                    .padding(10.dp),
                            )
                        }
                        is Task.Input -> {
                            fun submit() {
                                if (text.isBlank() || answered != null) return
                                answered = Academy.check(task, text)
                                if (answered == false) mistakes++
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = { if (answered == null) text = it },
                                singleLine = true,
                                textStyle = Ns.text,
                                cursorBrush = SolidColor(Ns.Green),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.fillMaxWidth().border(1.dp, Ns.Dim).padding(10.dp),
                                decorationBox = { inner ->
                                    if (text.isEmpty()) Text("ответ…", style = Ns.text.copy(color = Ns.Faint))
                                    inner()
                                },
                            )
                            if (answered == null) {
                                Spacer(Modifier.height(8.dp))
                                TermButton("ПРОВЕРИТЬ", Modifier.fillMaxWidth(), enabled = text.isNotBlank()) { submit() }
                            } else if (answered == false) {
                                Text("правильно: ${task.answers.first()}", style = Ns.text.copy(color = Ns.Green), modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    answered?.let { ok ->
                        Text(
                            (if (ok) "✓ ВЕРНО. " else "✗ МИМО. ") + task.explain,
                            style = Ns.text.copy(color = if (ok) Ns.Green else Ns.Amber),
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                        TermButton(if (step == lesson.tasks.lastIndex) "ЗАВЕРШИТЬ" else "ДАЛЕЕ", Modifier.fillMaxWidth()) { next() }
                    }
                }
                else -> {
                    GlitchText("УРОК ПРОЙДЕН")
                    Text(
                        "+${lesson.reward} узлов сети. ошибок: $mistakes",
                        style = Ns.text.copy(color = Ns.Amber),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(
                        if (mistakes == 0) "<КИЛОБАЙТ> чисто. ни одной ошибки, уважаю" else "<КИЛОБАЙТ> ошибки — это нормально, так и учатся. дальше будет интереснее",
                        style = Ns.text.copy(color = Ns.Cyan),
                    )
                    Spacer(Modifier.height(16.dp))
                    TermButton("К СПИСКУ УРОКОВ", Modifier.fillMaxWidth(), onClick = onExit)
                }
            }
        }
    }
}
