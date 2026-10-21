package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.XdgPopup
import dev.myarch.wayland.protocol.XdgPositioner
import dev.myarch.wayland.protocol.XdgSurface
import dev.myarch.wayland.protocol.XdgToplevel
import dev.myarch.wayland.protocol.XdgWmBase
import dev.myarch.wayland.protocol.ZxdgDecorationManagerV1
import dev.myarch.wayland.protocol.ZxdgToplevelDecorationV1
import dev.myarch.wayland.wire.WireMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** `xdg_wm_base`: the entry point to the desktop shell protocol. */
public class XdgWmBaseResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, XdgWmBase.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            XdgWmBase.Request.DESTROY -> destroySelf()
            XdgWmBase.Request.CREATE_POSITIONER ->
                client.addResource(PositionerResource(client, message.newId(0), version))
            XdgWmBase.Request.GET_XDG_SURFACE -> {
                val newId = message.newId(0)
                val surface = client.requireSurface(id, message.objectId(1))
                if (surface.role != null) {
                    throw ProtocolError(
                        id,
                        XdgWmBase.Error.ROLE,
                        "surface already has the ${surface.role?.roleName} role",
                    )
                }
                client.addResource(XdgSurfaceResource(client, newId, version, surface))
            }
            XdgWmBase.Request.PONG -> client.compositor.onPong(message.uint(0))
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "xdg_wm_base opcode ${message.opcode}")
        }
    }

    /** Asks the client to prove it is still responsive. */
    public fun ping(serial: Int) {
        send("ping", serial)
    }
}

/**
 * `xdg_surface`: the part of the shell protocol shared by windows and popups.
 *
 * It owns the configure handshake: we propose a size, the client acknowledges
 * the serial and commits content matching it.
 */
public class XdgSurfaceResource(
    client: Client,
    id: Int,
    version: Int,
    public val surface: SurfaceResource,
) : Resource(client, id, version, XdgSurface.SPEC) {

    /** The geometry the client declared as its "real" window bounds. */
    public var windowGeometry: Rect? = null
        private set

    public var lastAckedConfigure: Int = 0
        private set

    private var pendingConfigureSerial: Int = 0

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            XdgSurface.Request.DESTROY -> destroySelf()
            XdgSurface.Request.GET_TOPLEVEL -> {
                val toplevel = ToplevelResource(client, message.newId(0), version, this)
                surface.role = toplevel
                client.addResource(toplevel)
                toplevel.announceCapabilities()
            }
            XdgSurface.Request.GET_POPUP -> {
                val newId = message.newId(0)
                val parentId = message.objectId(1)
                val parent = if (parentId == 0) {
                    null
                } else {
                    client.resource(parentId) as? XdgSurfaceResource
                        ?: throw ProtocolError(id, DisplayError.INVALID_OBJECT, "object $parentId is not an xdg_surface")
                }
                val positioner = client.resource(message.objectId(2)) as? PositionerResource
                    ?: throw ProtocolError(id, DisplayError.INVALID_OBJECT, "argument is not an xdg_positioner")
                val popup = PopupResource(client, newId, version, this, parent, positioner)
                surface.role = popup
                client.addResource(popup)
                popup.sendInitialConfigure()
            }
            XdgSurface.Request.SET_WINDOW_GEOMETRY ->
                windowGeometry = Rect(message.int(0), message.int(1), message.int(2), message.int(3))
            XdgSurface.Request.ACK_CONFIGURE -> lastAckedConfigure = message.uint(0)
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "xdg_surface opcode ${message.opcode}")
        }
    }

    /** Starts a configure round; the serial the client must acknowledge. */
    public fun configure(): Int {
        val serial = client.compositor.nextSerial()
        pendingConfigureSerial = serial
        send("configure", serial)
        return serial
    }

    /** True when the client has caught up with the last size we proposed. */
    public val isConfigureAcknowledged: Boolean
        get() = lastAckedConfigure == pendingConfigureSerial
}

/**
 * `xdg_toplevel`: an application window.
 *
 * Every toplevel becomes its own Android task, so the "window manager" part of
 * Myarch is mostly a translation layer: Android owns stacking, the recents
 * list and the back gesture, and we forward the consequences into the
 * protocol.
 */
