package com.nullsignal.core.terminal

import com.nullsignal.core.games.Ciphers
import com.nullsignal.core.net.Subnet
import com.nullsignal.core.security.Crypto
import com.nullsignal.core.security.PasswordStrength
import com.nullsignal.core.security.UrlInspector
import com.nullsignal.core.security.WebAudit
import com.nullsignal.core.story.Trigger

/** Экраны лаунчера, которые можно открыть из терминала. */
enum class Module(val id: String, val title: String) {
    HOME("home", "HOME"),
    TERMINAL("term", "TERMINAL"),
    NETSCAN("scan", "NETSCAN"),
    MESSAGES("msg", "MESSAGES"),
    BREACH("breach", "BREACH"),
    WIFI("wifi", "WIFI-RADAR"),
    BLE("ble", "BLE-RADAR"),
    ACADEMY("learn", "ACADEMY"),
    AUDIT("audit", "AUDIT"),
    TOOLS("tools", "TOOLS"),
    MONITOR("mon", "MONITOR"),
    GAMES("games", "GAMES"),
    INTERCEPT("dns", "ПЕРЕХВАТ"),
    STALKER("stalk", "АНТИСЛЕЖКА"),
    EXIF("exif", "EXIF"),
}

/** Данные об устройстве и игре, которые терминал может показать. */
interface TerminalEnv {
    val operator: String
    val device: String
    val ip: String?
    val objective: String
    val followers: Int
    val apps: List<String>
    fun now(): String
}

sealed interface Action {
    data class Open(val module: Module) : Action
    data class LaunchApp(val label: String) : Action
    data object Clear : Action
    data object ToggleMatrix : Action
    /** Реальная команда, которую выполняет Android-часть (сеть, шелл). Вывод идёт потоком. */
    data class Exec(val command: String, val args: List<String>, val rawArgs: String) : Action
}

data class Output(
    val lines: List<String>,
    val action: Action? = null,
    val trigger: Trigger? = null,
)

/** Интерпретатор команд хакерского терминала. */
class Terminal(private val env: TerminalEnv) {

    private val _history = mutableListOf<String>()
    val history: List<String> get() = _history

    fun prompt(): String = "${env.operator}@nullsignal:~$"

