package dev.myarch.runtime

/**
 * The CPU architectures Myarch can install a container for.
 *
 * Android and Arch disagree about naming, and the rootfs images are published
 * under the Arch name, so the mapping has to be explicit.
 */
public enum class TargetArch(
    /** The Android ABI string, as reported by `Build.SUPPORTED_ABIS`. */
    public val androidAbi: String,
    /** The Arch Linux (ARM) architecture name used in image file names. */
    public val archName: String,
    /** `uname -m` inside the container. */
    public val machine: String,
) {
    ARM64("arm64-v8a", "aarch64", "aarch64"),
    ARM32("armeabi-v7a", "armv7h", "armv7l"),
    X86_64("x86_64", "x86_64", "x86_64"),
    ;

    public companion object {
        /**
         * Picks the architecture for a device.
         *
         * @param supportedAbis `Build.SUPPORTED_ABIS`, most preferred first
         */
        public fun forAbis(supportedAbis: List<String>): TargetArch? =
            supportedAbis.firstNotNullOfOrNull { abi -> entries.firstOrNull { it.androidAbi == abi } }

        public fun fromArchName(name: String): TargetArch? = entries.firstOrNull { it.archName == name }
    }
}
