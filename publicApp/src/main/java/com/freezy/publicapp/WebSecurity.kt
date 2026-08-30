package com.freezy.publicapp

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

/** HTTPS con validación normal del sistema y pin SPKI adicional. */
object WebSecurity {
    private class PinningTrustManager(
        private val delegate: X509TrustManager,
        private val pins: Set<String>
    ) : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(chain, authType, socket)
            else delegate.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(chain, authType, engine)
            else delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkServerTrusted(chain, authType)
            verify(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(chain, authType, socket)
            else delegate.checkServerTrusted(chain, authType)
            verify(chain)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(chain, authType, engine)
            else delegate.checkServerTrusted(chain, authType)
            verify(chain)
        }

        private fun verify(chain: Array<out X509Certificate>?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Empty certificate chain")
            val digest = MessageDigest.getInstance("SHA-256")
            val matched = chain.any { certificate ->
                Base64.encodeToString(digest.digest(certificate.publicKey.encoded), Base64.NO_WRAP) in pins
            }
            if (!matched) throw CertificateException("Certificate pin mismatch")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection !is HttpsURLConnection) return connection
        val pins = N.a(N.CERT_PINS)
            .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        if (pins.isEmpty()) return connection

        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinningTrustManager(delegate, pins)), java.security.SecureRandom())
        connection.sslSocketFactory = context.socketFactory
        return connection
    }
}
