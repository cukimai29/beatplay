package com.utaloom.platform

import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * One BeatPlay process per desktop session. Starting the app again focuses the
 * running window instead of loading VLC, the library, and the network stack a
 * second time (which made startup look slow and left duplicate tray icons).
 */
object SingleInstance {
    private const val PORT = 47821
    private const val SHOW = "show"

    val showRequests = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    /** Returns true for the first instance. A second process asks the first to show and returns false. */
    fun acquireOrNotify(): Boolean {
        val server = runCatching {
            ServerSocket(PORT, 1, InetAddress.getLoopbackAddress()).apply { reuseAddress = true }
        }.getOrNull()

        if (server == null) {
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), PORT).use { socket ->
                    socket.soTimeout = 800
                    socket.getOutputStream().bufferedWriter().use { it.write("$SHOW\n") }
                }
            }
            return false
        }

        thread(name = "beatplay-single-instance", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { socket ->
                        socket.soTimeout = 800
                        val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                        if (line?.trim() == SHOW) showRequests.tryEmit(Unit)
                    }
                }
            }
        }
        return true
    }
}
