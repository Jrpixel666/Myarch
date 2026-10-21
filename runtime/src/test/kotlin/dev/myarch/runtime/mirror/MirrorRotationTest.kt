package dev.myarch.runtime.mirror

import com.google.common.truth.Truth.assertThat
import dev.myarch.runtime.TargetArch
import org.junit.Test

/** The mirror switcher: ordering, probing and fallback. */
class MirrorRotationTest {

    private fun mirror(id: String, verified: Boolean = false) =
        Mirror(id = id, label = id, baseUrl = "https://$id.example", region = "Test", verified = verified)

    @Test
    fun `tries candidates in the order given`() {
        val rotation = MirrorRotation(listOf(mirror("a"), mirror("b")))
        assertThat(rotation.current?.id).isEqualTo("a")
        assertThat(rotation.pending().map { it.id }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `a pinned mirror goes first and is not duplicated`() {
        val pinned = mirror("b")
        val rotation = MirrorRotation(listOf(mirror("a"), mirror("b"), mirror("c")), pinned)
        assertThat(rotation.pending().map { it.id }).containsExactly("b", "a", "c").inOrder()
    }

    @Test
    fun `a failure moves to the next mirror`() {
        val rotation = MirrorRotation(listOf(mirror("a"), mirror("b")))
        val next = rotation.onFailure(mirror("a"), "connection reset")
        assertThat(next?.id).isEqualTo("b")
        assertThat(rotation.failureReasons()).containsEntry("a", "connection reset")
    }

    @Test
    fun `the last failure leaves nothing to try`() {
        val rotation = MirrorRotation(listOf(mirror("a")))
        assertThat(rotation.onFailure(mirror("a"), "404")).isNull()
        assertThat(rotation.current).isNull()
    }

    @Test
    fun `a pinned mirror is moved to the back rather than dropped`() {
        // Overriding an explicit choice silently is how someone ends up
        // downloading 700 MB from the other side of the planet.
        val pinned = mirror("pinned")
        val rotation = MirrorRotation(listOf(mirror("other"), pinned), pinned)
        val next = rotation.onFailure(pinned, "timeout")
        assertThat(next?.id).isEqualTo("other")
        assertThat(rotation.pending().map { it.id }).contains("pinned")
    }

    @Test
    fun `probing sorts by latency and retires unreachable mirrors`() {
        val rotation = MirrorRotation(listOf(mirror("slow"), mirror("dead"), mirror("fast")))
        rotation.applyProbeResults(
            mapOf(
                "slow" to ProbeResult.Reachable(1200, sizeBytes = 100),
                "dead" to ProbeResult.Unreachable("no route to host"),
                "fast" to ProbeResult.Reachable(90, sizeBytes = 100),
            ),
        )
        assertThat(rotation.pending().map { it.id }).containsExactly("fast", "slow").inOrder()
        assertThat(rotation.failureReasons()).containsKey("dead")
    }

    @Test
    fun `verified mirrors sort ahead of unprobed ones`() {
        val rotation = MirrorRotation(listOf(mirror("unverified"), mirror("verified", verified = true)))
        rotation.applyProbeResults(emptyMap())
        assertThat(rotation.pending().first().id).isEqualTo("verified")
    }

    @Test
    fun `probeAll uses the probe for every pending mirror`() {
        val rotation = MirrorRotation(listOf(mirror("a"), mirror("b")))
        val probed = ArrayList<String>()
        rotation.probeAll(
            probe = { url ->
                probed.add(url)
                if (url.contains("a.")) ProbeResult.Unreachable("nope") else ProbeResult.Reachable(10, null)
            },
            urlFor = { it.rootfsUrl(TargetArch.ARM64) },
        )
        assertThat(probed).hasSize(2)
        assertThat(rotation.pending().map { it.id }).containsExactly("b")
    }

    @Test
    fun `rootfs urls follow the Arch Linux ARM layout`() {
        val mirror = DefaultMirrors.byId("alarm-fl-us")!!
        assertThat(mirror.rootfsUrl(TargetArch.ARM64))
            .isEqualTo("https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz")
        // The 32 bit image is published as "armv7", not the "armv7h" package
        // architecture.
        assertThat(mirror.rootfsUrl(TargetArch.ARM32)).endsWith("ArchLinuxARM-armv7-latest.tar.gz")
    }

    @Test
    fun `pacman server lines keep pacman's own variables intact`() {
        val mirror = DefaultMirrors.byId("alarm-de3")!!
        assertThat(mirror.pacmanServerLine())
            .isEqualTo("Server = https://de3.mirror.archlinuxarm.org/\$arch/\$repo")
    }

    @Test
    fun `a custom url tolerates the shapes people paste`() {
        assertThat(DefaultMirrors.custom("https://example.org/os/").baseUrl).isEqualTo("https://example.org")
        assertThat(DefaultMirrors.custom("  https://example.org  ").baseUrl).isEqualTo("https://example.org")
        assertThat(DefaultMirrors.custom("https://example.org/os").baseUrl).isEqualTo("https://example.org")
    }

    @Test
    fun `every bundled mirror has a usable base url`() {
        for (mirror in DefaultMirrors.rootfs) {
            assertThat(mirror.baseUrl).startsWith("http")
            assertThat(mirror.baseUrl).doesNotContain(" ")
            assertThat(mirror.baseUrl.endsWith("/")).isFalse()
        }
        assertThat(DefaultMirrors.rootfs.map { it.id }).containsNoDuplicates()
    }
}
