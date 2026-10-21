package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.WlCompositor
import dev.myarch.wayland.protocol.WlRegion
import dev.myarch.wayland.protocol.WlSubcompositor
import dev.myarch.wayland.protocol.WlSubsurface
import dev.myarch.wayland.protocol.WlSurface
import dev.myarch.wayland.wire.WireMessage

/** `wl_compositor`: hands out surfaces and regions. */
public class CompositorResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlCompositor.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlCompositor.Request.CREATE_SURFACE ->
                client.addResource(SurfaceResource(client, message.newId(0), version))
            WlCompositor.Request.CREATE_REGION ->
                client.addResource(RegionResource(client, message.newId(0), version))
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_compositor opcode ${message.opcode}")
        }
    }
}

/** An axis-aligned rectangle in surface-local coordinates. */
public data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    public val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** `wl_region`: a set of rectangles, used for input and opaque hints. */
public class RegionResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlRegion.SPEC) {

    private val added = ArrayList<Rect>()
    private val subtracted = ArrayList<Rect>()

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlRegion.Request.DESTROY -> destroySelf()
            WlRegion.Request.ADD -> added.add(rectOf(message))
            WlRegion.Request.SUBTRACT -> subtracted.add(rectOf(message))
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_region opcode ${message.opcode}")
        }
    }

    private fun rectOf(message: WireMessage) =
        Rect(message.int(0), message.int(1), message.int(2), message.int(3))

    /**
     * Whether a point falls in the region.
     *
     * Myarch only needs this for input hit-testing, where an approximation of
     * "in one of the added rectangles and in none of the subtracted ones" is
     * exactly right for the shapes toolkits actually build (rounded corners
     * and shadow cut-outs).
     */
    public fun contains(x: Int, y: Int): Boolean {
        val inside = added.any { x >= it.x && y >= it.y && x < it.x + it.width && y < it.y + it.height }
        if (!inside) return false
        return subtracted.none { x >= it.x && y >= it.y && x < it.x + it.width && y < it.y + it.height }
    }

    /** True when the client never added anything, i.e. an empty region. */
    public val isEmpty: Boolean get() = added.isEmpty()
}

/** The half of a surface's state that a `commit` makes current. */
public class SurfaceState {
    public var buffer: BufferResource? = null
    public var bufferOffsetX: Int = 0
    public var bufferOffsetY: Int = 0
    public var scale: Int = 1
    public var transform: Int = 0
    public var opaqueRegion: RegionResource? = null
    public var inputRegion: RegionResource? = null
    public val damage: MutableList<Rect> = ArrayList()

    public fun copyFrom(other: SurfaceState) {
        buffer = other.buffer
        bufferOffsetX = other.bufferOffsetX
        bufferOffsetY = other.bufferOffsetY
        scale = other.scale
        transform = other.transform
        opaqueRegion = other.opaqueRegion
        inputRegion = other.inputRegion
        damage.clear()
        damage.addAll(other.damage)
    }
}

/**
 * `wl_surface`: a rectangle of content with no meaning of its own.
 *
 * What a surface *is* comes from the role assigned to it later -- an
 * `xdg_toplevel` window, an `xdg_popup` menu, a cursor, a subsurface. Myarch
 * promotes toplevels to Android tasks and draws popups and subsurfaces into
 * their parent's task.
 */
public class SurfaceResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlSurface.SPEC) {

    /** State the client is building up; applied on `commit`. */
    public val pending: SurfaceState = SurfaceState()

    /** State currently on screen. */
    public val current: SurfaceState = SurfaceState()

    private var bufferAttachedThisCommit = false
    private val frameCallbacks = ArrayList<CallbackResource>()

    /** The role object, once one is assigned. */
    public var role: SurfaceRole? = null
        internal set

    /** Subsurfaces stacked on top of this one, in stacking order. */
    public val children: MutableList<SubsurfaceResource> = ArrayList()

