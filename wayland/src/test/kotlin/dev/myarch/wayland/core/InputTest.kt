package dev.myarch.wayland.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Android-to-evdev translation and the modifier state machine. */
class InputTest {

    @Test
    fun `maps letters to their evdev positions, not alphabetical order`() {
        // KEYCODE_A is 29 and KEY_A is 30; KEYCODE_B is 30 and KEY_B is 48.
        assertThat(AndroidKeyMap.toEvdev(29)).isEqualTo(30)
        assertThat(AndroidKeyMap.toEvdev(30)).isEqualTo(48)
        assertThat(AndroidKeyMap.toEvdev(45)).isEqualTo(16) // Q -> KEY_Q
        assertThat(AndroidKeyMap.toEvdev(54)).isEqualTo(44) // Z -> KEY_Z
    }

    @Test
    fun `maps digits with zero after nine`() {
        assertThat(AndroidKeyMap.toEvdev(8)).isEqualTo(2) // 1 -> KEY_1
        assertThat(AndroidKeyMap.toEvdev(16)).isEqualTo(10) // 9 -> KEY_9
        assertThat(AndroidKeyMap.toEvdev(7)).isEqualTo(11) // 0 -> KEY_0
    }

    @Test
    fun `maps the function row across the gap between F10 and F11`() {
        assertThat(AndroidKeyMap.toEvdev(131)).isEqualTo(59) // F1
        assertThat(AndroidKeyMap.toEvdev(140)).isEqualTo(68) // F10
        assertThat(AndroidKeyMap.toEvdev(141)).isEqualTo(87) // F11
        assertThat(AndroidKeyMap.toEvdev(142)).isEqualTo(88) // F12
    }

    @Test
    fun `returns zero for keys with no Linux equivalent`() {
        assertThat(AndroidKeyMap.toEvdev(3)).isEqualTo(0) // KEYCODE_HOME
        assertThat(AndroidKeyMap.isMapped(3)).isFalse()
        assertThat(AndroidKeyMap.isMapped(29)).isTrue()
    }

    @Test
    fun `holding shift sets the shift mask and releasing clears it`() {
        val tracker = ModifierTracker()
        assertThat(tracker.onKey(LinuxKey.LEFTSHIFT, isPressed = true)).isTrue()
        assertThat(tracker.depressed).isEqualTo(ModifierTracker.SHIFT)
        assertThat(tracker.onKey(LinuxKey.LEFTSHIFT, isPressed = false)).isTrue()
        assertThat(tracker.depressed).isEqualTo(0)
    }

    @Test
    fun `combines modifiers and ignores repeats`() {
        val tracker = ModifierTracker()
        tracker.onKey(LinuxKey.LEFTCTRL, isPressed = true)
        tracker.onKey(LinuxKey.LEFTALT, isPressed = true)
        assertThat(tracker.depressed).isEqualTo(ModifierTracker.CONTROL or ModifierTracker.MOD1)
        // A key repeat must not look like a state change.
        assertThat(tracker.onKey(LinuxKey.LEFTCTRL, isPressed = true)).isFalse()
    }

    @Test
    fun `caps lock toggles on press and ignores release`() {
        val tracker = ModifierTracker()
        assertThat(tracker.onKey(LinuxKey.CAPSLOCK, isPressed = true)).isTrue()
        assertThat(tracker.locked).isEqualTo(ModifierTracker.LOCK)
        assertThat(tracker.onKey(LinuxKey.CAPSLOCK, isPressed = false)).isFalse()
        assertThat(tracker.locked).isEqualTo(ModifierTracker.LOCK)
        tracker.onKey(LinuxKey.CAPSLOCK, isPressed = true)
        assertThat(tracker.locked).isEqualTo(0)
    }

    @Test
    fun `non-modifier keys do not change the mask`() {
        val tracker = ModifierTracker()
        assertThat(tracker.onKey(LinuxKey.SPACE, isPressed = true)).isFalse()
        assertThat(tracker.depressed).isEqualTo(0)
    }

    @Test
    fun `reset forgets held modifiers when focus leaves the container`() {
        val tracker = ModifierTracker()
        tracker.onKey(LinuxKey.LEFTSHIFT, isPressed = true)
        tracker.reset()
        assertThat(tracker.depressed).isEqualTo(0)
    }
}
