package dev.myarch.wayland.core

/**
 * Linux `KEY_*` codes from `input-event-codes.h`.
 *
 * Wayland carries raw evdev scancodes, not characters: the client turns them
 * into symbols using the XKB keymap we hand it. So the Android key code has to
 * be translated back into the scancode a PC keyboard would have produced.
 */
@Suppress("MagicNumber")
public object LinuxKey {
    public const val ESC: Int = 1
    public const val MINUS: Int = 12
    public const val EQUAL: Int = 13
    public const val BACKSPACE: Int = 14
    public const val TAB: Int = 15
    public const val LEFTBRACE: Int = 26
    public const val RIGHTBRACE: Int = 27
    public const val ENTER: Int = 28
    public const val LEFTCTRL: Int = 29
    public const val SEMICOLON: Int = 39
    public const val APOSTROPHE: Int = 40
    public const val GRAVE: Int = 41
    public const val LEFTSHIFT: Int = 42
    public const val BACKSLASH: Int = 43
    public const val COMMA: Int = 51
    public const val DOT: Int = 52
    public const val SLASH: Int = 53
    public const val RIGHTSHIFT: Int = 54
    public const val LEFTALT: Int = 56
    public const val SPACE: Int = 57
    public const val CAPSLOCK: Int = 58
    public const val NUMLOCK: Int = 69
    public const val RIGHTCTRL: Int = 97
    public const val RIGHTALT: Int = 100
    public const val HOME: Int = 102
    public const val UP: Int = 103
    public const val PAGEUP: Int = 104
    public const val LEFT: Int = 105
    public const val RIGHT: Int = 106
    public const val END: Int = 107
    public const val DOWN: Int = 108
    public const val PAGEDOWN: Int = 109
    public const val INSERT: Int = 110
    public const val DELETE: Int = 111
    public const val LEFTMETA: Int = 125
    public const val RIGHTMETA: Int = 126
}

/**
 * Translates `android.view.KeyEvent` key codes into evdev scancodes.
 *
 * The table is written against the numeric constants rather than against
 * `KeyEvent` itself so the whole input path can be unit tested on a plain JVM.
 * Every entry names the constant it stands for.
 */
@Suppress("MagicNumber")
public object AndroidKeyMap {

    private val table: Map<Int, Int> = buildMap {
        // KEYCODE_A..KEYCODE_Z are 29..54 and are *not* in alphabetical order
        // on an evdev keyboard, so they are listed one by one.
        put(29, 30) // A
        put(30, 48) // B
        put(31, 46) // C
        put(32, 32) // D
        put(33, 18) // E
        put(34, 33) // F
        put(35, 34) // G
        put(36, 35) // H
        put(37, 23) // I
        put(38, 36) // J
        put(39, 37) // K
        put(40, 38) // L
        put(41, 50) // M
        put(42, 49) // N
        put(43, 24) // O
        put(44, 25) // P
        put(45, 16) // Q
        put(46, 19) // R
        put(47, 31) // S
        put(48, 20) // T
        put(49, 22) // U
        put(50, 47) // V
        put(51, 17) // W
        put(52, 45) // X
        put(53, 21) // Y
        put(54, 44) // Z

        // KEYCODE_0..KEYCODE_9 are 7..16; evdev puts 1..9 at 2..10 and 0 at 11.
        put(7, 11) // 0
        for (digit in 1..9) {
            put(7 + digit, 1 + digit)
        }

        put(62, LinuxKey.SPACE) // KEYCODE_SPACE
        put(66, LinuxKey.ENTER) // KEYCODE_ENTER
        put(67, LinuxKey.BACKSPACE) // KEYCODE_DEL
        put(112, LinuxKey.DELETE) // KEYCODE_FORWARD_DEL
        put(61, LinuxKey.TAB) // KEYCODE_TAB
        put(111, LinuxKey.ESC) // KEYCODE_ESCAPE
        put(59, LinuxKey.LEFTSHIFT) // KEYCODE_SHIFT_LEFT
        put(60, LinuxKey.RIGHTSHIFT) // KEYCODE_SHIFT_RIGHT
        put(57, LinuxKey.LEFTALT) // KEYCODE_ALT_LEFT
        put(58, LinuxKey.RIGHTALT) // KEYCODE_ALT_RIGHT
        put(113, LinuxKey.LEFTCTRL) // KEYCODE_CTRL_LEFT
        put(114, LinuxKey.RIGHTCTRL) // KEYCODE_CTRL_RIGHT
        put(117, LinuxKey.LEFTMETA) // KEYCODE_META_LEFT
        put(118, LinuxKey.RIGHTMETA) // KEYCODE_META_RIGHT
        put(115, LinuxKey.CAPSLOCK) // KEYCODE_CAPS_LOCK
        put(143, LinuxKey.NUMLOCK) // KEYCODE_NUM_LOCK

        put(19, LinuxKey.UP) // KEYCODE_DPAD_UP
        put(20, LinuxKey.DOWN) // KEYCODE_DPAD_DOWN
        put(21, LinuxKey.LEFT) // KEYCODE_DPAD_LEFT
        put(22, LinuxKey.RIGHT) // KEYCODE_DPAD_RIGHT
        put(92, LinuxKey.PAGEUP) // KEYCODE_PAGE_UP
        put(93, LinuxKey.PAGEDOWN) // KEYCODE_PAGE_DOWN
        put(122, LinuxKey.HOME) // KEYCODE_MOVE_HOME
        put(123, LinuxKey.END) // KEYCODE_MOVE_END
        put(124, LinuxKey.INSERT) // KEYCODE_INSERT

        put(69, LinuxKey.MINUS) // KEYCODE_MINUS
        put(70, LinuxKey.EQUAL) // KEYCODE_EQUALS
        put(71, LinuxKey.LEFTBRACE) // KEYCODE_LEFT_BRACKET
        put(72, LinuxKey.RIGHTBRACE) // KEYCODE_RIGHT_BRACKET
        put(73, LinuxKey.BACKSLASH) // KEYCODE_BACKSLASH
        put(74, LinuxKey.SEMICOLON) // KEYCODE_SEMICOLON
        put(75, LinuxKey.APOSTROPHE) // KEYCODE_APOSTROPHE
        put(68, LinuxKey.GRAVE) // KEYCODE_GRAVE
        put(55, LinuxKey.COMMA) // KEYCODE_COMMA
        put(56, LinuxKey.DOT) // KEYCODE_PERIOD
        put(76, LinuxKey.SLASH) // KEYCODE_SLASH

        // KEYCODE_F1..KEYCODE_F12 are 131..142. evdev has F1..F10 at 59..68 and
        // then jumps to 87/88 for F11 and F12.
        for (index in 0..9) {
            put(131 + index, 59 + index)
        }
        put(141, 87) // F11
        put(142, 88) // F12

        // Numeric keypad: KEYCODE_NUMPAD_0..9 are 144..153, evdev KP7 starts at 71.
        put(144, 82) // KP0
        put(145, 79) // KP1
        put(146, 80) // KP2
        put(147, 81) // KP3
        put(148, 75) // KP4
        put(149, 76) // KP5
        put(150, 77) // KP6
        put(151, 71) // KP7
        put(152, 72) // KP8
        put(153, 73) // KP9
        put(154, 98) // KEYCODE_NUMPAD_DIVIDE
        put(155, 55) // KEYCODE_NUMPAD_MULTIPLY
        put(156, 74) // KEYCODE_NUMPAD_SUBTRACT
        put(157, 78) // KEYCODE_NUMPAD_ADD
        put(158, 83) // KEYCODE_NUMPAD_DOT
        put(160, 96) // KEYCODE_NUMPAD_ENTER
    }