    public val width: Int get() = current.buffer?.width ?: 0
    public val height: Int get() = current.buffer?.height ?: 0

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlSurface.Request.DESTROY -> destroySelf()
            WlSurface.Request.ATTACH -> {
                val bufferId = message.objectId(0)
                pending.buffer = if (bufferId == 0) {
                    null
                } else {
                    client.resource(bufferId) as? BufferResource
                        ?: throw ProtocolError(id, DisplayError.INVALID_OBJECT, "object $bufferId is not a wl_buffer")
                }
                // The x/y arguments of `attach` are deprecated from version 5
                // onwards (clients must send 0 and use `offset` instead), but
                // they are still on the wire for every version.
                pending.bufferOffsetX = message.int(1)
                pending.bufferOffsetY = message.int(2)
                bufferAttachedThisCommit = true
            }
            WlSurface.Request.DAMAGE, WlSurface.Request.DAMAGE_BUFFER ->
                pending.damage.add(Rect(message.int(0), message.int(1), message.int(2), message.int(3)))
            WlSurface.Request.FRAME -> {
                val callback = CallbackResource(client, message.newId(0))
                client.addResource(callback)
                frameCallbacks.add(callback)
            }
            WlSurface.Request.SET_OPAQUE_REGION ->
                pending.opaqueRegion = regionArg(message)
            WlSurface.Request.SET_INPUT_REGION ->
                pending.inputRegion = regionArg(message)
            WlSurface.Request.COMMIT -> commit()
            WlSurface.Request.SET_BUFFER_TRANSFORM -> pending.transform = message.int(0)
            WlSurface.Request.SET_BUFFER_SCALE -> {
                val scale = message.int(0)
                if (scale <= 0) {
                    throw ProtocolError(id, WlSurface.Error.INVALID_SCALE, "buffer scale $scale")
                }
                pending.scale = scale
            }
            WlSurface.Request.OFFSET -> {
                pending.bufferOffsetX = message.int(0)
                pending.bufferOffsetY = message.int(1)
            }
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_surface opcode ${message.opcode}")
        }
    }

    private fun regionArg(message: WireMessage): RegionResource? {
        val regionId = message.objectId(0)
        if (regionId == 0) return null
        return client.resource(regionId) as? RegionResource
            ?: throw ProtocolError(id, DisplayError.INVALID_OBJECT, "object $regionId is not a wl_region")
    }

    private fun commit() {
        val previousBuffer = current.buffer
        current.copyFrom(pending)
        pending.damage.clear()

        role?.onSurfaceCommit(this)

        val buffer = current.buffer
        if (buffer != null) {
            val presented = client.compositor.present(this, buffer)
            // The client may reuse the buffer as soon as we have copied out of
            // it. Myarch copies synchronously, so release immediately; a
            // zero-copy path would hold the release until the frame retires.
            if (presented || buffer !== previousBuffer) {
                buffer.release()
            }
        } else if (previousBuffer != null) {
            // Attaching a null buffer unmaps the surface.
            role?.onSurfaceUnmapped(this)
        }
        fireFrameCallbacks()
        bufferAttachedThisCommit = false
    }

    private fun fireFrameCallbacks() {
        if (frameCallbacks.isEmpty()) return
        val time = client.compositor.timestampMillis()
        val pendingCallbacks = frameCallbacks.toList()
        frameCallbacks.clear()
        for (callback in pendingCallbacks) {
            callback.done(time)
        }
    }

    override fun onDestroy() {
        role?.onSurfaceDestroyed(this)
        client.compositor.forgetSurface(this)
        for (child in children.toList()) {
            child.detachFromParent()
        }
    }
}

/**
 * What a surface has been promoted to.
 *
 * Roles are exclusive and permanent for the lifetime of the surface, which is
 * why the protocol makes assigning a second one an error.
 */
public interface SurfaceRole {
    public val roleName: String

