package dev.myarch.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.myarch.runtime.theme.BuiltInThemes

/**
 * User settings, kept in `SharedPreferences`.
 *
 * Deliberately small: a launcher that loses the user's home screen layout
 * because a database migration failed is worse than one with fewer options.
 */
public class MyarchSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("myarch", Context.MODE_PRIVATE)

    /** The active Omarchy theme id. */
    public var themeId: String
        get() = prefs.getString(KEY_THEME, BuiltInThemes.default.id) ?: BuiltInThemes.default.id
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    /**
     * How many device pixels make one logical pixel in Linux windows.
     *
     * 2 is legible on a phone; 1 gives a desktop-sized workspace and is what
     * people pair with a Bluetooth keyboard and a big screen.
     */
    public var uiScale: Int
        get() = prefs.getInt(KEY_UI_SCALE, DEFAULT_UI_SCALE).coerceIn(1, MAX_UI_SCALE)
        set(value) = prefs.edit().putInt(KEY_UI_SCALE, value.coerceIn(1, MAX_UI_SCALE)).apply()

    /** Start the Linux session when the launcher starts. */
    public var autoStartSession: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    /** Keyboard layout passed to `xkbcli compile-keymap`. */
    public var keyboardLayout: String
        get() = prefs.getString(KEY_LAYOUT, "us") ?: "us"
        set(value) = prefs.edit().putString(KEY_LAYOUT, value).apply()

    /** The mirror the user pinned in the installer, if any. */
    public var preferredMirrorId: String?
        get() = prefs.getString(KEY_MIRROR, null)
        set(value) = prefs.edit().putString(KEY_MIRROR, value).apply()

    /** A mirror URL the user typed by hand. */
    public var customMirrorUrl: String?
        get() = prefs.getString(KEY_CUSTOM_MIRROR, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_MIRROR, value).apply()

    /** Show Linux applications alongside Android ones in the drawer. */
    public var showLinuxApps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LINUX_APPS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LINUX_APPS, value).apply()

    /** Serialised home screen pages; see [HomeLayoutStore]. */
    internal var homeLayout: String?
        get() = prefs.getString(KEY_HOME_LAYOUT, null)
        set(value) = prefs.edit().putString(KEY_HOME_LAYOUT, value).apply()

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_AUTOSTART = "autostart_session"
        const val KEY_LAYOUT = "keyboard_layout"
        const val KEY_MIRROR = "mirror_id"
        const val KEY_CUSTOM_MIRROR = "custom_mirror"
        const val KEY_SHOW_LINUX_APPS = "show_linux_apps"
        const val KEY_HOME_LAYOUT = "home_layout"

        const val DEFAULT_UI_SCALE = 2
        const val MAX_UI_SCALE = 4
    }
}
