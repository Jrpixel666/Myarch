package dev.myarch.app.ui.window

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.myarch.app.MyarchApplication
import dev.myarch.app.compositor.CompositorConnection
import dev.myarch.app.compositor.CompositorService
import dev.myarch.app.compositor.LinuxWindowView
import dev.myarch.runtime.theme.parseHexColor
import dev.myarch.wayland.core.WindowInfo

/**
 * One Linux window, as one Android task.
 *
 * This is the piece that makes Myarch feel like a phone rather than a remote
 * desktop: because the activity uses `documentLaunchMode="always"`, every
 * `xdg_toplevel` the container maps turns into its own task. Swiping through
 * recents switches Linux windows, swiping one away closes that window, and
 * Android's own split screen puts two Linux windows side by side at no extra
 * cost to us.
 */
public class LinuxWindowActivity : Activity() {

    private var windowId: Int = 0
    private var view: LinuxWindowView? = null
    private lateinit var connection: CompositorConnection
    private lateinit var container: FrameLayout
    private var placeholder: TextView? = null

    /** Finishes the task when the client closes its window. */
    private val windowGone = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(EXTRA_WINDOW_ID, -1) == windowId) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        windowId = intent.getIntExtra(EXTRA_WINDOW_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(dev.myarch.app.R.string.linux_window)

        val theme = MyarchApplication.of(this).theme
        container = FrameLayout(this).apply {
            setBackgroundColor(parseHexColor(theme.palette.background))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        placeholder = TextView(this).apply {
            text = "Waiting for $title..."
            setTextColor(parseHexColor(theme.palette.muted))
            textSize = PLACEHOLDER_TEXT_SP
            gravity = android.view.Gravity.CENTER
        }
        container.addView(
            placeholder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(container)
        describeTask(title, theme.palette.background)

        if (windowId == 0) {
            // Nothing to show: this happens if the task is restored after the
            // session was stopped, and leaving an empty black task is worse
            // than quietly going away.
            finishAndRemoveTask()
            return
        }

        connection = CompositorConnection(this, ::attachWindow)
        registerReceiver(windowGone, IntentFilter(ACTION_WINDOW_GONE))
    }

    private fun attachWindow(service: CompositorService) {
        val compositor = service.compositor ?: run {
            finishAndRemoveTask()
            return
        }
        val snapshot = service.surfaces.snapshot(windowId)
        if (snapshot == null) {
            finishAndRemoveTask()
            return
        }
        placeholder?.let(container::removeView)
        placeholder = null
        val windowView = LinuxWindowView(
            context = this,
            windowId = windowId,
            compositor = compositor,
            surfaces = service.surfaces,
            uiScale = MyarchApplication.of(this).settings.uiScale,
        )
        view = windowView
        container.addView(
            windowView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        windowView.requestFocus()
        describeTask(snapshot.info.title, MyarchApplication.of(this).theme.palette.background)
    }

    /** Names the task in the recents list, so windows are distinguishable. */
    private fun describeTask(title: String, backgroundHex: String) {
        val color = parseHexColor(backgroundHex)
        @Suppress("DEPRECATION")
        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ActivityManager.TaskDescription.Builder()
                .setLabel(title)
                .setPrimaryColor(color)
                .build()
        } else {
            ActivityManager.TaskDescription(title, null, color)
        }
        setTaskDescription(description)
    }

    override fun onStart() {
        super.onStart()
        if (windowId != 0) connection.bind(startSession = false)
    }

    override fun onResume() {
        super.onResume()
        // Android's task order is the source of truth for focus: whichever
        // window the user brought forward is the one that should receive keys.
        connection.service?.compositor?.focusWindow(windowId)
        view?.redraw()
    }

    override fun onStop() {
        connection.unbind()
        super.onStop()
    }

    @Deprecated("Back handling for API 28 and below; replaced by OnBackInvokedCallback later.")
    override fun onBackPressed() {
        val compositor = connection.service?.compositor
        // A back gesture should dismiss an open menu before it closes a window,
        // which is what the same gesture does in Android apps.
        if (compositor?.dismissPopups() == true) return
        compositor?.closeWindow(windowId)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(windowGone) }
        if (isFinishing) {
            // Swiping the task away is the user closing the window.
            connection.service?.compositor?.closeWindow(windowId)
        }
        runCatching { connection.unbind() }
        super.onDestroy()
    }

    public companion object {
        public const val EXTRA_WINDOW_ID: String = "dev.myarch.extra.WINDOW_ID"
        public const val EXTRA_TITLE: String = "dev.myarch.extra.WINDOW_TITLE"
        public const val ACTION_WINDOW_GONE: String = "dev.myarch.action.WINDOW_GONE"

        private const val PLACEHOLDER_TEXT_SP = 14f

        /**
         * Builds the intent that gives [info] a task of its own.
         *
         * The data URI matters: `documentLaunchMode` keys tasks by intent data,
         * so without a distinct URI per window Android would reuse one task for
         * all of them.
         */
        public fun intentFor(context: Context, info: WindowInfo): Intent =
            Intent(context, LinuxWindowActivity::class.java).apply {
                data = Uri.parse("myarch://window/${info.windowId}")
                putExtra(EXTRA_WINDOW_ID, info.windowId)
                putExtra(EXTRA_TITLE, info.title)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                )
            }

        /** Brings an already-open window's task to the front. */
        public fun focus(context: Context, info: WindowInfo) {
            context.startActivity(
                intentFor(context, info).apply {
                    // Without MULTIPLE_TASK the existing document task is
                    // reused, which is exactly what "focus" means here.
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                },
            )
        }
    }
}
