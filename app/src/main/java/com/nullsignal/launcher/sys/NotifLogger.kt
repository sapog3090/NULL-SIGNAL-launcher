package com.nullsignal.launcher.sys

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File

/**
 * Журнал уведомлений: сохраняет каждое сообщение, пришедшее в уведомлении.
 * Если отправитель потом удалит сообщение — у тебя останется копия.
 * Всё хранится только на телефоне, в личной папке приложения.
 */
class NotifLogger : NotificationListenerService() {

    /** Последний сохранённый текст по ключу уведомления — чтобы не писать дубли. */
    private val seen = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handle(sbn) }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        // Мессенджеры: каждое сообщение отдельно, с именем отправителя
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val messages = style?.messages.orEmpty()
        if (messages.isNotEmpty()) {
            val chat = style?.conversationTitle?.toString() ?: title
            messages.forEach { m ->
                val text = m.text?.toString().orEmpty()
                if (text.isBlank()) return@forEach
                val sender = m.person?.name?.toString()
                val head = listOfNotNull(chat.ifBlank { null }, sender?.takeIf { it != chat }).joinToString(" › ")
                save(sbn, app, head, text, if (m.timestamp > 0) m.timestamp else sbn.postTime)
            }
            return
        }
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: ""
        if (title.isBlank() && text.isBlank()) return
        save(sbn, app, title, text, sbn.postTime)
    }

    private fun save(sbn: StatusBarNotification, app: String, title: String, text: String, time: Long) {
        val sig = "${sbn.packageName}|$title|$text"
        val hash = sig.hashCode().toLong()
        synchronized(seen) {
            if (seen.containsKey(sig)) return
            seen[sig] = hash
            if (seen.size > 3000) seen.remove(seen.keys.first())
        }
        NotifStore.append(this, NotifStore.Entry(time, sbn.packageName, app, title, text, sbn.key))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // REASON_APP_CANCEL: приложение само убрало уведомление (сообщение удалили или прочитали на другом устройстве)
        if (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL) {
            runCatching { NotifStore.markRemoved(this, sbn.key) }
        }
    }
}

object NotifStore {
    data class Entry(
        val time: Long,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val key: String,
        val removedByApp: Boolean = false,
    )

    private const val MAX = 6000
    private fun file(ctx: Context) = File(ctx.filesDir, "notif.jsonl")
    private var lines = -1

    @Synchronized
    fun append(ctx: Context, e: Entry) {
        val o = JSONObject().put("t", e.time).put("p", e.pkg).put("a", e.app).put("ti", e.title).put("x", e.text).put("k", e.key)
        file(ctx).appendText(o.toString() + "\n")
        if (lines < 0) lines = runCatching { file(ctx).useLines { it.count() } }.getOrDefault(0)
        lines++
        if (lines > MAX + 500) trim(ctx)
    }

    @Synchronized
    fun markRemoved(ctx: Context, key: String) {
        file(ctx).appendText(JSONObject().put("r", key).put("t", System.currentTimeMillis()).toString() + "\n")
        lines++
    }

    private fun trim(ctx: Context) {
        val keep = file(ctx).readLines().takeLast(MAX)
        file(ctx).writeText(keep.joinToString("\n", postfix = "\n"))
        lines = keep.size
    }

    /** Все записи, свежие сверху. */
    @Synchronized
    fun load(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val removed = HashSet<String>()
        val entries = ArrayList<Entry>()
        f.forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            if (o.has("r")) { removed += o.getString("r"); return@forEachLine }
            entries += Entry(
                o.optLong("t"), o.optString("p"), o.optString("a"), o.optString("ti"), o.optString("x"), o.optString("k"),
            )
        }
        return entries.map { if (it.key in removed) it.copy(removedByApp = true) else it }.sortedByDescending { it.time }
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
        lines = 0
    }

    fun enabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, NotifLogger::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }
}
