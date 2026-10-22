package com.apkforge

import com.apkforge.ui.humanSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-JVM checks for the byte-size formatter used across the UI. Kept
 * Android-free so it runs under `./gradlew test` without an emulator.
 */
class HumanSizeTest {

    @Test
    fun zeroAndNegative() {
        assertThat(humanSize(0)).isEqualTo("0 B")
        assertThat(humanSize(-5)).isEqualTo("0 B")
    }

    @Test
    fun bytesStayBytes() {
        assertThat(humanSize(512)).isEqualTo("512 B")
        assertThat(humanSize(1023)).isEqualTo("1023 B")
    }

    @Test
    fun scalesUpThroughUnits() {
        assertThat(humanSize(1024)).isEqualTo("1.0 KB")
        assertThat(humanSize(1024L * 1024)).isEqualTo("1.0 MB")
        assertThat(humanSize(5L * 1024 * 1024 * 1024)).isEqualTo("5.0 GB")
    }
}
