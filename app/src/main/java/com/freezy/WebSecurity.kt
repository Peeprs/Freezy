package com.freezy

import android.util.Base64
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebSecurity — Canal TLS endurecido y compartido por todas las
 * conexiones al servidor de licencias (Login, Main y BubbleService).
 */
object WebSecurity {

    private class PinningTrustManager(
        private val delegate: X509TrustManager,
        private val pins: Set<String>
    ) : X509ExtendedTrustManager() {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkClientTrusted(chain, authType, socket)
            } else {
                delegate.checkClientTrusted(chain, authType)
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkClientTrusted(chain, authType, engine)
            } else {
                delegate.checkClientTrusted(chain, authType)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
            verifyPins(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkServerTrusted(chain, authType, socket)
            } else {
                delegate.checkServerTrusted(chain, authType)
            }
            verifyPins(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (delegate is X509ExtendedTrustManager) {
                delegate.checkServerTrusted(chain, authType, engine)
            } else {
                delegate.checkServerTrusted(chain, authType)
            }
            verifyPins(chain)
        }

        private fun verifyPins(chain: Array<out X509Certificate>?) {
            if (chain == null) return
            if (pins.isEmpty()) throw CertificateException("No certificate pins configured")

            val digest = MessageDigest.getInstance("SHA-256")
            val matched = chain.any { cert ->
                val encoded = Base64.encodeToString(
                    digest.digest(cert.publicKey.encoded),
                    Base64.NO_WRAP
                )
                encoded in pins
            }
            if (!matched) throw CertificateException("Certificate pin mismatch — connection aborted")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    /** Lee los pines (Base64, separados por comas) desde el binario nativo. */
    private fun getPins(): Set<String> =
        NativeBridge.getNativeString(NativeBridge.STRING_CERT_PIN)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Abre una conexión HTTP(S) endurecida. En HTTPS mantenemos la
     * validación estándar del sistema y, SOLO si hay pines nativos
     * configurados, exigimos además que algún certificado de la cadena
     * coincida con uno de ellos.
     */
    fun open(urlString: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        val pins = getPins()
        if (conn is HttpsURLConnection && pins.isNotEmpty()) {
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                val delegate = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                if (delegate != null) {
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, arrayOf(PinningTrustManager(delegate, pins)), java.security.SecureRandom())
                    conn.sslSocketFactory = ctx.socketFactory
                }
            } catch (e: Exception) {
                if (com.system.network.ui.BuildConfig.DEBUG) e.printStackTrace()
            }
        }
        return conn
    }
}
