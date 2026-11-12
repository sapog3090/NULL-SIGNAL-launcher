package com.nullsignal.core.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Обмен файлами в своей сети Wi-Fi: телефон на время становится маленьким сайтом,
 * на который можно зайти с компьютера, ввести PIN и скачать или загрузить файлы.
 */
class DropServer(
    val pin: String,
    private val shared: () -> List<Shared>,
    /** Куда писать загруженный файл; null — отказать. Поток закрывает сервер. */
    private val receive: (name: String) -> OutputStream?,
    private val log: (String) -> Unit = {},
    /** Загрузка файла закончилась: ok = false — связь оборвалась, файл неполный. */
    private val finished: (name: String, ok: Boolean) -> Unit = { _, _ -> },
) {
    class Shared(val name: String, val size: Long, val open: () -> InputStream)

    private var server: ServerSocket? = null
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile private var fails = 0
    @Volatile private var lockedUntil = 0L

    val port: Int get() = server?.localPort ?: 0

    fun start(port: Int = 0): Int {
        val s = try { ServerSocket(port) } catch (e: Exception) { ServerSocket(0) }
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                Thread({ runCatching { c.use { handle(it); linger(it) } } }, "drop-conn").apply { isDaemon = true }.start()
            }
        }, "drop-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() { runCatching { server?.close() }; server = null }

    /**
     * Ответ уже отправлен, но клиент может ещё досылать тело запроса. Если закрыть сокет сразу,
     * он получит обрыв связи вместо ответа — поэтому дочитываем остаток (не больше 4 МБ и 2 секунд).
     */
    private fun linger(sock: Socket) {
        runCatching {
            sock.shutdownOutput()
            sock.soTimeout = 2000
            val input = sock.getInputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (total < 4 * 1024 * 1024) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        }
    }

    // ---------- HTTP ----------

    private fun readLine(input: InputStream, max: Int = 8192): String? {
        val sb = ByteArrayOutputStream()
        while (sb.size() < max) {
            val c = input.read()
            if (c < 0) return if (sb.size() == 0) null else sb.toString("UTF-8")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.write(c)
        }
        return sb.toString("UTF-8")
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>, body: ByteArray) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        (headers + mapOf("Content-Length" to body.size.toString(), "Connection" to "close", "Cache-Control" to "no-store")).forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        out.write(sb.append("\r\n").toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun page(out: OutputStream, status: String, body: String, headers: Map<String, String> = emptyMap()) {
        val html = "<!doctype html><html lang=ru><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>NULL//SIGNAL</title>" +
            "<style>body{background:#000;color:#e8e4dc;font:15px/1.5 ui-monospace,Menlo,Consolas,monospace;max-width:640px;margin:0 auto;padding:24px 16px}" +
            "h1{font-size:14px;letter-spacing:.3em;color:#e5112e;margin:0 0 24px}a{color:#e8e4dc}small,.dim{color:#8e8883}" +
            ".row{display:flex;gap:12px;padding:10px 0;border-bottom:1px solid #2a2726}.row a{flex:1;word-break:break-all}" +
            "input,button{font:inherit;background:#0e0d0d;color:#e8e4dc;border:1px solid #6f6a66;padding:10px 12px;border-radius:0}" +
            "button{border-color:#e5112e;color:#e5112e;cursor:pointer;letter-spacing:.15em}h2{font-size:11px;letter-spacing:.2em;color:#b9b4ae;margin:28px 0 8px}" +
            ".bad{color:#e5112e}</style><h1>NULL//SIGNAL · ТАЙНИК</h1>" + body + "</html>"
        respond(out, status, mapOf("Content-Type" to "text/html; charset=utf-8") + headers, html.toByteArray())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun human(n: Long): String = when {
        n >= 1 shl 30 -> "%.1f ГБ".format(n / 1073741824.0)
        n >= 1 shl 20 -> "%.1f МБ".format(n / 1048576.0)
        n >= 1024 -> "%.0f КБ".format(n / 1024.0)
        else -> "$n Б"
    }

    private fun loginPage(out: OutputStream, note: String = "", status: String = "200 OK") = page(
        out, status,
        "<p>Введи PIN с экрана телефона.</p><form method=post action=/login><input name=pin inputmode=numeric autocomplete=off autofocus placeholder=PIN> <button>ВОЙТИ</button></form>" +
            (if (note.isNotEmpty()) "<p class=bad>${esc(note)}</p>" else ""),
    )

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        val input = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        val out = sock.getOutputStream()
        val request = readLine(input) ?: return
        val parts = request.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 100) return
        }
        val authed = headers["cookie"].orEmpty().split(';').any { it.trim() == "ns=$token" }
        val who = sock.inetAddress.hostAddress ?: "?"

        when {
            method == "POST" && path == "/login" -> {
                if (System.currentTimeMillis() < lockedUntil) return loginPage(out, "Слишком много неверных попыток. Подожди минуту.", "429 Too Many Requests")
                val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
                val body = ByteArray(len)
                var got = 0
                while (got < len) { val n = input.read(body, got, len - got); if (n < 0) break; got += n }
                val form = String(body, 0, got, Charsets.UTF_8).split('&').associate { it.substringBefore('=') to runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("") }
                if (form["pin"]?.trim() == pin) {
                    fails = 0
                    log("вход: $who")
                    respond(out, "303 See Other", mapOf("Location" to "/", "Set-Cookie" to "ns=$token; HttpOnly; SameSite=Strict"), ByteArray(0))
                } else {
                    if (++fails >= 5) { lockedUntil = System.currentTimeMillis() + 60_000; fails = 0; log("5 неверных PIN подряд от $who — вход закрыт на минуту") }
                    loginPage(out, "Неверный PIN.", "403 Forbidden")
                }
            }
            !authed -> if (path == "/") loginPage(out) else respond(out, "303 See Other", mapOf("Location" to "/"), ByteArray(0))
            method == "GET" && path == "/" -> {
                val list = shared()
                val sb = StringBuilder("<h2>СКАЧАТЬ С ТЕЛЕФОНА</h2>")
                if (list.isEmpty()) sb.append("<p class=dim>На телефоне пока ничего не выбрано.</p>")
                list.forEachIndexed { i, f -> sb.append("<div class=row><a href=/get/$i>${esc(f.name)}</a><small>${if (f.size >= 0) human(f.size) else ""}</small></div>") }
                sb.append("<h2>ОТПРАВИТЬ НА ТЕЛЕФОН</h2><form method=post action=/put enctype=multipart/form-data><input type=file name=f multiple required> <button>ОТПРАВИТЬ</button></form>")
                if (parts[1].contains("ok=")) sb.append("<p>Принято файлов: ${esc(parts[1].substringAfter("ok="))}</p>")
                page(out, "200 OK", sb.toString())
            }
            method == "GET" && path.startsWith("/get/") -> {
                val f = shared().getOrNull(path.removePrefix("/get/").toIntOrNull() ?: -1) ?: return page(out, "404 Not Found", "<p>Такого файла нет.</p>")
                // размер известен не всегда: тогда конец файла — это закрытие соединения
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" + (if (f.size >= 0) "Content-Length: ${f.size}\r\n" else "") +
                    "Content-Disposition: attachment; filename*=UTF-8''${URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray())
                f.open().use { it.copyTo(out, 64 * 1024) }
                out.flush()
                log("скачан: ${f.name} → $who")
            }
            method == "POST" && path == "/put" -> {
                val boundary = Regex("boundary=\"?([^\";]+)\"?").find(headers["content-type"].orEmpty())?.groupValues?.get(1)
                    ?: return page(out, "400 Bad Request", "<p>Не получилось разобрать загрузку.</p>")
                // читаем ровно столько, сколько обещал браузер: иначе на битом запросе можно ждать вечно
                val length = headers["content-length"]?.toLongOrNull()
                    ?: return page(out, "411 Length Required", "<p>Браузер не сообщил размер загрузки.</p>")
                sock.soTimeout = 120_000
                val n = upload(Limited(input, length), boundary, who)
                respond(out, "303 See Other", mapOf("Location" to "/?ok=$n"), ByteArray(0))
            }
            else -> page(out, "404 Not Found", "<p>Здесь ничего нет. <a href=/>На главную</a></p>")
        }
    }

    // ---------- multipart ----------

    private class Limited(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val c = src.read()
            if (c >= 0) left--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun failure(d: ByteArray): IntArray {
        val f = IntArray(d.size)
        var k = 0
        for (i in 1 until d.size) {
            while (k > 0 && d[i] != d[k]) k = f[k - 1]
            if (d[i] == d[k]) k++
            f[i] = k
        }
        return f
    }

    /** Копирует поток до разделителя (сам разделитель съедается). false — поток кончился раньше. */
    internal fun copyUntil(input: InputStream, d: ByteArray, out: OutputStream?, limit: Long = Long.MAX_VALUE): Boolean {
        val fail = failure(d)
        var k = 0
        var written = 0L
        while (true) {
            val c = input.read()
            if (c < 0) { out?.write(d, 0, k); return false }
            val b = c.toByte()
            while (k > 0 && b != d[k]) {
                val nk = fail[k - 1]
                out?.write(d, 0, k - nk)
                written += k - nk
                k = nk
            }
            if (b == d[k]) k++ else { out?.write(c); written++ }
            if (k == d.size) return true
            if (written > limit) throw IllegalStateException("файл слишком большой")
        }
    }

    private fun upload(input: InputStream, boundary: String, who: String): Int {
        val delimiter = ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)
        // до первой части: строка «--boundary»
        while (true) {
            val line = readLine(input) ?: return 0
            if (line == "--$boundary") break
        }
        var saved = 0
        while (true) {
            var fileName: String? = null
            while (true) {
                val line = readLine(input) ?: return saved
                if (line.isEmpty()) break
                if (line.startsWith("content-disposition", ignoreCase = true)) {
                    fileName = Regex("filename=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                }
            }
            val clean = fileName?.let { safeName(it) }
            val sink = if (clean.isNullOrEmpty()) null else receive(clean)
            val ok = try { copyUntil(input, delimiter, sink, 8L * 1024 * 1024 * 1024) } finally { runCatching { sink?.close() } }
            if (sink != null) {
                finished(clean!!, ok)
                if (ok) { saved++; log("получен: $clean ← $who") } else log("загрузка оборвалась: $clean")
            }
            if (!ok) return saved
            val a = input.read()
            val b = input.read()
            if (a == '-'.code && b == '-'.code) return saved
        }
    }

    companion object {
        /** Имя загруженного файла без путей и управляющих символов. */
        fun safeName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.code >= 32 && it !in "<>:\"|?*" && it != '‮' }.trim().trim('.').take(120)

        fun newPin(random: java.util.Random = SecureRandom()): String = "%06d".format(random.nextInt(1_000_000))
    }
}
