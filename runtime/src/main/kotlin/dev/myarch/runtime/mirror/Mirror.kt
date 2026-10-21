package dev.myarch.runtime.mirror

import dev.myarch.runtime.TargetArch

/**
 * A download source for rootfs images and pacman packages.
 *
 * @param id stable key used to remember health and user preference
 * @param baseUrl no trailing slash, e.g. `https://fl.us.mirror.archlinuxarm.org`
 * @param verified whether this URL was reachable when the list was compiled;
 *   unverified mirrors are still offered because reachability depends on where
 *   the phone is, but they sort below the verified ones
 */
public data class Mirror(
    val id: String,
    val label: String,
    val baseUrl: String,
    val region: String,
    val verified: Boolean = false,
    val note: String = "",
) {
    /** URL of a rootfs image for [arch] on this mirror. */
    public fun rootfsUrl(arch: TargetArch): String =
        "$baseUrl/os/ArchLinuxARM-${imageName(arch)}-latest.tar.gz"

    /** The `Server =` line this mirror contributes to pacman's mirrorlist. */
    public fun pacmanServerLine(): String = "Server = $baseUrl/\$arch/\$repo"

    private fun imageName(arch: TargetArch): String = when (arch) {
        // Arch Linux ARM publishes the 32 bit image as "armv7", not "armv7h".
        TargetArch.ARM32 -> "armv7"
        TargetArch.ARM64 -> "aarch64"
        TargetArch.X86_64 -> "x86_64"
    }
}

/**
 * The mirrors Myarch ships with.
 *
 * Arch Linux ARM's own front door (`os.archlinuxarm.org`) is a redirector: it
 * sends clients to whichever mirror is close to them, which is usually the best
 * choice and is therefore first. The rest are listed so the installer can fall
 * back -- and so a user on a network that blocks one of them can pick another
 * by hand.
 *
 * The `verified` flag records which hosts answered a range request when this
 * list was last reviewed (see docs/MIRRORS.md for how to re-check).
 */
public object DefaultMirrors {

    public val rootfs: List<Mirror> = listOf(
        Mirror(
            id = "alarm-geo",
            label = "Arch Linux ARM (automatic)",
            baseUrl = "http://os.archlinuxarm.org",
            region = "Global",
            verified = true,
            note = "Redirects to the mirror closest to you. Try this first.",
        ),
        Mirror(
            id = "alarm-fl-us",
            label = "Florida, United States",
            baseUrl = "https://fl.us.mirror.archlinuxarm.org",
            region = "North America",
            verified = true,
        ),
        Mirror(
            id = "alarm-ca-us",
            label = "California, United States",
            baseUrl = "https://ca.us.mirror.archlinuxarm.org",
            region = "North America",
            verified = true,
        ),
        Mirror(
            id = "alarm-de3",
            label = "Germany (de3)",
            baseUrl = "https://de3.mirror.archlinuxarm.org",
            region = "Europe",
            verified = true,
        ),
        Mirror(
            id = "alarm-tx-us",
            label = "Texas, United States",
            baseUrl = "http://tx.us.mirror.archlinuxarm.org",
            region = "North America",
        ),
        Mirror(
            id = "alarm-il-us",
            label = "Illinois, United States",
            baseUrl = "http://il.us.mirror.archlinuxarm.org",
            region = "North America",
        ),
        Mirror(
            id = "alarm-sg",
            label = "Singapore",
            baseUrl = "http://sg.mirror.archlinuxarm.org",
            region = "Asia",
        ),
        Mirror(
            id = "alarm-au",
            label = "Australia",
            baseUrl = "http://au.mirror.archlinuxarm.org",
            region = "Oceania",
        ),
        Mirror(
            id = "alarm-jp",
            label = "Japan",
            baseUrl = "http://jp.mirror.archlinuxarm.org",
            region = "Asia",
        ),
    )

    /** Mirrors written into the container's `/etc/pacman.d/mirrorlist`. */
    public val pacman: List<Mirror> = rootfs

    public fun byId(id: String): Mirror? = rootfs.firstOrNull { it.id == id }

    /**
     * Builds a mirror from a URL the user typed.
     *
     * Trailing slashes and a trailing `/os` are both tolerated, because those
     * are the two shapes people paste.
     */
    public fun custom(url: String): Mirror {
        val trimmed = url.trim().trimEnd('/').removeSuffix("/os")
        return Mirror(
            id = "custom",
            label = "Custom mirror",
            baseUrl = trimmed,
            region = "Custom",
            note = "Entered by hand",
        )
    }
}
