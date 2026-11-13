package com.nullsignal.launcher.sys

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nullsignal.launcher.MainActivity
import com.nullsignal.launcher.R

/**
 * Держит комнату шифрочата живой, пока телефон в кармане: без службы Android через несколько минут
 * усыпит сеть приложения. Постоянное уведомление показывает, кто в эфире; новые сообщения — отдельным.
 */
class ChatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            ChatSession.close()
            stopSelf()
            return START_NOT_STICKY
        }
        channels(this)
        val n = ongoing(this, lastText.ifEmpty { "комната открыта" })
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else startForeground(ONGOING, n)
        }.onFailure { stopSelf() }
        // процесс убили — комнаты больше нет, перезапускать службу незачем
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lastText = ""
        super.onDestroy()
    }

    companion object {
        private const val CH_LINK = "chat_link"
        private const val CH_MSG = "chat_msg"
        private const val ONGOING = 4747
        private const val MSG = 4748
        const val ACTION_LEAVE = "com.nullsignal.launcher.CHAT_LEAVE"
        const val EXTRA_OPEN = "open"

        @Volatile private var lastText = ""
        private val lines = ArrayDeque<String>()

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ChatService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ChatService::class.java)) }
            cancelMessages(ctx)
            lastText = ""
        }

        private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Обновить строку «кто в эфире» — только если текст изменился. */
        fun refresh(ctx: Context, text: String) {
            if (text == lastText) return
            lastText = text
            if (!canNotify(ctx) || ChatSession.node == null) return
            runCatching { NotificationManagerCompat.from(ctx).notify(ONGOING, ongoing(ctx, text)) }
        }

        /** Новое сообщение, пока приложение свёрнуто. На экране блокировки текст скрыт. */
        fun message(ctx: Context, from: String, text: String) {
            if (!canNotify(ctx)) return
            channels(ctx)
            synchronized(lines) {
                lines.addLast("$from: ${text.take(120)}")
                while (lines.size > 6) lines.removeFirst()
            }
            val count = synchronized(lines) { lines.size }
            val style = NotificationCompat.InboxStyle()
            synchronized(lines) { lines.forEach { style.addLine(it) } }
            val hidden = NotificationCompat.Builder(ctx, CH_MSG)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setContentTitle("ШИФРОЧАТ")
                .setContentText("новое сообщение")
                .build()
            val n = NotificationCompat.Builder(ctx, CH_MSG)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setColor(0xFFE5112E.toInt())
                .setContentTitle(if (count > 1) "ШИФРОЧАТ · новых: $count" else "$from · ШИФРОЧАТ")
                .setContentText(if (count > 1) "$from: ${text.take(120)}" else text.take(120))
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(hidden)
                .setAutoCancel(true)
                .setContentIntent(openIntent(ctx))
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(MSG, n) }
        }

        fun cancelMessages(ctx: Context) {
            synchronized(lines) { lines.clear() }
            runCatching { NotificationManagerCompat.from(ctx).cancel(MSG) }
        }

        private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, "chat")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ongoing(ctx: Context, text: String): Notification {
            val leave = PendingIntent.getService(
                ctx, 1, Intent(ctx, ChatService::class.java).setAction(ACTION_LEAVE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CH_LINK)
                .setSmallIcon(R.drawable.ic_stat_chat)
                .setColor(0xFFE5112E.toInt())
                .setContentTitle("ШИФРОЧАТ на связи")
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(openIntent(ctx))
                .addAction(0, "ВЫЙТИ ИЗ КОМНАТЫ", leave)
                .build()
        }

        private fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CH_LINK) == null) {
                nm.createNotificationChannel(NotificationChannel(CH_LINK, "Шифрочат: связь", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Комната открыта и слушает эфир"
                    setShowBadge(false)
                })
            }
            if (nm.getNotificationChannel(CH_MSG) == null) {
                nm.createNotificationChannel(NotificationChannel(CH_MSG, "Шифрочат: сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Новые сообщения, пока приложение свёрнуто"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
            }
        }
    }
}
