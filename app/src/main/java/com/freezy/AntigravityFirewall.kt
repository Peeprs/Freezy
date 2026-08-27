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
        init { System.loadLibrary("ncx") }

        @Volatile
        var isTunnelRunning: Boolean = false
            private set

        /** Llamado desde BubbleService para activar/desactivar el drop asimétrico */
        @JvmStatic external fun setLagActive(active: Boolean)

        /** Filtro Ghost saliente independiente: descarta UDP de 50..200 bytes. */
        @JvmStatic external fun setGhostActive(active: Boolean)

        /** Inicia captura o, al apagar, reproduce los paquetes de Teleport Drop. */
        @JvmStatic external fun setTeleportDropActive(active: Boolean)

        /** 0 Apagado, 1 Capturando, 2 Reproduciendo. */
        @JvmStatic external fun getTeleportDropState(): Int

        /** Cancela y limpia Teleport Drop sin reproducir (solo para cierre del servicio). */
        @JvmStatic external fun cancelTeleportDrop()

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
        if (intent?.action == NativeBridge.getNativeString(NativeBridge.S91)) {
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
                NativeBridge.getNativeString(NativeBridge.S93),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(NativeBridge.getNativeString(NativeBridge.S93))
            .setContentText(NativeBridge.getNativeString(NativeBridge.S106))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2, notification)
        }

        val targetPackage = intent?.getStringExtra(NativeBridge.getNativeString(NativeBridge.S92))
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
            builder.setSession(NativeBridge.getNativeString(NativeBridge.S93))
                .addAddress(NativeBridge.getNativeString(NativeBridge.S94), 32)
                .addRoute(NativeBridge.getNativeString(NativeBridge.S95), 0)   // Capturar todo el tráfico IPv4
            
            // MTU real de red. 65535 hacía que el juego generara datagramas que
            // después no podían reconstruirse en el proxy (límite ~1500),
            // provocando pérdida sostenida y un ping aparente de 999 ms.
            builder.setMtu(1500)

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
            isTunnelRunning = true

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
        isTunnelRunning = false
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
