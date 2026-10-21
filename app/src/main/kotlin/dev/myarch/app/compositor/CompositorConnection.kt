package dev.myarch.app.compositor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

/**
 * Binds an activity to [CompositorService].
 *
 * Wrapped in one place because every screen needs the same three things -- bind
 * on start, unbind on stop, and a callback once the service is there -- and
 * getting the unbind wrong leaks the activity.
 */
public class CompositorConnection(
    private val context: Context,
    private val onConnected: (CompositorService) -> Unit,
    private val onDisconnected: () -> Unit = {},
) : ServiceConnection {

    public var service: CompositorService? = null
        private set

    private var bound = false

    /** Starts the service if needed and binds to it. */
    public fun bind(startSession: Boolean) {
        if (bound) return
        val intent = Intent(context, CompositorService::class.java)
        if (startSession) {
            CompositorService.start(context)
        }
        bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    public fun unbind() {
        if (!bound) return
        runCatching { context.unbindService(this) }
        bound = false
        service = null
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val local = binder as? CompositorService.LocalBinder ?: return
        service = local.service()
        local.service().let(onConnected)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        onDisconnected()
    }
}
