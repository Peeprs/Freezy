package com.freezy.network

import android.content.Context
import android.util.Log

class InputMonitor(private val context: Context) {

    companion object {
        private const val TAG = "InputMonitor"
        
        init {
            try {
                System.loadLibrary("ncx")
                Log.i(TAG, "Librería nativa cargada en InputMonitor.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Error al cargar la librería nativa: ${e.message}")
            }
        }
    }

    private var isMonitoring = false
 
    // Métodos Nativos
    private external fun startTouchMonitor(devicePath: String, x1: Int, y1: Int, x2: Int, y2: Int, screenWidth: Int, screenHeight: Int, rotation: Int): Boolean
    private external fun stopTouchMonitor()
    external fun updateFireZone(x1: Int, y1: Int, x2: Int, y2: Int)
 
    fun startMonitoring(devicePath: String = "/dev/input/event2", x1: Int = 500, y1: Int = 1000, x2: Int = 800, y2: Int = 1300) {
        if (isMonitoring) return
        
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        
        val rotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                context.display?.rotation ?: 0
            } catch (e: Exception) {
                0
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
            } catch (e: Exception) {
                0
            }
        }
        
        Log.i(TAG, "Iniciando monitoreo de entrada en $devicePath con pantalla ${screenWidth}x${screenHeight}, rotación $rotation...")
        val success = startTouchMonitor(devicePath, x1, y1, x2, y2, screenWidth, screenHeight, rotation)
        
        if (success) {
            isMonitoring = true
            Log.i(TAG, "Monitoreo iniciado con éxito.")
        } else {
            Log.e(TAG, "No se pudo iniciar el monitoreo. ¿Tienes permisos de Root?")
        }
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        stopTouchMonitor()
        isMonitoring = false
        Log.i(TAG, "Monitoreo de entrada detenido.")
    }
}
