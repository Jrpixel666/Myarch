package dev.myarch.app.compositor

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.myarch.app.ui.window.LinuxWindowActivity
import dev.myarch.wayland.core.FrameBuffer
import dev.myarch.wayland.core.LayerFrame
import dev.myarch.wayland.core.LayerSurfaceInfo
import dev.myarch.wayland.core.WindowInfo
import dev.myarch.wayland.core.WindowSink

/**
 * Turns compositor events into Android ones.
 *
 * The important decision lives here: a mapped `xdg_toplevel` starts an activity
 * in a **new document task**, which is what makes each Linux window its own
 * entry in the recents list, swipeable and switchable like any Android app.
 * Panels (`zwlr_layer_shell_v1`) deliberately do *not* get a task -- they are
 * drawn by the launcher's home screen.
 */
public class AndroidWindowSink(
    private val context: Context,
    private val surfaces: WindowSurfaces,
    private val onWindowCountChanged: (Int) -> Unit,
) : WindowSink {

    override fun onWindowAdded(info: WindowInfo) {
        surfaces.upsertWindow(info)
        onWindowCountChanged(surfaces.windowIds().size)
        Log.i(TAG, "launching a task for window ${info.windowId} (${info.appId})")
        context.startActivity(LinuxWindowActivity.intentFor(context, info))
    }

    override fun onWindowChanged(info: WindowInfo) {
        surfaces.upsertWindow(info)
    }

    override fun onLayerFrame(layer: LayerFrame) {
        surfaces.putLayer(layer)
    }

    override fun onLayerRemoved(windowId: Int, surfaceId: Int) {
        surfaces.removeLayer(windowId, surfaceId)
    }

    override fun onWindowRemoved(windowId: Int) {
        surfaces.removeWindow(windowId)
        onWindowCountChanged(surfaces.windowIds().size)
        // The activity listens for its window disappearing and finishes itself;
        // broadcasting avoids holding activity references in the compositor.
        context.sendBroadcast(
            Intent(LinuxWindowActivity.ACTION_WINDOW_GONE)
                .putExtra(LinuxWindowActivity.EXTRA_WINDOW_ID, windowId)
                .setPackage(context.packageName),
        )
    }

    override fun onPanelAdded(info: LayerSurfaceInfo) {
        surfaces.upsertPanel(info)
    }

    override fun onPanelChanged(info: LayerSurfaceInfo) {
        surfaces.upsertPanel(info)
    }

    override fun onPanelFrame(info: LayerSurfaceInfo, frame: FrameBuffer) {
        surfaces.putPanelFrame(info, frame)
    }

    override fun onPanelRemoved(surfaceId: Int) {
        surfaces.removePanel(surfaceId)
    }

    private companion object {
        const val TAG = "MyarchSink"
    }
}
