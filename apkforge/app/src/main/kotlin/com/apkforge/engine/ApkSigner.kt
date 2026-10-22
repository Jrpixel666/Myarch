package com.apkforge.engine

import android.content.Context
import com.android.apksig.ApkSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Signs a rebuilt APK with a bundled throwaway debug key, using Google's own
 * `apksig` (the exact library `apksigner` wraps) so the output carries valid v1
 * + v2 + v3 signatures and installs on a normal device.
 *
 * The key is a self-signed debug certificate shipped in assets as a PKCS12
 * store (Android's keystore does not support the JKS format). It exists only so
 * a rebuilt APK is installable for testing — it is not a release key, and it is
 * intentionally public.
 */
class ApkSigner(private val context: Context) {

    private companion object {
        const val KEYSTORE_ASSET = "debug.p12"
        const val ALIAS = "apkforge"
        val PASSWORD = "apkforge".toCharArray()
    }

    suspend fun sign(
        input: File,
        output: File,
        log: ApkToolEngine.LogSink,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (output.exists()) output.delete()
            output.parentFile?.mkdirs()

            val keyStore = KeyStore.getInstance("PKCS12")
            context.assets.open(KEYSTORE_ASSET).use { keyStore.load(it, PASSWORD) }

            val privateKey = keyStore.getKey(ALIAS, PASSWORD) as PrivateKey
            val chain = keyStore.getCertificateChain(ALIAS)
                .map { it as X509Certificate }

            val signerConfig = ApkSigner.SignerConfig.Builder(ALIAS, privateKey, chain).build()
            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()

            log.line("apksig: signing ${input.name} with debug key")
            signer.sign()
            log.line("apksig: signed → ${output.absolutePath}")
            output
        }.onFailure {
            log.line("ERROR: signing failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
