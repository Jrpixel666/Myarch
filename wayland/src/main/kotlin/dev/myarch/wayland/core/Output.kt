package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.WlOutput
import dev.myarch.wayland.wire.WireMessage

/**
 * The single `wl_output` Myarch advertises: the phone's screen.
 *
 * Clients use it to pick a scale factor and to decide how large a maximised
 * window should be, so the values here come from the real display metrics
 * rather than being invented.
 */
public class OutputState(
    public var widthPx: Int,
    public var heightPx: Int,
    public var physicalWidthMm: Int,
    public var physicalHeightMm: Int,
    /** Refresh rate in mHz, e.g. 60000 for 60 Hz. */
    public var refreshMilliHz: Int,
    /** Integer scale factor; 2 or 3 on most phones. */
    public var scale: Int,
    public var name: String = "myarch-0",
    public var description: String = "Myarch virtual output",
) {
    /** Window size in logical pixels, which is what clients should render. */
    public val logicalWidth: Int get() = widthPx / scale

    public val logicalHeight: Int get() = heightPx / scale
}

/** `wl_output`: one screen's geometry, mode and scale. */
public class OutputResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlOutput.SPEC) {

    init {
        sendState(client.compositor.output)
    }

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlOutput.Request.RELEASE -> destroySelf()
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_output opcode ${message.opcode}")
        }
    }

    /** Sends the full output state, ending with `done` as the protocol requires. */
    public fun sendState(output: OutputState) {
        send(
            "geometry",
            0,
            0,
            output.physicalWidthMm,
            output.physicalHeightMm,
            WlOutput.Subpixel.UNKNOWN,
            "Myarch",
            "Virtual",
            WlOutput.Transform.NORMAL,
        )
        send(
            "mode",
            WlOutput.Mode.CURRENT or WlOutput.Mode.PREFERRED,
            output.widthPx,
            output.heightPx,
            output.refreshMilliHz,
        )
        if (supports(SCALE_SINCE)) {
            send("scale", output.scale)
        }
        if (supports(NAME_SINCE)) {
            send("name", output.name)
            send("description", output.description)
        }
        if (supports(DONE_SINCE)) {
            send("done")
        }
    }

    private companion object {
        const val DONE_SINCE = 2
        const val SCALE_SINCE = 2
        const val NAME_SINCE = 4
    }
}
