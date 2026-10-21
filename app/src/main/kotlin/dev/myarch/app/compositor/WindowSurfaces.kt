package dev.myarch.app.compositor

import android.graphics.Bitmap
import dev.myarch.wayland.core.FrameBuffer
import dev.myarch.wayland.core.LayerFrame
import dev.myarch.wayland.core.LayerSurfaceInfo
import dev.myarch.wayland.core.WindowInfo

/** One drawable layer: a window's own surface, a subsurface, or a popup. */
public data class RenderLayer(
    val surfaceId: Int,
    val x: Int,
    val y: Int,
    val zIndex: Int,
    val bitmap: Bitmap,
)

/** Everything needed to draw one window. */
public data class WindowSnapshot(
    val info: WindowInfo,
    /** Back to front. */
    val layers: List<RenderLayer>,
)

/**
 * Holds the pixels the compositor has produced, keyed by window.
 *
 * The compositor hands over a buffer it immediately reuses, so the frame is
 * copied into a `Bitmap` here, on the compositor's thread, and the UI thread
 * only ever reads completed bitmaps. Bitmaps are kept and reused while the size
 * is unchanged: allocating one per frame would make the GC the bottleneck.
 */
public class WindowSurfaces {

    private val lock = Any()
    private val windows = LinkedHashMap<Int, MutableWindow>()
    private val panels = LinkedHashMap<Int, PanelEntry>()
    private val windowListeners = HashMap<Int, MutableList<() -> Unit>>()
    private val globalListeners = ArrayList<() -> Unit>()

    private class MutableWindow(var info: WindowInfo) {
        val layers = LinkedHashMap<Int, RenderLayer>()
    }

    private class PanelEntry(var info: LayerSurfaceInfo, var bitmap: Bitmap?)

    // ------------------------------------------------------------- windows

    public fun upsertWindow(info: WindowInfo) {
        synchronized(lock) {
            windows.getOrPut(info.windowId) { MutableWindow(info) }.info = info
        }
        notifyWindow(info.windowId)
    }

    public fun removeWindow(windowId: Int) {
        synchronized(lock) { windows.remove(windowId) }
        notifyWindow(windowId)
    }

    /** Copies a frame in and returns the window it belongs to. */
    public fun putLayer(layer: LayerFrame) {
        synchronized(lock) {
            val window = windows[layer.windowId] ?: return
            val existing = window.layers[layer.surfaceId]
            val bitmap = reuseOrCreate(existing?.bitmap, layer.frame)
            window.layers[layer.surfaceId] = RenderLayer(
                surfaceId = layer.surfaceId,
                x = layer.x,
                y = layer.y,
                zIndex = layer.zIndex,
                bitmap = bitmap,
            )
        }
        notifyWindow(layer.windowId)
    }

    public fun removeLayer(windowId: Int, surfaceId: Int) {
        synchronized(lock) { windows[windowId]?.layers?.remove(surfaceId) }
        notifyWindow(windowId)
    }

    public fun snapshot(windowId: Int): WindowSnapshot? = synchronized(lock) {
        val window = windows[windowId] ?: return null
        WindowSnapshot(window.info, window.layers.values.sortedBy { it.zIndex })
    }

    public fun windowIds(): List<Int> = synchronized(lock) { windows.keys.toList() }

    public fun windowInfos(): List<WindowInfo> = synchronized(lock) { windows.values.map { it.info } }

    // -------------------------------------------------------------- panels

    public fun upsertPanel(info: LayerSurfaceInfo) {
        synchronized(lock) {
            val entry = panels[info.surfaceId]
            if (entry == null) {
                panels[info.surfaceId] = PanelEntry(info, null)
            } else {
                entry.info = info
            }
        }
        notifyGlobal()
    }

    public fun putPanelFrame(info: LayerSurfaceInfo, frame: FrameBuffer) {
        synchronized(lock) {
            val entry = panels.getOrPut(info.surfaceId) { PanelEntry(info, null) }
            entry.info = info
            entry.bitmap = reuseOrCreate(entry.bitmap, frame)
        }
        notifyGlobal()
    }

    public fun removePanel(surfaceId: Int) {
        synchronized(lock) { panels.remove(surfaceId) }
        notifyGlobal()
    }

    /** Panels with pixels, bottom layer first. */
    public fun panelSnapshots(): List<PanelSnapshot> = synchronized(lock) {
        panels.values
            .mapNotNull { entry -> entry.bitmap?.let { PanelSnapshot(entry.info, it) } }
            .sortedBy { it.info.layer }
    }

    // ----------------------------------------------------------- listeners

    /** Registers a callback for one window; returns a function that removes it. */
    public fun observeWindow(windowId: Int, listener: () -> Unit): () -> Unit {
        synchronized(lock) {
            windowListeners.getOrPut(windowId) { ArrayList() }.add(listener)
        }
        return {
            synchronized(lock) {
                windowListeners[windowId]?.remove(listener)
            }
        }
    }

    /** Registers a callback for window-list and panel changes. */
    public fun observeAll(listener: () -> Unit): () -> Unit {
        synchronized(lock) { globalListeners.add(listener) }
        return { synchronized(lock) { globalListeners.remove(listener) } }
    }

    private fun notifyWindow(windowId: Int) {
        val listeners = synchronized(lock) {
            (windowListeners[windowId]?.toList() ?: emptyList()) + globalListeners.toList()
        }
        for (listener in listeners) listener()
    }

    private fun notifyGlobal() {
        val listeners = synchronized(lock) { globalListeners.toList() }
        for (listener in listeners) listener()
    }

    /**
     * Copies [frame] into a bitmap, reusing [existing] when the size matches.
     *
     * `copyPixelsFromBuffer` needs the buffer positioned at zero and expects
     * exactly `width * height * 4` bytes, which is the layout
     * [dev.myarch.wayland.core.PixelConverter] produces.
     */
    private fun reuseOrCreate(existing: Bitmap?, frame: FrameBuffer): Bitmap {
        val bitmap = if (
            existing != null &&
            !existing.isRecycled &&
            existing.width == frame.width &&
            existing.height == frame.height
        ) {
            existing
        } else {
            existing?.recycle()
            Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        }
        val pixels = frame.pixels.duplicate()
        pixels.position(0)
        pixels.limit(frame.sizeBytes)
        bitmap.copyPixelsFromBuffer(pixels)
        return bitmap
    }
}

/** A panel and its current pixels. */
public data class PanelSnapshot(val info: LayerSurfaceInfo, val bitmap: Bitmap)