    fun execute(raw: String): Output {
        val line = raw.trim()
        if (line.isEmpty()) return Output(emptyList())
        _history += line
        val cmd = line.substringBefore(' ').lowercase()
        val arg = line.substringAfter(' ', "").trim()

        return when (cmd) {
            "help", "?" -> Output(HELP)
            "clear", "cls" -> Output(emptyList(), Action.Clear)
            "whoami" -> Output(
                listOf(
                    "оператор: ${env.operator}",
                    "устройство: ${env.device}",
                    "статус: ПРОВЕРЕН ✓ (человек, не бот)",
                ),
                trigger = Trigger.WHOAMI,
            )
            "date", "time" -> Output(listOf(env.now()))
            "echo" -> Output(listOf(arg))
            "ip", "ifconfig" -> Output(listOf(env.ip?.let { "wlan0  inet $it" } ?: "нет сети: подключись к Wi-Fi"))
            "quest", "task" -> Output(listOf("[ЗАДАЧА] ${env.objective}"))
            "nodes", "followers" -> Output(listOf("узлов сети: ${env.followers}"))
            "history" -> Output(_history.mapIndexed { i, s -> "${(i + 1).toString().padStart(3)}  $s" })
            "apps", "ls" -> {
                val list = env.apps.filter { arg.isEmpty() || it.contains(arg, ignoreCase = true) }
                Output(if (list.isEmpty()) listOf("ничего не найдено") else list.map { "  $it" })
            }
            "run", "launch" -> runApp(arg)
            "open", "cd" -> openModule(arg)
            "scan", "nmap" -> Output(listOf("запускаю NETSCAN…"), Action.Open(Module.NETSCAN))
            "breach", "crack" -> Output(listOf("подключаюсь к тренажёру…"), Action.Open(Module.BREACH))
            "msg", "inbox" -> Output(listOf("открываю канал группы…"), Action.Open(Module.MESSAGES))
            "exit", "home" -> Output(emptyList(), Action.Open(Module.HOME))
            "signal" -> Output(
                listOf("отправляю сигнал…", "▂▃▅▇█ канал открыт", "NULL//SIGNAL: мы тебя слышим."),
                trigger = Trigger.SIGNAL_CMD,
            )
            "about" -> Output(
                listOf(
                    "NULL//SIGNAL shell v0.1",
                    "лаунчер-игра про хакеров. всё «взломанное» здесь вымышлено,",
                    "а сетевые инструменты работают только в твоей сети.",
                ),
            )
            "audit" -> Output(listOf("запускаю аудит…"), Action.Open(Module.AUDIT))
            "tools", "vault" -> Output(listOf("открываю TOOLS…"), Action.Open(Module.TOOLS))
            "mon", "monitor", "top" -> Output(listOf("открываю монитор сети…"), Action.Open(Module.MONITOR))
            "dns", "firewall", "sniff" -> Output(listOf("открываю перехватчик…"), Action.Open(Module.INTERCEPT))
            "stalk", "antispy" -> Output(listOf("ищу слежку…"), Action.Open(Module.STALKER))
            "exif" -> Output(listOf("открываю EXIF…"), Action.Open(Module.EXIF))
            "games" -> Output(listOf("открываю GAMES…"), Action.Open(Module.GAMES))
            "matrix" -> Output(listOf("wake up, ${env.operator}…"), Action.ToggleMatrix)
            "rot13" -> Output(listOf(Ciphers.rot13(arg)))
            "atbash" -> Output(listOf(Ciphers.atbash(arg)))
            "rev" -> Output(listOf(arg.reversed()))
            "caesar" -> caesar(arg)
            "hex" -> codec(arg, "hex", Ciphers::hexEncode, Ciphers::hexDecode)
            "bin" -> codec(arg, "bin", Ciphers::binEncode, Ciphers::binDecode)
            "pwcheck" -> pwcheck(arg)
            "encrypt" -> {
                val pw = arg.substringBefore(' ')
                val text = arg.substringAfter(' ', "")
                if (pw.isEmpty() || text.isEmpty()) Output(listOf("использование: encrypt <пароль> <текст>"))
                else Output(listOf(Crypto.encrypt(text, pw)))
            }
            "decrypt" -> {
                val pw = arg.substringBefore(' ')
                val blob = arg.substringAfter(' ', "")
                if (pw.isEmpty() || blob.isEmpty()) Output(listOf("использование: decrypt <пароль> <ns1:…>"))
                else Output(listOf(Crypto.decrypt(blob, pw) ?: "неверный пароль или повреждённые данные"))
            }
            "urlcheck", "phish" -> {
                if (arg.isEmpty()) Output(listOf("использование: urlcheck <ссылка>"))
                else UrlInspector.inspect(arg).let { r ->
                    Output(listOf("домен: ${r.host ?: "?"}") +
                        if (r.safeLooking) listOf("✓ явных признаков фишинга нет (это не гарантия)") else r.warnings.map { "⚠ $it" })
                }
            }
            "jwt" -> WebAudit.decodeJwt(arg)?.let { (h, p) -> Output(listOf("header:  $h", "payload: $p", "(подпись не проверяется)")) }
                ?: Output(listOf("использование: jwt <токен>"))
            "portscan" -> {
                val ip = arg.trim()
                when {
                    ip.isEmpty() -> Output(listOf("использование: portscan <ip в твоей сети>"))
                    runCatching { Subnet.isPrivate(ip) }.getOrDefault(false) ->
                        Output(emptyList(), Action.Exec("portscan", listOf(ip), ip))
                    else -> Output(listOf("portscan работает только с адресами твоей локальной сети (192.168.x.x, 10.x.x.x, 172.16–31.x.x)"))
                }
            }
            "learn", "academy" -> Output(listOf("открываю курс Килобайта…"), Action.Open(Module.ACADEMY))
            "wifi" -> Output(listOf("включаю WIFI-радар…"), Action.Open(Module.WIFI))
            "ble", "bt" -> Output(listOf("включаю BLE-радар…"), Action.Open(Module.BLE))
            in EXEC -> {
                val args = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val usage = EXEC.getValue(cmd)
                if (usage.isNotEmpty() && args.isEmpty()) Output(listOf("использование: $usage"))
                else Output(emptyList(), Action.Exec(cmd, args, arg))
            }
            "hash" -> hash(arg)
            "b64" -> b64(arg)
            "passgen" -> Output(listOf(Tools.password(arg.toIntOrNull()?.coerceIn(8, 64) ?: 20)))
            "sudo" -> Output(listOf("${env.operator} не в списке sudoers. об этом инциденте будет доложено Ворону."))
            "hack" -> Output(listOf("в кино это одна команда. здесь — попробуй breach."))
            "rm" -> Output(listOf("rm: отказано. Килобайт уже однажды снёс себе прод."))
            else -> Output(listOf("$cmd: команда не найдена. введи help"))
        }
    }

    private fun runApp(query: String): Output {
        if (query.isEmpty()) return Output(listOf("использование: run <название приложения>"))
        val match = env.apps.firstOrNull { it.equals(query, ignoreCase = true) }
            ?: env.apps.firstOrNull { it.startsWith(query, ignoreCase = true) }
            ?: env.apps.firstOrNull { it.contains(query, ignoreCase = true) }
            ?: return Output(listOf("run: «$query» не найдено. список: apps"))
        return Output(listOf("запуск: $match"), Action.LaunchApp(match))
    }

    private fun hash(arg: String): Output {
        val alg = arg.substringBefore(' ').lowercase()
        val text = arg.substringAfter(' ', "")
        val digest = Tools.hash(alg, text) ?: return Output(listOf("использование: hash <md5|sha1|sha256|sha512> <текст>"))
        return Output(listOf("$alg: $digest"))
    }

