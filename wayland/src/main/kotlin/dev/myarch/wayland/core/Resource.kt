package dev.myarch.wayland.core

import dev.myarch.wayland.wire.Interface
import dev.myarch.wayland.wire.WireMessage

/**
 * A protocol object living in one client's id space.
 *
 * Wayland is object oriented on the wire: the client allocates ids, we attach
 * behaviour to them, and every request names the id it targets. Subclasses
 * implement [handleRequest] and are free to create further resources.
 */
public abstract class Resource(
    public val client: Client,
    public val id: Int,
    public val version: Int,
    public val spec: Interface,
) {
    /** Handles one request. Unknown opcodes are rejected by the decoder. */
    public abstract fun handleRequest(message: WireMessage)

    /** Called when the resource leaves the client's id space. */
    public open fun onDestroy() {}

    /** Sends an event named [event] from this object. */
    public fun send(event: String, vararg args: Any?) {
        client.sendEvent(id, spec.event(event), args.toList())
    }

    /** True when the client bound a version at least [required]. */
    public fun supports(required: Int): Boolean = version >= required

    protected fun destroySelf() {
        client.destroyResource(id)
    }

    override fun toString(): String = "${spec.name}@$id"
}

/**
 * The `wl_display.error` codes every interface shares.
 *
 * @see dev.myarch.wayland.protocol.WlDisplay.Error
 */
public object DisplayError {
    public const val INVALID_OBJECT: Int = 0
    public const val INVALID_METHOD: Int = 1
    public const val NO_MEMORY: Int = 2
    public const val IMPLEMENTATION: Int = 3
}

/** Raised to send `wl_display.error` and keep serving the client. */
public class ProtocolError(
    public val objectId: Int,
    public val code: Int,
    message: String,
) : RuntimeException(message)
