package com.nullsignal.core

import com.nullsignal.core.hw.Ir
import com.nullsignal.core.hw.IrImport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrImportTest {
    @Test fun flipperFile() {
        val file = """
            Filetype: IR signals file
            Version: 1
            # 
            name: Power
            type: parsed
            protocol: NEC
            address: 04 00 00 00
            command: 08 00 00 00
            # 
            name: Vol_up
            type: parsed
            protocol: NECext
            address: 00 7F 00 00
            command: 15 EA 00 00
            # 
            name: Mute
            type: raw
            frequency: 38000
            duty_cycle: 0.330000
            data: 9024 4512 564 564 564 1692
            # 
            name: Odd
            type: parsed
            protocol: Kaseikyo
            address: 01 02 00 00
            command: 03 00 00 00
        """.trimIndent()
        val r = IrImport.flipper(file)
        assertEquals(listOf("Power", "Vol_up", "Mute"), r.buttons.map { it.name })
        assertEquals(listOf("Odd (Kaseikyo)"), r.skipped)
        assertArrayEquals(Ir.nec(0x04, 0x08).pattern, r.buttons[0].signal.pattern)
        assertArrayEquals(Ir.necBytes(listOf(0x00, 0x7F, 0x15, 0xEA)).pattern, r.buttons[1].signal.pattern)
        assertArrayEquals(intArrayOf(9024, 4512, 564, 564, 564, 1692), r.buttons[2].signal.pattern)
        // NECext с 8-битной командой: команда дополняется инверсией
        assertArrayEquals(Ir.necBytes(listOf(0x02, 0x00, 0x10, 0xEF)).pattern, IrImport.parsed("NECext", listOf(2, 0), listOf(0x10, 0))!!.pattern)
        assertArrayEquals(Ir.samsung(7, 2).pattern, IrImport.parsed("Samsung32", listOf(7), listOf(2))!!.pattern)
    }

    @Test fun prontoCode() {
        // NEC в Pronto: несущая 0x006D ≈ 38 кГц, заголовок 0x0156/0x00AB ≈ 9 мс / 4.5 мс
        val s = IrImport.pronto("0000 006D 0002 0000 0156 00AB 0015 0015")!!
        assertEquals(37900.0, s.carrierHz.toDouble(), 300.0)
        assertEquals(9000.0, s.pattern[0].toDouble(), 60.0)
        assertEquals(4500.0, s.pattern[1].toDouble(), 60.0)
        assertEquals(4, s.pattern.size)
        assertNull(IrImport.pronto("привет"))
        assertNull(IrImport.pronto("0000 006D 0005 0000 0156"))
        val any = IrImport.any("0000 006D 0002 0000 0156 00AB 0015 0015", "Питание")
        assertEquals("Питание", any.buttons.single().name)
        assertTrue(IrImport.any("ерунда").buttons.isEmpty())
    }
}
