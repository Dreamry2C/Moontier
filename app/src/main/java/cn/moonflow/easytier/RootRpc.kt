package cn.moonflow.easytier

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Minimal protobuf wire codec. Unknown fields are retained/skipped, never interpreted as text. */
internal class RpcMessage(val data: ByteArray = byteArrayOf()) {
    private fun fields(): List<Pair<Int, Any>> {
        val result = ArrayList<Pair<Int, Any>>()
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(offset < data.size) { "RPC varint truncated" }
                val byte = data[offset++].toInt() and 255
                require(shift < 63 || byte <= 1) { "RPC varint overflow" }
                value = value or ((byte and 127).toLong() shl shift)
                if (byte < 128) return value
            }
            error("RPC varint overflow")
        }
        while (offset < data.size) {
            val tag = varint().toInt()
            require(tag ushr 3 > 0) { "RPC field number invalid" }
            when (tag and 7) {
                0 -> result += (tag ushr 3) to varint()
                1, 5 -> {
                    offset += if (tag and 7 == 1) 8 else 4
                    require(offset <= data.size) { "RPC fixed field truncated" }
                }
                2 -> {
                    val size = varint()
                    require(size in 0..(data.size - offset).toLong()) { "RPC field truncated" }
                    result += (tag ushr 3) to data.copyOfRange(offset, offset + size.toInt())
                    offset += size.toInt()
                }
                else -> error("Unsupported RPC wire type")
            }
        }
        return result
    }

    private val decoded by lazy { fields() }
    fun number(id: Int, default: Long = 0): Long = decoded.lastOrNull { it.first == id }?.second as? Long ?: default
    fun messages(id: Int): List<RpcMessage> = decoded.filter { it.first == id }.mapNotNull { (it.second as? ByteArray)?.let(::RpcMessage) }
    fun message(id: Int): RpcMessage = messages(id).lastOrNull() ?: RpcMessage()
    fun text(id: Int): String = message(id).data.toString(Charsets.UTF_8)
    fun has(id: Int): Boolean = decoded.any { it.first == id }

    class Builder {
        private val output = ByteArrayOutputStream()
        private fun varint(number: Long) {
            var value = number
            while (value and -128L != 0L) {
                output.write((value.toInt() and 127) or 128)
                value = value ushr 7
            }
            output.write(value.toInt())
        }
        fun number(id: Int, value: Long) = apply { varint((id shl 3).toLong()); varint(value) }
        fun bytes(id: Int, value: ByteArray) = apply {
            varint(((id shl 3) or 2).toLong()); varint(value.size.toLong()); output.write(value)
        }
        fun message(id: Int, value: RpcMessage) = bytes(id, value.data)
        fun text(id: Int, value: String) = bytes(id, value.toByteArray(Charsets.UTF_8))
        fun build() = RpcMessage(output.toByteArray())
    }

    fun uuid(): String = UUID((number(1) shl 32) or number(2), (number(3) shl 32) or number(4)).toString()

    companion object {
        fun uuid(value: String): RpcMessage {
            val id = UUID.fromString(value)
            return Builder().number(1, id.mostSignificantBits ushr 32)
                .number(2, id.mostSignificantBits and 0xffffffffL)
                .number(3, id.leastSignificantBits ushr 32)
                .number(4, id.leastSignificantBits and 0xffffffffL).build()
        }
    }
}

/** EasyTier v2.6.4 common.proto / api_manage.proto over the loopback TCP portal.
 * A single connection serves all polls and commands. No native helper is needed.
 * Calls are serialized; a failed mutation is never automatically replayed.
 */
internal class RootRpc(private val port: Int = 14999) : AutoCloseable {
    private var socket: Socket? = null
    private var transaction = 0L

    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    @Synchronized
    fun call(method: Int, request: RpcMessage = RpcMessage(), timeoutMs: Int = 8000): RpcMessage {
        try {
            val connection = socket ?: Socket().also {
                try {
                    it.connect(InetSocketAddress("127.0.0.1", port), 2000)
                    it.tcpNoDelay = true
                    socket = it
                } catch (error: Exception) { it.close(); throw error }
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            connection.soTimeout = timeoutMs
            val tid = ++transaction
            val descriptor = RpcMessage.Builder().text(2, "WebClientService")
                // easytier-rpc-build assigns one-based method indexes.
                .text(3, "WebClientService").number(4, method.toLong() + 1).build()
            val body = RpcMessage.Builder().message(2, request).number(3, timeoutMs.toLong()).build()
            require(body.data.size <= MAX_RESPONSE_BYTES) { "RPC request too large" }
            val chunks = body.data.asList().chunked(1024)
            chunks.forEachIndexed { index, chunk ->
                val packet = RpcMessage.Builder().number(1, 1).number(2, 1).number(3, tid)
                    .message(4, descriptor).bytes(5, chunk.toByteArray()).number(6, 1)
                    .number(7, chunks.size.toLong()).number(8, index.toLong())
                    .message(10, RpcMessage.Builder().number(1, 1).number(2, 1).build()).build()
                val frame = ByteBuffer.allocate(20 + packet.data.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(16 + packet.data.size).putInt(1).putInt(1)
                    .put(8.toByte()).put(0.toByte()).put(1.toByte()).put(0.toByte())
                    .putInt(packet.data.size).put(packet.data).array()
                connection.getOutputStream().write(frame)
            }
            val input = DataInputStream(connection.getInputStream())
            val pieces = HashMap<Int, ByteArray>()
            var total = -1
            var size = 0
            while (true) {
                connection.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).toInt().also {
                    check(it > 0) { "本地 Core RPC 超时" }
                }
                val length = Integer.reverseBytes(input.readInt())
                require(length in 16..MAX_FRAME_BYTES) { "RPC frame length invalid: $length" }
                val frame = ByteArray(length)
                input.readFully(frame)
                require(frame[8] == 9.toByte()) { "Unexpected RPC packet type" }
                val packet = RpcMessage(frame.copyOfRange(16, frame.size))
                require(packet.number(3) == tid && packet.number(6) == 0L) { "RPC transaction mismatch" }
                require(packet.message(10).number(1, 1) == 1L) { "Unexpected RPC compression" }
                val count = packet.number(7, 1).toInt().coerceAtLeast(1)
                val index = packet.number(8).toInt()
                require(count in 1..32768 && index in 0 until count && (total == -1 || total == count)) { "RPC fragment invalid" }
                total = count
                require(index !in pieces) { "Duplicate RPC fragment" }
                val bytes = packet.message(5).data
                size += bytes.size
                require(size <= MAX_RESPONSE_BYTES) { "RPC response too large" }
                pieces[index] = bytes
                if (pieces.size == total) break
            }
            val output = ByteArrayOutputStream(size)
            for (index in 0 until total) output.write(pieces.getValue(index))
            val response = RpcMessage(output.toByteArray())
            if (response.has(2)) {
                val error = response.message(2)
                val detail = (1..8).firstNotNullOfOrNull { kind ->
                    if (error.has(kind)) error.message(kind).text(1).ifBlank { "错误类型 $kind" } else null
                }
                throw IllegalStateException("本地 Core RPC: ${detail ?: "请求失败"}")
            }
            return response.message(1)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    companion object {
        private const val MAX_FRAME_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
