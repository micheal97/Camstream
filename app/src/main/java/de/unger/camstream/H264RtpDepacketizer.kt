package de.unger.camstream

import java.io.ByteArrayOutputStream

class H264RtpDepacketizer {

    private var fuBuffer: ByteArrayOutputStream? = null
    fun reset() {
        fuBuffer = null
    }
    fun push(packet: ByteArray, length: Int): List<ByteArray> {
        if (length < 12) return emptyList()

        val version = (packet[0].toInt() ushr 6) and 0x03
        if (version != 2) return emptyList()

        val cc = packet[0].toInt() and 0x0F
        val extension = ((packet[0].toInt() ushr 4) and 0x01) == 1

        var offset = 12 + cc * 4
        if (length <= offset) return emptyList()

        if (extension) {
            if (length < offset + 4) return emptyList()
            val extLenWords = ((packet[offset + 2].toInt() and 0xFF) shl 8) or
                    (packet[offset + 3].toInt() and 0xFF)
            offset += 4 + extLenWords * 4
            if (length <= offset) return emptyList()
        }

        val payload = packet.copyOfRange(offset, length)
        if (payload.isEmpty()) return emptyList()

        val nalType = payload[0].toInt() and 0x1F

        return when {
            nalType in 1..23 -> listOf(payload) // single NAL unit

            nalType == 28 -> handleFuA(payload)

            else -> emptyList()
        }
    }

    private fun handleFuA(payload: ByteArray): List<ByteArray> {
        if (payload.size < 2) return emptyList()

        val fuIndicator = payload[0]
        val fuHeader = payload[1]

        val start = (fuHeader.toInt() and 0x80) != 0
        val end = (fuHeader.toInt() and 0x40) != 0
        val nalType = fuHeader.toInt() and 0x1F
        val nri = fuIndicator.toInt() and 0x60
        val nalHeader = (nri or nalType).toByte()

        val frag = payload.copyOfRange(2, payload.size)

        if (start) {
            fuBuffer = ByteArrayOutputStream()
            fuBuffer!!.write(nalHeader.toInt())
            fuBuffer!!.write(frag)
            return emptyList()
        }

        val buf = fuBuffer ?: return emptyList()
        buf.write(frag)

        return if (end) {
            val nal = buf.toByteArray()
            fuBuffer = null
            listOf(nal)
        } else {
            emptyList()
        }
    }
}