package com.evolia.app.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

/** Pure-JVM tests for the RFCOMM length-prefixed framing (no Bluetooth radio). */
class BluetoothFramingTest {

    private fun header(n: Int) = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(),
    )

    @Test
    fun roundtripSingleFrame() {
        val out = ByteArrayOutputStream()
        val payload = "hello".toByteArray()
        BluetoothFraming.writeFrame(out, payload)
        val back = BluetoothFraming.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(payload, back)
    }

    @Test
    fun multipleFramesInSequence() {
        val out = ByteArrayOutputStream()
        BluetoothFraming.writeFrame(out, "one".toByteArray())
        BluetoothFraming.writeFrame(out, "two".toByteArray())
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("one", String(BluetoothFraming.readFrame(input)!!))
        assertEquals("two", String(BluetoothFraming.readFrame(input)!!))
        assertNull("clean EOF after the last frame", BluetoothFraming.readFrame(input))
    }

    @Test
    fun cleanEofReturnsNull() {
        assertNull(BluetoothFraming.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun truncatedPayloadThrows() {
        val data = header(10) + ByteArray(3) // claims 10 bytes, only 3 follow
        assertThrows(EOFException::class.java) {
            BluetoothFraming.readFrame(ByteArrayInputStream(data))
        }
    }

    @Test
    fun oversizedLengthThrows() {
        val data = header(BluetoothFraming.MAX_FRAME_BYTES + 1)
        assertThrows(IOException::class.java) {
            BluetoothFraming.readFrame(ByteArrayInputStream(data))
        }
    }

    @Test
    fun nonPositiveLengthThrows() {
        assertThrows(IOException::class.java) {
            BluetoothFraming.readFrame(ByteArrayInputStream(header(0)))
        }
    }

    @Test
    fun writeRejectsEmptyAndOversizedPayload() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothFraming.writeFrame(out, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothFraming.writeFrame(out, ByteArray(BluetoothFraming.MAX_FRAME_BYTES + 1))
        }
    }
}
