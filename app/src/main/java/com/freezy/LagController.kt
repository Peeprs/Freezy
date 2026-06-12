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

    fun activarFakeLagRoot() {
        Thread {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                
                // Comandos secuenciales en una misma sesión de terminal root
                val cmds = listOf(
                    "iptables -D INPUT -p udp --sport 7000:25000 -j FREEZY_FAKELAG",
                    "iptables -F FREEZY_FAKELAG",
                    "iptables -X FREEZY_FAKELAG",
                    "iptables -N FREEZY_FAKELAG",
                    "iptables -I INPUT -p udp --sport 7000:25000 -j FREEZY_FAKELAG",
                    "iptables -A FREEZY_FAKELAG -j DROP"
                )
                
                for (cmd in cmds) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun desactivarFakeLagRoot() {
        Thread {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(process.outputStream)
                
                val cmds = listOf(
                    "iptables -D INPUT -p udp --sport 7000:25000 -j FREEZY_FAKELAG",
                    "iptables -F FREEZY_FAKELAG",
                    "iptables -X FREEZY_FAKELAG"
                )
                
                for (cmd in cmds) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes("exit\n")
                os.flush()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
