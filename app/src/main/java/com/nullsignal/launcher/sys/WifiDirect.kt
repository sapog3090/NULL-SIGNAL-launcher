package com.nullsignal.launcher.sys

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Точка Wi-Fi Direct без роутера. Имя и пароль выводятся из кодового слова комнаты, поэтому
 * один телефон поднимает точку, а остальные с тем же словом подключаются к ней одной кнопкой.
 * Телефоны в одной точке видят друг друга, и шифрочат работает как в обычной сети Wi-Fi.
 */
@SuppressLint("MissingPermission")
class WifiDirect(ctx: Context) {
    enum class Role { NONE, OWNER, CLIENT }

    private val app = ctx.applicationContext
    private val mgr = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val main = Handler(Looper.getMainLooper())

    var role by mutableStateOf(Role.NONE)
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    val supported: Boolean get() = mgr != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun ch(): WifiP2pManager.Channel? {
        val m = mgr ?: return null
        return channel ?: runCatching { m.initialize(app, Looper.getMainLooper(), null) }.getOrNull()?.also { channel = it }
    }

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "телефон не поддерживает Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi занят — включи Wi-Fi и попробуй ещё раз"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "нет запросов"
        else -> "система отказала — включи Wi-Fi и геолокацию"
    }

    private fun listener(ok: () -> Unit, fail: (String) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = ok()
        override fun onFailure(code: Int) = fail(reason(code))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun config(name: String, pass: String): WifiP2pConfig = WifiP2pConfig.Builder()
        .setNetworkName(name)
        .setPassphrase(pass)
        .enablePersistentMode(false)
        // 2,4 ГГц бьёт дальше и сквозь стены лучше, чем 5 ГГц
        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
        .build()

    /** Поднять точку: этот телефон становится «роутером» для остальных. */
    fun host(name: String, pass: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { status = "нужен Android 10 или новее"; return }
        val m = mgr ?: run { status = "Wi-Fi Direct недоступен"; return }
        val c = ch() ?: run { status = "Wi-Fi Direct недоступен"; return }
        busy = true
        status = "поднимаю точку…"
        // старую группу убираем, иначе система ответит «занято»
        m.removeGroup(c, listener({ create(m, c, name, pass) }, { create(m, c, name, pass) }))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun create(m: WifiP2pManager, c: WifiP2pManager.Channel, name: String, pass: String) {
        m.createGroup(c, config(name, pass), listener({
            role = Role.OWNER
            busy = false
            status = "точка $name поднята. Остальные: то же кодовое слово → «подключиться»"
            main.postDelayed({ refresh() }, 1500)
        }, { busy = false; status = it }))
    }

    /** Подключиться к точке, которую поднял кто-то с тем же кодовым словом. */
    fun join(name: String, pass: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { status = "нужен Android 10 или новее"; return }
        val m = mgr ?: run { status = "Wi-Fi Direct недоступен"; return }
        val c = ch() ?: run { status = "Wi-Fi Direct недоступен"; return }
        busy = true
        status = "ищу точку $name…"
        m.connect(c, config(name, pass), listener({
            status = "подключаюсь к $name…"
            main.postDelayed({ busy = false; refresh() }, 4000)
        }, { busy = false; status = "$it. Точку уже подняли? Попробуй ещё раз через 10 секунд" }))
    }

    /** Обновить состояние группы: кто хозяин, сколько подключено. */
    fun refresh() {
        val m = mgr ?: return
        val c = ch() ?: return
        runCatching {
            m.requestGroupInfo(c) { g ->
                if (g == null) {
                    if (role != Role.NONE) status = "точка закрыта"
                    role = Role.NONE
                } else {
                    role = if (g.isGroupOwner) Role.OWNER else Role.CLIENT
                    status = if (g.isGroupOwner) "точка ${g.networkName}: подключено ${g.clientList.size}"
                    else "в точке ${g.networkName}"
                }
            }
        }
    }

    fun leave() {
        val m = mgr ?: return
        val c = channel ?: return
        if (role == Role.NONE && !busy) return
        runCatching { m.cancelConnect(c, null) }
        runCatching { m.removeGroup(c, null) }
        role = Role.NONE
        busy = false
        status = "точка закрыта"
    }

    fun close() {
        leave()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { channel?.close() }
        channel = null
    }
}
