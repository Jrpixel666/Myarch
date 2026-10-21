package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.WlCallback
import dev.myarch.wayland.protocol.WlDisplay
import dev.myarch.wayland.protocol.WlRegistry
import dev.myarch.wayland.wire.Interface
import dev.myarch.wayland.wire.MessageDecoder
import dev.myarch.wayland.wire.MessageEncoder
import dev.myarch.wayland.wire.Message
import dev.myarch.wayland.wire.UntypedNewId
import dev.myarch.wayland.wire.WireFormatException
import dev.myarch.wayland.wire.WireMessage
import dev.myarch.wayland.wire.WIRE_ORDER
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/** The id the protocol reserves for `wl_display`. */
public const val DISPLAY_ID: Int = 1

/** Client ids at or above this value are allocated by the server. */
public const val SERVER_ID_BASE: Int = 0xFF000000.toInt()

/**
 * One connected Wayland client: its object id space, its socket, and the loop
 * that turns bytes into dispatched requests.
 *
 * A client is driven by a single thread ([serve]); everything the compositor
 * does on behalf of that client therefore happens on that thread, and shared
 * compositor state is guarded by the compositor's own lock.
 */
public class Client(
    public val compositor: Compositor,
    private val transport: ClientTransport,
    public val clientId: Int,
) {
    private val objects = HashMap<Int, Resource>()
    private val receivedFds = ArrayDeque<FileDescriptor>()
    private val readBuffer: ByteBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES).order(WIRE_ORDER)
    private val decoder = MessageDecoder { objectId -> objects[objectId]?.spec }

    @Volatile
    private var running = true

    private val log get() = compositor.log

    init {
        objects[DISPLAY_ID] = DisplayResource(this)
    }

    /** Every live resource, for diagnostics. */
    public fun resourceCount(): Int = objects.size

    public fun resource(id: Int): Resource? = objects[id]

    /**
     * Registers [resource] under the id the client allocated for it.
     *
     * Reusing a live id is a protocol error; a well-behaved client never does
     * it, but a buggy one would otherwise silently corrupt our bookkeeping.
     */
    public fun addResource(resource: Resource) {
        val previous = objects.put(resource.id, resource)
        if (previous != null) {
            objects[resource.id] = previous
            throw ProtocolError(
                DISPLAY_ID,
                DisplayError.INVALID_OBJECT,
                "id ${resource.id} is already bound to $previous",
            )
        }
    }

    /**
     * Removes [id] from the id space and tells the client the id is free again.
     *
     * `wl_display.delete_id` is what lets clients recycle ids without racing
     * the server, so it must be sent for every client-allocated object.
     */
    public fun destroyResource(id: Int) {
        val resource = objects.remove(id) ?: return
        runCatching { resource.onDestroy() }
            .onFailure { log.warn("teardown of $resource failed", it) }
        if (id < SERVER_ID_BASE) {
            sendEvent(DISPLAY_ID, WlDisplay.SPEC.event("delete_id"), listOf(id))
        }
    }

    /** Encodes and writes one event. */
    public fun sendEvent(objectId: Int, message: Message, args: List<Any?>) {
        if (!running) return
        try {
            val encoded = MessageEncoder.encode(objectId, message, args)
            synchronized(transport) { transport.write(encoded.bytes, encoded.fds) }
        } catch (io: IOException) {
            log.warn("client $clientId write failed, dropping connection", io)
            running = false
        }
    }

    /** Sends `wl_display.error` and, for fatal cases, stops serving. */
    public fun postError(objectId: Int, code: Int, message: String) {
        log.warn("protocol error on client $clientId: $message")
        sendEvent(DISPLAY_ID, WlDisplay.SPEC.event("error"), listOf(objectId, code, message))
    }

    /**
     * Reads and dispatches until the peer disconnects or the connection breaks.
     */
    public fun serve() {
        log.info("client $clientId connected (${transport.describe})")
        try {
            while (running) {
                val read = transport.read(readBuffer)
                if (read < 0) break
                receivedFds.addAll(transport.drainReceivedFds())
                readBuffer.flip()
                dispatchBuffered()
                readBuffer.compact()
            }
        } catch (format: WireFormatException) {
            // Nothing in the stream tells us where the next message starts, so
            // the only safe response is to drop the client.
            postError(DISPLAY_ID, DisplayError.INVALID_METHOD, format.message ?: "malformed message")
            log.warn("client $clientId sent a malformed message", format)
        } catch (io: IOException) {
            log.debug("client $clientId disconnected: ${io.message}")
        } finally {
            close()
        }
    }

    private fun dispatchBuffered() {
        while (true) {
            val message = decoder.decode(readBuffer, receivedFds) ?: return
            dispatch(message)
        }
    }

    private fun dispatch(message: WireMessage) {
        val target = objects[message.objectId]
        if (target == null) {
            postError(
                message.objectId,
                DisplayError.INVALID_OBJECT,
                "request for object ${message.objectId} which no longer exists",
            )
            return
        }
        val spec = target.spec.request(message.opcode)
        try {
            // One lock for the whole resource graph: see Compositor's threading
            // notes. Requests and calls from the Android UI thread both take it,
            // so protocol state is never touched concurrently.
            synchronized(compositor.lock) { target.handleRequest(message) }
        } catch (error: ProtocolError) {
            postError(error.objectId, error.code, error.message ?: "protocol error")
        } catch (unexpected: RuntimeException) {
            // A bug in us, not in the client: report it as such rather than
            // pretending the client misbehaved, and keep the session alive.
            log.warn("while handling ${target.spec.name}.${spec?.name} on $target", unexpected)
            postError(
                message.objectId,
                DisplayError.IMPLEMENTATION,
                "myarch failed to handle ${target.spec.name}.${spec?.name}: ${unexpected.message}",
            )
        }
        if (spec?.destructor == true && objects.containsKey(message.objectId)) {
            synchronized(compositor.lock) { destroyResource(message.objectId) }
        }
    }

    /** Tears the client down, releasing every resource it owns. */
    public fun close() {
        if (!running && objects.isEmpty()) return
        running = false
        synchronized(compositor.lock) {
            val ids = objects.keys.toList()
            for (id in ids) {
                val resource = objects.remove(id) ?: continue
                runCatching { resource.onDestroy() }
                    .onFailure { log.warn("teardown of $resource failed", it) }
            }
        }
        runCatching { transport.close() }
        compositor.onClientGone(this)
        log.info("client $clientId disconnected")
    }

    /** `wl_display`, the one object that exists before the client asks. */
    private class DisplayResource(client: Client) :
        Resource(client, DISPLAY_ID, WlDisplay.VERSION, WlDisplay.SPEC) {

        override fun handleRequest(message: WireMessage) {
            when (message.opcode) {
                WlDisplay.Request.SYNC -> {
                    val callbackId = message.newId(0)
                    val callback = CallbackResource(client, callbackId)
                    client.addResource(callback)
                    callback.done(client.compositor.nextSerial())
                }
                WlDisplay.Request.GET_REGISTRY -> {
                    val registry = RegistryResource(client, message.newId(0))
                    client.addResource(registry)
                    registry.publishGlobals()
                }
                else -> throw ProtocolError(
                    id,
                    DisplayError.INVALID_METHOD,
                    "wl_display has no request ${message.opcode}",
                )
            }
        }
    }
}

