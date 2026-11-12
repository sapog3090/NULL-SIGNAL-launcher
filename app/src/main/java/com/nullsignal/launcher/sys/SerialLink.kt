package com.nullsignal.launcher.sys

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.nullsignal.core.hw.UsbInfo

/**
 * Монитор последовательного порта платы (Arduino, ESP32, Raspberry Pi Pico) через USB OTG.
 * Соединение живёт, пока жив процесс.
 */
object SerialLink {
    private const val ACTION = "com.nullsignal.launcher.USB_PERMISSION"

    val lines = mutableStateListOf<String>()
    var partial by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
    var deviceName by mutableStateOf("")
        private set
    var hex by mutableStateOf(false)
    var dtr by mutableStateOf(true)
        private set
    var rts by mutableStateOf(true)
        private set

    private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private val main = Handler(Looper.getMainLooper())

    fun ifaces(d: UsbDevice): List<UsbInfo.Iface> = (0 until d.interfaceCount).map {
        val i = d.getInterface(it)
        UsbInfo.Iface(i.interfaceClass, i.interfaceSubclass, i.interfaceProtocol)
    }

    fun canSerial(d: UsbDevice): Boolean =
        UsbSerialProber.getDefaultProber().probeDevice(d) != null || UsbInfo.isSerial(d.vendorId, d.productId, ifaces(d))

    fun label(d: UsbDevice): String =
        UsbInfo.board(d.vendorId, d.productId) ?: runCatching { d.productName }.getOrNull() ?: UsbInfo.vendor(d.vendorId)
        ?: "USB %04X:%04X".format(d.vendorId, d.productId)

    /** Просит разрешение на устройство; после «ОК» подключается сам. */
    fun requestAndConnect(ctx: Context, d: UsbDevice, baud: Int) {
        val um = ctx.getSystemService(UsbManager::class.java) ?: return
        if (um.hasPermission(d)) { connect(ctx, d, baud); return }
        val app = ctx.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { app.unregisterReceiver(this) }
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(app, d, baud)
                else status = "доступ к устройству не выдан"
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION).setPackage(app.packageName), flags)
        status = "разреши доступ к устройству в системном окне"
        um.requestPermission(d, pi)
    }

    fun connect(ctx: Context, d: UsbDevice, baud: Int) {
        close()
        val um = ctx.getSystemService(UsbManager::class.java) ?: return
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(d) ?: CdcAcmSerialDriver(d)
            val conn = um.openDevice(d) ?: throw IllegalStateException("не удалось открыть устройство")
            val p = driver.ports.first()
            p.open(conn)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.setDTR(dtr); p.setRTS(rts) }
            val manager = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = append(data)
                override fun onRunError(e: Exception) {
                    main.post { status = "соединение потеряно: ${e.message ?: ""}"; connected = false }
                }
            })
            manager.start()
            port = p
            io = manager
            deviceName = label(d)
            connected = true
            status = "подключено: ${label(d)} · $baud бод"
            lines += "[подключено к ${label(d)} на $baud бод]"
        } catch (e: Exception) {
            status = "ошибка: ${e.message ?: e.javaClass.simpleName}"
            close()
        }
    }

    private fun append(data: ByteArray) {
        val text = if (hex) data.joinToString(" ", postfix = " ") { "%02X".format(it) } else String(data, Charsets.UTF_8)
        main.post {
            val cur = StringBuilder(partial)
            for (ch in text) {
                when (ch) {
                    '\n' -> { lines += cur.toString(); cur.setLength(0) }
                    '\r' -> Unit
                    else -> cur.append(ch)
                }
            }
            if (hex && cur.length > 96) { lines += cur.toString(); cur.setLength(0) }
            partial = cur.toString()
            while (lines.size > 3000) lines.removeAt(0)
        }
    }

    fun send(bytes: ByteArray) {
        val p = port ?: return
        Thread { runCatching { p.write(bytes, 1000) }.onFailure { e -> main.post { status = "ошибка записи: ${e.message}" } } }.start()
    }

    fun sendLine(text: String, ending: String) = send((text + ending).toByteArray())

    fun switchDtr(v: Boolean) { dtr = v; runCatching { port?.setDTR(v) } }
    fun switchRts(v: Boolean) { rts = v; runCatching { port?.setRTS(v) } }

    /** Сброс платы: Arduino перезагружается по фронту DTR, ESP32 — по RTS (EN). */
    fun reset() {
        val p = port ?: return
        Thread {
            runCatching {
                p.setDTR(false); p.setRTS(true)
                Thread.sleep(120)
                p.setRTS(false); p.setDTR(true)
                Thread.sleep(50)
                p.setRTS(rts); p.setDTR(dtr)
            }
        }.start()
        lines += "[сброс платы]"
    }

    fun clear() { lines.clear(); partial = "" }

    fun close() {
        runCatching { io?.stop() }
        runCatching { port?.close() }
        io = null
        port = null
        if (connected) lines += "[отключено]"
        connected = false
    }
}
