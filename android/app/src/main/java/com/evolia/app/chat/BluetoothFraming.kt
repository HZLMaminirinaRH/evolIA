package com.evolia.app.chat

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed framing for streaming opaque chat envelopes over a Bluetooth
 * RFCOMM socket — a continuous byte stream, unlike UDP's self-delimiting
 * datagrams. Each frame is a 4-byte big-endian length followed by that many
 * bytes. The length is capped at ChatIntake.MAX_DATAGRAM_BYTES so a hostile peer
 * cannot make us allocate an unbounded buffer (a garbled/oversized length is an
 * IOException the caller scores as malformed and drops the link). Pure java.io so
 * it unit-tests on the JVM with in-memory byte streams.
 */
object BluetoothFraming {

    const val MAX_FRAME_BYTES = ChatIntake.MAX_DATAGRAM_BYTES

    /** Write one length-prefixed frame and flush it. */
    fun writeFrame(out: OutputStream, payload: ByteArray) {
        require(payload.size in 1..MAX_FRAME_BYTES) { "frame size out of range: ${payload.size}" }
        val n = payload.size
        out.write(
            byteArrayOf(
                (n ushr 24).toByte(),
                (n ushr 16).toByte(),
                (n ushr 8).toByte(),
                n.toByte(),
            ),
        )
        out.write(payload)
        out.flush()
    }

    /** Read one frame, or null on a clean EOF at a frame boundary. A truncated
     *  frame or an out-of-range length throws IOException. */
    fun readFrame(input: InputStream): ByteArray? {
        val first = input.read()
        if (first == -1) return null // clean close between frames
        val header = ByteArray(4)
        header[0] = first.toByte()
        readFully(input, header, 1, 3)
        val n = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        if (n <= 0 || n > MAX_FRAME_BYTES) throw IOException("bad frame length: $n")
        val payload = ByteArray(n)
        readFully(input, payload, 0, n)
        return payload
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val r = input.read(buf, off + read, len - read)
            if (r == -1) throw EOFException("stream closed mid-frame")
            read += r
        }
    }
}
