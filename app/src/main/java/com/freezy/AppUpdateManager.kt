package com.freezy

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.system.network.ui.BuildConfig
import com.system.network.ui.R
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Comprobación global de versión dinámica.
 * El modal SOLO se muestra si la versión instalada es estrictamente inferior a la del servidor.
 * Una vez que la versión coincide o es más reciente, NUNCA vuelve a mostrarse.
 */
object AppUpdateManager {
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    private const val DEFAULT_WHATSAPP_URL = "https://whatsapp.com/channel/0029Vb9K5FJ545usgyohs136"
    private const val DEFAULT_TIKTOK_URL = "https://www.tiktok.com/@freezyt"

    private val checking = AtomicBoolean(false)
    @Volatile private var lastCheckAt = 0L
    @Volatile private var pendingUpdateConfig: JSONObject? = null
    private var activeDialog: AlertDialog? = null
    private var activeActivity = WeakReference<Activity>(null)

    fun showDebugPreview(activity: Activity) {
        if (!BuildConfig.DEBUG) return
        val preview = JSONObject().apply {
            put("versionName", "4.2.0")
            put("changelog", "• Flujo dinámico desde base de datos\n• Modal inteligente por versión\n• Mejoras de rendimiento")
            put("whatsappUrl", DEFAULT_WHATSAPP_URL)
            put("tiktokUrl", DEFAULT_TIKTOK_URL)
        }
        pendingUpdateConfig = preview
        showDialog(activity, preview)
    }

