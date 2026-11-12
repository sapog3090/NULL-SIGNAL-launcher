package com.nullsignal.launcher.sys

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.CodingErrorAction

/**
 * SSH-клиент (JSch): живёт, пока жив процесс, — уйти с экрана можно, соединение останется.
 * Проверка ключа сервера по принципу «доверяй при первом подключении»: если отпечаток
 * сменился — подключение рвётся (это признак перехвата).
 */
object SshClient {
    data class SavedHost(val name: String, val user: String, val host: String, val port: Int) {
        val target: String get() = "$user@$host" + if (port != 22) ":$port" else ""
    }

    val lines = mutableStateListOf<String>()
    var partial by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set
    var connecting by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
        private set

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    private val main = Handler(Looper.getMainLooper())

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>78MDEc]")

    // ---------- ключи ----------

    private fun keyFile(ctx: Context) = File(ctx.filesDir, "ssh_id_rsa")
    private fun pubFile(ctx: Context) = File(ctx.filesDir, "ssh_id_rsa.pub")

    fun publicKey(ctx: Context): String? = pubFile(ctx).takeIf { it.exists() }?.readText()?.trim()

    suspend fun generateKey(ctx: Context): String = withContext(Dispatchers.IO) {
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 3072)
        kp.writePrivateKey(keyFile(ctx).absolutePath)
        val bos = ByteArrayOutputStream()
        kp.writePublicKey(bos, "nullsignal@android")
        kp.dispose()
        val pub = bos.toString("UTF-8").trim()
        pubFile(ctx).writeText(pub)
        pub
    }

    fun deleteKey(ctx: Context) { keyFile(ctx).delete(); pubFile(ctx).delete() }

    // ---------- сохранённые хосты ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ssh", 0)

    fun hosts(ctx: Context): List<SavedHost> = prefs(ctx).getStringSet("hosts", emptySet())!!.mapNotNull { s ->
        val p = s.split('|')
        if (p.size == 4) SavedHost(p[0], p[1], p[2], p[3].toIntOrNull() ?: 22) else null
    }.sortedBy { it.name.lowercase() }

    fun saveHost(ctx: Context, h: SavedHost) {
        val all = hosts(ctx).filter { !(it.host == h.host && it.user == h.user && it.port == h.port) } + h
        prefs(ctx).edit().putStringSet("hosts", all.map { "${it.name.replace("|", "/")}|${it.user}|${it.host}|${it.port}" }.toSet()).apply()
    }

    fun removeHost(ctx: Context, h: SavedHost) {
        prefs(ctx).edit().putStringSet("hosts", hosts(ctx).filter { it != h }.map { "${it.name}|${it.user}|${it.host}|${it.port}" }.toSet()).apply()
    }

    /** user@host[:port] или user@host -p port */
    fun parseTarget(s: String): Triple<String, String, Int>? {
        val t = s.trim()
        val port = Regex("-p\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val main = t.replace(Regex("-p\\s*\\d+"), "").trim()
        if ('@' !in main) return null
        val user = main.substringBefore('@')
        val hostPart = main.substringAfter('@')
        val host = hostPart.substringBefore(':')
        val p = port ?: hostPart.substringAfter(':', "").toIntOrNull() ?: 22
        if (user.isBlank() || host.isBlank()) return null
        return Triple(user, host, p)
    }

    // ---------- соединение ----------

    private fun post(block: () -> Unit) = main.post(block)

    private fun append(text: String) {
        val clean = ANSI.replace(text, "").replace("\r\n", "\n").replace("\u0007", "")
        post {
            val cur = StringBuilder(partial)
            for (ch in clean) {
                when (ch) {
                    '\n' -> { lines += cur.toString(); cur.setLength(0) }
                    '\r' -> cur.setLength(0)
                    '\b' -> if (cur.isNotEmpty()) cur.setLength(cur.length - 1)
                    else -> cur.append(ch)
                }
            }
            partial = cur.toString()
            while (lines.size > 3000) lines.removeAt(0)
        }
    }

    suspend fun connect(ctx: Context, user: String, host: String, port: Int, password: String) {
        if (connecting || connected) return
        connecting = true
        status = "подключаюсь к $user@$host:$port …"
        lines.clear(); partial = ""
        try {
            withContext(Dispatchers.IO) {
                val jsch = JSch()
                keyFile(ctx).takeIf { it.exists() }?.let { jsch.addIdentity(it.absolutePath) }
                val s = jsch.getSession(user, host, port)
                if (password.isNotEmpty()) s.setPassword(password)
                // ключ сервера проверяем сами (ниже): принимаем, а затем сверяем отпечаток
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                s.setServerAliveInterval(30_000)
                s.connect(12_000)
                val fp = s.hostKey.getFingerPrint(jsch)
                val key = "fp_$host:$port"
                val known = prefs(ctx).getString(key, null)
                if (known != null && known != fp) {
                    s.disconnect()
                    throw IllegalStateException("КЛЮЧ СЕРВЕРА ИЗМЕНИЛСЯ! было $known, стало $fp — возможен перехват. Если сервер переустанавливали — удали его из сохранённых и подключись заново.")
                }
                if (known == null) prefs(ctx).edit().putString(key, fp).apply()
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("vt100", 80, 30, 0, 0)
                val input = ch.inputStream
                out = ch.outputStream
                ch.connect(8000)
                session = s
                channel = ch
                append(if (known == null) "[новый сервер, отпечаток ключа: $fp — запомнен]\n" else "[ключ сервера совпал ✓]\n")
                Thread({
                    val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                    val buf = ByteArray(8192)
                    try {
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            append(dec.decode(java.nio.ByteBuffer.wrap(buf, 0, n)).toString())
                        }
                    } catch (e: Exception) {
                        append("\n[${e.message}]\n")
                    }
                    post { connected = false; status = "соединение закрыто" }
                }, "ssh-reader").start()
            }
            connected = true
            title = "$user@$host"
            status = null
            saveHost(ctx, SavedHost(host, user, host, port))
        } catch (e: Exception) {
            status = "ошибка: " + (e.message ?: e.javaClass.simpleName).let {
                when {
                    "Auth fail" in it || "Auth cancel" in it -> "не пустило: неверный логин/пароль или ключ не добавлен на сервер"
                    "timeout" in it.lowercase() -> "сервер не ответил (таймаут)"
                    "refused" in it.lowercase() -> "порт закрыт — SSH-сервер не запущен?"
                    else -> it
                }
            }
            disconnect()
        } finally {
            connecting = false
        }
    }

    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        runCatching { out?.apply { write(text.toByteArray()); flush() } }
    }

    suspend fun sendLine(cmd: String) = send(cmd + "\n")

    fun disconnect() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null; session = null; out = null
        connected = false
    }
}
