package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.WlCompositor
import dev.myarch.wayland.protocol.WlOutput
import dev.myarch.wayland.protocol.WlSeat
import dev.myarch.wayland.protocol.WlShm
import dev.myarch.wayland.protocol.WlSubcompositor
import dev.myarch.wayland.protocol.XdgToplevel
import dev.myarch.wayland.protocol.XdgWmBase
import dev.myarch.wayland.protocol.ZwlrLayerShellV1
import dev.myarch.wayland.protocol.ZxdgDecorationManagerV1
import dev.myarch.wayland.wire.WireMessage
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Tunables the Android layer fills in from the real display and settings. */
public data class CompositorConfig(
    val outputWidthPx: Int,
    val outputHeightPx: Int,
    val physicalWidthMm: Int = DEFAULT_PHYSICAL_WIDTH_MM,
    val physicalHeightMm: Int = DEFAULT_PHYSICAL_HEIGHT_MM,
    val refreshMilliHz: Int = DEFAULT_REFRESH_MILLI_HZ,
    /**
     * How many device pixels one logical pixel covers.
     *
     * Myarch advertises `wl_output.scale = 1` and instead configures windows
     * at `size / uiScale`, upscaling the result when it blits. Toolkits that
     * honour fractional scaling look better with `uiScale = 1` plus a DPI
     * setting inside the container; everything else is legible at 2.
     * See docs/ARCHITECTURE.md#scaling.
     */
    val uiScale: Int = DEFAULT_UI_SCALE,
    /** Supplies the XKB keymap; see [Keymap]. */
    val keymapProvider: () -> Keymap? = { null },
) {
    public companion object {
        public const val DEFAULT_PHYSICAL_WIDTH_MM: Int = 68
        public const val DEFAULT_PHYSICAL_HEIGHT_MM: Int = 150
        public const val DEFAULT_REFRESH_MILLI_HZ: Int = 60_000
        public const val DEFAULT_UI_SCALE: Int = 2
    }
}

/**
 * The compositor: protocol state, the window list, and the bridge to Android.
 *
 * ## Threading
 *
 * Each client is served by its own thread. Every mutation of protocol state --
 * whether it comes from a client request or from the Android UI calling in --
 * happens while holding [lock], so the resource graph is only ever touched by
 * one thread at a time. Writes to a client socket are additionally serialised
 * by the transport itself, so an event sent from the UI thread cannot interleave
 * with one sent from the client's own thread.
 */
