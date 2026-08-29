package com.freezy

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Comprueba la arquitectura real del juego antes de habilitar funciones avanzadas. */
object GameCompatibility {
    data class Report(
        val supportsAdvancedFeatures: Boolean,
        val message: String
    )

    private data class CachedReport(val lastUpdateTime: Long, val report: Report)
    private val cache = ConcurrentHashMap<String, CachedReport>()

    @JvmStatic
    fun inspect(context: Context, targetPackage: String): Report {
        val maxPackage = NativeBridge.getNativeString(NativeBridge.S98)
        if (targetPackage == maxPackage) {
            return Report(
                false,
                "FF MAX/64 bits: solo Fake Lag."
            )
        }

        val appInfo = try {
            context.packageManager.getApplicationInfo(targetPackage, 0)
        } catch (_: Exception) {
            return Report(
                false,
                "No se pudo verificar el juego. Las opciones avanzadas requieren Free Fire 32 bits."
            )
        }
        val packageInfo = try {
            context.packageManager.getPackageInfo(targetPackage, 0)
        } catch (_: Exception) {
            null
        }
        val lastUpdate = packageInfo?.lastUpdateTime ?: 0L
        cache[targetPackage]?.takeIf { it.lastUpdateTime == lastUpdate }?.let { return it.report }

        val nativeDir = appInfo.nativeLibraryDir.orEmpty().lowercase()
        val report = when {
            nativeDir.contains("arm64") || nativeDir.contains("x86_64") -> unsupported64()
            nativeDir.contains("armeabi") || nativeDir.endsWith("/arm") ||
                (nativeDir.contains("x86") && !nativeDir.contains("x86_64")) -> supported32()
            else -> inspectApkLibraries(
                listOfNotNull(appInfo.sourceDir, *appInfo.splitSourceDirs.orEmpty())
            )
        }
        cache[targetPackage] = CachedReport(lastUpdate, report)
        return report
    }

    private fun inspectApkLibraries(apkPaths: List<String>): Report {
        var has32 = false
        var has64 = false
        apkPaths.distinct().forEach { path ->
            val apk = File(path)
            if (!apk.isFile) return@forEach
            try {
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name.lowercase()
                        if (!name.startsWith("lib/")) continue
                        when {
                            name.startsWith("lib/arm64-v8a/") ||
                                name.startsWith("lib/x86_64/") -> has64 = true
                            name.startsWith("lib/armeabi-v7a/") ||
                                name.startsWith("lib/armeabi/") ||
                                name.startsWith("lib/x86/") -> has32 = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // En Android de 64 bits, un APK que ofrece ABI de 64 bits se ejecutará
        // con esa ABI aunque también incluya bibliotecas de 32 bits.
        return when {
            has64 && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() -> unsupported64()
            has32 -> supported32()
            else -> Report(
                false,
                "Instala la versión 32 bits solo Fake Lag."
            )
        }
    }

    private fun supported32() = Report(
        true,
        "FF 32 bits compatible con las opciones avanzadas."
    )

    private fun unsupported64() = Report(
        false,
        "FF 64 bits: solo Fake Lag."
    )
}