/** `wl_callback`: a one-shot "the thing you asked about has happened". */
public class CallbackResource(client: Client, id: Int) :
    Resource(client, id, WlCallback.VERSION, WlCallback.SPEC) {

    override fun handleRequest(message: WireMessage): Unit =
        throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_callback has no requests")

    /** Fires the callback and retires the object, as the protocol requires. */
    public fun done(data: Int) {
        send("done", data)
        destroySelf()
    }
}

/** `wl_registry`: advertises the globals and binds them on request. */
public class RegistryResource(client: Client, id: Int) :
    Resource(client, id, WlRegistry.VERSION, WlRegistry.SPEC) {

    public fun publishGlobals() {
        for (global in client.compositor.globals) {
            send("global", global.name, global.spec.name, global.version)
        }
    }

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlRegistry.Request.BIND -> {
                val name = message.uint(0)
                val requested = message.args[1] as UntypedNewId
                val global = client.compositor.globals.firstOrNull { it.name == name }
                    ?: throw ProtocolError(
                        id,
                        DisplayError.INVALID_OBJECT,
                        "no global numbered $name",
                    )
                if (global.spec.name != requested.interfaceName) {
                    throw ProtocolError(
                        id,
                        DisplayError.INVALID_OBJECT,
                        "global $name is ${global.spec.name}, not ${requested.interfaceName}",
                    )
                }
                if (requested.version < 1 || requested.version > global.version) {
                    throw ProtocolError(
                        id,
                        DisplayError.INVALID_OBJECT,
                        "${global.spec.name} version ${requested.version} requested, " +
                            "we implement 1..${global.version}",
                    )
                }
                val bound = global.bind(client, requested.id, requested.version)
                client.addResource(bound)
            }
            else -> throw ProtocolError(
                id,
                DisplayError.INVALID_METHOD,
                "wl_registry has no request ${message.opcode}",
            )
        }
    }
}

/** An advertised global and the factory that binds it. */
public class Global(
    public val name: Int,
    public val spec: Interface,
    public val version: Int,
    private val factory: (Client, Int, Int) -> Resource,
) {
    public fun bind(client: Client, id: Int, version: Int): Resource = factory(client, id, version)
}

private const val READ_BUFFER_BYTES = 1 shl 16
