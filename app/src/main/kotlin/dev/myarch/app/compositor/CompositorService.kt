package dev.myarch.app.compositor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import dev.myarch.app.MyarchApplication
import dev.myarch.app.R
import dev.myarch.app.ui.console.ConsoleActivity
import dev.myarch.runtime.MyarchPaths
import dev.myarch.runtime.container.ContainerManager
import dev.myarch.wayland.android.LocalSocketServerTransport
import dev.myarch.wayland.core.Compositor
import dev.myarch.wayland.core.CompositorConfig
import dev.myarch.wayland.core.Keymap
import dev.myarch.wayland.core.WaylandLog
import dev.myarch.wayland.core.WaylandServer
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the session is doing, for the UI to show. */
public sealed interface SessionState {
    public data object Stopped : SessionState

    public data object Starting : SessionState

    public data class Running(val windows: Int) : SessionState

    public data class Failed(val reason: String) : SessionState
}

/**
 * Owns the compositor, the Wayland socket and the container's session process.
 *
 * It is a foreground service because everything the user can see depends on it:
 * if Android reclaimed it, every open Linux window would vanish at once. The
 * notification is the honest price of that.
 */
public class CompositorService : Service() {

    private val binder = LocalBinder()

    private var server: WaylandServer? = null
    private var transport: LocalSocketServerTransport? = null
    private var sessionProcess: Process? = null
    private var logPump: Thread? = null

    private val _state = MutableStateFlow<SessionState>(SessionState.Stopped)
    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Observable session state. */
    public val state: StateFlow<SessionState> get() = _state.asStateFlow()

    /** The tail of the session log, shown in the diagnostics screen. */
    public val sessionLog: StateFlow<List<String>> get() = _log.asStateFlow()

    /** Pixels and window metadata for the UI. */
    public val surfaces: WindowSurfaces = WindowSurfaces()

    public var compositor: Compositor? = null
        private set

    private lateinit var paths: MyarchPaths
    private lateinit var containerManager: ContainerManager

