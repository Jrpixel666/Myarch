package dev.myarch.app.compositor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.myarch.wayland.core.AndroidKeyMap
import dev.myarch.wayland.core.Compositor
import dev.myarch.wayland.core.TouchPhase

/**
 * Draws one Linux window and forwards input to it.
 *
 * A `SurfaceView` is the right surface for this: the compositor produces frames
 * on its own thread, and a SurfaceView can be drawn from that thread without
 * going through the view hierarchy's invalidate/measure/layout cycle on every
 * frame.
 */
@SuppressLint("ViewConstructor")
public class LinuxWindowView(
    context: Context,
    private val windowId: Int,
    private val compositor: Compositor,
    private val surfaces: WindowSurfaces,
    private val uiScale: Int,
) : SurfaceView(context) {

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }
    private val destination = Rect()
    private var unobserve: (() -> Unit)? = null

    @Volatile
    private var surfaceReady = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                    redraw()
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    // Tell the client how big its window actually is. The
                    // compositor divides by the UI scale and configures the
                    // toplevel, which answers with a matching buffer.
                    compositor.resizeWindow(windowId, width, height)
                    redraw()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                }
            },
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unobserve = surfaces.observeWindow(windowId) { redraw() }
        compositor.focusWindow(windowId)
    }

    override fun onDetachedFromWindow() {
        unobserve?.invoke()
        unobserve = null
        super.onDetachedFromWindow()
    }

    /**
     * Composites the window's layers onto the surface.
     *
     * Called from the compositor thread, so it must tolerate the surface being
     * torn down underneath it -- `lockCanvas` returns null in that window.
     */
    public fun redraw() {
        if (!surfaceReady) return
        val snapshot = surfaces.snapshot(windowId) ?: return
        val canvas = try {
            holder.lockCanvas()
        } catch (illegal: IllegalStateException) {
            null
        } ?: return
        try {
            canvas.drawColor(Color.BLACK)
            for (layer in snapshot.layers) {
                destination.set(
                    layer.x * uiScale,
                    layer.y * uiScale,
                    (layer.x + layer.bitmap.width) * uiScale,
                    (layer.y + layer.bitmap.height) * uiScale,
                )
                if (!layer.bitmap.isRecycled) {
                    canvas.drawBitmap(layer.bitmap, null, destination, paint)
                }
            }
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    @Suppress("ReturnCount")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                compositor.dispatchTouch(
                    windowId,
                    event.getPointerId(index),
                    TouchPhase.DOWN,
                    event.getX(index),
                    event.getY(index),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                // A move event carries every pointer at once.
                for (index in 0 until event.pointerCount) {
                    compositor.dispatchTouch(
                        windowId,
                        event.getPointerId(index),
                        TouchPhase.MOVE,
                        event.getX(index),
                        event.getY(index),
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                compositor.dispatchTouch(
                    windowId,
                    event.getPointerId(index),
                    TouchPhase.UP,
                    event.getX(index),
                    event.getY(index),
                )
            }
            MotionEvent.ACTION_CANCEL ->
                compositor.dispatchTouch(windowId, 0, TouchPhase.CANCEL, 0f, 0f)
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            // Wayland axis values are in surface pixels, not notches.
            compositor.dispatchScroll(
                windowId,
                -horizontal * SCROLL_STEP_PX,
                -vertical * SCROLL_STEP_PX,
                finished = true,
            )
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            compositor.dispatchHover(windowId, event.x, event.y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = forwardKey(keyCode, pressed = true)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = forwardKey(keyCode, pressed = false)

    private fun forwardKey(keyCode: Int, pressed: Boolean): Boolean {
        // Back is the system's gesture, not the window's: it closes popups
        // first, and the activity handles the rest.
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        val evdev = AndroidKeyMap.toEvdev(keyCode)
        if (evdev == 0) return false
        compositor.dispatchKey(windowId, evdev, pressed)
        return true
    }

    private companion object {
        /** One mouse-wheel notch, in surface pixels. */
        const val SCROLL_STEP_PX = 40f
    }
}
