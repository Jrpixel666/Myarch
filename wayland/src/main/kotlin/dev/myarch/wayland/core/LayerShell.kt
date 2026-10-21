package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.ZwlrLayerShellV1
import dev.myarch.wayland.protocol.ZwlrLayerSurfaceV1
import dev.myarch.wayland.wire.WireMessage

/**
 * `zwlr_layer_shell_v1`: panels, bars and desktop widgets.
 *
 * This is the protocol Quickshell (and Waybar, and mako) use for anything that
 * is not an application window. It matters more here than on a desktop: in
 * Myarch, layer surfaces are the natural fit for the launcher's home screen,
 * so an Omarchy bar or widget renders directly onto the Android home screen
 * instead of becoming a task of its own.
 *
 * The four layers map onto the launcher as follows:
 *
 * | Layer      | Where it is drawn                                  |
 * |------------|----------------------------------------------------|
 * | background | behind the icon grid, i.e. as the wallpaper        |
 * | bottom     | behind the icon grid, above the wallpaper          |
 * | top        | above the icon grid (bars, widget panels)         |
 * | overlay    | above everything, including open Linux windows     |
 */
public class LayerShellResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, ZwlrLayerShellV1.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            ZwlrLayerShellV1.Request.GET_LAYER_SURFACE -> {
                val newId = message.newId(0)
                val surface = client.requireSurface(id, message.objectId(1))
                // Argument 2 is the output; Myarch only has one, so a null
                // output (meaning "you choose") needs no special case.
                val layer = message.uint(3)
                val namespace = message.string(4)
                if (surface.role != null) {
                    throw ProtocolError(
                        id,
                        ZwlrLayerShellV1.Error.ROLE,
                        "surface already has the ${surface.role?.roleName} role",
                    )
                }
                if (layer > ZwlrLayerShellV1.Layer.OVERLAY) {
                    throw ProtocolError(id, ZwlrLayerShellV1.Error.INVALID_LAYER, "layer $layer")
                }
                val layerSurface = LayerSurfaceResource(client, newId, version, surface, layer, namespace)
                surface.role = layerSurface
                client.addResource(layerSurface)
                layerSurface.sendInitialConfigure()
            }
            ZwlrLayerShellV1.Request.DESTROY -> destroySelf()
            else -> throw ProtocolError(
                id,
                DisplayError.INVALID_METHOD,
                "zwlr_layer_shell_v1 opcode ${message.opcode}",
            )
        }
    }
}

/** Which edges a layer surface is pinned to, and how big it wants to be. */
public data class LayerGeometry(
    val anchor: Int,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val marginTop: Int = 0,
    val marginRight: Int = 0,
    val marginBottom: Int = 0,
    val marginLeft: Int = 0,
    val exclusiveZone: Int = 0,
) {
    public val anchorsTop: Boolean get() = anchor and ZwlrLayerSurfaceV1.Anchor.TOP != 0
    public val anchorsBottom: Boolean get() = anchor and ZwlrLayerSurfaceV1.Anchor.BOTTOM != 0
    public val anchorsLeft: Boolean get() = anchor and ZwlrLayerSurfaceV1.Anchor.LEFT != 0
    public val anchorsRight: Boolean get() = anchor and ZwlrLayerSurfaceV1.Anchor.RIGHT != 0

    /**
     * Resolves the size to configure, given the screen.
     *
     * A zero dimension means "you decide", which the protocol only allows when
     * the surface is anchored to both opposite edges -- so it stretches.
     */
    public fun resolveSize(outputWidth: Int, outputHeight: Int): Pair<Int, Int> {
        val width = when {
            requestedWidth > 0 -> requestedWidth
            anchorsLeft && anchorsRight -> outputWidth - marginLeft - marginRight
            else -> outputWidth
        }
        val height = when {
            requestedHeight > 0 -> requestedHeight
            anchorsTop && anchorsBottom -> outputHeight - marginTop - marginBottom
            else -> outputHeight
        }
        return width.coerceAtLeast(1) to height.coerceAtLeast(1)
    }

    /** Where the surface sits on the screen once sized. */
    public fun resolvePosition(
        width: Int,
        height: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): Pair<Int, Int> {
        val x = when {
            anchorsLeft && !anchorsRight -> marginLeft
            anchorsRight && !anchorsLeft -> outputWidth - width - marginRight
            else -> (outputWidth - width) / 2 + marginLeft - marginRight
        }
        val y = when {
            anchorsTop && !anchorsBottom -> marginTop
            anchorsBottom && !anchorsTop -> outputHeight - height - marginBottom
            else -> (outputHeight - height) / 2 + marginTop - marginBottom
        }
        return x to y
    }
}

/** What the launcher needs to place one layer surface. */
public data class LayerSurfaceInfo(
    public val surfaceId: Int,
    /** The client's own label, e.g. `quickshell` or `waybar`. */
    public val namespace: String,
    /** One of `ZwlrLayerShellV1.Layer`. */
    public val layer: Int,
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val exclusiveZone: Int,
    public val acceptsKeyboard: Boolean,
)

