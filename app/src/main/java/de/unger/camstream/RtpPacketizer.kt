package de.unger.camstream

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class H264RtpPacketizer(
    host: String,
    port: Int,
    private val fps: Int = 5,
    private val mtu: Int = 1200
) : Closeable {

    private val socket = DatagramSocket()
    private val target = InetSocketAddress(host, port)

    private var seq = 0
    private var timestamp = 0L
    private val ssrc = 0x12345678

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun setCodecConfig(sps: ByteArray?, pps: ByteArray?) {
        this.sps = sps
        this.pps = pps
    }

    fun sendAccessUnit(nals: List<ByteArray>, keyFrame: Boolean) {
        if (nals.isEmpty()) return

        timestamp += 90_000L / fps

        val toSend = buildList {
            if (keyFrame) {
                sps?.let { add(it) }
                pps?.let { add(it) }
            }
            addAll(nals)
        }

        toSend.forEachIndexed { index, nal ->
            val marker = index == toSend.lastIndex
            sendNal(nal, marker, timestamp)
        }
    }

    private fun sendNal(nal: ByteArray, marker: Boolean, ts: Long) {
        val maxPayload = mtu - 12
        if (nal.size <= maxPayload) {
            val packet = ByteArray(12 + nal.size)
            writeRtpHeader(packet, marker, ts)
            System.arraycopy(nal, 0, packet, 12, nal.size)
            send(packet)
        } else {
            sendFuA(nal, marker, ts)
        }
    }

    private fun sendFuA(nal: ByteArray, marker: Boolean, ts: Long) {
        val nalHeader = nal[0]
        val nri = nalHeader.toInt() and 0x60
        val type = nalHeader.toInt() and 0x1F

        val fuIndicator = (nri or 28).toByte() // FU-A type = 28
        val payloadMax = mtu - 12 - 2

        var offset = 1
        var first = true

        while (offset < nal.size) {
            val remaining = nal.size - offset
            val chunk = minOf(payloadMax, remaining)
            val end = offset + chunk >= nal.size

            val fuHeader = (
                    (if (first) 0x80 else 0) or
                            (if (end) 0x40 else 0) or
                            type
                    ).toByte()

            val packet = ByteArray(12 + 2 + chunk)
            writeRtpHeader(packet, marker && end, ts)
            packet[12] = fuIndicator
            packet[13] = fuHeader
            System.arraycopy(nal, offset, packet, 14, chunk)
            send(packet)

            offset += chunk
            first = false
        }
    }

    private fun writeRtpHeader(buf: ByteArray, marker: Boolean, ts: Long) {
        buf[0] = 0x80.toByte() // V=2
        buf[1] = ((if (marker) 0x80 else 0x00) or 96).toByte() // dynamic PT=96
        buf[2] = (seq ushr 8).toByte()
        buf[3] = seq.toByte()
        buf[4] = (ts ushr 24).toByte()
        buf[5] = (ts ushr 16).toByte()
        buf[6] = (ts ushr 8).toByte()
        buf[7] = ts.toByte()
        buf[8] = (ssrc ushr 24).toByte()
        buf[9] = (ssrc ushr 16).toByte()
        buf[10] = (ssrc ushr 8).toByte()
        buf[11] = ssrc.toByte()
        seq = (seq + 1) and 0xFFFF
    }

    private fun send(packet: ByteArray) {
        socket.send(DatagramPacket(packet, packet.size, target))
    }

    override fun close() {
        socket.close()
    }
}