public class Compositor(
    public val config: CompositorConfig,
    public val sink: WindowSink,
    public val log: WaylandLog = WaylandLog.None,
) {
    /** Guards the whole resource graph; see the class docs. */
    public val lock: Any = Any()

    public val output: OutputState = OutputState(
        widthPx = config.outputWidthPx,
        heightPx = config.outputHeightPx,
        physicalWidthMm = config.physicalWidthMm,
        physicalHeightMm = config.physicalHeightMm,
        refreshMilliHz = config.refreshMilliHz,
        // We do the scaling ourselves, so clients see an unscaled output.
        scale = 1,
    )

    private val serials = AtomicInteger(1)
    private val startedAtNanos = System.nanoTime()
    private val windowIds = AtomicInteger(1)

    private val toplevels = LinkedHashMap<Int, ToplevelResource>()
    private val seats = ArrayList<SeatResource>()
    private val clients = ArrayList<Client>()
    private val outputResources = ArrayList<OutputResource>()
    private val pixelBuffers = HashMap<Int, ByteBuffer>()
    private val livePopups = ArrayList<PopupResource>()
    private val panels = LinkedHashMap<Int, LayerSurfaceResource>()

    private var focusedWindowId: Int = 0
    private var cachedKeymap: Keymap? = null
    private var keymapResolved = false
    private val modifiers = ModifierTracker()

    /** The globals advertised to every client that asks. */
    public val globals: List<Global> = listOf(
        Global(1, WlCompositor.SPEC, WlCompositor.VERSION) { c, id, v -> CompositorResource(c, id, v) },
        Global(2, WlSubcompositor.SPEC, WlSubcompositor.VERSION) { c, id, v -> SubcompositorResource(c, id, v) },
        Global(3, WlShm.SPEC, WlShm.VERSION) { c, id, v -> ShmResource(c, id, v) },
        Global(4, WlSeat.SPEC, WlSeat.VERSION) { c, id, v -> SeatResource(c, id, v) },
        Global(5, WlOutput.SPEC, WlOutput.VERSION) { c, id, v ->
            OutputResource(c, id, v).also { synchronized(lock) { outputResources.add(it) } }
        },
        Global(6, XdgWmBase.SPEC, XdgWmBase.VERSION) { c, id, v -> XdgWmBaseResource(c, id, v) },
        Global(
            7,
            ZxdgDecorationManagerV1.SPEC,
            ZxdgDecorationManagerV1.VERSION,
        ) { c, id, v -> DecorationManagerResource(c, id, v) },
        Global(8, ZwlrLayerShellV1.SPEC, ZwlrLayerShellV1.VERSION) { c, id, v -> LayerShellResource(c, id, v) },
    )

    /** Monotonically increasing event serial, as the protocol requires. */
    public fun nextSerial(): Int = serials.incrementAndGet()

    /** Milliseconds since the compositor started, for event timestamps. */
    public fun timestampMillis(): Int = ((System.nanoTime() - startedAtNanos) / 1_000_000L).toInt()

    /** The keymap to hand to new keyboards, resolved once and cached. */
    public fun keymap(): Keymap? {
        if (!keymapResolved) {
            cachedKeymap = runCatching { config.keymapProvider() }
                .onFailure { log.warn("could not load a keymap; clients will get none", it) }
                .getOrNull()
            keymapResolved = true
        }
        return cachedKeymap
    }

    // ---------------------------------------------------------------- clients

    internal fun onClientArrived(client: Client) {
        synchronized(lock) { clients.add(client) }
    }

    internal fun onClientGone(client: Client) {
        synchronized(lock) {
            clients.remove(client)
            seats.removeAll { it.client === client }
            outputResources.removeAll { it.client === client }
            val orphaned = toplevels.filterValues { it.client === client }.keys.toList()
            for (windowId in orphaned) {
                toplevels.remove(windowId)
                sink.onWindowRemoved(windowId)
            }
            val orphanedPanels = panels.filterValues { it.client === client }.keys.toList()
            for (surfaceId in orphanedPanels) {
                panels.remove(surfaceId)
                sink.onPanelRemoved(surfaceId)
            }
        }
    }

    internal fun registerSeat(seat: SeatResource) {
        synchronized(lock) { seats.add(seat) }
    }

    internal fun unregisterSeat(seat: SeatResource) {
        synchronized(lock) { seats.remove(seat) }
    }

    // ---------------------------------------------------------------- windows

    /** The window list, newest last. */
    public fun windows(): List<WindowInfo> = synchronized(lock) {
        toplevels.values.map { it.toInfo() }
    }

    public fun window(windowId: Int): WindowInfo? = synchronized(lock) {
        toplevels[windowId]?.toInfo()
    }

    private fun ToplevelResource.toInfo(): WindowInfo = WindowInfo(
        windowId = windowId,
        title = title.ifEmpty { appId.ifEmpty { "Linux window" } },
        appId = appId,
        width = xdgSurface.surface.width,
        height = xdgSurface.surface.height,
        mapped = mapped,
    )

    internal fun onToplevelMapped(toplevel: ToplevelResource) {
        if (toplevel.windowId == 0) {
            toplevel.windowId = windowIds.getAndIncrement()
        }
        toplevels[toplevel.windowId] = toplevel
        log.info("window ${toplevel.windowId} mapped: ${toplevel.appId} \"${toplevel.title}\"")
        sink.onWindowAdded(toplevel.toInfo())
    }

    internal fun onToplevelUnmapped(toplevel: ToplevelResource) {
        if (toplevels.remove(toplevel.windowId) != null) {
            log.info("window ${toplevel.windowId} unmapped")
            sink.onWindowRemoved(toplevel.windowId)
        }
        pixelBuffers.remove(toplevel.xdgSurface.surface.id)
    }

    internal fun onToplevelMetadataChanged(toplevel: ToplevelResource) {
        if (toplevels.containsKey(toplevel.windowId)) {
            sink.onWindowChanged(toplevel.toInfo())
        }
    }

    internal fun forgetSurface(surface: SurfaceResource) {
        pixelBuffers.remove(surface.id)
    }

    /** Re-sends the last configure, for requests we accept but cannot honour. */
    internal fun reconfigure(toplevel: ToplevelResource) {
        val width = toplevel.xdgSurface.surface.width.takeIf { it > 0 } ?: logicalWidth()
        val height = toplevel.xdgSurface.surface.height.takeIf { it > 0 } ?: logicalHeight()
        toplevel.configure(width, height, activeStates())
    }

    private fun activeStates(): List<Int> = listOf(
        XdgToplevel.State.ACTIVATED,
        XdgToplevel.State.MAXIMIZED,
    )

    private fun logicalWidth(): Int = (output.widthPx / config.uiScale).coerceAtLeast(1)

    private fun logicalHeight(): Int = (output.heightPx / config.uiScale).coerceAtLeast(1)

    // -------------------------------------------------------------- rendering

    /**
     * Converts a freshly committed buffer and pushes it to the Android layer.
     *
     * @return true when the frame reached the sink; false when the surface has
     *   no window yet (a client may commit before we have mapped it).
     */
    internal fun present(surface: SurfaceResource, buffer: BufferResource): Boolean {
        val placement = resolvePlacement(surface) ?: return false
        val needed = buffer.width * buffer.height * FrameBuffer.BYTES_PER_PIXEL
        if (needed <= 0) return false
        val pixels = pixelBuffers.getOrPut(surface.id) { ByteBuffer.allocateDirect(needed) }
            .let { existing ->
                if (existing.capacity() < needed) {
                    ByteBuffer.allocateDirect(needed).also { pixelBuffers[surface.id] = it }
                } else {
                    existing
                }
            }
        if (!buffer.copyInto(pixels)) return false
        val frame = FrameBuffer(buffer.width, buffer.height, pixels)
        when (placement) {
            is Placement.Window -> sink.onLayerFrame(
                LayerFrame(
                    windowId = placement.windowId,
                    surfaceId = surface.id,
                    x = placement.x,
                    y = placement.y,
                    zIndex = placement.zIndex,
                    frame = frame,
                ),
            )
            is Placement.Panel -> sink.onPanelFrame(placement.layerSurface.toInfo(), frame)
        }
        return true
    }

    /** Where a committed surface's pixels should end up. */
    private sealed interface Placement {
        class Window(val windowId: Int, val x: Int, val y: Int, val zIndex: Int) : Placement

        class Panel(val layerSurface: LayerSurfaceResource) : Placement
    }

    /**
     * Walks a surface's role chain to find which window it belongs to and
     * where inside that window it sits.
     */
    private fun resolvePlacement(surface: SurfaceResource): Placement? {
        var current: SurfaceResource = surface
        var x = 0
        var y = 0
        var zIndex = 0
        var depth = 0
        while (depth++ < MAX_ROLE_DEPTH) {
            when (val role = current.role) {
                is ToplevelResource ->
                    return if (role.mapped || role.windowId != 0) {
                        Placement.Window(role.windowId, x, y, zIndex)
                    } else {
                        null
                    }
                is LayerSurfaceResource ->
                    // A panel's own subsurfaces and popups are drawn by the
                    // launcher relative to the panel, so only the root matters.
                    return Placement.Panel(role)
                is SubsurfaceResource -> {
                    x += role.x
                    y += role.y
                    zIndex = maxOf(zIndex, SUBSURFACE_Z_BASE)
                    current = role.parent
                }
                is PopupResource -> {
                    x += role.geometry.x
                    y += role.geometry.y
                    zIndex = POPUP_Z_BASE
                    current = role.parent?.surface ?: return null
                }
                else -> return null
            }
        }
        log.warn("surface ${surface.id} nests more than $MAX_ROLE_DEPTH levels deep; dropping frame")
        return null
    }

    // ------------------------------------------------------- Android -> Linux

    /**
     * Tells the window which size its Android task actually is.
     *
     * Called when the task's surface is created or the device rotates.
     */
    public fun resizeWindow(windowId: Int, widthPx: Int, heightPx: Int) {
        synchronized(lock) {
            val toplevel = toplevels[windowId] ?: return
            val width = (widthPx / config.uiScale).coerceAtLeast(1)
            val height = (heightPx / config.uiScale).coerceAtLeast(1)
            toplevel.configure(width, height, activeStates())
        }
    }

    /** Asks the client to close, e.g. when the user swipes the task away. */
    public fun closeWindow(windowId: Int) {
        synchronized(lock) { toplevels[windowId]?.requestClose() }
    }

    /** Moves keyboard focus, following which Android task is in front. */
    public fun focusWindow(windowId: Int) {
        synchronized(lock) {
            if (focusedWindowId == windowId) return
            val serial = nextSerial()
            toplevels[focusedWindowId]?.let { previous ->
                forEachKeyboard { it.leave(serial, previous.xdgSurface.surface.id) }
            }
            focusedWindowId = windowId
            val next = toplevels[windowId] ?: return
            forEachKeyboard { keyboard ->
                keyboard.enter(nextSerial(), next.xdgSurface.surface.id, emptyList())
                keyboard.modifiers(nextSerial(), modifiers.depressed, 0, modifiers.locked, 0)
            }
            next.configure(
                next.xdgSurface.surface.width.takeIf { it > 0 } ?: logicalWidth(),
                next.xdgSurface.surface.height.takeIf { it > 0 } ?: logicalHeight(),
                activeStates(),
            )
        }
    }

    /** The window that currently has keyboard focus, or 0. */
    public val focusedWindow: Int get() = synchronized(lock) { focusedWindowId }

    /**
     * Forwards a touch point.
     *
     * Coordinates are in Android view pixels; they are divided by the UI scale
     * to reach the logical pixels the client rendered in.
     */
    public fun dispatchTouch(
        windowId: Int,
        touchId: Int,
        phase: TouchPhase,
        xPx: Float,
        yPx: Float,
    ) {
        synchronized(lock) {
            val toplevel = toplevels[windowId] ?: return
            val surfaceId = toplevel.xdgSurface.surface.id
            val x = xPx / config.uiScale
            val y = yPx / config.uiScale
            val time = timestampMillis()
            var delivered = false
            forEachTouch { touch ->
                delivered = true
                when (phase) {
                    TouchPhase.DOWN -> touch.down(nextSerial(), time, surfaceId, touchId, x.toDouble(), y.toDouble())
                    TouchPhase.MOVE -> touch.motion(time, touchId, x.toDouble(), y.toDouble())
                    TouchPhase.UP -> touch.up(nextSerial(), time, touchId)
                    TouchPhase.CANCEL -> touch.cancel()
                }
                if (phase != TouchPhase.CANCEL) touch.frame()
            }
            // Many X11-era toolkits running under Wayland never bind wl_touch.
            // Synthesising a pointer keeps them usable with a finger.
            if (!delivered || touchId == 0) {
                dispatchPointerForTouch(toplevel, surfaceId, phase, x.toDouble(), y.toDouble(), time)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun dispatchPointerForTouch(
        toplevel: ToplevelResource,
        surfaceId: Int,
        phase: TouchPhase,
        x: Double,
        y: Double,
        time: Int,
    ) {
        forEachPointer { pointer ->
            when (phase) {
                TouchPhase.DOWN -> {
                    pointer.enter(nextSerial(), surfaceId, x, y)
                    pointer.motion(time, x, y)
                    pointer.button(nextSerial(), time, LinuxButton.LEFT, pressed = true)
                }
                TouchPhase.MOVE -> pointer.motion(time, x, y)
                TouchPhase.UP -> {
                    pointer.button(nextSerial(), time, LinuxButton.LEFT, pressed = false)
                    pointer.leave(nextSerial(), surfaceId)
                }
                TouchPhase.CANCEL -> {
                    pointer.button(nextSerial(), time, LinuxButton.LEFT, pressed = false)
                    pointer.leave(nextSerial(), surfaceId)
                }
            }
        }
    }

    /** Forwards a mouse or stylus move without a button held. */
    public fun dispatchHover(windowId: Int, xPx: Float, yPx: Float) {
        synchronized(lock) {
            toplevels[windowId] ?: return
            val time = timestampMillis()
            forEachPointer { it.motion(time, (xPx / config.uiScale).toDouble(), (yPx / config.uiScale).toDouble()) }
        }
    }

    /** Forwards a scroll gesture, in logical pixels per event. */
    public fun dispatchScroll(windowId: Int, horizontalPx: Float, verticalPx: Float, finished: Boolean = false) {
        synchronized(lock) {
            toplevels[windowId] ?: return
            val time = timestampMillis()
            forEachPointer { pointer ->
                pointer.axis(
                    time,
                    (horizontalPx / config.uiScale).toDouble(),
                    (verticalPx / config.uiScale).toDouble(),
                )
                if (finished) pointer.axisStop(time)
            }
        }
    }

    /**
     * Forwards a key press.
     *
     * @param evdevCode a Linux `KEY_*` code; see [AndroidKeyMap] for the
     *   translation from Android key codes.
     */
    public fun dispatchKey(windowId: Int, evdevCode: Int, pressed: Boolean) {
        synchronized(lock) {
            val toplevel = toplevels[windowId] ?: return
            val time = timestampMillis()
            val modifiersChanged = modifiers.onKey(evdevCode, pressed)
            forEachKeyboard { keyboard ->
                if (modifiersChanged) {
                    keyboard.modifiers(nextSerial(), modifiers.depressed, 0, modifiers.locked, 0)
                }
                keyboard.key(nextSerial(), time, evdevCode, pressed)
            }
            if (focusedWindowId != toplevel.windowId) {
                focusedWindowId = toplevel.windowId
            }
        }
    }

    /** Dismisses every open popup, e.g. on a tap outside or a back gesture. */
    public fun dismissPopups(): Boolean = synchronized(lock) {
        if (livePopups.isEmpty()) return false
        for (popup in livePopups.toList()) {
            popup.dismiss()
        }
        true
    }

    /** Updates the advertised output after a rotation or a settings change. */
    public fun updateOutput(widthPx: Int, heightPx: Int, refreshMilliHz: Int = output.refreshMilliHz) {
        synchronized(lock) {
            output.widthPx = widthPx
            output.heightPx = heightPx
            output.refreshMilliHz = refreshMilliHz
            for (resource in outputResources) {
                resource.sendState(output)
            }
            for (toplevel in toplevels.values) {
                toplevel.configure(logicalWidth(), logicalHeight(), activeStates())
            }
            for (panel in panels.values) {
                panel.sendInitialConfigure()
                if (panel.mapped) sink.onPanelChanged(panel.toInfo())
            }
        }
    }

    // ---------------------------------------------------------------- popups

    internal fun onPopupGrab(popup: PopupResource) {
        if (!livePopups.contains(popup)) livePopups.add(popup)
    }

    internal fun onPopupCommit(popup: PopupResource) {
        if (!livePopups.contains(popup)) livePopups.add(popup)
    }

    internal fun onPopupGone(popup: PopupResource) {
        livePopups.remove(popup)
        when (val placement = resolvePlacement(popup.xdgSurface.surface)) {
            is Placement.Window -> sink.onLayerRemoved(placement.windowId, popup.xdgSurface.surface.id)
            is Placement.Panel -> sink.onPanelChanged(placement.layerSurface.toInfo())
            null -> Unit
        }
    }

    internal fun onPong(serial: Int) {
        log.debug("client answered ping $serial")
    }

    internal fun onCursorSet(message: WireMessage) {
        // Myarch draws no cursor: touch has none, and a Bluetooth mouse gets
        // Android's own pointer. The request is accepted so clients that set a
        // cursor on every enter event are not left in an error state.
        log.debug("ignoring set_cursor from object ${message.objectId}")
    }

    // ---------------------------------------------------------------- panels

    /** Panels currently on the home screen, back to front. */
    public fun panels(): List<LayerSurfaceInfo> = synchronized(lock) {
        panels.values.filter { it.mapped }.map { it.toInfo() }.sortedBy { it.layer }
    }

    internal fun onLayerSurfaceMapped(layerSurface: LayerSurfaceResource) {
        panels[layerSurface.surface.id] = layerSurface
        log.info("panel \"${layerSurface.namespace}\" mapped on layer ${layerSurface.layer}")
        sink.onPanelAdded(layerSurface.toInfo())
    }

    internal fun onLayerSurfaceChanged(layerSurface: LayerSurfaceResource) {
        if (panels.containsKey(layerSurface.surface.id)) {
            sink.onPanelChanged(layerSurface.toInfo())
        }
    }

    internal fun onLayerSurfaceUnmapped(layerSurface: LayerSurfaceResource) {
        if (panels.remove(layerSurface.surface.id) != null) {
            sink.onPanelRemoved(layerSurface.surface.id)
        }
        pixelBuffers.remove(layerSurface.surface.id)
    }

    /**
     * Forwards a touch on a panel, so widgets are actually interactive.
     *
     * Coordinates are relative to the panel, in Android view pixels.
     */
    public fun dispatchPanelTouch(surfaceId: Int, touchId: Int, phase: TouchPhase, xPx: Float, yPx: Float) {
        synchronized(lock) {
            val panel = panels[surfaceId] ?: return
            val x = (xPx / config.uiScale).toDouble()
            val y = (yPx / config.uiScale).toDouble()
            val time = timestampMillis()
            var delivered = false
            forEachTouch { touch ->
                delivered = true
                when (phase) {
                    TouchPhase.DOWN -> touch.down(nextSerial(), time, surfaceId, touchId, x, y)
                    TouchPhase.MOVE -> touch.motion(time, touchId, x, y)
                    TouchPhase.UP -> touch.up(nextSerial(), time, touchId)
                    TouchPhase.CANCEL -> touch.cancel()
                }
                if (phase != TouchPhase.CANCEL) touch.frame()
            }
            if (!delivered || touchId == 0) {
                // Quickshell's QML MouseArea listens for pointer events, not
                // touch, so a widget would otherwise ignore taps entirely.
                forEachPointer { pointer ->
                    when (phase) {
                        TouchPhase.DOWN -> {
                            pointer.enter(nextSerial(), surfaceId, x, y)
                            pointer.motion(time, x, y)
                            pointer.button(nextSerial(), time, LinuxButton.LEFT, pressed = true)
                        }
                        TouchPhase.MOVE -> pointer.motion(time, x, y)
                        TouchPhase.UP, TouchPhase.CANCEL -> {
                            pointer.button(nextSerial(), time, LinuxButton.LEFT, pressed = false)
                            pointer.leave(nextSerial(), surfaceId)
                        }
                    }
                }
            }
            if (panel.acceptsKeyboardFocus()) {
                focusPanelKeyboard(panel)
            }
        }
    }

    private fun LayerSurfaceResource.acceptsKeyboardFocus(): Boolean = toInfo().acceptsKeyboard

    private fun focusPanelKeyboard(panel: LayerSurfaceResource) {
        if (focusedWindowId == -panel.surface.id) return
        focusedWindowId = -panel.surface.id
        forEachKeyboard { keyboard ->
            keyboard.enter(nextSerial(), panel.surface.id, emptyList())
            keyboard.modifiers(nextSerial(), modifiers.depressed, 0, modifiers.locked, 0)
        }
    }

    /** Forwards a key to whichever panel has keyboard focus (e.g. a launcher). */
    public fun dispatchPanelKey(surfaceId: Int, evdevCode: Int, pressed: Boolean) {
        synchronized(lock) {
            panels[surfaceId] ?: return
            val time = timestampMillis()
            val modifiersChanged = modifiers.onKey(evdevCode, pressed)
            forEachKeyboard { keyboard ->
                if (modifiersChanged) {
                    keyboard.modifiers(nextSerial(), modifiers.depressed, 0, modifiers.locked, 0)
                }
                keyboard.key(nextSerial(), time, evdevCode, pressed)
            }
        }
    }

    private inline fun forEachKeyboard(action: (KeyboardResource) -> Unit) {
        for (seat in seats) for (keyboard in seat.keyboards) action(keyboard)
    }

    private inline fun forEachPointer(action: (PointerResource) -> Unit) {
        for (seat in seats) for (pointer in seat.pointers) action(pointer)
    }

    private inline fun forEachTouch(action: (TouchResource) -> Unit) {
        for (seat in seats) for (touch in seat.touches) action(touch)
    }

    private companion object {
        const val MAX_ROLE_DEPTH = 32
        const val SUBSURFACE_Z_BASE = 100
        const val POPUP_Z_BASE = 1000
    }
}

/** The lifecycle of one touch point. */
public enum class TouchPhase { DOWN, MOVE, UP, CANCEL }
