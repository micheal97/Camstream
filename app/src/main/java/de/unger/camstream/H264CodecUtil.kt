package de.unger.camstream
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

object H264CodecUtil {

    fun splitAccessUnit(data: ByteArray): List<ByteArray> {
        return if (looksLikeAnnexB(data)) {
            splitAnnexB(data)
        } else {
            splitAvcc(data)
        }
    }

    fun extractCsdNal(format: MediaFormat, key: String): ByteArray? {
        val bb = format.getByteBuffer(key)?.duplicate() ?: return null
        val arr = ByteArray(bb.remaining())
        bb.get(arr)
        return stripStartCode(arr)
    }

    private fun looksLikeAnnexB(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return (
                data[0] == 0.toByte() &&
                        data[1] == 0.toByte() &&
                        (
                                (data[2] == 1.toByte()) ||
                                        (data[2] == 0.toByte() && data[3] == 1.toByte())
                                )
                )
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 &&
            data[0] == 0.toByte() &&
            data[1] == 0.toByte() &&
            data[2] == 0.toByte() &&
            data[3] == 1.toByte()
        ) {
            return data.copyOfRange(4, data.size)
        }
        if (data.size >= 3 &&
            data[0] == 0.toByte() &&
            data[1] == 0.toByte() &&
            data[2] == 1.toByte()
        ) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }

    private fun splitAvcc(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (bb.remaining() >= 4) {
            val len = bb.int
            if (len <= 0 || len > bb.remaining()) break
            val nal = ByteArray(len)
            bb.get(nal)
            out += nal
        }
        return out
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val starts = mutableListOf<Int>()

        var i = 0
        while (i < data.size - 3) {
            val is3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val is4 = i < data.size - 4 &&
                    data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (is4 || is3) {
                starts += i
                i += if (is4) 4 else 3
            } else {
                i++
            }
        }

        for (idx in starts.indices) {
            val start = starts[idx]
            val prefixLen = if (
                start < data.size - 3 &&
                data[start] == 0.toByte() &&
                data[start + 1] == 0.toByte() &&
                data[start + 2] == 0.toByte() &&
                data[start + 3] == 1.toByte()
            ) 4 else 3

            val nalStart = start + prefixLen
            val nalEnd = if (idx + 1 < starts.size) starts[idx + 1] else data.size
            if (nalEnd > nalStart) {
                out += data.copyOfRange(nalStart, nalEnd)
            }
        }

        return out
    }
}