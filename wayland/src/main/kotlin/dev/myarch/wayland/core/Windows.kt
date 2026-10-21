package dev.myarch.wayland.core

/** What the Android layer needs to know about a Linux window. */
public data class WindowInfo(
    /** Stable handle; also the Android task's identifier. */
    val windowId: Int,
    val title: String,
    val appId: String,
    /** Size in logical pixels, i.e. what we last configured. */
    val width: Int,
    val height: Int,
    /** True once the client has committed content for it. */
    val mapped: Boolean,
)

/**
 * One layer of a window's content: the window's own surface, a subsurface, or
 * a popup.
 *
 * Myarch sends layers rather than finished frames so the Android renderer can
 * composite a menu over its parent window without the compositor having to
 * own an offscreen canvas.
 */
public data class LayerFrame(
    val windowId: Int,
    /** The `wl_surface` id, stable for the layer's lifetime. */
    val surfaceId: Int,
    /** Offset within the window, in logical pixels. */
    val x: Int,
    val y: Int,
    /** Larger values draw on top. */
    val zIndex: Int,
    val frame: FrameBuffer,
)

/**
 * How the compositor reaches the Android UI.
 *
 * Every method is called on a compositor thread. Implementations must not
 * block: hand the data to the UI thread and return. [onLayerFrame] is the one
 * exception -- the pixel buffer it receives is reused for the next frame, so
 * the implementation has to copy out of it before returning.
 */
public interface WindowSink {
    /** A toplevel has content for the first time: give it an Android task. */
    public fun onWindowAdded(info: WindowInfo)

    /** The title, app id or size changed. */
    public fun onWindowChanged(info: WindowInfo)

    /** New pixels are available for one layer of a window. */
    public fun onLayerFrame(layer: LayerFrame)

    /** A layer (subsurface or popup) is gone; drop it from the window. */
    public fun onLayerRemoved(windowId: Int, surfaceId: Int)

    /** The window is gone: finish the Android task. */
    public fun onWindowRemoved(windowId: Int)

    /**
     * A panel or widget appeared (`zwlr_layer_shell_v1`).
     *
     * The launcher draws these on the home screen rather than giving them a
     * task, which is what makes an Omarchy bar or widget feel native.
     */
    public fun onPanelAdded(info: LayerSurfaceInfo)

    /** A panel's layer, size or anchors changed. */
    public fun onPanelChanged(info: LayerSurfaceInfo)

    /** New pixels for a panel. The buffer is reused, so copy before returning. */
    public fun onPanelFrame(info: LayerSurfaceInfo, frame: FrameBuffer)

    /** The panel is gone. */
    public fun onPanelRemoved(surfaceId: Int)

    public companion object {
        /** Useful for tests and for running the compositor headless. */
        public val Discard: WindowSink = object : WindowSink {
            override fun onWindowAdded(info: WindowInfo) = Unit

            override fun onWindowChanged(info: WindowInfo) = Unit

            override fun onLayerFrame(layer: LayerFrame) = Unit

            override fun onLayerRemoved(windowId: Int, surfaceId: Int) = Unit

            override fun onWindowRemoved(windowId: Int) = Unit

            override fun onPanelAdded(info: LayerSurfaceInfo) = Unit

            override fun onPanelChanged(info: LayerSurfaceInfo) = Unit

            override fun onPanelFrame(info: LayerSurfaceInfo, frame: FrameBuffer) = Unit

            override fun onPanelRemoved(surfaceId: Int) = Unit
        }
    }
}