    /** Binder handing the activities the service itself. */
    public inner class LocalBinder : Binder() {
        public fun service(): CompositorService = this@CompositorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val app = MyarchApplication.of(this)
        paths = app.paths
        containerManager = app.containerManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.compositor_stopped)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> stopSession()
            ACTION_RESTART -> {
                stopSession()
                startSession()
            }
        }
        // The session must survive the activity that asked for it.
        return START_STICKY
    }

    /**
     * Brings up the compositor, then the container's session.
     *
     * The order matters: the socket has to exist before any guest process looks
     * for `$WAYLAND_DISPLAY`, or Quickshell exits before the user sees anything.
     */
    public fun startSession() {
        if (_state.value is SessionState.Running || _state.value is SessionState.Starting) return
        _state.value = SessionState.Starting
        appendLog("starting the Myarch session")

        val problem = containerManager.readinessProblem()
        if (problem != null) {
            fail(problem)
            return
        }

        try {
            startCompositor()
        } catch (io: IOException) {
            fail("Could not create the Wayland socket: ${io.message}")
            return
        }

        try {
            val process = containerManager.startDesktop()
            sessionProcess = process
            pumpSessionLog(process)
            _state.value = SessionState.Running(0)
            updateNotification(getString(R.string.compositor_running))
            appendLog("container session started")
        } catch (io: IOException) {
            fail("Could not start the container: ${io.message}")
        }
    }

    private fun startCompositor() {
        if (server != null) return
        paths.prepare()
        val config = CompositorConfig(
            outputWidthPx = displayWidth(),
            outputHeightPx = displayHeight(),
            uiScale = MyarchApplication.of(this).settings.uiScale,
            keymapProvider = ::loadKeymap,
        )
        val sink = AndroidWindowSink(this, surfaces) { windows ->
            _state.value = SessionState.Running(windows)
        }
        val newCompositor = Compositor(config, sink, logger())
        val socketTransport = LocalSocketServerTransport(paths.waylandSocket)
        val newServer = WaylandServer(socketTransport, newCompositor, logger())
        newServer.start()
        compositor = newCompositor
        transport = socketTransport
        server = newServer
        appendLog("compositor listening on ${socketTransport.path}")
    }

    /**
     * Opens the keymap for `wl_keyboard.keymap`.
     *
     * The container regenerates a full keymap on session start; before that
     * exists we stage the compact one bundled in the APK, because clients block
     * until they are sent a keymap.
     */
    private fun loadKeymap(): Keymap? {
        val generated = paths.keymapFile
        val source = if (generated.isFile && generated.length() > 0) {
            generated
        } else {
            val fallback = File(paths.scripts, "keymap/us-fallback.xkb")
            if (!fallback.isFile) {
                appendLog("no keymap available; keyboard input will not work")
                return null
            }
            fallback.copyTo(generated, overwrite = true)
            appendLog("using the bundled fallback keymap")
            generated
        }
        return try {
            // The descriptor stays open for the compositor's lifetime: clients
            // mmap it, so closing it would pull the keymap out from under them.
            val stream = FileInputStream(source)
            Keymap(format = XKB_V1, fd = stream.fd, sizeBytes = source.length().toInt())
        } catch (io: IOException) {
            appendLog("could not open the keymap: ${io.message}")
            null
        }
    }

    /** Stops the container session but leaves the compositor listening. */
    public fun stopSession() {
        sessionProcess?.destroy()
        sessionProcess = null
        logPump?.interrupt()
        logPump = null
        for (windowId in surfaces.windowIds()) {
            surfaces.removeWindow(windowId)
        }
        _state.value = SessionState.Stopped
        updateNotification(getString(R.string.compositor_stopped))
        appendLog("session stopped")
    }

    private fun pumpSessionLog(process: Process) {
        logPump = Thread({
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) appendLog(line)
                }
            }
            val exit = runCatching { process.waitFor() }.getOrNull()
            if (_state.value is SessionState.Running) {
                // The session leader exiting means the shell died; say so rather
                // than leaving a "running" state with an empty screen.
                _state.value = SessionState.Failed("The session exited (code $exit)")
                updateNotification("Session exited")
            }
        }, "myarch-session-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        appendLog(reason)
        _state.value = SessionState.Failed(reason)
        updateNotification("Session failed")
    }

    private fun appendLog(line: String) {
        _log.value = (_log.value + line).takeLast(MAX_LOG_LINES)
    }

    private fun logger(): WaylandLog = object : WaylandLog {
        override fun debug(message: String) {
            Log.d(TAG, message)
        }

        override fun info(message: String) {
            Log.i(TAG, message)
            appendLog(message)
        }

        override fun warn(message: String, error: Throwable?) {
            Log.w(TAG, message, error)
            appendLog("warning: $message")
        }
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun displayWidth(): Int = displayMetrics().widthPixels.coerceAtLeast(MIN_DIMENSION)

    private fun displayHeight(): Int = displayMetrics().heightPixels.coerceAtLeast(MIN_DIMENSION)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.compositor_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.compositor_channel_description)
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ConsoleActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopSession()
        server?.close()
        transport?.close()
        server = null
        transport = null
        compositor = null
        super.onDestroy()
    }

    public companion object {
        private const val TAG = "MyarchCompositor"
        private const val CHANNEL_ID = "myarch-session"
        private const val NOTIFICATION_ID = 1
        private const val MAX_LOG_LINES = 500
        private const val MIN_DIMENSION = 320

        /** `wl_keyboard.keymap_format.xkb_v1`. */
        private const val XKB_V1 = 1

        public const val ACTION_START: String = "dev.myarch.action.START_SESSION"
        public const val ACTION_STOP: String = "dev.myarch.action.STOP_SESSION"
        public const val ACTION_RESTART: String = "dev.myarch.action.RESTART_SESSION"

        /** Starts the service and the session. */
        public fun start(context: Context) {
            val intent = Intent(context, CompositorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        public fun stop(context: Context) {
            context.startService(Intent(context, CompositorService::class.java).setAction(ACTION_STOP))
        }
    }
}