public class ToplevelResource(
    client: Client,
    id: Int,
    version: Int,
    public val xdgSurface: XdgSurfaceResource,
) : Resource(client, id, version, XdgToplevel.SPEC), SurfaceRole {

    override val roleName: String = "xdg_toplevel"

    public var title: String = ""
        private set

    public var appId: String = ""
        private set

    public var minWidth: Int = 0
        private set

    public var minHeight: Int = 0
        private set

    public var maxWidth: Int = 0
        private set

    public var maxHeight: Int = 0
        private set

    public var parent: ToplevelResource? = null
        private set

    /** Set once the client has committed content, i.e. the window is real. */
    public var mapped: Boolean = false
        internal set

    /** The handle the Android layer knows this window by. */
    public var windowId: Int = 0
        internal set

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            XdgToplevel.Request.DESTROY -> destroySelf()
            XdgToplevel.Request.SET_PARENT -> {
                val parentId = message.objectId(0)
                parent = if (parentId == 0) null else client.resource(parentId) as? ToplevelResource
                notifyChanged()
            }
            XdgToplevel.Request.SET_TITLE -> {
                title = message.string(0)
                notifyChanged()
            }
            XdgToplevel.Request.SET_APP_ID -> {
                appId = message.string(0)
                notifyChanged()
            }
            XdgToplevel.Request.SET_MAX_SIZE -> {
                maxWidth = message.int(0)
                maxHeight = message.int(1)
            }
            XdgToplevel.Request.SET_MIN_SIZE -> {
                minWidth = message.int(0)
                minHeight = message.int(1)
            }
            // Android decides where windows live, so interactive move/resize,
            // maximise and minimise have no meaning here. We accept them and
            // re-send a configure so the client is never left waiting.
            XdgToplevel.Request.MOVE,
            XdgToplevel.Request.RESIZE,
            XdgToplevel.Request.SET_MAXIMIZED,
            XdgToplevel.Request.UNSET_MAXIMIZED,
            XdgToplevel.Request.SET_FULLSCREEN,
            XdgToplevel.Request.UNSET_FULLSCREEN,
            XdgToplevel.Request.SET_MINIMIZED,
            XdgToplevel.Request.SHOW_WINDOW_MENU,
            -> client.compositor.reconfigure(this)
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "xdg_toplevel opcode ${message.opcode}")
        }
    }

    private fun notifyChanged() {
        if (mapped) client.compositor.onToplevelMetadataChanged(this)
    }

    /** Tells the client which window-management verbs we honour. */
    public fun announceCapabilities() {
        if (supports(XDG_TOPLEVEL_WM_CAPABILITIES_SINCE)) {
            val capabilities = encodeUints(listOf(XdgToplevel.WmCapabilities.FULLSCREEN))
            send("wm_capabilities", capabilities)
        }
    }

    /**
     * Proposes a size.
     *
     * @param states the `xdg_toplevel.state` values that apply; a window that
     *   fills an Android task is always activated and maximised.
     */
    public fun configure(width: Int, height: Int, states: List<Int>) {
        if (supports(XDG_TOPLEVEL_CONFIGURE_BOUNDS_SINCE)) {
            send("configure_bounds", width, height)
        }
        send("configure", width, height, encodeUints(states))
        xdgSurface.configure()
    }

    /** Asks the client to close, the protocol's equivalent of the X button. */
    public fun requestClose() {
        send("close")
    }

    override fun onSurfaceCommit(surface: SurfaceResource) {
        if (!mapped && surface.current.buffer != null) {
            mapped = true
            client.compositor.onToplevelMapped(this)
        }
    }

    override fun onSurfaceUnmapped(surface: SurfaceResource) {
        if (mapped) {
            mapped = false
            client.compositor.onToplevelUnmapped(this)
        }
    }

    override fun onSurfaceDestroyed(surface: SurfaceResource) {
        if (mapped) {
            mapped = false
            client.compositor.onToplevelUnmapped(this)
        }
    }

    private companion object {
        const val XDG_TOPLEVEL_CONFIGURE_BOUNDS_SINCE = 4
        const val XDG_TOPLEVEL_WM_CAPABILITIES_SINCE = 5
    }
}

