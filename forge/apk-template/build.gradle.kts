// Pinned to the same Android Gradle Plugin that the Myarch project uses, so a
// machine that can build Myarch can build these generated apps with no extra
// toolchain. Gradle itself comes from the wrapper (8.11.1), copied in by the
// builder at build time.
plugins {
    id("com.android.application") version "8.7.3" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