    public fun onSurfaceCommit(surface: SurfaceResource)

    public fun onSurfaceUnmapped(surface: SurfaceResource) {}

    public fun onSurfaceDestroyed(surface: SurfaceResource) {}
}

/** `wl_subcompositor`: nests surfaces inside one another. */
public class SubcompositorResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlSubcompositor.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlSubcompositor.Request.DESTROY -> destroySelf()
            WlSubcompositor.Request.GET_SUBSURFACE -> {
                val newId = message.newId(0)
                val surface = client.requireSurface(id, message.objectId(1))
                val parent = client.requireSurface(id, message.objectId(2))
                if (surface === parent) {
                    throw ProtocolError(id, WlSubcompositor.Error.BAD_SURFACE, "a surface cannot nest in itself")
                }
                if (surface.role != null) {
                    throw ProtocolError(
                        id,
                        WlSubcompositor.Error.BAD_SURFACE,
                        "surface already has the ${surface.role?.roleName} role",
                    )
                }
                val subsurface = SubsurfaceResource(client, newId, version, surface, parent)
                surface.role = subsurface
                parent.children.add(subsurface)
                client.addResource(subsurface)
            }
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_subcompositor opcode ${message.opcode}")
        }
    }
}

/**
 * `wl_subsurface`: a surface positioned relative to a parent.
 *
 * Toolkits use these for client-side decorations, tooltips and video overlays.
 * Myarch tracks their geometry and composites them into the parent window's
 * Android surface.
 */
public class SubsurfaceResource(
    client: Client,
    id: Int,
    version: Int,
    public val surface: SurfaceResource,
    public val parent: SurfaceResource,
) : Resource(client, id, version, WlSubsurface.SPEC), SurfaceRole {

    override val roleName: String = "subsurface"

    public var x: Int = 0
        private set

    public var y: Int = 0
        private set

    /** True when the subsurface commits with its parent rather than on its own. */
    public var synchronized: Boolean = true
        private set

    private var pendingX: Int = 0
    private var pendingY: Int = 0

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlSubsurface.Request.DESTROY -> destroySelf()
            WlSubsurface.Request.SET_POSITION -> {
                pendingX = message.int(0)
                pendingY = message.int(1)
            }
            WlSubsurface.Request.PLACE_ABOVE, WlSubsurface.Request.PLACE_BELOW -> {
                val sibling = client.requireSurface(id, message.objectId(0))
                restack(sibling, above = message.opcode == WlSubsurface.Request.PLACE_ABOVE)
            }
            WlSubsurface.Request.SET_SYNC -> synchronized = true
            WlSubsurface.Request.SET_DESYNC -> synchronized = false
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_subsurface opcode ${message.opcode}")
        }
    }

    private fun restack(sibling: SurfaceResource, above: Boolean) {
        val siblings = parent.children
        val me = siblings.indexOf(this)
        if (me < 0) return
        val target = siblings.indexOfFirst { it.surface === sibling }
        siblings.removeAt(me)
        val insertAt = when {
            target < 0 -> if (above) siblings.size else 0
            above -> (if (target > me) target else target + 1).coerceAtMost(siblings.size)
            else -> (if (target > me) target - 1 else target).coerceAtLeast(0)
        }
        siblings.add(insertAt, this)
    }

    override fun onSurfaceCommit(surface: SurfaceResource) {
        x = pendingX
        y = pendingY
    }

    override fun onSurfaceDestroyed(surface: SurfaceResource) {
        detachFromParent()
    }

    internal fun detachFromParent() {
        parent.children.remove(this)
    }
}

/** Resolves a surface id or raises the protocol error the caller should send. */
internal fun Client.requireSurface(sender: Int, surfaceId: Int): SurfaceResource =
    resource(surfaceId) as? SurfaceResource
        ?: throw ProtocolError(sender, DisplayError.INVALID_OBJECT, "object $surfaceId is not a wl_surface")
