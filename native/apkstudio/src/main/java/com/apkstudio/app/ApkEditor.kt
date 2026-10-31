package com.apkstudio.app

import android.content.Context
import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

object ApkEditor {
    private const val ID_LABEL = 0x01010001
    private const val ID_ICON = 0x01010002
    private const val ID_ROUND_ICON = 0x0101052c

    /** Changes the app name and/or icon of [src], re-signs it and writes [out]. */
    fun edit(ctx: Context, src: File, newName: String?, icon: ByteArray?, out: File) {
        val unsigned = File(ctx.cacheDir, "edited-unsigned.apk")
        val module = ApkModule.loadApkFile(src)
        val manifest = module.getAndroidManifest()
        val targets = listOfNotNull(manifest.getApplicationElement(), manifest.getMainActivity())

        if (!newName.isNullOrBlank()) {
            for (el in targets) el.getOrCreateAndroidAttribute("label", ID_LABEL).setValueAsString(newName)
        }
        if (icon != null) {
            val path = "res/apkstudio_icon.png"
            val entry = module.getTableBlock().pickOne().getOrCreate("", "drawable", "apkstudio_icon")
            entry.setValueAsString(path)
            module.add(ByteInputSource(icon, path))
            val id = entry.getResourceId()
            for (el in targets) {
                el.getOrCreateAndroidAttribute("icon", ID_ICON).setTypeAndData(ValueType.REFERENCE, id)
                el.searchAttributeByResourceId(ID_ROUND_ICON)?.setTypeAndData(ValueType.REFERENCE, id)
            }
        }
        module.writeApk(unsigned)
        sign(ctx, unsigned, out)
        unsigned.delete()
    }

    private fun sign(ctx: Context, input: File, out: File) {
        val keyBytes = ctx.assets.open("key.pk8").use { it.readBytes() }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = ctx.assets.open("cert.der").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val signer = ApkSigner.SignerConfig.Builder("apkstudio", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(out)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
