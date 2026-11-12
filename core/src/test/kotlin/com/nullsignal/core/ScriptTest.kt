package com.nullsignal.core

import com.nullsignal.core.academy.Practice
import com.nullsignal.core.breach.Breach
import com.nullsignal.core.breach.BreachScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScriptTest {
    @Test fun solverTemplateWinsMostGames() {
        val solver = BreachScript.TEMPLATES[0].code
        var wins = 0
        val games = 30
        var worst = 0L
        for (seed in 1..games) {
            val game = Breach(random = Random(seed))
            val s = BreachScript(game, {})
            s.py.random.setSeed(seed.toLong())
            val r = s.run(solver)
            assertTrue(r.problem?.text() ?: r.halted ?: "", r.ok)
            if (game.state == Breach.State.WON) wins++
            worst = maxOf(worst, r.millis)
        }
        println("solver: $wins/$games побед, самый долгий запуск $worst мс")
        assertTrue("побед $wins из $games", wins >= games * 85 / 100)
    }

    @Test fun otherTemplatesRunCleanly() {
        for (t in BreachScript.TEMPLATES.drop(1)) {
            val out = StringBuilder()
            val game = Breach(secret = "9F3A")
            val r = BreachScript(game, { out.append(it) }).run(t.code)
            assertTrue(t.title + ": " + (r.problem?.text() ?: r.halted ?: ""), r.ok)
            assertTrue(out.isNotEmpty())
        }
        // перебор подряд тратит все попытки и не падает
        val game = Breach(secret = "9F3A")
        BreachScript(game, {}).run(BreachScript.TEMPLATES[2].code)
        assertEquals(Breach.State.LOST, game.state)
    }

    @Test fun scriptApiErrors() {
        val game = Breach(secret = "1234", maxAttempts = 2)
        val seen = ArrayList<Breach.Attempt>()
        val s = BreachScript(game, {}, { seen += it })
        var r = s.run("print(guess('12'))")
        assertEquals("ValueError", r.problem!!.type)
        r = s.run("a = guess('1243')\nb = score('1234', '4321')\nc = history()\nd = attempts_left()")
        assertTrue(r.problem?.text() ?: "", r.ok)
        assertEquals("(2, 2)", com.nullsignal.core.py.pyRepr(s.py.get("a")!!))
        assertEquals("(0, 4)", com.nullsignal.core.py.pyRepr(s.py.get("b")!!))
        assertEquals("[('1243', 2, 2)]", com.nullsignal.core.py.pyRepr(s.py.get("c")!!))
        assertEquals(1L, s.py.get("d"))
        assertEquals(1, seen.size)
        r = s.run("guess('1234')\nguess('1234')")
        assertEquals("RuntimeError", r.problem!!.type)
        assertEquals(Breach.State.WON, game.state)
    }

    @Test fun analysisNarrowsDown() {
        val g = Breach(secret = "A1B2")
        var a = g.analyze()!!
        assertEquals(65536, a.remaining)
        assertTrue(a.impossible.isEmpty())
        g.guess("0000")
        a = g.analyze()!!
        assertEquals(15 * 15 * 15 * 15, a.remaining)
        assertEquals(setOf('0'), a.impossible)
        g.guess("A1C3")
        g.guess("B2A1")
        a = g.analyze()!!
        assertTrue(a.remaining in 1..2000)
        assertTrue('A' in a.byPosition[0])
        assertFalse('0' in a.byPosition[2])
    }

    @Test fun practiceSolutionsPass() {
        assertEquals(Practice.tasks.size, Practice.tasks.map { it.id }.toSet().size)
        for (t in Practice.tasks) {
            val ok = Practice.check(t, t.solution)
            assertTrue(t.id + ": " + (ok.problem?.text() ?: ok.lines.filter { !it.ok }.joinToString { it.text }), ok.passed)
            val starter = Practice.check(t, t.starter)
            assertFalse(t.id + ": заготовка не должна проходить проверку", starter.passed)
            assertTrue(t.id + ": заготовка должна запускаться без ошибок: " + starter.problem?.text(), starter.problem == null)
        }
    }

    @Test fun practiceExplainsMistakes() {
        val t = Practice.tasks.first { it.id == "p2" }
        var r = Practice.check(t, "def total(nums):\n    s = 0\n    for n in nums:\n        s += n\n")
        assertFalse(r.passed)
        assertTrue(r.lines.first { !it.ok }.text.contains("return"))
        r = Practice.check(t, "def summ(nums):\n    return 0\n")
        assertTrue(r.lines.first { !it.ok }.text.contains("как в условии"))
        r = Practice.check(t, "def total(nums)\n    return 0\n")
        assertNotNull(r.problem)
        r = Practice.check(t, "def total(nums):\n    while True:\n        pass\n")
        assertFalse(r.passed)
    }
}

class PyExamplesTest {
    @Test fun allExamplesRun() {
        for (e in com.nullsignal.core.academy.PyExamples.list) {
            val out = StringBuilder()
            val py = com.nullsignal.core.py.MiniPy { out.append(it) }
            py.realSleep = false
            val r = py.run(e.code)
            assertTrue(e.title + ": " + (r.problem?.text() ?: r.halted ?: ""), r.ok)
            assertTrue(e.title + ": пустой вывод", out.isNotBlank())
            assertTrue(e.title, com.nullsignal.core.py.MiniPy.syntax(e.code) == null)
        }
        val pw = com.nullsignal.core.academy.PyExamples.list[1].code
        assertTrue(pw.contains("\"!@#\$%&*\""))
        val out = StringBuilder()
        com.nullsignal.core.py.MiniPy { out.append(it) }.run(com.nullsignal.core.academy.PyExamples.list[4].code)
        assertTrue(out.toString(), out.contains("192.168.1.0") && out.contains("255.255.255.0") && out.contains("254"))
    }
}

class TerminalPythonTest {
    private val env = object : com.nullsignal.core.terminal.TerminalEnv {
        override val operator = "op1"
        override val device = "test"
        override val ip: String? = null
        override val objective = ""
        override val followers = 0
        override val apps = emptyList<String>()
        override fun now() = "now"
    }

    @Test fun pythonOneLiners() {
        val t = com.nullsignal.core.terminal.Terminal(env)
        assertEquals(listOf("1024"), t.execute("py 2 ** 10").lines)
        assertEquals(listOf("ok"), t.execute("py x = 21").lines)
        assertEquals(listOf("42"), t.execute("python x * 2").lines)
        assertEquals(listOf("'0xff'"), t.execute("py hex(255)").lines)
        assertEquals(listOf("привет"), t.execute("py print(\"привет\")").lines)
        assertTrue(t.execute("py 1/0").lines.first().contains("ZeroDivisionError"))
        assertTrue(t.execute("py while True: pass").lines.last().startsWith("остановлено"))
        assertEquals(com.nullsignal.core.terminal.Action.Open(com.nullsignal.core.terminal.Module.PYTHON), t.execute("py").action)
        assertEquals(com.nullsignal.core.terminal.Action.Open(com.nullsignal.core.terminal.Module.LANCHAT), t.execute("chat").action)
    }
}
