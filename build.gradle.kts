plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * Shared accessors for the knobs declared in gradle.properties so every module
 * agrees on the SDK levels and the version stamped into the APK.
 */
val myarchCompileSdk: Int by extra((providers.gradleProperty("myarch.compileSdk").get()).toInt())
val myarchTargetSdk: Int by extra((providers.gradleProperty("myarch.targetSdk").get()).toInt())
val myarchMinSdk: Int by extra((providers.gradleProperty("myarch.minSdk").get()).toInt())
val myarchVersionCode: Int by extra((providers.gradleProperty("myarch.versionCode").get()).toInt())
val myarchVersionName: String by extra(providers.gradleProperty("myarch.versionName").get())

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
