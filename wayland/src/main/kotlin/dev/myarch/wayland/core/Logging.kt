package dev.myarch.wayland.core

/**
 * The compositor avoids `android.util.Log` directly so the core stays
 * unit-testable; the Android layer installs a sink that forwards to logcat
 * and to the on-screen diagnostics console.
 */
public interface WaylandLog {
    public fun debug(message: String)

    public fun info(message: String)

    public fun warn(message: String, error: Throwable? = null)

    public companion object {
        /** Discards everything; the default so tests stay quiet. */
        public val None: WaylandLog = object : WaylandLog {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String, error: Throwable?) = Unit
        }
    }
}