    fun showUpdateModal(activity: Activity, rawMessage: String? = null) {
        val (parsedVersion, parsedChangelog) = parseServerMessage(rawMessage)
        val config = JSONObject().apply {
            put("versionName", parsedVersion)
            put("changelog", parsedChangelog)
            put("whatsappUrl", pendingUpdateConfig?.optString("whatsappUrl") ?: DEFAULT_WHATSAPP_URL)
            put("tiktokUrl", pendingUpdateConfig?.optString("tiktokUrl") ?: DEFAULT_TIKTOK_URL)
        }

        if (isUpdateRequired(config)) {
            activity.runOnUiThread { showDialog(activity, config) }
        }

        // Consultar /api/version para enriquecer enlaces y changelog oficial
        Thread {
            try {
                val endpoint = SecurePrefs.getSecureString(activity, "secure_endpoint")
                    .ifEmpty { NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT) }
                val baseUrl = endpoint.substringBefore("/api").trimEnd('/')
                val conn = WebSecurity.open("$baseUrl/api/version")
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                if (conn.responseCode == 200) {
                    val serverConfig = JSONObject(conn.inputStream.bufferedReader().readText())
                    if (isUpdateRequired(serverConfig)) {
                        val updatedName = serverConfig.optString("versionName", parsedVersion).ifEmpty { parsedVersion }
                        val updatedChangelog = serverConfig.optString("changelog", parsedChangelog).ifEmpty { parsedChangelog }
                        serverConfig.put("versionName", updatedName)
                        serverConfig.put("changelog", updatedChangelog)
                        pendingUpdateConfig = serverConfig
                        activity.runOnUiThread { showDialog(activity, serverConfig) }
                    } else {
                        pendingUpdateConfig = null
                        activity.runOnUiThread { activeDialog?.dismiss() }
                    }
                }
            } catch (_: Throwable) {}
        }.start()
    }

    fun check(activity: Activity, force: Boolean = false) {
        pendingUpdateConfig?.let {
            if (isUpdateRequired(it)) showDialog(activity, it) else pendingUpdateConfig = null
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < CHECK_INTERVAL_MS) return
        if (!checking.compareAndSet(false, true)) return

        Thread {
            try {
                val endpoint = SecurePrefs.getSecureString(activity, "secure_endpoint")
                    .ifEmpty { NativeBridge.getNativeString(NativeBridge.STRING_ENDPOINT) }
                val baseUrl = endpoint.substringBefore("/api").trimEnd('/')
                val conn = WebSecurity.open("$baseUrl/api/version")
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val config = JSONObject(conn.inputStream.bufferedReader().readText())
                    lastCheckAt = System.currentTimeMillis()
                    if (isUpdateRequired(config)) {
                        pendingUpdateConfig = config
                        activity.runOnUiThread { showDialog(activity, config) }
                    } else {
                        pendingUpdateConfig = null
                    }
                }
            } catch (_: Exception) {
                // Failsafe
            } finally {
                checking.set(false)
            }
        }.start()
    }

    /** Compara semánticamente la versión del servidor contra la versión instalada. */
    fun isUpdateRequired(serverConfig: JSONObject): Boolean {
        val latestCode = serverConfig.optInt("versionCode", 0)
        val latestName = serverConfig.optString("versionName", "")

        val currentCode = BuildConfig.VERSION_CODE
        val currentName = BuildConfig.VERSION_NAME.removePrefix("v").substringBefore("-")

        if (latestCode > 0 && currentCode > 0) {
            return latestCode > currentCode
        }

        if (latestName.isEmpty()) return false

        val cleanLatest = latestName.removePrefix("v").substringBefore("-")
        val lParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = currentName.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(lParts.size, cParts.size)

        for (i in 0 until maxLen) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun parseServerMessage(rawMessage: String?): Pair<String, String> {
        if (rawMessage.isNullOrBlank()) {
            val fallbackName = pendingUpdateConfig?.optString("versionName", "nueva") ?: "nueva"
            val fallbackChangelog = pendingUpdateConfig?.optString("changelog", "Hay una nueva versión disponible. Por favor, actualiza desde nuestros canales oficiales.") ?: "Hay una nueva versión disponible. Por favor, actualiza desde nuestros canales oficiales."
            return fallbackName to fallbackChangelog
        }

        val versionRegex = Regex("""v?(\d+\.\d+(\.\d+)?)""")
        val match = versionRegex.find(rawMessage)
        val extractedVersion = match?.groupValues?.get(1) ?: pendingUpdateConfig?.optString("versionName", "nueva") ?: "nueva"

        val lines = rawMessage.lines()
        val filtered = lines.filterNot {
            it.contains("http", ignoreCase = true) ||
            it.contains("mediafire", ignoreCase = true) ||
            it.contains("Acceso Bloqueado", ignoreCase = true) ||
            it.contains("versión es obsoleta", ignoreCase = true) ||
            it.contains("Por favor actualiza", ignoreCase = true) ||
            it.contains("descargándola", ignoreCase = true)
        }.map { line ->
            if (line.trimStart().startsWith("Novedades", ignoreCase = true)) {
                line.substringAfter(":").trim()
            } else line
        }.filter { it.isNotBlank() }

        val changelog = if (filtered.isEmpty()) {
            "Hay una nueva versión disponible. Por favor, actualiza desde nuestros canales oficiales."
        } else {
            filtered.joinToString("\n").trim()
        }

        return extractedVersion to changelog
    }

    private fun showDialog(activity: Activity, config: JSONObject) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!isUpdateRequired(config)) {
            activeDialog?.dismiss()
            return
        }
        if (activeDialog?.isShowing == true && activeActivity.get() === activity) {
            val content = activeDialog?.findViewById<View>(android.R.id.content)
            content?.findViewById<TextView>(R.id.tv_update_title)?.text =
                "Freezy v${config.optString("versionName", "nueva")}"
            content?.findViewById<TextView>(R.id.tv_update_changelog)?.text =
                config.optString("changelog", "Hay una nueva versión disponible.")
            return
        }
        activeDialog?.dismiss()

        val latestName = config.optString("versionName", "nueva")
        val changelog = config.optString("changelog", "Hay una nueva versión disponible.")
        val whatsappUrl = config.optString("whatsappUrl", DEFAULT_WHATSAPP_URL)
            .trim().ifEmpty { DEFAULT_WHATSAPP_URL }
        val tiktokUrl = config.optString("tiktokUrl", DEFAULT_TIKTOK_URL)
            .trim().ifEmpty { DEFAULT_TIKTOK_URL }

        val content = activity.layoutInflater.inflate(R.layout.dialog_update_required, null)
        content.findViewById<TextView>(R.id.tv_update_badge)?.text =
            NativeBridge.getNativeString(NativeBridge.STRING_BADGE_UPDATE_REQUIRED)
        content.findViewById<TextView>(R.id.tv_update_title)?.text = "Freezy v$latestName"
        content.findViewById<TextView>(R.id.tv_update_subtitle)?.text =
            NativeBridge.getNativeString(NativeBridge.STRING_UPDATE_SUBTITLE)
        content.findViewById<TextView>(R.id.tv_update_changelog)?.text = changelog
        content.findViewById<TextView>(R.id.tv_update_hint)?.text =
            NativeBridge.getNativeString(NativeBridge.STRING_UPDATE_HINT)
        content.findViewById<TextView>(R.id.btn_update_whatsapp)?.text =
            NativeBridge.getNativeString(NativeBridge.STRING_BTN_OPEN_WHATSAPP)
        content.findViewById<TextView>(R.id.tv_update_tiktok_title)?.text =
            NativeBridge.getNativeString(NativeBridge.STRING_TIKTOK_TITLE)
        content.findViewById<TextView>(R.id.tv_update_tiktok_subtitle)?.text =
            NativeBridge.getNativeString(NativeBridge.STRING_TIKTOK_SUBTITLE)

        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(false)
            .create()

        content.findViewById<TextView>(R.id.btn_update_whatsapp).setOnClickListener {
            openUrl(activity, whatsappUrl)
        }
        content.findViewById<View>(R.id.btn_update_tiktok).setOnClickListener {
            openUrl(activity, tiktokUrl)
        }

        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activeActivity.clear()
            }
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.82f }
            setLayout((activity.resources.displayMetrics.widthPixels * 0.90f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
        activeDialog = dialog
        activeActivity = WeakReference(activity)
    }

    private fun openUrl(activity: Activity, url: String) {
        val packages = if (url.contains("whatsapp.com", ignoreCase = true)) {
            listOf("com.whatsapp", "com.whatsapp.w4b", null)
        } else {
            listOf(null)
        }
        for (packageName in packages) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    if (packageName != null) setPackage(packageName)
                })
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(activity, "No se pudo abrir el enlace.", Toast.LENGTH_LONG).show()
    }
}
