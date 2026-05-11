package com.freezy.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log
import com.system.network.ui.R
import java.io.DataOutputStream
import android.widget.Toast

class RecoilService : Service() {

    private var deviceFd: Int = -1

    companion object {
        private const val TAG = "RecoilService"
        private const val CHANNEL_ID = "RecoilServiceChannel"
        
        init {
            try {
                System.loadLibrary("freezy_net")
                Log.i(TAG, "Librería freezy_net cargada con éxito.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Error al cargar la librería freezy_net: ${e.message}")
            }
        }
    }

    // Métodos Nativos
    private external fun initEngine(): Int
    private external fun stopEngine(fd: Int)
    private external fun startRecoil(fd: Int)
    private external fun stopRecoil()
    private external fun setRecoilProfile(base: Int, inc: Float, max: Int)

    private fun grantPermissions() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("chmod 666 /dev/uinput\n")
            os.writeBytes("chmod 666 /dev/input/event*\n")
            os.writeBytes("chcon u:object_r:input_device:s0 /dev/input/event*\n")
            os.writeBytes("setenforce 0\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            Log.i(TAG, "Permisos otorgados con éxito via SU")
        } catch (e: Exception) {
            Log.e(TAG, "Error al solicitar Root: ${e.message}")
            Toast.makeText(this, "¡Error! Se requiere Root para No-Recoil", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()
        startForeground(1, notification)
        
        // Dar permisos antes de inicializar el motor
        grantPermissions()
        
        // Inicializar el motor (Requiere Root para acceder a /dev/uinput)
        deviceFd = initEngine()
        if (deviceFd < 0) {
            Log.e(TAG, "No se pudo inicializar el motor. ¿Tienes permisos de Root?")
        } else {
            Log.i(TAG, "Motor inicializado con FD: $deviceFd")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val strength = intent?.getIntExtra("strength", 10) ?: 10
        val smoothing = intent?.getIntExtra("smoothing", 20) ?: 20

        when (action) {
            "START_RECOIL" -> {
                if (deviceFd >= 0) {
                    Thread {
                        startRecoil(deviceFd)
                    }.start()
                }
            }
            "STOP_RECOIL" -> {
                stopRecoil()
            }
            "SET_PROFILE" -> {
                val base = intent.getIntExtra("base", 5)
                val inc = intent.getFloatExtra("inc", 1.2f)
                val max = intent.getIntExtra("max", 25)
                setRecoilProfile(base, inc, max)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        if (deviceFd >= 0) {
            stopEngine(deviceFd)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Recoil Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recoil Engine Active")
            .setContentText("El motor de retroceso está ejecutándose.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
