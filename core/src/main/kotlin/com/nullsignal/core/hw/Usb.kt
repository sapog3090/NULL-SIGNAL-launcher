package com.nullsignal.core.hw

/**
 * USB-устройства, подключённые к телефону через OTG: кто это и не притворяется ли
 * «флешка» клавиатурой (так устроены BadUSB — они сами набирают команды).
 */
object UsbInfo {
    data class Iface(val cls: Int, val subclass: Int, val protocol: Int)

    fun className(cls: Int): String = when (cls) {
        0x01 -> "аудио"
        0x02 -> "связь (CDC)"
        0x03 -> "HID — клавиатура/мышь/геймпад"
        0x05 -> "физическое устройство"
        0x06 -> "изображения (камера, MTP)"
        0x07 -> "принтер"
        0x08 -> "накопитель"
        0x09 -> "хаб"
        0x0A -> "данные CDC (последовательный порт)"
        0x0B -> "смарт-карта"
        0x0E -> "видео"
        0x10 -> "аудио/видео"
        0xDC -> "диагностика"
        0xE0 -> "беспроводной адаптер"
        0xEF -> "составное"
        0xFE -> "служебный (DFU)"
        0xFF -> "фирменный протокол"
        else -> "класс 0x%02X".format(cls)
    }

    fun hidKind(i: Iface): String? = when {
        i.cls != 0x03 -> null
        i.protocol == 1 -> "клавиатура"
        i.protocol == 2 -> "мышь"
        else -> "HID"
    }

    private val VENDORS = mapOf(
        0x2341 to "Arduino", 0x2A03 to "Arduino", 0x1A86 to "WCH (чип CH340/CH9102)", 0x10C4 to "Silicon Labs (CP210x)",
        0x0403 to "FTDI", 0x303A to "Espressif (ESP32)", 0x2E8A to "Raspberry Pi", 0x239A to "Adafruit", 0x1B4F to "SparkFun",
        0x16C0 to "Teensy / V-USB", 0x16D0 to "Digistump (Digispark)", 0x0483 to "STMicroelectronics", 0x1366 to "SEGGER",
        0x0D28 to "ARM (DAPLink)", 0x1915 to "Nordic Semiconductor", 0x067B to "Prolific (PL2303)", 0x04D8 to "Microchip",
        0x2886 to "Seeed Studio", 0x1209 to "pid.codes (открытое железо)", 0x05AC to "Apple", 0x046D to "Logitech",
        0x045E to "Microsoft", 0x0781 to "SanDisk", 0x090C to "Silicon Motion", 0x058F to "Alcor Micro", 0x0951 to "Kingston",
        0x8564 to "Transcend", 0x18D1 to "Google", 0x04E8 to "Samsung", 0x2717 to "Xiaomi", 0x0BDA to "Realtek", 0x0B95 to "ASIX",
        0x1D6B to "Linux Foundation", 0x05E3 to "Genesys Logic", 0x13FE to "Phison", 0x154B to "PNY", 0x0930 to "Toshiba",
    )

    private val BOARDS = mapOf(
        (0x2341 to 0x0043) to "Arduino Uno", (0x2341 to 0x0001) to "Arduino Uno (старый)", (0x2341 to 0x0042) to "Arduino Mega 2560",
        (0x2341 to 0x8036) to "Arduino Leonardo", (0x2341 to 0x8037) to "Arduino Micro", (0x2341 to 0x0058) to "Arduino Nano Every",
        (0x2A03 to 0x0043) to "Arduino Uno", (0x1A86 to 0x7523) to "CH340: Arduino Nano / ESP8266 (клон)",
        (0x1A86 to 0x55D4) to "CH9102: ESP32 DevKit", (0x10C4 to 0xEA60) to "CP2102: ESP32 / NodeMCU",
        (0x0403 to 0x6001) to "FTDI FT232R", (0x0403 to 0x6015) to "FTDI FT231X", (0x0403 to 0x6010) to "FTDI FT2232",
        (0x303A to 0x1001) to "ESP32-S3 / C3 (встроенный USB)", (0x303A to 0x0002) to "ESP32-S2",
        (0x2E8A to 0x0005) to "Raspberry Pi Pico (MicroPython)", (0x2E8A to 0x000A) to "Raspberry Pi Pico (SDK)",
        (0x2E8A to 0x0003) to "Raspberry Pi Pico (режим загрузки)", (0x16D0 to 0x0753) to "Digispark ATtiny85",
        (0x1B4F to 0x9206) to "SparkFun Pro Micro", (0x0483 to 0x5740) to "STM32 (виртуальный COM-порт)",
        (0x0483 to 0xDF11) to "STM32 (загрузчик DFU)", (0x067B to 0x2303) to "PL2303 (USB-COM адаптер)",
    )

    /** Микроконтроллеры, которые умеют прикидываться клавиатурой (по прошивке). */
    private val PROGRAMMABLE_VIDS = setOf(0x2341, 0x2A03, 0x1B4F, 0x16C0, 0x16D0, 0x303A, 0x2E8A, 0x239A, 0x0483, 0x1209)

    fun vendor(vid: Int): String? = VENDORS[vid]
    fun board(vid: Int, pid: Int): String? = BOARDS[vid to pid]

    /** Есть ли у устройства последовательный порт (CDC или известный USB-UART). */
    fun isSerial(vid: Int, pid: Int, ifaces: List<Iface>): Boolean =
        ifaces.any { it.cls == 0x02 || it.cls == 0x0A } || vid in setOf(0x1A86, 0x10C4, 0x0403, 0x067B) || board(vid, pid) != null

    data class Verdict(val level: Int, val notes: List<String>) // 0 — норма, 1 — внимание, 2 — опасно

    fun assess(vid: Int, pid: Int, ifaces: List<Iface>): Verdict {
        val notes = mutableListOf<String>()
        var level = 0
        val keyboard = ifaces.any { it.cls == 0x03 && it.protocol == 1 }
        val hid = ifaces.any { it.cls == 0x03 }
        val storage = ifaces.any { it.cls == 0x08 }
        if (keyboard && storage) {
            level = 2
            notes += "накопитель, который ещё и клавиатура — так устроены BadUSB: «флешка» сама набирает команды на компьютере"
        }
        if (hid && vid in PROGRAMMABLE_VIDS) {
            level = maxOf(level, 2)
            notes += "плата-микроконтроллер выдаёт себя за ${if (keyboard) "клавиатуру" else "устройство ввода"}: она может печатать команды сама. Если прошивка не твоя — не подключай к компьютеру"
        }
        if (keyboard && level == 0) {
            level = 1
            notes += "это клавиатура. Если ты подключал флешку или кабель — это подозрительно"
        }
        if (ifaces.any { it.cls == 0xFF } && board(vid, pid) == null && vendor(vid) == null) {
            level = maxOf(level, 1)
            notes += "неизвестный производитель с фирменным протоколом"
        }
        if (notes.isEmpty()) notes += "ничего подозрительного"
        return Verdict(level, notes)
    }
}
