package com.apkforge

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Pins the exact apktool-lib surface that [com.apkforge.engine.ApkToolEngine]
 * reaches through reflection. apktool reshuffles this API across releases, so
 * if the pinned version is bumped and something moves, this fails with a clear
 * pointer instead of a runtime ClassNotFound / NoSuchMethod on-device.
 *
 * Verified end-to-end (a real decode) against apktool-lib 2.9.3; this keeps the
 * contract checked without decoding an APK on every build.
 */
class ApkToolApiContractTest {

    private val configType = Class.forName("brut.androlib.Config")
    private val decoderType = Class.forName("brut.androlib.ApkDecoder")
    private val builderType = Class.forName("brut.androlib.ApkBuilder")
    private val extFileType = Class.forName("brut.directory.ExtFile")

    @Test
    fun configFactoryAndSettersExist() {
        assertThat(configType.getMethod("getDefaultConfig")).isNotNull()
        assertThat(configType.getMethod("setDecodeSources", Short::class.javaPrimitiveType)).isNotNull()
        assertThat(configType.getMethod("setDecodeResources", Short::class.javaPrimitiveType)).isNotNull()
    }

    @Test
    fun configFieldsTheEngineWritesExist() {
        // setConfig() falls back to these public fields when no setter exists.
        assertThat(configType.getField("frameworkDirectory").type).isEqualTo(String::class.java)
        assertThat(configType.getField("forceDelete").type).isEqualTo(Boolean::class.javaPrimitiveType)
        assertThat(configType.getField("aaptPath").type).isEqualTo(String::class.java)
        assertThat(configType.getField("aaptVersion").type).isEqualTo(Int::class.javaPrimitiveType)
        assertThat(configType.getField("useAapt2").type).isEqualTo(Boolean::class.javaPrimitiveType)
    }

    @Test
    fun decodeConstantsExist() {
        assertThat(configType.getField("DECODE_SOURCES_SMALI").getShort(null)).isNotNull()
        assertThat(configType.getField("DECODE_RESOURCES_NONE").getShort(null)).isNotNull()
    }

    @Test
    fun extFileIsAFileSoTypeMatchingWorks() {
        // The engine passes an ExtFile where ctors may declare File; this IS-A
        // relationship is what makes the type-matched construction valid.
        assertThat(File::class.java.isAssignableFrom(extFileType)).isTrue()
        assertThat(extFileType.getConstructor(File::class.java)).isNotNull()
    }

    @Test
    fun decoderHasConfigPlusApkConstructorAndDecode() {
        val ctor = decoderType.constructors.firstOrNull { c ->
            c.parameterTypes.size == 2 &&
                c.parameterTypes.any { it.isAssignableFrom(configType) } &&
                c.parameterTypes.any { it.isAssignableFrom(extFileType) }
        }
        assertThat(ctor).isNotNull()
        assertThat(decoderType.getMethod("decode", File::class.java)).isNotNull()
    }

    @Test
    fun builderHasConfigPlusApkConstructorAndBuild() {
        val ctor = builderType.constructors.firstOrNull { c ->
            c.parameterTypes.size == 2 &&
                c.parameterTypes.any { it.isAssignableFrom(configType) } &&
                c.parameterTypes.any { it.isAssignableFrom(extFileType) }
        }
        assertThat(ctor).isNotNull()
        assertThat(builderType.getMethod("build", File::class.java)).isNotNull()
    }

    @Test
    fun xmlpullWrapperClassesArePresent() {
        // The bug that bit us: apktool's resource decoder needs the wrapper
        // package (from xpp3). Core org.xmlpull is on the Android bootclasspath,
        // but the wrapper classes must be in the APK.
        assertThat(Class.forName("org.xmlpull.v1.wrapper.XmlSerializerWrapper")).isNotNull()
    }
}
