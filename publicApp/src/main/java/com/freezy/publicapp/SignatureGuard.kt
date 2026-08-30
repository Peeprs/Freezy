package com.freezy.publicapp

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Process
import java.security.MessageDigest

object SignatureGuard {
    fun verify(activity: Activity) {
        if (BuildConfig.DEBUG) return
        val isOfficial = runCatching {
            val info =
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val certificate = info.signingInfo?.apkContentsSigners?.singleOrNull()
                ?: return@runCatching false
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.toByteArray())
                .joinToString("") { "%02X".format(it) }
            N.c(digest)
        }.getOrDefault(false)

        // En release cualquier error de lectura o firma distinta se considera
        // manipulación. Nunca se continúa en modo permisivo.
        if (!isOfficial) {
            activity.finishAffinity()
            Process.killProcess(Process.myPid())
        }
    }
}
