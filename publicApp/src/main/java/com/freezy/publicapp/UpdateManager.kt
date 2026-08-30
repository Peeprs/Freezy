package com.freezy.publicapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Gestor dinámico de actualizaciones.
 * El modal SOLO se muestra si la versión instalada es inferior a la del servidor.
 * Una vez que la versión coincide o es más reciente, NUNCA vuelve a mostrarse.
 */
object UpdateManager {
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    private val checking = AtomicBoolean(false)
    @Volatile private var lastCheckAt = 0L
    @Volatile private var pendingConfig: JSONObject? = null
    private var activeDialog: AlertDialog? = null
    private var activeActivity = WeakReference<Activity>(null)

    fun check(activity: Activity, force: Boolean = false) {
        pendingConfig?.let {
            if (isUpdateRequired(it)) showDialog(activity, it) else pendingConfig = null
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < CHECK_INTERVAL_MS) return
        if (!checking.compareAndSet(false, true)) return

        Thread {
            try {
                val endpoint = N.a(N.ENDPOINT)
                val baseUrl = endpoint.substringBefore("/api").trimEnd('/')
                val connection = WebSecurity.open("$baseUrl/api/version")
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode == 200) {
                    val config = JSONObject(connection.inputStream.bufferedReader().readText())
                    lastCheckAt = System.currentTimeMillis()
                    if (isUpdateRequired(config)) {
                        pendingConfig = config
                        activity.runOnUiThread { showDialog(activity, config) }
                    } else {
                        pendingConfig = null
                    }
                }
            } catch (_: Exception) {
                // Failsafe de red
            } finally {
                checking.set(false)
            }
        }.start()
    }

    fun showDebugPreview(activity: Activity) {
        if (!BuildConfig.DEBUG) return
        val config = JSONObject().apply {
            put("versionName", "4.2.0")
            put("changelog", "• Flujo dinámico desde base de datos\n• Modal inteligente por versión\n• Mejoras de rendimiento")
            put("whatsappUrl", N.a(N.WHATSAPP_URL))
            put("tiktokUrl", N.a(N.TIKTOK_URL))
        }
        pendingConfig = config
        showDialog(activity, config)
    }

    fun showUpdateModal(activity: Activity, rawMessage: String? = null) {
        val (parsedVersion, parsedChangelog) = parseServerMessage(rawMessage)
        val config = JSONObject().apply {
            put("versionName", parsedVersion)
            put("changelog", parsedChangelog)
            put("whatsappUrl", pendingConfig?.optString("whatsappUrl") ?: N.a(N.WHATSAPP_URL))
            put("tiktokUrl", pendingConfig?.optString("tiktokUrl") ?: N.a(N.TIKTOK_URL))
        }

        // Solo mostrar si efectivamente la versión del servidor es más reciente
        if (isUpdateRequired(config)) {
            activity.runOnUiThread { showDialog(activity, config) }
        }

        Thread {
            try {
                val endpoint = N.a(N.ENDPOINT)
                val baseUrl = endpoint.substringBefore("/api").trimEnd('/')
                val connection = WebSecurity.open("$baseUrl/api/version")
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                if (connection.responseCode == 200) {
                    val serverConfig = JSONObject(connection.inputStream.bufferedReader().readText())
                    if (isUpdateRequired(serverConfig)) {
                        val updatedName = serverConfig.optString("versionName", parsedVersion).ifEmpty { parsedVersion }
                        val updatedChangelog = serverConfig.optString("changelog", parsedChangelog).ifEmpty { parsedChangelog }
                        serverConfig.put("versionName", updatedName)
                        serverConfig.put("changelog", updatedChangelog)
                        pendingConfig = serverConfig
                        activity.runOnUiThread { showDialog(activity, serverConfig) }
                    } else {
                        pendingConfig = null
                        activity.runOnUiThread { activeDialog?.dismiss() }
                    }
                }
            } catch (_: Throwable) {}
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
            val fallbackName = pendingConfig?.optString("versionName", "nueva") ?: "nueva"
            val fallbackChangelog = pendingConfig?.optString("changelog", "Hay una nueva versión disponible. Por favor, actualiza desde nuestros canales oficiales.") ?: "Hay una nueva versión disponible. Por favor, actualiza desde nuestros canales oficiales."
            return fallbackName to fallbackChangelog
        }

        val versionRegex = Regex("""v?(\d+\.\d+(\.\d+)?)""")
        val match = versionRegex.find(rawMessage)
        val extractedVersion = match?.groupValues?.get(1) ?: pendingConfig?.optString("versionName", "nueva") ?: "nueva"

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

        val content = activity.layoutInflater.inflate(R.layout.dialog_update_required, null)
        content.findViewById<TextView>(R.id.tv_update_badge)?.text = N.a(N.BADGE_UPDATE)
        content.findViewById<TextView>(R.id.tv_update_title)?.text =
            "Freezy v${config.optString("versionName", "nueva")}"
        content.findViewById<TextView>(R.id.tv_update_subtitle)?.text = N.a(N.SUBTITLE_UPDATE)
        content.findViewById<TextView>(R.id.tv_update_changelog)?.text =
            config.optString("changelog", "Hay una nueva versión disponible.")
        content.findViewById<TextView>(R.id.tv_update_hint)?.text = N.a(N.HINT_UPDATE)
        content.findViewById<TextView>(R.id.btn_update_whatsapp)?.text = N.a(N.BTN_UPDATE_WA)
        content.findViewById<TextView>(R.id.tv_update_tiktok_title)?.text = N.a(N.TITLE_UPDATE_TT)
        content.findViewById<TextView>(R.id.tv_update_tiktok_subtitle)?.text = N.a(N.SUBTITLE_UPDATE_TT)

        val dialog = AlertDialog.Builder(activity)
            .setView(content)
            .setCancelable(false)
            .create()
        content.findViewById<TextView>(R.id.btn_update_whatsapp).setOnClickListener {
            openUrl(activity, config.optString("whatsappUrl", N.a(N.WHATSAPP_URL)))
        }
        content.findViewById<View>(R.id.btn_update_tiktok).setOnClickListener {
            openUrl(activity, config.optString("tiktokUrl", N.a(N.TIKTOK_URL)))
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
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.90f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        activeDialog = dialog
        activeActivity = WeakReference(activity)
    }

    fun openUrl(activity: Activity, url: String) {
        val packages = if (url.contains("whatsapp.com", true)) {
            listOf("com.whatsapp", "com.whatsapp.w4b", null)
        } else listOf(null)
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
