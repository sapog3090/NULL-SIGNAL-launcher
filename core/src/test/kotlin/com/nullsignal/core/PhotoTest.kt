package com.nullsignal.core

import com.nullsignal.core.photo.JpegForensics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class PhotoTest {
    private fun jpeg(quality: Float, w: Int = 64, h: Int = 48): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or ((x + y) * 2))
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val p = writer.defaultWriteParam
            p.compressionMode = ImageWriteParam.MODE_EXPLICIT
            p.compressionQuality = quality
            writer.write(null, IIOImage(img, null, null), p)
        }
        writer.dispose()
        return out.toByteArray()
    }

    @Test fun qualityEstimate() {
        for (q in listOf(50, 75, 90)) {
            val r = JpegForensics.analyze(jpeg(q / 100f))!!
            assertTrue("q=$q got ${r.quality}", kotlin.math.abs(r.quality!! - q) <= 2)
            assertEquals(64, r.width)
            assertEquals(48, r.height)
            assertFalse(r.progressive)
        }
    }

    @Test fun ijgTablesRoundTrip() {
        for (q in listOf(10, 30, 50, 60, 85, 95)) {
            assertTrue("q=$q", kotlin.math.abs(JpegForensics.estimateQuality(JpegForensics.ijgTable(q)) - q) <= 1)
        }
    }

    @Test fun editorTraces() {
        val base = jpeg(0.8f)
        // вставляем после SOI комментарий и APP13 Photoshop
        val com = "CREATOR: gd-jpeg v1.0".toByteArray()
        val app13 = "Photoshop 3.0\u00008BIM".toByteArray()
        fun seg(marker: Int, body: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body
        val edited = base.copyOfRange(0, 2) + seg(0xFE, com) + seg(0xED, app13) + base.copyOfRange(2, base.size)
        val r = JpegForensics.analyze(edited)!!
        assertTrue(r.editors.toString(), r.editors.any { it.contains("Photoshop") })
        assertTrue(r.editors.toString(), r.editors.any { it.contains("gd") })
        assertNull(JpegForensics.analyze("PNG".toByteArray()))
        assertFalse(r.hasExif)
    }

    @Test fun xmpCreatorTool() {
        val base = jpeg(0.9f)
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><rdf:Description xmp:CreatorTool=\"Snapseed 2.0\"/></x:xmpmeta>".toByteArray()
        val seg = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((xmp.size + 2) shr 8).toByte(), (xmp.size + 2).toByte()) + xmp
        val r = JpegForensics.analyze(base.copyOfRange(0, 2) + seg + base.copyOfRange(2, base.size))!!
        assertTrue(r.hasXmp)
        assertEquals(listOf("Snapseed"), r.editors)
    }
}
