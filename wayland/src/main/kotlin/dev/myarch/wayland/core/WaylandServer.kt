package dev.myarch.wayland.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Accepts connections and gives each client a thread.
 *
 * A thread per client is the right shape here: a phone runs a handful of Linux
 * windows, not hundreds, and a blocking read loop is far easier to reason about
 * than a hand-rolled poll loop -- especially since `LocalSocket` has no
 * selectable channel on Android.
 */
public class WaylandServer(
    private val transport: ServerTransport,
    public val compositor: Compositor,
    private val log: WaylandLog = WaylandLog.None,
) : Closeable {

    private val clientIds = AtomicInteger(1)
    private val threads = ArrayList<Thread>()

    @Volatile
    private var running = false

    private var acceptThread: Thread? = null

    /** Starts accepting in the background. */
    public fun start() {
        check(!running) { "server already started" }
        running = true
        acceptThread = Thread({ acceptLoop() }, "myarch-wayland-accept").apply {
            isDaemon = true
            start()
        }
        log.info("wayland server listening")
    }

    private fun acceptLoop() {
        while (running) {
            val clientTransport = try {
                transport.accept()
            } catch (io: IOException) {
                if (running) log.warn("accept failed", io)
                null
            } ?: break
            val client = Client(compositor, clientTransport, clientIds.getAndIncrement())
            compositor.onClientArrived(client)
            val thread = Thread({ client.serve() }, "myarch-wayland-client-${client.clientId}").apply {
                isDaemon = true
            }
            synchronized(threads) {
                threads.removeAll { !it.isAlive }
                threads.add(thread)
            }
            thread.start()
        }
        log.info("wayland server stopped accepting")
    }

    /** Number of client threads still alive; for diagnostics. */
    public fun activeClients(): Int = synchronized(threads) { threads.count { it.isAlive } }

    override fun close() {
        running = false
        runCatching { transport.close() }
        acceptThread?.interrupt()
        synchronized(threads) {
            for (thread in threads) thread.interrupt()
            threads.clear()
        }
    }
}
