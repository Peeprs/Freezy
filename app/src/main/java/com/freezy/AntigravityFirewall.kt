package com.freezy

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * AntigravityFirewall — Proxy UDP Asimétrico sin Root (Fase 1 Optimizado).
 *
 * La capa de Kotlin solo pide permisos y abre el descriptor de archivo (FD).
 */
class AntigravityFirewall : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectivityManager: android.net.ConnectivityManager? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    companion object {
        init { System.loadLibrary("freezy_net") }

        /** Llamado desde BubbleService para activar/desactivar el drop asimétrico */
        @JvmStatic external fun setLagActive(active: Boolean)

        @JvmStatic external fun notifyNetworkChange()
    }

    private external fun startNativeEngine(fd: Int)
    private external fun stopNativeEngine()

    /**
     * Callback llamado desde C++ (JNI) para proteger un socket de bucles de ruteo.
     * DEBE ser public para que JNI pueda invocarlo.
     */
    @Suppress("unused")
    fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_VPN") {
            Log.i("AntigravityFirewall", "STOP_VPN recibido")
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        shutdown() // Limpiar instancia previa si la hay

        // Crear notificación para el Foreground Service (Requerido en Android 14+)
        val channelId = "vpn_service_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VPN Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Freezy VPN Activo")
            .setContentText("Procesando paquetes UDP para reducir el lag")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }

        val targetPackage = intent?.getStringExtra("TARGET_PACKAGE")
        if (targetPackage.isNullOrEmpty()) {
            Log.e("AntigravityFirewall", "Target package nulo. Abortando.")
            stopSelf()
            return START_NOT_STICKY
        }

        openTunnel(targetPackage)
        return START_NOT_STICKY
    }

    private fun openTunnel(targetPackage: String) {
        try {
            val builder = Builder()
            builder.setSession("FreezyProxy")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)   // Capturar todo el tráfico IPv4
                .setMtu(65535)             // Evitar fragmentación IP entregando paquetes reensamblados al motor nativo

            // Aislamiento de Aplicación Estricto
            try {
                builder.addAllowedApplication(targetPackage)
                Log.i("AntigravityFirewall", "Proxy activo estrictamente solo para: $targetPackage")
            } catch (e: Exception) {
                Log.e("AntigravityFirewall", "Error restringiendo paquete: ${e.message}")
            }

            val iface = builder.establish()
            if (iface == null) {
                Log.e("AntigravityFirewall", "establish() = null. ¿Permiso VPN revocado?")
                stopSelf()
                return
            }
            vpnInterface = iface

            // Monitorear cambios de red activa (WiFi <-> Datos)
            try {
                connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        super.onAvailable(network)
                        Log.i("AntigravityFirewall", "Cambio de red detectado. Restableciendo sockets nativos...")
                        notifyNetworkChange()
                    }
                }
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            } catch (e: Exception) {
                Log.e("AntigravityFirewall", "No se pudo registrar callback de red: ${e.message}")
            }

            // Delegación Absoluta del FD al entorno JNI (C++)
            Thread {
                try {
                    Log.i("AntigravityFirewall", "Delegando FD=${iface.fd} al motor nativo.")
                    startNativeEngine(iface.fd)
                } catch (e: Exception) {
                    Log.e("AntigravityFirewall", "Error delegando FD: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            Log.e("AntigravityFirewall", "Error abriendo túnel: ${e.message}")
            stopSelf()
        }
    }

    private fun shutdown() {
        stopNativeEngine()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
        networkCallback = null
        Thread.sleep(60) // Dar tiempo al thread C++ de salir del select()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown()
    }

    override fun onRevoke() {
        super.onRevoke()
        shutdown()
    }
}