/** Encodes a list of enum values as the `array` the protocol expects. */
internal fun encodeUints(values: List<Int>): ByteArray {
    val buffer = ByteBuffer.allocate(values.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (value in values) buffer.putInt(value)
    return buffer.array()
}

/**
 * `xdg_positioner`: how a popup wants to be placed relative to its parent.
 *
 * Myarch keeps the constraint solving simple -- it honours the anchor rect and
 * the offset, and clamps the result to the output -- which is enough for
 * menus, combo boxes and tooltips.
 */
public class PositionerResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, XdgPositioner.SPEC) {

    public var width: Int = 0
        private set

    public var height: Int = 0
        private set

    public var anchorRect: Rect = Rect(0, 0, 0, 0)
        private set

    public var anchor: Int = XdgPositioner.Anchor.NONE
        private set

    public var gravity: Int = XdgPositioner.Gravity.NONE
        private set

    public var offsetX: Int = 0
        private set

    public var offsetY: Int = 0
        private set

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            XdgPositioner.Request.DESTROY -> destroySelf()
            XdgPositioner.Request.SET_SIZE -> {
                width = message.int(0)
                height = message.int(1)
                if (width < 1 || height < 1) {
                    throw ProtocolError(id, XdgPositioner.Error.INVALID_INPUT, "popup size ${width}x$height")
                }
            }
            XdgPositioner.Request.SET_ANCHOR_RECT -> {
                anchorRect = Rect(message.int(0), message.int(1), message.int(2), message.int(3))
            }
            XdgPositioner.Request.SET_ANCHOR -> anchor = message.uint(0)
            XdgPositioner.Request.SET_GRAVITY -> gravity = message.uint(0)
            XdgPositioner.Request.SET_OFFSET -> {
                offsetX = message.int(0)
                offsetY = message.int(1)
            }
            // Constraint adjustment, reactive repositioning and parent-size
            // hints are accepted and ignored: the fallback placement below is
            // already clamped to the screen.
            XdgPositioner.Request.SET_CONSTRAINT_ADJUSTMENT,
            XdgPositioner.Request.SET_REACTIVE,
            XdgPositioner.Request.SET_PARENT_SIZE,
            XdgPositioner.Request.SET_PARENT_CONFIGURE,
            -> Unit
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "xdg_positioner opcode ${message.opcode}")
        }
    }

    /** Resolves the popup rectangle in the parent's coordinate space. */
    public fun resolve(parentWidth: Int, parentHeight: Int): Rect {
        var x = anchorRect.x + offsetX
        var y = anchorRect.y + offsetY
        when (anchor) {
            XdgPositioner.Anchor.TOP -> x += anchorRect.width / 2
            XdgPositioner.Anchor.BOTTOM -> {
                x += anchorRect.width / 2
                y += anchorRect.height
            }
            XdgPositioner.Anchor.LEFT -> y += anchorRect.height / 2
            XdgPositioner.Anchor.RIGHT -> {
                x += anchorRect.width
                y += anchorRect.height / 2
            }
            XdgPositioner.Anchor.TOP_RIGHT -> x += anchorRect.width
            XdgPositioner.Anchor.BOTTOM_LEFT -> y += anchorRect.height
            XdgPositioner.Anchor.BOTTOM_RIGHT -> {
                x += anchorRect.width
                y += anchorRect.height
            }
            else -> Unit
        }
        when (gravity) {
            XdgPositioner.Gravity.LEFT, XdgPositioner.Gravity.TOP_LEFT, XdgPositioner.Gravity.BOTTOM_LEFT ->
                x -= width
            XdgPositioner.Gravity.TOP, XdgPositioner.Gravity.BOTTOM -> x -= width / 2
            else -> Unit
        }
        when (gravity) {
            XdgPositioner.Gravity.TOP, XdgPositioner.Gravity.TOP_LEFT, XdgPositioner.Gravity.TOP_RIGHT ->
                y -= height
            XdgPositioner.Gravity.LEFT, XdgPositioner.Gravity.RIGHT -> y -= height / 2
            else -> Unit
        }
        val clampedX = x.coerceIn(0, maxOf(0, parentWidth - width))
        val clampedY = y.coerceIn(0, maxOf(0, parentHeight - height))
        return Rect(clampedX, clampedY, width, height)
    }
}