    /** The evdev code for [androidKeyCode], or 0 when there is no equivalent. */
    public fun toEvdev(androidKeyCode: Int): Int = table[androidKeyCode] ?: 0

    /** True when Myarch can forward this Android key code. */
    public fun isMapped(androidKeyCode: Int): Boolean = table.containsKey(androidKeyCode)

    /** The number of mapped keys; used by the tests to catch table damage. */
    public val size: Int get() = table.size
}

/**
 * Tracks which modifiers are held so `wl_keyboard.modifiers` can be sent.
 *
 * The bit positions are XKB's default order, which is what every keymap
 * produced by `xkbcomp` uses: Shift, Lock, Control, Mod1 (Alt), Mod2 (NumLock),
 * Mod3, Mod4 (Logo), Mod5 (AltGr).
 */
public class ModifierTracker {

    private val pressed = HashSet<Int>()

    /** Currently held modifier mask. */
    public var depressed: Int = 0
        private set

    /** Latched-on modifier mask, i.e. Caps Lock and Num Lock. */
    public var locked: Int = 0
        private set

    /**
     * Records a key transition.
     *
     * @return true when the modifier state changed and the client needs a new
     *   `wl_keyboard.modifiers` event
     */
    public fun onKey(evdevCode: Int, isPressed: Boolean): Boolean {
        val mask = maskFor(evdevCode)
        if (mask == 0) return false
        if (evdevCode == LinuxKey.CAPSLOCK || evdevCode == LinuxKey.NUMLOCK) {
            // Lock keys toggle on press and ignore the release.
            if (!isPressed) return false
            locked = locked xor mask
            return true
        }
        val changed = if (isPressed) pressed.add(evdevCode) else pressed.remove(evdevCode)
        if (!changed) return false
        depressed = pressed.fold(0) { acc, code -> acc or maskFor(code) }
        return true
    }

    /** Forgets all state, for when focus leaves the container entirely. */
    public fun reset() {
        pressed.clear()
        depressed = 0
    }

    private fun maskFor(evdevCode: Int): Int = when (evdevCode) {
        LinuxKey.LEFTSHIFT, LinuxKey.RIGHTSHIFT -> SHIFT
        LinuxKey.CAPSLOCK -> LOCK
        LinuxKey.LEFTCTRL, LinuxKey.RIGHTCTRL -> CONTROL
        LinuxKey.LEFTALT -> MOD1
        LinuxKey.NUMLOCK -> MOD2
        LinuxKey.LEFTMETA, LinuxKey.RIGHTMETA -> MOD4
        LinuxKey.RIGHTALT -> MOD5
        else -> 0
    }

    public companion object {
        public const val SHIFT: Int = 1 shl 0
        public const val LOCK: Int = 1 shl 1
        public const val CONTROL: Int = 1 shl 2
        public const val MOD1: Int = 1 shl 3
        public const val MOD2: Int = 1 shl 4
        public const val MOD3: Int = 1 shl 5
        public const val MOD4: Int = 1 shl 6
        public const val MOD5: Int = 1 shl 7
    }
}
