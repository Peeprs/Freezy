package com.freezy

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.security.MessageDigest

/**
 * SignatureGuard — Verifica que la APK instalada esté firmada con el
 * certificado oficial de release.
 *
 * Cualquier re-firma (apktool / MT Manager + keystore ajeno) cambia el
 * digest SHA-256 del certificado y provoca el cierre inmediato de la app.
 * No opera en builds de debug (firmadas con el debug keystore de Gradle).
 *
 * Para regenerar el digest tras rotar el keystore de release:
 *   keytool -list -v -keystore keystores/freezy-release.jks -alias freezy
 *   (campo "SHA256:" sin los ':').
 */
object SignatureGuard {

    // SHA-256 del certificado de firma oficial (release):
    // CN=Freezy, keystores/freezy-release.jks
    private const val RELEASE_SIGNER_SHA256 =
        "76A8003C6D98EDB403D651B27612031605F4C5EE4AEF131F6FC230DA7E48CB66"

    /** Comprueba la firma y, si la APK fue re-firmada, cierra el proceso. */
    fun verify(activity: Activity) {
        if (com.system.network.ui.BuildConfig.DEBUG) return

        val actual = signingCertificateSha256(activity) ?: return
        if (!actual.equals(RELEASE_SIGNER_SHA256, ignoreCase = true)) {
            android.util.Log.e("SignatureGuard", "APK re-firmada. Digest real: $actual")
            Process.killProcess(Process.myPid())
            activity.finishAffinity()
        }
    }

    private fun signingCertificateSha256(context: Context): String? = try {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
        digest.joinToString("") { "%02X".format(it) }
    } catch (e: Exception) {
        null
    }
}
