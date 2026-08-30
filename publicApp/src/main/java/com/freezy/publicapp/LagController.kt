package com.freezy.publicapp

import android.content.Context
import java.io.DataOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object LagController {
    var diasLicenciaRestantes: Int = 0
    var fakeLagActivo: Boolean = false

    fun initLicencia(context: Context) {
        val expDate = SecurePrefs.get(context, "expiration_date")
        if (expDate.isNotEmpty() && expDate != "--") {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val d2 = sdf.parse(expDate)
                if (d2 != null) {
                    val diffMs = d2.time - System.currentTimeMillis()
                    val diffDays = TimeUnit.DAYS.convert(diffMs, TimeUnit.MILLISECONDS)
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
                com.freezy.AntigravityFirewall.setLagActive(activar)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fakeLagActivo = activar
        return true
    }

    fun activarFakeLagRoot() {
        Thread {
            try {
                val process = Runtime.getRuntime().exec(N.a(N.SU))
                val os = DataOutputStream(process.outputStream)
                val cmds = listOf(
                    N.a(N.IPTABLES_CLEAN_1),
                    N.a(N.IPTABLES_CLEAN_2),
                    N.a(N.IPTABLES_CLEAN_3),
                    N.a(N.IPTABLES_CREATE),
                    N.a(N.IPTABLES_INSERT),
                    N.a(N.IPTABLES_DROP)
                )
                for (cmd in cmds) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes(N.a(N.SU_EXIT))
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
                val process = Runtime.getRuntime().exec(N.a(N.SU))
                val os = DataOutputStream(process.outputStream)
                val cmds = listOf(
                    N.a(N.IPTABLES_CLEAN_1),
                    N.a(N.IPTABLES_CLEAN_2),
                    N.a(N.IPTABLES_CLEAN_3)
                )
                for (cmd in cmds) {
                    os.writeBytes("$cmd\n")
                }
                os.writeBytes(N.a(N.SU_EXIT))
                os.flush()
                process.waitFor()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
