package com.freezy

import java.io.DataOutputStream

object LagController {
    var diasLicenciaRestantes: Int = 0 // Esto lo alimentas desde tu backend (Telegram/Supabase)
    
    var fakeLagActivo: Boolean = false

    fun initLicencia(context: android.content.Context) {
        val expDate = SecurePrefs.getSecureString(context, "expiration_date")
        if (expDate.isNotEmpty() && expDate != "--") {
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
                val process = Runtime.getRuntime().exec(NativeBridge.getNativeString(NativeBridge.STRING_SU))
                val os = DataOutputStream(process.outputStream)
                
                val cmds = listOf(
                    NativeBridge.getNativeString(NativeBridge.S85),
                    NativeBridge.getNativeString(NativeBridge.S86),
                    NativeBridge.getNativeString(NativeBridge.S87),
                    NativeBridge.getNativeString(NativeBridge.S88),
                    NativeBridge.getNativeString(NativeBridge.S89),
                    NativeBridge.getNativeString(NativeBridge.S90)
                )
                
                for (cmd in cmds) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes(NativeBridge.getNativeString(NativeBridge.STRING_SU_CMD_EXIT))
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
                val process = Runtime.getRuntime().exec(NativeBridge.getNativeString(NativeBridge.STRING_SU))
                val os = DataOutputStream(process.outputStream)
                
                val cmds = listOf(
                    NativeBridge.getNativeString(NativeBridge.S85),
                    NativeBridge.getNativeString(NativeBridge.S86),
                    NativeBridge.getNativeString(NativeBridge.S87)
                )
                
                for (cmd in cmds) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes(NativeBridge.getNativeString(NativeBridge.STRING_SU_CMD_EXIT))
                os.flush()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
