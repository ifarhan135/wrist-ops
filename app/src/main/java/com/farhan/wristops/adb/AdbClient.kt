package com.farhan.wristops.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal adb client, enough to connect to a device over TCP and run one
 * shell command. No library: the whole protocol we need is a handshake, an
 * optional auth round trip, and a single stream.
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val crypto: AdbCrypto
) {

    class AdbException(message: String) : IOException(message)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    private class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    /**
     * Connects, authenticates and runs [command], returning whatever the shell
     * printed. The device may cut the connection as the command takes effect
     * (that is normal for a power command), which is not treated as a failure.
     */
    fun runShellCommand(command: String): String {
        try {
            open()
            connectAndAuthenticate()
            return openShellStream(command)
        } finally {
            close()
        }
    }

    private fun open() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
    }

    private fun close() {
        try {
            socket?.close()
        } catch (e: IOException) {
            // Nothing useful to do while tearing down.
        }
        socket = null
        input = null
        output = null
    }

    private fun connectAndAuthenticate() {
        send(A_CNXN, VERSION, MAX_DATA, "host::wristops\u0000".toByteArray(Charsets.US_ASCII))

        var sentSignature = false
        var sentPublicKey = false

        while (true) {
            val message = receive()
            when (message.command) {
                A_CNXN -> return

                A_AUTH -> {
                    if (message.arg0 != AUTH_TOKEN) {
                        throw AdbException("Unexpected auth stage ${message.arg0}")
                    }
                    if (!sentSignature) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, crypto.signToken(message.payload))
                        sentSignature = true
                    } else if (!sentPublicKey) {
                        // The device has not seen this key before. Sending it makes the
                        // TV show "Allow debugging from this device?", which needs a human.
                        socket?.soTimeout = AUTHORIZE_TIMEOUT_MS
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.adbPublicKeyPayload())
                        sentPublicKey = true
                    } else {
                        throw AdbException("Device rejected our key")
                    }
                }

                A_CLSE -> throw AdbException("Device closed the connection during auth")
            }
        }
    }

    private fun openShellStream(command: String): String {
        val localId = 1
        send(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.US_ASCII))

        socket?.soTimeout = READ_TIMEOUT_MS
        val collected = StringBuilder()
        var remoteId = 0
        var opened = false

        while (true) {
            val message = try {
                receive()
            } catch (e: IOException) {
                // A power command kills the link mid-stream; if the shell was
                // accepted, the command already ran.
                if (opened) return collected.toString()
                throw e
            }

            when (message.command) {
                A_OKAY -> {
                    if (message.arg1 == localId) {
                        remoteId = message.arg0
                        opened = true
                    }
                }

                A_WRTE -> {
                    collected.append(String(message.payload, Charsets.UTF_8))
                    // The remote stalls until each write is acknowledged.
                    send(A_OKAY, localId, message.arg0, ByteArray(0))
                }

                A_CLSE -> {
                    if (!opened) throw AdbException("Device refused to run the command")
                    send(A_CLSE, localId, remoteId, ByteArray(0))
                    return collected.toString()
                }
            }
        }
    }

    private fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val stream = output ?: throw AdbException("Not connected")
        val header = ByteArray(HEADER_SIZE)
        putInt(header, 0, command)
        putInt(header, 4, arg0)
        putInt(header, 8, arg1)
        putInt(header, 12, payload.size)
        putInt(header, 16, checksum(payload))
        putInt(header, 20, command.inv())
        stream.write(header)
        if (payload.isNotEmpty()) stream.write(payload)
        stream.flush()
    }

    private fun receive(): Message {
        val stream = input ?: throw AdbException("Not connected")
        val header = ByteArray(HEADER_SIZE)
        stream.readFully(header)

        val command = getInt(header, 0)
        val arg0 = getInt(header, 4)
        val arg1 = getInt(header, 8)
        val length = getInt(header, 12)

        if (command != getInt(header, 20).inv()) {
            throw AdbException("Corrupt adb header")
        }
        if (length < 0 || length > MAX_DATA) {
            throw AdbException("Implausible payload length $length")
        }

        val payload = ByteArray(length)
        if (length > 0) stream.readFully(payload)
        return Message(command, arg0, arg1, payload)
    }

    /** adb's "checksum" is just the sum of the payload bytes, unsigned. */
    private fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += (b.toInt() and 0xFF)
        return sum
    }

    private fun putInt(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun getInt(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)
    }

    companion object {
        const val DEFAULT_PORT = 5555

        private const val HEADER_SIZE = 24
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 256 * 1024

        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_WRTE = 0x45545257
        private const val A_CLSE = 0x45534c43

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3

        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 10000
        private const val AUTHORIZE_TIMEOUT_MS = 90000
    }
}
