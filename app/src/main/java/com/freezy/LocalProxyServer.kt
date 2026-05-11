package com.freezy

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.net.DatagramSocket
import java.net.DatagramPacket
import kotlin.concurrent.thread

object LocalProxyServer {
    private const val TAG = "LocalProxyServer"
    private const val PORT = 10808
    private var isRunning = false
    private var tcpServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    // Variable controlada por BubbleService para activar el lag asimétrico
    var isLagSwitchActive = false

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                tcpServerSocket = ServerSocket(PORT)
                startUDPServer()
                Log.i(TAG, "Servidor Proxy Local iniciado en el puerto $PORT (TCP/UDP)")
                while (isRunning) {
                    val clientSocket = tcpServerSocket?.accept()
                    clientSocket?.let {
                        handleClient(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en Servidor Proxy", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        thread {
            try {
                // Aquí (Paso 4) el motor C++ enviará el tráfico descifrado.
                // Filtraremos subida vs bajada.
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val buffer = ByteArray(4096)
                
                while (isRunning && !socket.isClosed) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    
                    if (isLagSwitchActive) {
                        // Lógica Asimétrica: Destruir el paquete entrante (INPUT)
                        // pero permitir que se envíen al servidor remoto (OUTPUT)
                        continue // Drop de prueba temporal
                    }
                    
                    // Si no está activo el Freezy, redirigir normalmente
                }
            } catch (e: Exception) {
                // Conexión finalizada o error de lectura
            } finally {
                socket.close()
            }
        }
    }

    private fun startUDPServer() {
        thread {
            try {
                udpSocket = DatagramSocket(PORT + 1) // Puerto UDP dedicado
                val buffer = ByteArray(65535)
                
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    
                    if (isLagSwitchActive) {
                        // AQUÍ OCURRE LA MAGIA DEL PASO 4
                        // Free Fire usa UDP para el movimiento de los jugadores.
                        // Si el Lag Switch está encendido, simplemente aplicamos "drop" (ignoramos el paquete)
                        // Esto hace que el enemigo se congele en nuestra pantalla, pero nuestros disparos sigan saliendo.
                        Log.d(TAG, "Bloqueando paquete UDP de Garena (Asimétrico)")
                        continue 
                    }
                    
                    // Si Freezy está apagado, reenviamos el paquete al destino original
                    // (Lógica de ruteo normal del proxy)
                }
            } catch (e: Exception) {
                // Socket cerrado
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            tcpServerSocket?.close()
            udpSocket?.close()
        } catch (e: Exception) {}
        Log.i(TAG, "Servidor Proxy Local detenido")
    }
}
