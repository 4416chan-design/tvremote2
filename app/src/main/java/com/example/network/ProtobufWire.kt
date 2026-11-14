package com.example.network

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Lightweight pure-Kotlin Protobuf binary wire codec and varint framing
 * for Android TV Remote Protocol v2 (PairingMessage & RemoteMessage).
 */
object ProtobufWire {

    class Writer {
        private val out = ByteArrayOutputStream()

        fun writeVarintField(fieldNumber: Int, value: Int) {
            writeVarint(((fieldNumber shl 3) or 0).toLong())
            writeVarint(value.toLong())
        }

        fun writeBoolField(fieldNumber: Int, value: Boolean) {
            writeVarint(((fieldNumber shl 3) or 0).toLong())
            out.write(if (value) 1 else 0)
        }

        fun writeStringField(fieldNumber: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeBytesField(fieldNumber, bytes)
        }

        fun writeBytesField(fieldNumber: Int, bytes: ByteArray) {
            writeVarint(((fieldNumber shl 3) or 2).toLong())
            writeVarint(bytes.size.toLong())
            out.write(bytes)
        }

        fun writeMessageField(fieldNumber: Int, build: Writer.() -> Unit) {
            val nested = Writer().apply(build).toByteArray()
            writeBytesField(fieldNumber, nested)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun writeVarint(raw: Long) {
            var v = raw
            while ((v and -128L) != 0L) {
                out.write(((v and 0x7FL) or 0x80L).toInt())
                v = v ushr 7
            }
            out.write((v and 0x7FL).toInt())
        }
    }

    data class Field(
        val number: Int,
        val wireType: Int,
        val varint: Long = 0L,
        val bytes: ByteArray = ByteArray(0)
    ) {
        fun asString(): String = bytes.toString(Charsets.UTF_8)
        fun asMessage(): List<Field> = parseFields(bytes)
    }

    fun encode(build: Writer.() -> Unit): ByteArray = Writer().apply(build).toByteArray()

    fun frameMessage(payload: ByteArray): ByteArray {
        val frame = ByteArrayOutputStream(payload.size + 5)
        var v = payload.size
        while ((v and -128) != 0) {
            frame.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        frame.write(v and 0x7F)
        frame.write(payload)
        return frame.toByteArray()
    }

    fun writeFramedMessage(out: OutputStream, payload: ByteArray) {
        out.write(frameMessage(payload))
        out.flush()
    }

    fun readFramedMessage(input: InputStream): ByteArray {
        val length = readVarintFromStream(input).toInt()
        if (length < 0 || length > 1024 * 1024) {
            throw IllegalStateException("Invalid protobuf frame length: $length")
        }
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read < 0) throw EOFException("Stream closed while reading frame")
            offset += read
        }
        return buffer
    }

    fun parseFields(data: ByteArray): List<Field> {
        val result = mutableListOf<Field>()
        var idx = 0
        while (idx < data.size) {
            val (tag, nextIdx) = readVarint(data, idx)
            idx = nextIdx
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            when (wireType) {
                0 -> {
                    val (v, after) = readVarint(data, idx)
                    idx = after
                    result.add(Field(fieldNum, wireType, varint = v))
                }
                1 -> {
                    idx = (idx + 8).coerceAtMost(data.size)
                }
                2 -> {
                    val (lenLong, afterLen) = readVarint(data, idx)
                    val len = lenLong.toInt().coerceAtLeast(0)
                    val end = (afterLen + len).coerceAtMost(data.size)
                    val slice = data.copyOfRange(afterLen, end)
                    idx = end
                    result.add(Field(fieldNum, wireType, bytes = slice))
                }
                5 -> {
                    idx = (idx + 4).coerceAtMost(data.size)
                }
                else -> break
            }
        }
        return result
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < data.size && shift < 64) {
            val b = data[i++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result to i
            shift += 7
        }
        return result to i
    }

    private fun readVarintFromStream(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = input.read()
            if (b < 0) throw EOFException("Stream closed while reading varint length")
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalStateException("Malformed varint in stream")
    }
}