/** `zwlr_layer_surface_v1`: one panel or widget. */
public class LayerSurfaceResource(
    client: Client,
    id: Int,
    version: Int,
    public val surface: SurfaceResource,
    public var layer: Int,
    public val namespace: String,
) : Resource(client, id, version, ZwlrLayerSurfaceV1.SPEC), SurfaceRole {

    override val roleName: String = "layer_surface"

    public var geometry: LayerGeometry = LayerGeometry(anchor = 0, requestedWidth = 0, requestedHeight = 0)
        private set

    public var keyboardInteractivity: Int = ZwlrLayerSurfaceV1.KeyboardInteractivity.NONE
        private set

    /** Size we last configured, which is what the client renders at. */
    public var configuredWidth: Int = 0
        private set

    public var configuredHeight: Int = 0
        private set

    public var mapped: Boolean = false
        private set

    @Suppress("CyclomaticComplexMethod")
    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            ZwlrLayerSurfaceV1.Request.SET_SIZE -> {
                geometry = geometry.copy(
                    requestedWidth = message.uint(0),
                    requestedHeight = message.uint(1),
                )
                sendInitialConfigure()
            }
            ZwlrLayerSurfaceV1.Request.SET_ANCHOR -> {
                geometry = geometry.copy(anchor = message.uint(0))
                sendInitialConfigure()
            }
            ZwlrLayerSurfaceV1.Request.SET_EXCLUSIVE_ZONE ->
                geometry = geometry.copy(exclusiveZone = message.int(0))
            ZwlrLayerSurfaceV1.Request.SET_MARGIN -> {
                geometry = geometry.copy(
                    marginTop = message.int(0),
                    marginRight = message.int(1),
                    marginBottom = message.int(2),
                    marginLeft = message.int(3),
                )
                sendInitialConfigure()
            }
            ZwlrLayerSurfaceV1.Request.SET_KEYBOARD_INTERACTIVITY ->
                keyboardInteractivity = message.uint(0)
            ZwlrLayerSurfaceV1.Request.SET_LAYER -> {
                layer = message.uint(0)
                if (mapped) client.compositor.onLayerSurfaceChanged(this)
            }
            ZwlrLayerSurfaceV1.Request.SET_EXCLUSIVE_EDGE -> Unit
            ZwlrLayerSurfaceV1.Request.ACK_CONFIGURE -> Unit
            ZwlrLayerSurfaceV1.Request.GET_POPUP -> {
                // A layer surface may parent an xdg_popup (a bar's dropdown
                // menu). The popup already knows how to position itself against
                // its parent, so there is nothing to do but accept it.
                client.compositor.log.debug("layer surface $namespace parented a popup")
            }
            ZwlrLayerSurfaceV1.Request.DESTROY -> destroySelf()
            else -> throw ProtocolError(
                id,
                DisplayError.INVALID_METHOD,
                "zwlr_layer_surface_v1 opcode ${message.opcode}",
            )
        }
    }

    /** Proposes the size this surface should render at. */
    public fun sendInitialConfigure() {
        val output = client.compositor.output
        val scale = client.compositor.config.uiScale
        val (width, height) = geometry.resolveSize(
            outputWidth = (output.widthPx / scale).coerceAtLeast(1),
            outputHeight = (output.heightPx / scale).coerceAtLeast(1),
        )
        configuredWidth = width
        configuredHeight = height
        send("configure", client.compositor.nextSerial(), width, height)
    }

    /** Tells the client the surface is gone, e.g. the launcher dismissed it. */
    public fun sendClosed() {
        send("closed")
    }

    /** Geometry for the launcher, in logical pixels. */
    public fun toInfo(): LayerSurfaceInfo {
        val output = client.compositor.output
        val scale = client.compositor.config.uiScale
        val (x, y) = geometry.resolvePosition(
            width = configuredWidth,
            height = configuredHeight,
            outputWidth = (output.widthPx / scale).coerceAtLeast(1),
            outputHeight = (output.heightPx / scale).coerceAtLeast(1),
        )
        return LayerSurfaceInfo(
            surfaceId = surface.id,
            namespace = namespace,
            layer = layer,
            x = x,
            y = y,
            width = configuredWidth,
            height = configuredHeight,
            exclusiveZone = geometry.exclusiveZone,
            acceptsKeyboard = keyboardInteractivity != ZwlrLayerSurfaceV1.KeyboardInteractivity.NONE,
        )
    }

    override fun onSurfaceCommit(surface: SurfaceResource) {
        if (!mapped && surface.current.buffer != null) {
            mapped = true
            client.compositor.onLayerSurfaceMapped(this)
        }
    }

    override fun onSurfaceUnmapped(surface: SurfaceResource) {
        if (mapped) {
            mapped = false
            client.compositor.onLayerSurfaceUnmapped(this)
        }
    }

    override fun onSurfaceDestroyed(surface: SurfaceResource) {
        onSurfaceUnmapped(surface)
    }
}
