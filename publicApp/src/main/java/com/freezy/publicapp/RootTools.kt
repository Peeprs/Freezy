package com.freezy.publicapp

import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

object RootTools {

    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(N.a(N.SU))
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes(N.a(N.SU_EXIT))
            os.flush()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return false
            }
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun isDeviceRooted(): Boolean {
        return hasRootAccess()
    }
}
