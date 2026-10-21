package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.WlKeyboard
import dev.myarch.wayland.protocol.WlPointer
import dev.myarch.wayland.protocol.WlSeat
import dev.myarch.wayland.protocol.WlTouch
import dev.myarch.wayland.wire.Fixed
import dev.myarch.wayland.wire.WireMessage
import java.io.FileDescriptor

/** Linux `input-event-codes.h` button numbers, as the protocol requires. */
public object LinuxButton {
    public const val LEFT: Int = 0x110
    public const val RIGHT: Int = 0x111
    public const val MIDDLE: Int = 0x112
}

/** The keymap Myarch hands to clients. */
public data class Keymap(
    /** `wl_keyboard.keymap_format`; 1 is `xkb_v1`. */
    val format: Int,
    val fd: FileDescriptor,
    val sizeBytes: Int,
)

/**
 * `wl_seat`: one set of input devices.
 *
 * A phone has a touchscreen, an on-screen keyboard and possibly a Bluetooth
 * mouse or keyboard, so Myarch advertises all three capabilities and
 * synthesises pointer events from touch where a client only understands a
 * mouse.
 */
public class SeatResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlSeat.SPEC) {

    internal val keyboards = ArrayList<KeyboardResource>()
    internal val pointers = ArrayList<PointerResource>()
    internal val touches = ArrayList<TouchResource>()

    init {
        send(
            "capabilities",
            WlSeat.Capability.POINTER or WlSeat.Capability.KEYBOARD or WlSeat.Capability.TOUCH,
        )
        if (supports(SEAT_NAME_SINCE)) {
            send("name", "myarch-seat0")
        }
        client.compositor.registerSeat(this)
    }

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlSeat.Request.GET_POINTER -> {
                val pointer = PointerResource(client, message.newId(0), version)
                client.addResource(pointer)
                pointers.add(pointer)
            }
            WlSeat.Request.GET_KEYBOARD -> {
                val keyboard = KeyboardResource(client, message.newId(0), version)
                client.addResource(keyboard)
                keyboards.add(keyboard)
                keyboard.sendKeymap(client.compositor.keymap())
            }
            WlSeat.Request.GET_TOUCH -> {
                val touch = TouchResource(client, message.newId(0), version)
                client.addResource(touch)
                touches.add(touch)
            }
            WlSeat.Request.RELEASE -> destroySelf()
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_seat opcode ${message.opcode}")
        }
    }

    override fun onDestroy() {
        client.compositor.unregisterSeat(this)
    }

    private companion object {
        const val SEAT_NAME_SINCE = 2
    }
}

/** `wl_keyboard`: key presses and modifier state for the focused surface. */
public class KeyboardResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlKeyboard.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlKeyboard.Request.RELEASE -> destroySelf()
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_keyboard opcode ${message.opcode}")
        }
    }

    /**
     * Sends the keymap, or declares that there is none.
     *
     * Clients mmap the descriptor, so it has to be a real seekable file. The
     * container generates a full XKB keymap on first boot; if that has not
     * happened yet we fall back to the compact map bundled in the APK, and if
     * even that is missing we must still send a keymap event -- `libwayland`
     * clients block forever waiting for one.
     */
    public fun sendKeymap(keymap: Keymap?) {
        if (keymap == null) {
            send("keymap", WlKeyboard.KeymapFormat.NO_KEYMAP, NULL_FD, 0)
        } else {
            send("keymap", keymap.format, keymap.fd, keymap.sizeBytes)
        }
        if (supports(REPEAT_INFO_SINCE)) {
            send("repeat_info", KEY_REPEAT_RATE_HZ, KEY_REPEAT_DELAY_MS)
        }
    }

    public fun enter(serial: Int, surfaceId: Int, pressedKeys: List<Int>) {
        send("enter", serial, surfaceId, encodeUints(pressedKeys))
    }

    public fun leave(serial: Int, surfaceId: Int) {
        send("leave", serial, surfaceId)
    }

    public fun key(serial: Int, timeMs: Int, keyCode: Int, pressed: Boolean) {
        val state = if (pressed) WlKeyboard.KeyState.PRESSED else WlKeyboard.KeyState.RELEASED
        send("key", serial, timeMs, keyCode, state)
    }

    public fun modifiers(serial: Int, depressed: Int, latched: Int, locked: Int, group: Int) {
        send("modifiers", serial, depressed, latched, locked, group)
    }

    private companion object {
        const val REPEAT_INFO_SINCE = 4
        const val KEY_REPEAT_RATE_HZ = 25
        const val KEY_REPEAT_DELAY_MS = 400

        /**
         * `wl_keyboard.keymap` has no nullable descriptor, so the no-keymap case
         * still needs one on the wire. `FileDescriptor.in` is always valid and
         * the client ignores it when the format says there is no keymap.
         */
        val NULL_FD: FileDescriptor = FileDescriptor.`in`
    }
}

