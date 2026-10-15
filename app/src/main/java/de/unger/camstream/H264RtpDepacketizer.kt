package de.unger.camstream

import java.io.ByteArrayOutputStream

class H264RtpDepacketizer {

    private var fuBuffer: ByteArrayOutputStream? = null

    // HIER NEU: Speicher für die Konfigurationsdaten
    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    fun reset() {
        fuBuffer = null
        sps = null
        pps = null
    }

    // Hilfsfunktion: Fügt den Annex-B Startcode (00 00 00 01) vor ein NAL-Paket
    private fun prefixWithStartCode(nal: ByteArray): ByteArray {
        val result = ByteArray(4 + nal.size)
        result[0] = 0x00
        result[1] = 0x00
        result[2] = 0x00
        result[3] = 0x01
        System.arraycopy(nal, 0, result, 4, nal.size)
        return result
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
            // HIER ERWEITERT: SPS (7) und PPS (8) abfangen
            nalType == 7 -> {
                sps = prefixWithStartCode(payload)
                emptyList() // Braucht nicht decodiert zu werden, ist Config!
            }

            nalType == 8 -> {
                pps = prefixWithStartCode(payload)
                emptyList() // Ist Config!
            }

            // Single NAL Einzelframes (z.B. Typ 1 oder Typ 5 IDR-Slices)
            nalType in 1..23 -> listOf(prefixWithStartCode(payload))

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
            // HIER ERWEITERT: Auch zusammengesetzte FU-As brauchen den Startcode!
            listOf(prefixWithStartCode(nal))
        } else {
            emptyList()
        }
    }
}