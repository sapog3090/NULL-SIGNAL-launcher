package com.nullsignal.launcher.sys

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.nullsignal.core.hw.NdefT4

/** Эмуляция NFC-метки: пока включена, телефон отвечает считывателю как метка со ссылкой или текстом. */
object NfcEmu {
    @Volatile var tag: NdefT4? = null
    @Volatile var reads = 0
}

class NfcTagService : HostApduService() {
    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val tag = NfcEmu.tag ?: return NdefT4.SW_NOT_FOUND
        val r = tag.respond(apdu)
        if ((apdu.getOrNull(1)?.toInt() ?: 0) and 0xFF == 0xB0) NfcEmu.reads++
        return r
    }

    override fun onDeactivated(reason: Int) = Unit
}