/**
 * `xdg_popup`: a menu, tooltip or combo box list.
 *
 * Popups are drawn inside their parent window's Android task rather than
 * getting a task of their own: a menu in its own entry in the recents list
 * would be surprising, and popups are destroyed as soon as the grab breaks.
 */
public class PopupResource(
    client: Client,
    id: Int,
    version: Int,
    public val xdgSurface: XdgSurfaceResource,
    public val parent: XdgSurfaceResource?,
    private val positioner: PositionerResource,
) : Resource(client, id, version, XdgPopup.SPEC), SurfaceRole {

    override val roleName: String = "xdg_popup"

    public var geometry: Rect = Rect(0, 0, 0, 0)
        private set

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            XdgPopup.Request.DESTROY -> destroySelf()
            XdgPopup.Request.GRAB -> client.compositor.onPopupGrab(this)
            XdgPopup.Request.REPOSITION -> {
                val token = message.uint(1)
                sendInitialConfigure()
                send("repositioned", token)
            }
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "xdg_popup opcode ${message.opcode}")
        }
    }

    public fun sendInitialConfigure() {
        val parentSurface = parent?.surface
        val rect = positioner.resolve(
            parentWidth = parentSurface?.width ?: client.compositor.output.widthPx,
            parentHeight = parentSurface?.height ?: client.compositor.output.heightPx,
        )
        geometry = rect
        send("configure", rect.x, rect.y, rect.width, rect.height)
        xdgSurface.configure()
    }

    /** Dismisses the popup, e.g. when the user taps outside it. */
    public fun dismiss() {
        send("popup_done")
    }

    override fun onSurfaceCommit(surface: SurfaceResource) {
        client.compositor.onPopupCommit(this)
    }

    override fun onSurfaceDestroyed(surface: SurfaceResource) {
        client.compositor.onPopupGone(this)
    }
}

/**
 * `zxdg_decoration_manager_v1`: lets us tell clients not to draw title bars.
 *
 * Android already draws a task header in recents and the window fills the
 * screen, so a client-side title bar is wasted space. Myarch always answers
 * "server side", which for us means "no decoration at all".
 */
public class DecorationManagerResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, ZxdgDecorationManagerV1.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            ZxdgDecorationManagerV1.Request.DESTROY -> destroySelf()
            ZxdgDecorationManagerV1.Request.GET_TOPLEVEL_DECORATION -> {
                val newId = message.newId(0)
                val toplevel = client.resource(message.objectId(1)) as? ToplevelResource
                    ?: throw ProtocolError(id, DisplayError.INVALID_OBJECT, "argument is not an xdg_toplevel")
                val decoration = ToplevelDecorationResource(client, newId, version, toplevel)
                client.addResource(decoration)
                decoration.announceMode()
            }
            else -> throw ProtocolError(
                id,
                DisplayError.INVALID_METHOD,
                "zxdg_decoration_manager_v1 opcode ${message.opcode}",
            )
        }
    }
}

/** `zxdg_toplevel_decoration_v1`: one window's decoration mode. */
public class ToplevelDecorationResource(
    client: Client,
    id: Int,
    version: Int,
    private val toplevel: ToplevelResource,
) : Resource(client, id, version, ZxdgToplevelDecorationV1.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            ZxdgToplevelDecorationV1.Request.DESTROY -> destroySelf()
            ZxdgToplevelDecorationV1.Request.SET_MODE,
            ZxdgToplevelDecorationV1.Request.UNSET_MODE,
            -> announceMode()
            else -> throw ProtocolError(
                id,
                DisplayError.INVALID_METHOD,
                "zxdg_toplevel_decoration_v1 opcode ${message.opcode}",
            )
        }
    }

    public fun announceMode() {
        send("configure", ZxdgToplevelDecorationV1.Mode.SERVER_SIDE)
        toplevel.xdgSurface.configure()
    }
}
