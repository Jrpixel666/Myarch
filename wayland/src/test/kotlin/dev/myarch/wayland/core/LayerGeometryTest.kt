package dev.myarch.wayland.core

import com.google.common.truth.Truth.assertThat
import dev.myarch.wayland.protocol.ZwlrLayerSurfaceV1.Anchor
import org.junit.Test

/**
 * Panel placement, which decides where an Omarchy bar or widget lands on the
 * home screen.
 */
class LayerGeometryTest {

    private val screenWidth = 540
    private val screenHeight = 1200

    @Test
    fun `a bar anchored left and right stretches to the screen width`() {
        val geometry = LayerGeometry(
            anchor = Anchor.TOP or Anchor.LEFT or Anchor.RIGHT,
            requestedWidth = 0,
            requestedHeight = 38,
        )
        val (width, height) = geometry.resolveSize(screenWidth, screenHeight)
        assertThat(width).isEqualTo(screenWidth)
        assertThat(height).isEqualTo(38)
        assertThat(geometry.resolvePosition(width, height, screenWidth, screenHeight)).isEqualTo(0 to 0)
    }

    @Test
    fun `margins shrink a stretched surface and offset it`() {
        val geometry = LayerGeometry(
            anchor = Anchor.TOP or Anchor.LEFT or Anchor.RIGHT,
            requestedWidth = 0,
            requestedHeight = 40,
            marginTop = 12,
            marginLeft = 8,
            marginRight = 8,
        )
        val (width, _) = geometry.resolveSize(screenWidth, screenHeight)
        assertThat(width).isEqualTo(screenWidth - 16)
        val (x, y) = geometry.resolvePosition(width, 40, screenWidth, screenHeight)
        assertThat(x).isEqualTo(8)
        assertThat(y).isEqualTo(12)
    }

    @Test
    fun `a bottom anchored panel sits against the bottom edge`() {
        val geometry = LayerGeometry(
            anchor = Anchor.BOTTOM or Anchor.LEFT or Anchor.RIGHT,
            requestedWidth = 0,
            requestedHeight = 60,
            marginBottom = 10,
        )
        val (width, height) = geometry.resolveSize(screenWidth, screenHeight)
        val (_, y) = geometry.resolvePosition(width, height, screenWidth, screenHeight)
        assertThat(y).isEqualTo(screenHeight - 60 - 10)
    }

    @Test
    fun `an unanchored widget is centred`() {
        val geometry = LayerGeometry(anchor = 0, requestedWidth = 200, requestedHeight = 100)
        val (width, height) = geometry.resolveSize(screenWidth, screenHeight)
        val (x, y) = geometry.resolvePosition(width, height, screenWidth, screenHeight)
        assertThat(x).isEqualTo((screenWidth - 200) / 2)
        assertThat(y).isEqualTo((screenHeight - 100) / 2)
    }

    @Test
    fun `a surface anchored to one side keeps its requested width`() {
        val geometry = LayerGeometry(
            anchor = Anchor.RIGHT or Anchor.TOP,
            requestedWidth = 180,
            requestedHeight = 90,
        )
        val (width, height) = geometry.resolveSize(screenWidth, screenHeight)
        assertThat(width).isEqualTo(180)
        val (x, _) = geometry.resolvePosition(width, height, screenWidth, screenHeight)
        assertThat(x).isEqualTo(screenWidth - 180)
    }

    @Test
    fun `anchor helpers read the bitmask`() {
        val geometry = LayerGeometry(Anchor.TOP or Anchor.RIGHT, 0, 0)
        assertThat(geometry.anchorsTop).isTrue()
        assertThat(geometry.anchorsRight).isTrue()
        assertThat(geometry.anchorsBottom).isFalse()
        assertThat(geometry.anchorsLeft).isFalse()
    }
}
