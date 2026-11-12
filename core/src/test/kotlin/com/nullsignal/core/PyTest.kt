package com.nullsignal.core

import com.nullsignal.core.py.MiniPy
import com.nullsignal.core.py.PyProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PyTest {
    private fun run(src: String, setup: (MiniPy) -> Unit = {}): String {
        val sb = StringBuilder()
        val py = MiniPy { sb.append(it) }
        setup(py)
        val r = py.run(src.trimIndent())
        assertTrue(r.problem?.text() ?: r.halted ?: "", r.ok)
        return sb.toString().trimEnd('\n')
    }

    private fun problem(src: String): PyProblem {
        val r = MiniPy().run(src.trimIndent())
        assertNotNull("ожидалась ошибка", r.problem)
        return r.problem!!
    }

    @Test fun arithmetic() {
        assertEquals("7 3 10 2.5 2 1 25", run("x = 5\ny = 2\nprint(x + y, x - y, x * y, x / y, x // y, x % y, x ** y)"))
        assertEquals(
            "3 -4 -2 2 1267650600228229401496703205376 0.5 2.5 3 31 42 1.5 2 4 2.67",
            run("""print(7 // 2, -7 // 2, 7 % -3, -7 % 3, 2 ** 100, 2 ** -1, 10 / 4, int(3.9), int("0x1f", 16), int("42"), float("1.5"), round(2.5), round(3.5), round(2.675, 2))"""),
        )
        assertEquals("True False True 2 x True True True True", run("""print(1 < 2 < 3, 1 < 2 > 5, not 0, 1 and 2, 0 or "x", None is None, [] == [], "a" < "b", [1, 2] < [1, 3])"""))
        assertEquals("1.0 0.30000000000000004 1e+20 1e-07 6.0 1e+16 0.3333333333333333", run("print(str(1.0), 0.1 + 0.2, 1e20, 1e-7, 3.0 * 2, 10 ** 16 * 1.0, 1 / 3)"))
        assertEquals("(3, 2) 1024 1 3 0xff 0b101 0o10 65 Я False True 2", run("""print(divmod(17, 5), pow(2, 10), pow(3, 4, 5), abs(-3), hex(255), bin(5), oct(8), ord("A"), chr(1071), bool(""), bool("0"), int(True) + 1)"""))
        assertEquals("-4 6 -6 1 8 3 255", run("print(-2 ** 2, 2 + 2 * 2, ~5, 7 & 3 ^ 2, 1 << 3, 13 >> 2, 0xff)"))
        assertEquals("9223372036854775808 -9223372036854775809 True", run("a = 9223372036854775807\nprint(a + 1, -a - 2, a * a > a)"))
    }

    @Test fun strings() {
        assertEquals(
            "ghost:80\n00080 3.14 ff 0xff 'ghost'    ghost|ghost   |  ghost  |\n['a', 'b', 'c'] x-y\nGHOST g t ho tsohg",
            run(
                """
                name = "ghost"
                port = 80
                print(f"{name}:{port}")
                print(f"{port:05d} {3.14159:.2f} {255:x} {255:#x} {name!r} {name:>8}|{name:<8}|{name:^9}|")
                print("a,b,c".split(","), "-".join(["x", "y"]))
                print(name.upper(), name[0], name[-1], name[1:3], name[::-1])
                """,
            ),
        )
        assertEquals(
            "hello, world HeLLo, World ['a', 'b-c'] 2 -1 axbxc **ab** 00042 -0042 True A B True True ['a', 'b'] ('x', '=', '1')",
            run(
                """
                s = "  Hello, World  "
                print(s.strip().lower(), s.strip().replace("l", "L", 2), "a-b-c".split("-", 1), "abc".find("c"), "abc".find("z"), "x".join("abc"), "ab".center(6, "*"), "42".zfill(5), "-42".zfill(5), "Hello".startswith(("He", "x")), "a b".title(), "abc".isalpha(), "12".isdigit(), "a\nb".splitlines(), "x=1".partition("="))
                """,
            ),
        )
        assertEquals("list has 3 items, 12.3% (00042) ff", run("""print("%s has %d items, %.1f%% (%05d) %x" % ("list", 3, 12.345, 42, 255))"""))
        assertEquals("1 + 2 = 3 aba    7", run("""print("{} + {} = {}".format(1, 2, 3), "{0}{1}{0}".format("a", "b"), "{n:>4}".format(n=7))"""))
        assertEquals("\"it's\" 'say \"hi\"' [1, 'a', None, True, 2.5] (1,) {'k': [1]} {1}", run("""print(repr("it's"), repr('say "hi"'), [1, "a", None, True, 2.5], (1,), {"k": [1]}, {1})"""))
        assertEquals("1,234,567 50.0% +5 1.5e+03 abab 6 3", run("""print(f"{1234567:,} {0.5:.1%} {5:+d} {1500.0:.1e}", "ab" * 2, len("привет"), "banana".count("an") + 1)"""))
        assertEquals("{x} 7 [1, 2] 12", run("""x = 7; d = {"k": [1, 2]}; print(f"{{x}} {x} {d['k']} {x + 5}")"""))
    }

    @Test fun controlFlow() {
        assertEquals(
            "16\ndone 3",
            run(
                """
                total = 0
                for i in range(1, 11):
                    if i % 2 == 0:
                        continue
                    if i > 7:
                        break
                    total += i
                print(total)
                n = 0
                while n < 3:
                    n += 1
                else:
                    print("done", n)
                """,
            ),
        )
        assertEquals("big\nmid\nsmall", run("for x in [50, 5, 0]:\n    if x > 10:\n        print('big')\n    elif x > 1:\n        print('mid')\n    else:\n        print('small')"))
        assertEquals("1 2 3", run("x = 1; y = 2; z = 3\nif x: print(x, y, z)"))
    }

    @Test fun functions() {
        assertEquals(
            "2432902008176640000 15511210043330985984000000\nhi a 0 [] yo b 2 ['a', 'z']\n3\n12\n6 12 7\n[10, 11, 12]",
            run(
                """
                def fact(n):
                    return 1 if n <= 1 else n * fact(n - 1)
                def greet(name, greeting="hi", *rest, **opts):
                    return f"{greeting} {name} {len(rest)} {sorted(opts)}"
                print(fact(20), fact(25))
                print(greet("a"), greet("b", "yo", 1, 2, z=1, a=2))
                def counter():
                    c = 0
                    def inc():
                        nonlocal c
                        c += 1
                        return c
                    return inc
                f = counter()
                f(); f()
                print(f())
                total = 0
                def add(n: int) -> None:
                    global total
                    total += n
                add(5); add(7)
                print(total)
                mul = lambda a, b=2: a * b
                print(mul(3), mul(3, 4), (lambda: 7)())
                fs = [lambda x, i=i: x + i for i in range(3)]
                print([f(10) for f in fs])
                """,
            ),
        )
        assertEquals("None", run("def f():\n    pass\nprint(f())"))
        assertEquals("55", run("def fib(n):\n    return n if n < 2 else fib(n - 1) + fib(n - 2)\nprint(fib(10))"))
    }

    @Test fun collections() {
        assertEquals(
            "[5, 3, 2, 1] 4 11 1 5 1\nx 1\ny 2\n0 True ['x', 'y']\n3 True [1, 2, 3, 9]\n[1, 9, 25] {0: 0, 1: 2, 2: 4} (1, 2, 3)\n2 1\n1 [2, 3]",
            run(
                """
                a = [3, 1, 2]
                a.append(5); a.sort(reverse=True)
                print(a, len(a), sum(a), min(a), max(a), a.index(3))
                d = {"x": 1}
                d["y"] = 2
                for k, v in d.items():
                    print(k, v)
                print(d.get("z", 0), "x" in d, list(d.keys()))
                s = {1, 2, 2, 3}
                print(len(s), 2 in s, sorted(s | {9}))
                sq = [i * i for i in range(6) if i % 2]
                print(sq, {i: i * 2 for i in range(3)}, (1, 2) + (3,))
                t = 1, 2
                x, y = t
                x, y = y, x
                print(x, y)
                first, *rest = [1, 2, 3]
                print(first, rest)
                """,
            ),
        )
        assertEquals("['A', 'b', 'c'] [3, 2, 1] [('b', 1), ('a', 2)]", run("""print(sorted(["b", "A", "c"], key=str.lower), sorted([3, 1, 2], reverse=True), sorted({"b": 1, "a": 2}.items(), key=lambda kv: kv[1]))"""))
        assertEquals(
            "the 3",
            run("words = 'the quick brown the lazy the'.split()\ncounts = {}\nfor w in words:\n    counts[w] = counts.get(w, 0) + 1\nprint(max(counts, key=counts.get), counts['the'])"),
        )
        assertEquals(
            "[('a', 1), ('b', 2)] [(1, 'x'), (2, 'y')] ['1', '2'] True True [3, 2, 1] [10, 7, 4, 1] 4",
            run("""print(list(zip("ab", [1, 2])), list(enumerate("xy", 1)), list(map(str, [1, 2])), any(x > 2 for x in [1, 3]), all([]), list(reversed([1, 2, 3])), list(range(10, 0, -3)), len(range(0, 10, 3)))"""),
        )
        assertEquals("[2, 9, 7] True\n{} 0", run("a = [1, 2, 3, 4]\ndel a[0]\na[1:3] = [9]\nb = a\nb += [7]\nprint(a, a is b)\nm = {'a': 1}\ndel m['a']\nprint(m, len(m))"))
        assertEquals("[[1, 2], [3, 4]] 4 [1, 3] [(0, 'a'), (1, 'b')]", run("g = [[1, 2], [3, 4]]\nprint(g, g[1][1], [r[0] for r in g], [(i, c) for i in range(2) for c in 'ab' if (i == 0) == (c == 'a')])"))
    }

    @Test fun exceptions() {
        assertEquals(
            "fin\nfin\n5.0 div0\ncaught boom\nTrue\nelse ok\nKeyError",
            run(
                """
                def safe(x):
                    try:
                        return 10 / x
                    except ZeroDivisionError:
                        return "div0"
                    finally:
                        print("fin")
                print(safe(2), safe(0))
                try:
                    raise ValueError("boom")
                except (TypeError, ValueError) as e:
                    print("caught", e)
                try:
                    [1][5]
                except Exception as e:
                    print(str(e) != "")
                try:
                    x = 1
                except:
                    print("no")
                else:
                    print("else ok")
                try:
                    {}["k"]
                except LookupError as e:
                    print(type(e) == KeyError and "KeyError")
                """,
            ),
        )
    }

    @Test fun modules() {
        assertEquals(
            "5d41402abc4b2a76b9719d911017c592\nba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\naGk= hi\nHi 4869 b'Hi' 12",
            run(
                """
                import hashlib, base64
                print(hashlib.md5(b"hello").hexdigest())
                print(hashlib.sha256("abc".encode()).hexdigest())
                print(base64.b64encode(b"hi").decode(), base64.b64decode("aGk=").decode())
                print(bytes.fromhex("4869").decode(), b"Hi".hex(), bytes([72, 105]), len("привет".encode()))
                """,
            ),
        )
        assertEquals(
            "8 aa\n[(1, 2), (1, 3), (2, 1), (2, 3), (3, 1), (3, 2)]\n[('a', 'b'), ('a', 'c'), ('a', 'd')] 6\n[0, 2, 4] [1, 3, 6]",
            run(
                """
                import itertools
                from itertools import combinations as comb
                print(len(list(itertools.product("01", repeat=3))), "".join(next(iter(itertools.product("ab", repeat=2)))))
                print(list(itertools.permutations([1, 2, 3], 2)))
                print(list(comb("abcd", 2))[:3], len(list(comb("abcd", 2))))
                print(list(itertools.islice(itertools.count(0, 2), 3)), list(itertools.accumulate([1, 2, 3])))
                """,
            ),
        )
        assertEquals(
            "80 True None {\"a\": [1, 2], \"b\": \"\\u044f\"} [1, \"x\"]\n['10.0.0.5', '192.168.1.1'] a b c site ['a', 'b', 'c']",
            run(
                """
                import json, re
                data = json.loads('{"ip": "10.0.0.1", "ports": [22, 80], "up": true, "x": null}')
                print(data["ports"][1], data["up"], data["x"], json.dumps({"a": [1, 2], "b": "я"}), json.dumps([1, "x"], ensure_ascii=False))
                print(re.findall(r"\d+\.\d+\.\d+\.\d+", "from 10.0.0.5 to 192.168.1.1"), re.sub(r"\s+", " ", "a   b \n c"), re.search(r"(\w+)@(\w+)", "mail bob@site now").group(2), re.split(r"[,;]", "a,b;c"))
                """,
            ),
        )
        assertEquals(
            "b'#\"' hi 256 b'\\x01\\x02' [65, 66]",
            run("key = b'K'\ndata = bytes(c ^ key[0] for c in b'hi')\nprint(data, bytes(a ^ b for a, b in zip(data, b'KK')).decode(), int.from_bytes(b'\\x01\\x00', 'big'), (258).to_bytes(2, 'big'), list(b'AB'))"),
        )
        assertEquals("4.0 2 3 True 6 120 0.0 4", run("import math\nprint(math.sqrt(16), math.floor(2.7), math.ceil(2.1), math.pi > 3.14, math.gcd(12, 18), math.factorial(5), round(math.sin(0), 3), math.isqrt(17))"))
        assertEquals("True True 3 [('a', 2)]", run("import random, collections\nrandom.seed(1)\nx = random.randint(1, 6)\nprint(1 <= x <= 6, random.choice('ab') in 'ab', len(random.sample(range(10), 3)), collections.Counter('aab').most_common(1))"))
        assertEquals("True", run("import time\nt0 = time.perf_counter()\ntime.sleep(5)\nprint(time.perf_counter() - t0 >= 5)") { it.realSleep = false })
    }

    @Test fun beginnerHints() {
        var p = problem("x = 10\nIf x == 10:\n    print(1)")
        assertEquals("SyntaxError", p.type)
        assertEquals(2, p.line)
        assertTrue(p.hint!!.contains("'if'"))

        p = problem("x = 5\nif x = 5:\n    print(x)")
        assertTrue(p.message.contains("=="))

        p = problem("print \"hi\"")
        assertTrue(p.hint!!.contains("print(...)"))

        p = problem("x = 5\nif x == 5\n    print(x)")
        assertTrue(p.message.contains("двоеточ"))
        assertEquals(2, p.line)

        p = problem("if True:\nprint(1)")
        assertTrue(p.message.contains("отступ"))

        p = problem("name = 5\nprint(Name)")
        assertEquals("NameError", p.type)
        assertTrue(p.hint!!.contains("'name'"))

        p = problem("print(true)")
        assertTrue(p.hint!!.contains("True"))

        p = problem("print(\"a\" + 1)")
        assertEquals("TypeError", p.type)
        assertTrue(p.hint!!.contains("str("))

        p = problem("count = 0\ndef inc():\n    count += 1\ninc()")
        assertEquals("UnboundLocalError", p.type)
        assertTrue(p.hint!!.contains("global count"))
        assertEquals(3, p.line)
        assertEquals(4, p.calledFrom)

        p = problem("x = [1, 2, 3]\nprint(x[5])")
        assertEquals("IndexError", p.type)
        assertEquals(2, p.line)

        p = problem("for i in 5:\n    print(i)")
        assertTrue(p.hint!!.contains("range(5)"))

        p = problem("a = [1]\na.push(2)")
        assertEquals("AttributeError", p.type)
        assertTrue(p.hint!!.contains("append"))

        p = problem("print = 5\nprint(1)")
        assertTrue(p.hint!!.contains("затёрло"))

        p = problem("x = 1\nelse:\n    pass")
        assertTrue(p.message.contains("else"))

        p = problem("s = \"abc")
        assertTrue(p.message.contains("кавыч"))

        p = problem("print((1 + 2)")
        assertTrue(p.message.contains("скобка"))

        p = problem("import requests")
        assertEquals("ModuleNotFoundError", p.type)

        p = problem("def f():\n    return f()\nf()")
        assertEquals("RecursionError", p.type)

        p = problem("x = int(\"3.7\")")
        assertTrue(p.hint!!.contains("float"))

        p = problem("d = {}\nprint(d[\"k\"])")
        assertEquals("KeyError", p.type)

        assertNull(MiniPy().check("x = 1\nif x:\n    print(x)\n"))
        assertNotNull(MiniPy().check("x = = 1"))
        assertTrue(p.text().startsWith("Строка 2 — KeyError"))
    }

    @Test fun limits() {
        val py = MiniPy()
        py.maxSteps = 200_000
        val r = py.run("while True:\n    pass")
        assertTrue(r.halted!!.contains("лимит шагов"))

        val out = StringBuilder()
        val py2 = MiniPy { out.append(it) }
        val r2 = py2.run("s = 0\nfor i in range(2000000):\n    s += i\nprint(s)")
        assertTrue(r2.problem?.text() ?: r2.halted ?: "", r2.ok)
        assertEquals("1999999000000", out.toString().trim())

        val py3 = MiniPy()
        py3.maxMillis = 300
        py3.maxSteps = Long.MAX_VALUE
        assertTrue(py3.run("import itertools\nfor x in itertools.count():\n    pass").halted!!.contains("время"))

        assertEquals("MemoryError", MiniPy().run("x = [0] * 10 ** 9").problem!!.type)
        assertTrue(MiniPy().run("print(1)\nexit()\nprint(2)").ok)
    }

    @Test fun hostApi() {
        val out = StringBuilder()
        val py = MiniPy { out.append(it) }
        var calls = 0
        py.def("probe") { a -> calls++; (a[0] as Long).toInt() * 2 }
        py.set("TARGET", listOf("a", "b"))
        py.module("lab", mapOf("LEVEL" to 3, "name" to py.fn("name") { "ok" }))
        val r = py.run("import lab\nprint(probe(21), TARGET, lab.LEVEL, lab.name())\nresult = probe(1)")
        assertTrue(r.problem?.text() ?: "", r.ok)
        assertEquals("42 ['a', 'b'] 3 ok", out.toString().trim())
        assertEquals(2, calls)
        assertEquals(2L, py.get("result"))
    }
}