/** `wl_pointer`: mouse-shaped input, including touch we translate for it. */
public class PointerResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlPointer.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlPointer.Request.SET_CURSOR -> client.compositor.onCursorSet(message)
            WlPointer.Request.RELEASE -> destroySelf()
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_pointer opcode ${message.opcode}")
        }
    }

    public fun enter(serial: Int, surfaceId: Int, x: Double, y: Double) {
        send("enter", serial, surfaceId, Fixed.of(x), Fixed.of(y))
        frame()
    }

    public fun leave(serial: Int, surfaceId: Int) {
        send("leave", serial, surfaceId)
        frame()
    }

    public fun motion(timeMs: Int, x: Double, y: Double) {
        send("motion", timeMs, Fixed.of(x), Fixed.of(y))
        frame()
    }

    public fun button(serial: Int, timeMs: Int, button: Int, pressed: Boolean) {
        val state = if (pressed) WlPointer.ButtonState.PRESSED else WlPointer.ButtonState.RELEASED
        send("button", serial, timeMs, button, state)
        frame()
    }

    /** Scrolls by a distance in surface-local pixels. */
    public fun axis(timeMs: Int, horizontal: Double, vertical: Double) {
        if (supports(AXIS_SOURCE_SINCE)) {
            send("axis_source", WlPointer.AxisSource.FINGER)
        }
        if (vertical != 0.0) {
            send("axis", timeMs, WlPointer.Axis.VERTICAL_SCROLL, Fixed.of(vertical))
        }
        if (horizontal != 0.0) {
            send("axis", timeMs, WlPointer.Axis.HORIZONTAL_SCROLL, Fixed.of(horizontal))
        }
        frame()
    }

    /** Ends a kinetic scroll so clients stop their own momentum animation. */
    public fun axisStop(timeMs: Int) {
        if (supports(AXIS_SOURCE_SINCE)) {
            send("axis_stop", timeMs, WlPointer.Axis.VERTICAL_SCROLL)
            send("axis_stop", timeMs, WlPointer.Axis.HORIZONTAL_SCROLL)
            frame()
        }
    }

    /** Groups the events above into one logical input frame. */
    public fun frame() {
        if (supports(FRAME_SINCE)) {
            send("frame")
        }
    }

    private companion object {
        const val FRAME_SINCE = 5
        const val AXIS_SOURCE_SINCE = 5
    }
}

/** `wl_touch`: direct multi-touch, which is what a phone actually has. */
public class TouchResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlTouch.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlTouch.Request.RELEASE -> destroySelf()
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_touch opcode ${message.opcode}")
        }
    }

    @Suppress("LongParameterList")
    public fun down(serial: Int, timeMs: Int, surfaceId: Int, touchId: Int, x: Double, y: Double) {
        send("down", serial, timeMs, surfaceId, touchId, Fixed.of(x), Fixed.of(y))
    }

    public fun up(serial: Int, timeMs: Int, touchId: Int) {
        send("up", serial, timeMs, touchId)
    }

    public fun motion(timeMs: Int, touchId: Int, x: Double, y: Double) {
        send("motion", timeMs, touchId, Fixed.of(x), Fixed.of(y))
    }

    public fun frame() {
        send("frame")
    }

    /** Tells the client the gesture was taken over by the system. */
    public fun cancel() {
        send("cancel")
    }
}
