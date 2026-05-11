package com.freezy

/*
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * AntigravityFirewall — Proxy UDP Asimétrico sin Root.
 *
 * Comportamiento idéntico a:
 *   iptables -I INPUT  -p udp -j DROP   (LAG ON  → enemigos congelados)
 *   iptables -D INPUT  -p udp -j DROP   (LAG OFF → conexión normal)
 *
 * El motor C++ en native-lib.cpp:
 *  1. Lee paquetes salientes del juego desde el tun fd
 *  2. Los reenvía al servidor real mediante sockets protegidos (VpnService.protect)
 *  3. Recibe respuestas del servidor:
 *     - LAG OFF → escribe la respuesta al tun fd → el juego recibe normalmente
 *     - LAG ON  → dropea la respuesta → los enemigos se congelan en tu pantalla
 */
class AntigravityFirewall : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        init { System.loadLibrary("freezy_net") }

        /** Llamado desde BubbleService para activar/desactivar el drop asimétrico */
        @JvmStatic external fun setLagActive(active: Boolean)
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
        openTunnel(targetPackage)
        return START_NOT_STICKY
    }

    private fun openTunnel(targetPackage: String?) {
        try {
            val builder = Builder()
            builder.setSession("FreezyProxy")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)   // Capturar todo el tráfico IPv4
                .setMtu(1400)             // Evitar fragmentación (Sugerido: 1400)
                .setBlocking(false)

            if (targetPackage != null) {
                try {
                    builder.addAllowedApplication(targetPackage)
                    Log.i("AntigravityFirewall", "Proxy activo solo para: $targetPackage")
                } catch (e: Exception) {
                    Log.e("AntigravityFirewall", "Error restringiendo paquete: ${e.message}")
                }
            }

            val iface = builder.establish()
            if (iface == null) {
                Log.e("AntigravityFirewall", "establish() = null. ¿Permiso VPN revocado?")
                stopSelf()
                return
            }
            vpnInterface = iface

            // Lanzar el motor nativo en un hilo separado para NO bloquear la UI
            Thread {
                try {
                    Log.i("AntigravityFirewall", "Iniciando motor nativo con fd=${iface.fd}")
                    startNativeEngine(iface.fd)
                } catch (e: Exception) {
                    Log.e("AntigravityFirewall", "Error en el hilo del motor: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            Log.e("AntigravityFirewall", "Error abriendo túnel: ${e.message}")
            stopSelf()
        }
    }

    private fun shutdown() {
        stopNativeEngine()
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
*/
