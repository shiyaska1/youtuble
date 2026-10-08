package com.mobicareapp.wifisend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLEncoder
import java.util.Collections

/**
 * Serves one file over plain HTTP on the local network — for sending a scanned PDF straight to a
 * laptop connected to the phone's own mobile hotspot at Wi-Fi speed, much faster than Bluetooth
 * and needing no app on the receiving side, just a browser pointed at the shown link. Hand-rolled
 * on a raw [ServerSocket] instead of pulling in a server library: the whole job is "accept a
 * connection, ignore whatever it asks for, write this one file back".
 */
class WifiSendServer(private val file: File) {

    sealed class TransferState {
        data object Starting : TransferState()
        data class Running(val urls: List<String>) : TransferState()
        data object Transferring : TransferState()
        data object Done : TransferState()
        data class Failed(val message: String) : TransferState()
    }

    private val _state = MutableStateFlow<TransferState>(TransferState.Starting)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket(0)
                serverSocket = socket
                val urls = localAddresses().map { "http://$it:${socket.localPort}/${encodedName()}" }
                _state.value = TransferState.Running(urls)
                while (true) {
                    val client = socket.accept()
                    _state.value = TransferState.Transferring
                    runCatching { serveOnce(client) }
                    _state.value = TransferState.Done
                }
            } catch (e: IOException) {
                // Expected once stop() closes serverSocket — accept() throws and ends the loop;
                // only report it as a real failure if we didn't ask for the socket to close.
                if (serverSocket != null) _state.value = TransferState.Failed(e.message ?: "Server error")
            }
        }
    }

    fun stop() {
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        job?.cancel()
        job = null
    }

    private fun encodedName(): String = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")

    private fun serveOnce(client: Socket) {
        client.use { socket ->
            // Drains the request line + headers without closing the input stream — closing a
            // Socket's InputStream on the JVM closes the whole socket, which would break writing
            // the response right after. The request itself is ignored: this server has exactly
            // one file to offer no matter what path is asked for.
            consumeRequestHeaders(socket.getInputStream())

            val mimeType = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val out = socket.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mimeType\r\n" +
                        "Content-Length: ${file.length()}\r\n" +
                        "Content-Disposition: attachment; filename=\"${file.name}\"\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            file.inputStream().use { it.copyTo(out) }
            out.flush()
        }
    }

    private fun consumeRequestHeaders(input: InputStream) {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) {
                if (line.toString().trimEnd('\r').isEmpty()) break
                line.clear()
            } else if (b != '\r'.code) {
                line.append(b.toChar())
            }
        }
    }

    // The phone's mobile-data connection also shows up here as a private-looking IPv4 address
    // (often in the carrier's own 10.x.x.x CGNAT range) — that address is only reachable from the
    // carrier's network, not from a laptop connected to this phone's own Wi-Fi hotspot, so a link
    // built from it fails with exactly "site can't be reached". Interfaces named like this are
    // the cellular radio, never the Wi-Fi/hotspot one, so they're excluded outright.
    private val cellularInterfaceHints = listOf("rmnet", "ccmni", "pdp", "cdma", "cellular")

    private fun localAddresses(): List<String> {
        val candidates = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { iface -> cellularInterfaceHints.none { iface.name.contains(it, ignoreCase = true) } }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }

        // Android's own hotspot almost always hands itself a 192.168.x.x address — prefer that
        // over anything else that slipped through the filter above (e.g. a VPN's virtual adapter).
        val preferred = candidates.filter { it.startsWith("192.168.") }
        return preferred.ifEmpty { candidates }
    }
}
