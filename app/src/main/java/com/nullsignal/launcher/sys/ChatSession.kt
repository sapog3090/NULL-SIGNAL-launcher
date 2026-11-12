package com.nullsignal.launcher.sys

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nullsignal.core.net.ChatNode
import com.nullsignal.core.net.NtfyRelay

/**
 * Комната шифрочата живёт отдельно от экрана: можно уйти в терминал или в другой модуль,
 * а связь останется. Новые сообщения в это время копятся в счётчике и всплывают уведомлением.
 */
object ChatSession {
    private val main = Handler(Looper.getMainLooper())

    var node by mutableStateOf<ChatNode?>(null)
        private set
    val messages = mutableStateListOf<ChatNode.Message>()
    val delivered = mutableStateMapOf<Long, Boolean>()
    var peers by mutableStateOf<List<ChatNode.Peer>>(emptyList())
        private set
    var status by mutableStateOf(ChatNode.Status())
        private set
    var joinedAt by mutableLongStateOf(0L)
        private set
    var lan by mutableStateOf(true)
        private set
    var far by mutableStateOf(false)
        private set
    /** Сколько чужих сообщений пришло, пока экран чата был закрыт. */
    var unread by mutableIntStateOf(0)
        private set

    /** Экран чата сейчас на виду. */
    @Volatile var visible = false
        set(v) { field = v; if (v) unread = 0 }

    /** Что делать с новым сообщением, пока экран закрыт (показать уведомление). */
    var onIncoming: ((ChatNode.Message) -> Unit)? = null

    private var lock: WifiManager.MulticastLock? = null
    private var directRef: WifiDirect? = null

    /** Точка Wi-Fi Direct живёт вместе с комнатой, а не с экраном. */
    fun direct(ctx: Context): WifiDirect = directRef ?: WifiDirect(ctx).also { directRef = it }

    /** Вход в комнату. Долгая операция (PBKDF2 + сокет) — вызывать не из главного потока. */
    fun join(ctx: Context, room: String, nick: String, lan: Boolean, server: String?, selfId: Long): ChatNode {
        leave()
        main.post {
            messages.clear()
            delivered.clear()
            peers = emptyList()
            status = ChatNode.Status()
            unread = 0
        }
        val n = ChatNode(
            room, nick, ChatNode.DEFAULT_PORT,
            targets = if (lan) ({ ChatNode.localBroadcasts() }) else ({ emptyList() }),
            onMessage = { m -> main.post { add(m) } },
            onPeers = { p -> main.post { peers = p } },
            relay = server?.let { NtfyRelay(it) },
            stunServers = if (server != null) ChatNode.STUN_SERVERS else emptyList(),
            onStatus = { s -> main.post { status = s } },
            onSent = { id -> main.post { delivered[id] = true } },
            selfId = selfId,
        )
        if (lan) {
            // без этой блокировки многие телефоны не принимают широковещательные пакеты Wi-Fi
            lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("nullsignal-chat")?.apply { setReferenceCounted(false) }
            runCatching { lock?.acquire() }
        }
        n.start()
        main.post {
            node = n
            this.lan = lan
            far = server != null
            joinedAt = System.currentTimeMillis()
        }
        return n
    }

    private fun add(m: ChatNode.Message) {
        var i = messages.size
        while (i > 0 && messages[i - 1].time > m.time) i--
        messages.add(i, m)
        if (messages.size > 600) messages.removeAt(0)
        // свежие чужие сообщения, пока экран закрыт: считаем и показываем уведомление (история не в счёт)
        if (!m.mine && !visible && System.currentTimeMillis() - m.time < 5 * 60_000L) {
            unread++
            onIncoming?.invoke(m)
        }
    }

    fun send(text: String) = node?.send(text)

    fun leave() {
        val n = node
        if (n != null) Thread { n.stop() }.start()
        main.post {
            node = null
            peers = emptyList()
            status = ChatNode.Status()
        }
        runCatching { lock?.release() }
        lock = null
    }

    /** Выйти из комнаты и закрыть точку Wi-Fi Direct, если её поднимали. */
    fun close() {
        leave()
        main.post { directRef?.leave() }
    }

    @Suppress("unused")
    fun relayName(server: String): String = runCatching { NtfyRelay(server).name }.getOrDefault(server)
}