    private fun b64(arg: String): Output {
        val mode = arg.substringBefore(' ').lowercase()
        val text = arg.substringAfter(' ', "")
        return when (mode) {
            "enc", "e" -> Output(listOf(Tools.b64encode(text)))
            "dec", "d" -> Output(listOf(Tools.b64decode(text) ?: "b64: это не base64"))
            else -> Output(listOf("использование: b64 <enc|dec> <текст>"))
        }
    }

    private fun caesar(arg: String): Output {
        val first = arg.substringBefore(' ')
        val text = arg.substringAfter(' ', "")
        if (first == "brute" && text.isNotEmpty()) return Output(Ciphers.caesarBrute(text))
        val shift = first.toIntOrNull() ?: return Output(listOf("использование: caesar <сдвиг> <текст> | caesar brute <текст>"))
        return Output(listOf(Ciphers.caesar(text, shift)))
    }

    private fun codec(arg: String, name: String, enc: (String) -> String, dec: (String) -> String?): Output {
        val mode = arg.substringBefore(' ').lowercase()
        val text = arg.substringAfter(' ', "")
        return when (mode) {
            "enc", "e" -> Output(listOf(enc(text)))
            "dec", "d" -> Output(listOf(dec(text) ?: "$name: не получилось раскодировать"))
            else -> Output(listOf("использование: $name <enc|dec> <текст>"))
        }
    }

    private fun pwcheck(p: String): Output {
        if (p.isEmpty()) return Output(listOf("использование: pwcheck <пароль>  (никуда не отправляется)"))
        val r = PasswordStrength.check(p)
        return Output(
            listOf(
                "надёжность: ${r.verdict}  (~${r.entropyBits.toInt()} бит)",
                "перебор на мощной видеокарте: ${PasswordStrength.humanTime(r.crackSeconds)}",
            ) + r.issues.map { "  - $it" },
        )
    }

    private fun openModule(name: String): Output {
        val m = Module.entries.firstOrNull { it.id.equals(name, true) || it.title.equals(name, true) }
            ?: return Output(listOf("модули: " + Module.entries.joinToString(" ") { it.id }))
        return Output(listOf("открываю ${m.title}…"), Action.Open(m))
    }

    companion object {
        val HELP = listOf(
            "команды:",
            "  whoami            кто ты",
            "  quest             текущая задача",
            "  msg               сообщения группы",
            "  scan              сканер своей сети",
            "  breach            тренажёр взлома",
            "  learn             курс Python для хакеров",
            "  apps [фильтр]     установленные приложения",
            "  run <имя>         запустить приложение",
            "  open <модуль>     home / term / scan / msg / breach / learn",
            "  ip  date  nodes  history  echo  clear  about",
            "",
            "реальные инструменты:",
            "  ping <хост>       пинг (4 пакета)",
            "  nslookup <домен>  DNS-адреса",
            "  curl <url>        HTTP-статус и заголовки",
            "  nc <хост> <порт>  открыт ли порт",
            "  sh <команда>      настоящий Linux-шелл (песочница приложения)",
            "  sysinfo           железо и система",
            "  wifi / ble        радары эфира",
            "  hash <md5|sha1|sha256> <текст>",
            "  b64 <enc|dec> <текст>",
            "  passgen [длина]   стойкий пароль",
            "",
            "сеть:",
            "  traceroute <хост> маршрут пакетов до сайта",
            "  dig <домен> [тип] DNS-записи: A AAAA MX TXT NS",
            "  ssl <домен>       сертификат и шифрование сайта",
            "  sechead <url>     заголовки безопасности сайта",
            "  whois <домен>     кто владеет доменом",
            "  myip              внешний IP",
            "  wifiinfo          текущее Wi-Fi подключение",
            "  portscan <ip>     порты устройства в своей сети",
            "",
            "крипто:",
            "  pwcheck <пароль>  надёжность пароля",
            "  pwned <пароль>    был ли пароль в утечках",
            "  encrypt / decrypt <пароль> <текст>  AES-256",
            "  caesar <n> <т> | caesar brute <т>   rot13  atbash  rev",
            "  hex / bin <enc|dec> <текст>   jwt <токен>",
            "  urlcheck <ссылка> проверка на фишинг",
            "",
            "модули: dns stalk exif audit tools mon games learn  ·  matrix",
        )

        /** Команды, которые выполняет Android-часть. Значение — подсказка, если аргументы обязательны. */
        val EXEC: Map<String, String> = mapOf(
            "ping" to "ping <хост>",
            "nslookup" to "nslookup <домен>",
            "curl" to "curl <url>",
            "nc" to "nc <хост> <порт>",
            "sh" to "sh <команда>",
            "sysinfo" to "",
            "pwned" to "pwned <пароль>  (наружу уходят только 5 символов хеша)",
            "traceroute" to "traceroute <хост>",
            "dig" to "dig <домен> [A|AAAA|MX|TXT|NS|CNAME]",
            "ssl" to "ssl <домен>",
            "sechead" to "sechead <url>",
            "whois" to "whois <домен>",
            "myip" to "",
            "wifiinfo" to "",
        )
    }
}
