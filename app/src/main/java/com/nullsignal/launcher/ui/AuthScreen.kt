package com.nullsignal.launcher.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nullsignal.core.spy.Base32
import com.nullsignal.core.spy.Totp
import kotlinx.coroutines.delay

private object TotpStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("totp", 0)

    fun load(ctx: Context): List<Totp.Account> =
        prefs(ctx).getStringSet("accounts", emptySet())!!.mapNotNull { Totp.deserialize(it) }.sortedBy { it.label.lowercase() }

    fun save(ctx: Context, list: List<Totp.Account>) =
        prefs(ctx).edit().putStringSet("accounts", list.map { Totp.serialize(it) }.toSet()).apply()
}

@Composable
fun AuthScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var accounts by remember { mutableStateOf(TotpStore.load(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var adding by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Totp.Account?>(null) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(500) } }

    fun add(a: Totp.Account) {
        accounts = (accounts.filter { it.secret != a.secret } + a).sortedBy { it.label.lowercase() }
        TotpStore.save(ctx, accounts)
        msg = "добавлено: ${a.label}"
        adding = false
    }

    ModuleScreen("2FA", onBack) {
        Text(
            "Одноразовые коды для входа — как Google Authenticator, но в NULL//SIGNAL. Включи двухфакторку в Telegram, VK, Госуслугах, Steam, Discord, GitHub — " +
                "и даже с украденным паролем в аккаунт не войдут.",
            style = Ns.small,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TermButton("СКАНИРОВАТЬ QR", Modifier.weight(1f), color = Ns.Red) {
                GmsBarcodeScanning.getClient(ctx).startScan()
                    .addOnSuccessListener { code ->
                        val a = Totp.parseUri(code.rawValue ?: "")
                        if (a != null) add(a) else msg = "это не QR двухфакторки (нужен otpauth://totp/…)"
                    }
                    .addOnFailureListener { msg = "сканер недоступен: ${it.message}" }
            }
            TermButton(if (adding) "ОТМЕНА" else "ВРУЧНУЮ", Modifier.weight(1f)) { adding = !adding }
        }
        msg?.let { Text(it, style = Ns.small.copy(color = Ns.Amber)) }
        if (adding) ManualAdd { add(it) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (accounts.isEmpty() && !adding) item {
                Text(
                    "Пусто. На сайте включи «двухфакторную аутентификацию / приложение-аутентификатор» — появится QR-код. Отсканируй его здесь.",
                    style = Ns.small.copy(color = Ns.Dim),
                )
            }
            items(accounts, key = { it.secret + it.name }) { a ->
                val code = runCatching { Totp.code(a, now) }.getOrDefault("ошибка")
                val left = Totp.secondsLeft(now, a.period)
                val pretty = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
                Column(
                    Modifier.fillMaxWidth().hud(if (left <= 5) Ns.Red else Ns.Faint).clickable {
                        clip.setText(AnnotatedString(code)); msg = "код ${a.label} скопирован"
                    }.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.issuer.ifEmpty { a.name }.uppercase(), style = Ns.label.copy(color = Ns.Red))
                            if (a.issuer.isNotEmpty()) Text(a.name, style = Ns.small)
                        }
                        Text("${left}с", style = Ns.small.copy(color = if (left <= 5) Ns.Red else Ns.Dim))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (confirmDelete == a) "УДАЛИТЬ?" else "✕",
                            style = Ns.small.copy(color = if (confirmDelete == a) Ns.Red else Ns.Faint),
                            modifier = Modifier.clickable {
                                if (confirmDelete == a) {
                                    accounts = accounts - a
                                    TotpStore.save(ctx, accounts)
                                    confirmDelete = null
                                } else confirmDelete = a
                            }.padding(4.dp),
                        )
                    }
                    Text(pretty, style = Ns.title.copy(fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = 4.sp, color = Ns.Green))
                    HudBar(left / a.period.toFloat(), if (left <= 5) Ns.Red else Ns.Dim, height = 3.dp)
                }
            }
            if (accounts.isNotEmpty()) item {
                Text(
                    "Нажми на карточку — код скопируется. Важно: сохрани резервные коды сайтов. Если удалить приложение, коды пропадут вместе с ним.",
                    style = Ns.small.copy(color = Ns.Dim), modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ManualAdd(onAdd: (Totp.Account) -> Unit) {
    var issuer by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val valid = Base32.isValid(secret.replace(" ", ""))
    Panel(Modifier.padding(vertical = 6.dp)) {
        NsField(issuer, { issuer = it }, "сервис (например, GitHub)")
        Spacer(Modifier.height(6.dp))
        NsField(name, { name = it }, "аккаунт (почта или ник)")
        Spacer(Modifier.height(6.dp))
        NsField(secret, { secret = it.uppercase() }, "секретный ключ (буквы A–Z и цифры 2–7)")
        if (secret.isNotEmpty() && !valid) Text("ключ должен быть в base32", style = Ns.small.copy(color = Ns.Amber))
        TermButton("ДОБАВИТЬ", Modifier.fillMaxWidth().padding(top = 8.dp), color = Ns.Red, enabled = valid) {
            onAdd(Totp.Account(issuer.trim(), name.trim().ifEmpty { issuer.trim() }, secret.replace(" ", "")))
        }
    }
}
