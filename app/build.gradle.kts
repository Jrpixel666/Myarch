import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.myarch.app"
    compileSdk = (rootProject.extra["myarchCompileSdk"] as Int)

    defaultConfig {
        applicationId = "dev.myarch.app"
        minSdk = (rootProject.extra["myarchMinSdk"] as Int)
        targetSdk = (rootProject.extra["myarchTargetSdk"] as Int)
        versionCode = (rootProject.extra["myarchVersionCode"] as Int)
        versionName = rootProject.extra["myarchVersionName"] as String

        // The container's rootfs is hundreds of megabytes of small files; the
        // APK itself only carries proot and the guest-side scripts.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // A checked-in debug key keeps `assembleRelease` usable for sideloading
        // without asking every contributor to generate one. Release builds in
        // CI are signed with the repository's own key when it is configured.
        create("sideload") {
            val keystorePath = providers.gradleProperty("myarch.keystore").orNull
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("myarch.keystorePassword").orNull
                keyAlias = providers.gradleProperty("myarch.keyAlias").orNull
                keyPassword = providers.gradleProperty("myarch.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // R8 is off for now: the compositor dispatches protocol events by
            // name through generated tables, and shrinking without a careful
            // keep list is a good way to break it silently at runtime.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val keystore = providers.gradleProperty("myarch.keystore").orNull
            signingConfig = if (keystore != null) {
                signingConfigs.getByName("sideload")
            } else {
                null
            }
        }
    }

    // Executables shipped as lib*.so must be unpacked to the native library
    // directory; that directory is the only place a non-rooted app may execute
    // from. See docs/ANDROID_EXEC_CONSTRAINTS.md.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
            jniLibs.srcDirs("src/main/jniLibs")
        }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Copies the container's guest-side files into the APK's assets.
 *
 * `container/` is the single source of truth for the scripts, the Quickshell
 * configuration and the fallback keymap, so they can be read and edited as
 * normal files in the repository rather than being buried under
 * `src/main/assets`. Documentation in that directory is left out: it is for
 * people reading the repository, not for the device.
 */
abstract class PackageContainerAssets @Inject constructor(
    private val fs: FileSystemOperations,
) : DefaultTask() {

    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyAssets() {
        fs.sync {
            from(source) {
                include("scripts/**", "quickshell/**", "keymap/**")
                exclude("**/*.md")
            }
            into(outputDirectory.dir("container"))
        }
    }
}

val packageContainerAssets by tasks.registering(PackageContainerAssets::class) {
    group = "myarch"
    description = "Bundle container/ scripts, Quickshell config and the fallback keymap into assets"
    source.set(rootProject.layout.projectDirectory.dir("container"))
    outputDirectory.set(layout.buildDirectory.dir("generated/myarchAssets"))
}

// AGP's own API for generated sources, which wires the task dependency into
// every consumer -- packaging, lint and the merge tasks alike. Adding the
// directory to `sourceSets` instead leaves lint without a dependency on it.
extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            packageContainerAssets,
            PackageContainerAssets::outputDirectory,
        )
    }
}

dependencies {
    implementation(project(":wayland"))
    implementation(project(":runtime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
