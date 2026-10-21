plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.myarch.wayland"
    compileSdk = (rootProject.extra["myarchCompileSdk"] as Int)

    defaultConfig {
        minSdk = (rootProject.extra["myarchMinSdk"] as Int)
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    // The compositor is a library consumed by :app, so an explicit API surface
    // keeps accidental leaks out of it. `explicitApiWarning()` applies to
    // production sources only, which is what we want -- test classes have no
    // API to speak of.
    explicitApiWarning()

    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

/**
 * Regenerates the protocol tables from the XML in tools/protocols.
 *
 * The generated file is checked in so a normal build needs no Python; run this
 * task after updating a protocol definition.
 */
tasks.register<Exec>("generateProtocols") {
    group = "myarch"
    description = "Regenerate Protocols.kt from the Wayland protocol XML"
    val protocolDir = rootProject.layout.projectDirectory.dir("tools/protocols")
    val output = layout.projectDirectory
        .file("src/main/kotlin/dev/myarch/wayland/protocol/Protocols.kt")
    inputs.dir(protocolDir)
    outputs.file(output)
    commandLine(
        "python3",
        rootProject.file("tools/wlgen/wlgen.py").absolutePath,
        "--out",
        output.asFile.absolutePath,
    )
    argumentProviders.add {
        protocolDir.asFile.listFiles()
            ?.filter { it.extension == "xml" }
            ?.sortedBy { it.name }
            ?.map { it.absolutePath }
            ?: emptyList()
    }
}
