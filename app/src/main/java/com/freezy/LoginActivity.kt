package com.freezy

import com.system.network.ui.R

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    // Ingeniera inversa de la inversa (Carga librería nativa y llama ofuscador)
    init {
        System.loadLibrary("freezy_net")
    }
    private external fun getSecureEndpoint(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FreezyPrefs", Context.MODE_PRIVATE)
        
        // Guardar el endpoint para que otros servicios lo usen
        try {
            val endpointUrl = getSecureEndpoint()
            prefs.edit().putString("secure_endpoint", endpointUrl).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Mostrar descargo de responsabilidad si no lo ha aceptado
        if (!prefs.getBoolean("disclaimer_accepted", false)) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_disclaimer, null)
            val btnAccept = dialogView.findViewById<Button>(R.id.btn_accept_risk)
            val btnExit = dialogView.findViewById<Button>(R.id.btn_exit_app)

            val dialog = AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnAccept.setOnClickListener {
                prefs.edit().putBoolean("disclaimer_accepted", true).apply()
                dialog.dismiss()
            }

            btnExit.setOnClickListener {
                finishAffinity()
            }

            dialog.show()
        }

        // Si el usuario ya metió la Key correcta antes, saltamos directo a la app principal
        if (prefs.getBoolean("is_logged_in", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val etUser = findViewById<EditText>(R.id.et_user)
        val etKey = findViewById<EditText>(R.id.et_key)
        val btnLogin = findViewById<Button>(R.id.btn_login)

        // Restaurar datos guardados para comodidad del usuario
        etUser.setText(prefs.getString("saved_username", ""))
        etKey.setText(prefs.getString("saved_key", ""))

        btnLogin.setOnClickListener {
            val username = etUser.text.toString().trim()
            val key = etKey.text.toString().trim().uppercase()

            if (username.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "Por favor completa todos los campos", Toast.LENGTH_SHORT)
                        .show()
                return@setOnClickListener
            }

            btnLogin.text = "VERIFICANDO..."
            btnLogin.isEnabled = false

            // Conexión real al servidor privado
            Thread {
                        try {
                            val endpointUrl =
                                    getSecureEndpoint() // Obtenemos la URL de C++ (ofuscada)
                            val url = URL(endpointUrl)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.connectTimeout = 30000
                            conn.readTimeout = 30000
                            conn.doOutput = true

                            // Obtener HWID
                            val hwid =
                                    Settings.Secure.getString(
                                            contentResolver,
                                            Settings.Secure.ANDROID_ID
                                    )

                            // Enviar JSON con la Key, HWID, Username y Device Model
                            val deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                            val jsonInputString =
                                    "{\"key\": \"$key\", \"hwid\": \"$hwid\", \"username\": \"$username\", \"device_model\": \"$deviceModel\"}"
                            conn.outputStream.use { os ->
                                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                                os.write(input, 0, input.size)
                            }

                            val responseCode = conn.responseCode
                            if (responseCode == 200) {
                                val responseBody = conn.inputStream.bufferedReader().readText()
                                android.util.Log.d("LoginActivity", "Server Response: $responseBody")
                                val jsonObject = JSONObject(responseBody)
                                val isValid = jsonObject.getBoolean("valid")

                                if (isValid) {
                                    val createdAt = jsonObject.optString("created_at", "--")
                                    val expiresAt = jsonObject.optString("expires_at", "--")
                                    
                                    val activationDate = if (createdAt.length >= 10) createdAt.substring(0, 10) else createdAt
                                    val expirationDate = if (expiresAt.length >= 10) expiresAt.substring(0, 10) else expiresAt
                                    
                                    runOnUiThread {
                                        Logger.log(this@LoginActivity, "Licencia Validada")
                                        prefs.edit()
                                                .putBoolean("is_logged_in", true)
                                                .putString("saved_username", username)
                                                .putString("saved_key", key)
                                                .putString("activation_date", activationDate)
                                                .putString("expiration_date", expirationDate)
                                                .apply()
                                        Toast.makeText(
                                                        this@LoginActivity,
                                                        "¡Acceso Concedido!",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                        startActivity(
                                                Intent(this@LoginActivity, MainActivity::class.java)
                                        )
                                        finish()
                                    }
                                } else {
                                    val message = jsonObject.optString("message", "Licencia inválida o expirada.")
                                    runOnUiThread {
                                        Toast.makeText(
                                                        this@LoginActivity,
                                                        message,
                                                        Toast.LENGTH_LONG
                                                )
                                                .show()
                                        btnLogin.text = "INGRESAR"
                                        btnLogin.isEnabled = true
                                    }
                                }
                            } else {
                                // Leer el mensaje de error del servidor
                                val errorStream = conn.errorStream
                                val errorMessage =
                                        if (errorStream != null) {
                                            val errorResponse =
                                                    errorStream.bufferedReader().readText()
                                            try {
                                                JSONObject(errorResponse).getString("message")
                                            } catch (e: Exception) {
                                                "Licencia inválida o inexistente. Adquiere una oficial."
                                            }
                                        } else {
                                            "Licencia inválida o inexistente. Adquiere una oficial."
                                        }

                                runOnUiThread {
                                    Toast.makeText(
                                                    this@LoginActivity,
                                                    errorMessage,
                                                    Toast.LENGTH_LONG
                                            )
                                            .show()
                                    btnLogin.text = "INGRESAR"
                                    btnLogin.isEnabled = true
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            runOnUiThread {
                                Toast.makeText(
                                                this@LoginActivity,
                                                "Licencia inválida o inexistente. Adquiere una oficial.",
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                                btnLogin.text = "INGRESAR"
                                btnLogin.isEnabled = true
                            }
                        }
                    }
                    .start()
        }
    }


}
