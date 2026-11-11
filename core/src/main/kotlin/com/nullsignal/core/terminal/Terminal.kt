package com.nullsignal.core.terminal

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
            "  apps [фильтр]     установленные приложения",
            "  run <имя>         запустить приложение",
            "  open <модуль>     home / term / scan / msg / breach",
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
        )

        /** Команды, которые выполняет Android-часть. Значение — подсказка, если аргументы обязательны. */
        val EXEC: Map<String, String> = mapOf(
            "ping" to "ping <хост>",
            "nslookup" to "nslookup <домен>",
            "curl" to "curl <url>",
            "nc" to "nc <хост> <порт>",
            "sh" to "sh <команда>",
            "sysinfo" to "",
        )
    }
}
