package com.freezy

import java.io.DataOutputStream

object LagController {
    var diasLicenciaRestantes: Int = 0 // Esto lo alimentas desde tu backend (Telegram/Supabase)
    
    var fakeLagActivo: Boolean = false

    fun initLicencia(context: android.content.Context) {
        val prefs = context.getSharedPreferences("FreezyPrefs", android.content.Context.MODE_PRIVATE)
        val expDate = prefs.getString("expiration_date", "")
        if (!expDate.isNullOrEmpty() && expDate != "--") {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val d2 = sdf.parse(expDate)
                if (d2 != null) {
                    val diffMs = d2.time - System.currentTimeMillis()
                    val diffDays = java.util.concurrent.TimeUnit.DAYS.convert(diffMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    diasLicenciaRestantes = if (diffDays > 0) diffDays.toInt() else 0
                }
            } catch (e: Exception) {
                e.printStackTrace()
                diasLicenciaRestantes = 0
            }
        } else {
            diasLicenciaRestantes = 0
        }
    }

    fun toggleFakeLag(activar: Boolean, useRoot: Boolean): Boolean {
        if (useRoot) {
            if (activar) activarFakeLagRoot() else desactivarFakeLagRoot()
        } else {
            try {
                AntigravityFirewall.setLagActive(activar)
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
        fakeLagActivo = activar
        return true // Retorna true si el cambio fue exitoso
    }

    fun ejecutarComandoRoot(comando: String) {
        Thread {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                os.writeBytes("$comando\n")
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun activarFakeLagRoot() {
        // Asegurarse de limpiar cualquier regla previa
        desactivarFakeLagRoot()

        // 1. Crear la cadena personalizada para Fake Lag
        ejecutarComandoRoot("iptables -N FREEZY_FAKELAG")
        // 2. Enrutar tráfico UDP entrante del juego a nuestra cadena
        ejecutarComandoRoot("iptables -I INPUT -p udp --sport 7000:25000 -j FREEZY_FAKELAG")
        // 3. Bloquear todo el tráfico entrante del juego
        ejecutarComandoRoot("iptables -A FREEZY_FAKELAG -j DROP")
    }

    fun desactivarFakeLagRoot() {
        // 1. Eliminar la regla de redirección en INPUT
        ejecutarComandoRoot("iptables -D INPUT -p udp --sport 7000:25000 -j FREEZY_FAKELAG")
        // 2. Vaciar las sub-reglas
        ejecutarComandoRoot("iptables -F FREEZY_FAKELAG")
        // 3. Eliminar la cadena personalizada
        ejecutarComandoRoot("iptables -X FREEZY_FAKELAG")
    }
}
