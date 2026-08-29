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

/** Comprobación global de versión, independiente del estado de la licencia. */
object AppUpdateManager {
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
    private const val DEFAULT_WHATSAPP_URL = "https://whatsapp.com/channel/0029Vb9K5FJ545usgyohs136"
    private const val DEFAULT_TIKTOK_URL = "https://www.tiktok.com/@freezyt"

    private val checking = AtomicBoolean(false)
    @Volatile private var lastCheckAt = 0L
    @Volatile private var pendingUpdateConfig: JSONObject? = null
    private var activeDialog: AlertDialog? = null
    private var activeActivity = WeakReference<Activity>(null)

    /** Vista previa local disponible sólo en Debug; no modifica la versión publicada. */
    fun showDebugPreview(activity: Activity) {
        if (!BuildConfig.DEBUG) return
        val preview = JSONObject().apply {
            put("versionName", "4.1.0")
            put("changelog", "• Mejoras de estabilidad\n• Nuevo canal oficial de avisos\n• Experiencia de actualización renovada")
            put("whatsappUrl", DEFAULT_WHATSAPP_URL)
            put("tiktokUrl", DEFAULT_TIKTOK_URL)
        }
        pendingUpdateConfig = preview
        showDialog(activity, preview)
    }

    fun check(activity: Activity, force: Boolean = false) {
        pendingUpdateConfig?.let { showDialog(activity, it) }
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
                    val latestCode = config.optInt("versionCode", 0)
                    val latestName = config.optString("versionName", "")
                    val isNewer = latestCode > BuildConfig.VERSION_CODE ||
                        (latestCode <= 0 && latestName.isNotEmpty() && latestName != BuildConfig.VERSION_NAME)

                    if (isNewer) {
                        pendingUpdateConfig = config
                        activity.runOnUiThread {
                            showDialog(activity, config)
                        }
                    } else {
                        pendingUpdateConfig = null
                    }
                }
            } catch (_: Exception) {
                // Una caída de la comprobación no debe bloquear el acceso a la app.
            } finally {
                checking.set(false)
            }
        }.start()
    }

    private fun showDialog(activity: Activity, config: JSONObject) {
        if (activity.isFinishing || activity.isDestroyed) return
        val shownDialog = activeDialog
        if (shownDialog?.isShowing == true && activeActivity.get() === activity) return
        if (shownDialog?.isShowing == true) shownDialog.dismiss()

        val latestName = config.optString("versionName", "nueva")
        val changelog = config.optString("changelog", "Hay una nueva versión disponible.")
        val whatsappUrl = config.optString("whatsappUrl", DEFAULT_WHATSAPP_URL)
            .trim().ifEmpty { DEFAULT_WHATSAPP_URL }
        val tiktokUrl = config.optString("tiktokUrl", DEFAULT_TIKTOK_URL)
            .trim().ifEmpty { DEFAULT_TIKTOK_URL }

        val content = activity.layoutInflater.inflate(R.layout.dialog_update_required, null)
        content.findViewById<TextView>(R.id.tv_update_title).text = "Freezy v$latestName"
        content.findViewById<TextView>(R.id.tv_update_changelog).text = changelog

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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                if (packageName != null) intent.setPackage(packageName)
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // Probar la siguiente aplicación o el navegador genérico.
            }
        }
        Toast.makeText(activity, "No se pudo abrir el enlace.", Toast.LENGTH_LONG).show()
    }
}
