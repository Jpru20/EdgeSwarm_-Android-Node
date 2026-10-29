package com.edgeswarm.node

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Wire-compatible with the SWCT/1 Rust and Node.js codecs; no task semantics change. */
internal object SwarmCompactTransportV170 {
    const val MEDIA_TYPE = "application/vnd.swarm.swct.v1"
    const val ACCEPT = "$MEDIA_TYPE, application/json"
    const val RESULT_READY_HEADER = "X-Swarm-SWCT-Result-Ready"

    private const val HEADER_BYTES = 48
    private const val VERSION = 1
    private const val MAX_EXTRA_ENCODED_BYTES = 64 * 1024
    private val MAGIC = byteArrayOf(83, 87, 67, 84) // SWCT

    enum class Kind(val wire: Int, val maxRaw: Int) {
        TASK(1, 2 * 1024 * 1024),
        RESULT(3, 4 * 1024 * 1024)
    }

    data class WireBody(val bytes: ByteArray, val mediaType: String, val compact: Boolean)

    fun decodeTaskResponse(contentType: String?, body: ByteArray): ByteArray {
        return if (contentType?.substringBefore(';')?.trim()
                ?.equals(MEDIA_TYPE, ignoreCase = true) == true) {
            decode(Kind.TASK, body)
        } else {
            body // JSON compatibility, including older server versions
        }
    }

    fun chooseResult(raw: ByteArray, negotiated: Boolean): WireBody {
        val json = WireBody(raw, "application/json; charset=utf-8", false)
        if (!negotiated || raw.size < 1024 || raw.size > Kind.RESULT.maxRaw) return json
        val frame = encode(Kind.RESULT, raw)
        val saved = raw.size - frame.size
        return if (saved >= 128 && saved.toLong() * 100 >= raw.size.toLong() * 8) {
            WireBody(frame, MEDIA_TYPE, true)
        } else json
    }

    fun encode(kind: Kind, raw: ByteArray): ByteArray {
        require(raw.size <= kind.maxRaw) { "RAW_LIMIT_EXCEEDED" }
        var body = raw
        var codec = 0
        if (raw.size >= 512) {
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { stream -> stream.write(raw) }
            }.toByteArray()
            if (raw.size - compressed.size >= 128 &&
                compressed.size.toLong() * 100 <= raw.size.toLong() * 92) {
                body = compressed
                codec = 1
            }
        }
        require(body.size <= kind.maxRaw + MAX_EXTRA_ENCODED_BYTES) {
            "ENCODED_LIMIT_EXCEEDED"
        }
        val out = ByteArrayOutputStream(HEADER_BYTES + body.size)
        DataOutputStream(out).use { frame ->
            frame.write(MAGIC)
            frame.writeByte(VERSION)
            frame.writeByte(kind.wire)
            frame.writeByte(codec)
            frame.writeByte(0) // flags
            frame.writeInt(raw.size) // network big endian
            frame.writeInt(body.size)
            frame.write(MessageDigest.getInstance("SHA-256").digest(raw))
            frame.write(body)
        }
        return out.toByteArray()
    }

    fun decode(kind: Kind, frame: ByteArray): ByteArray {
        require(frame.size >= HEADER_BYTES) { "FRAME_TOO_SHORT" }
        require(frame.copyOfRange(0, 4).contentEquals(MAGIC)) { "BAD_MAGIC" }
        require((frame[4].toInt() and 0xff) == VERSION) { "BAD_VERSION" }
        require((frame[5].toInt() and 0xff) == kind.wire) { "WRONG_KIND" }
        require(frame[7].toInt() == 0) { "BAD_FLAGS" }
        val codec = frame[6].toInt() and 0xff
        val rawLength = readIntBE(frame, 8)
        val encodedLength = readIntBE(frame, 12)
        require(rawLength >= 0 && rawLength <= kind.maxRaw) { "RAW_LIMIT_EXCEEDED" }
        require(encodedLength >= 0 && encodedLength <= kind.maxRaw + MAX_EXTRA_ENCODED_BYTES) {
            "ENCODED_LIMIT_EXCEEDED"
        }
        require(frame.size.toLong() == HEADER_BYTES.toLong() + encodedLength.toLong()) {
            "BAD_LENGTH"
        }
        val raw = when (codec) {
            0 -> {
                require(rawLength == encodedLength) { "IDENTITY_LENGTH_MISMATCH" }
                frame.copyOfRange(HEADER_BYTES, frame.size)
            }
            1 -> {
                val output = ByteArrayOutputStream(minOf(rawLength, 8192))
                GZIPInputStream(ByteArrayInputStream(frame, HEADER_BYTES, encodedLength)).use { stream ->
                    val buffer = ByteArray(8192)
                    var size = 0
                    while (true) {
                        val n = stream.read(buffer)
                        if (n == -1) break
                        if (n == 0) continue
                        size += n
                        require(size <= rawLength && size <= kind.maxRaw) {
                            "DECOMPRESSION_LIMIT_EXCEEDED"
                        }
                        output.write(buffer, 0, n)
                    }
                }
                output.toByteArray()
            }
            else -> throw IllegalArgumentException("UNSUPPORTED_CODEC")
        }
        require(raw.size == rawLength) { "RAW_LENGTH_MISMATCH" }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        require(MessageDigest.isEqual(digest, frame.copyOfRange(16, HEADER_BYTES))) {
            "PAYLOAD_HASH_MISMATCH"
        }
        return raw
    }

    private fun readIntBE(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 255) shl 24) or
            ((data[offset + 1].toInt() and 255) shl 16) or
            ((data[offset + 2].toInt() and 255) shl 8) or
            (data[offset + 3].toInt() and 255)
}
