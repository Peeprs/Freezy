package com.freezy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.freezy.publicapp.N
import com.freezy.publicapp.R

/**
 * Motor VPN de la edición pública.
 * Contiene exclusivamente el proxy de red controlado por el usuario.
 */
class AntigravityFirewall : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        const val ACTION_STOP = "com.freezy.publicapp.STOP_VPN"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val TAG = "FreezyPublicVpn"
        private const val CHANNEL_ID = "freezy_public_vpn"
        private const val NOTIFICATION_ID = 4102

        @Volatile var isTunnelRunning = false
            private set
        @Volatile var isLagActive = false
            private set

        fun setLagActive(active: Boolean) {
            isLagActive = active
            if (isTunnelRunning) {
                runCatching { N.f(active) }
            }
        }

        fun toggleLag(): Boolean {
            setLagActive(!isLagActive)
            return isLagActive
        }

        fun disableLag() {
            setLagActive(false)
        }
    }

    @Suppress("unused")
    fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE) ?: N.a(N.PKG_FF_NORMAL)

        shutdown()
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Freezy · Motor de red")
            .setContentText("Fake Lag listo para controlarse desde la burbuja")
            .setSmallIcon(R.drawable.ic_notification_public)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        openTunnel(targetPackage)
        return START_NOT_STICKY
    }

    private fun openTunnel(targetPackage: String) {
        try {
            val builder = Builder()
                .setSession("FreezyProxy")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)
            try {
                builder.addAllowedApplication(targetPackage)
            } catch (_: Exception) {}
            val established = builder.establish() ?: run {
                stopSelf()
                return
            }
            vpnInterface = established
            isTunnelRunning = true
            registerNetworkChanges()
            Thread({
                runCatching { N.d(this, established.fd) }
            }, "Freezy-TunReader").start()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el túnel", e)
            shutdown()
            stopSelf()
        }
    }

    private fun registerNetworkChanges() {
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
        }
        networkCallback = callback
        runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }
    }

    private fun shutdown() {
        isTunnelRunning = false
        disableLag()
        networkCallback?.let { runCatching { connectivityManager?.unregisterNetworkCallback(it) } }
        networkCallback = null
        connectivityManager = null
        vpnInterface?.let { runCatching { it.close() } }
        vpnInterface = null
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Servicio de Red Freezy", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
