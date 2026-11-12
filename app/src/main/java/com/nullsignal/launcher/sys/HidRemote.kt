package com.nullsignal.launcher.sys

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nullsignal.core.hw.Hid
import java.util.concurrent.Executors

/**
 * Телефон как Bluetooth-клавиатура, мышь и пульт для своего компьютера или ТВ-приставки.
 * Работает на Android 9+, если производитель не отключил профиль HID.
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.P)
object HidRemote {
    var status by mutableStateOf("выключен")
        private set
    var ready by mutableStateOf(false)
        private set
    var host by mutableStateOf<String?>(null)
        private set

    private var hid: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    private val io = Executors.newSingleThreadExecutor()

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            ready = registered
            status = if (registered) "готов: подключись с компьютера или выбери устройство ниже" else "не зарегистрирован"
            if (registered && pluggedDevice != null) hid?.connect(pluggedDevice)
        }

        override fun onConnectionStateChanged(d: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { device = d; host = runCatching { d.name }.getOrNull() ?: d.address; status = "подключён" }
                BluetoothProfile.STATE_CONNECTING -> status = "подключаюсь…"
                BluetoothProfile.STATE_DISCONNECTED -> { if (device == d) { device = null; host = null }; status = if (ready) "отключён — подключись снова" else "выключен" }
            }
        }
    }

    fun start(ctx: Context) {
        if (hid != null) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { status = "включи Bluetooth"; return }
        status = "запускаю…"
        val ok = adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings("NULL//SIGNAL", "пульт", "NULL//SIGNAL", BluetoothHidDevice.SUBCLASS1_COMBO, Hid.DESCRIPTOR)
                if (!h.registerApp(sdp, null, null, io, callback)) status = "телефон не дал включить режим клавиатуры"
            }

            override fun onServiceDisconnected(profile: Int) { hid = null; ready = false; status = "выключен" }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) status = "производитель отключил режим Bluetooth-клавиатуры на этом телефоне"
    }

    fun stop(ctx: Context) {
        val h = hid ?: return
        runCatching { device?.let { h.disconnect(it) } }
        runCatching { h.unregisterApp() }
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null
        device = null
        host = null
        ready = false
        status = "выключен"
    }

    fun bonded(ctx: Context): List<BluetoothDevice> =
        runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())

    fun connect(d: BluetoothDevice) { hid?.connect(d) }

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = device ?: return
        io.execute { runCatching { h.sendReport(d, id, data) } }
    }

    fun key(code: Int, mod: Int = 0) {
        send(Hid.KEYBOARD, Hid.keyReport(mod, code))
        send(Hid.KEYBOARD, Hid.KEY_UP)
    }

    /** Напечатать текст (английская раскладка). Возвращает, сколько символов пропущено. */
    fun type(text: String): Int {
        var skipped = 0
        for (c in text.take(500)) {
            val k = Hid.key(c)
            if (k == null) { skipped++; continue }
            key(k.second, k.first)
        }
        return skipped
    }

    fun media(usage: Int) {
        send(Hid.MEDIA, Hid.mediaReport(usage))
        send(Hid.MEDIA, Hid.mediaReport(0))
    }

    fun mouse(dx: Int, dy: Int, buttons: Int = 0, wheel: Int = 0) = send(Hid.MOUSE, Hid.mouseReport(buttons, dx, dy, wheel))

    fun click(button: Int = 1) {
        mouse(0, 0, button)
        mouse(0, 0, 0)
    }

    val connected: Boolean get() = host != null

    @Suppress("unused")
    fun adapterName(ctx: Context): String? = runCatching { (ctx.getSystemService(BluetoothManager::class.java)?.adapter as BluetoothAdapter?)?.name }.getOrNull()
}
