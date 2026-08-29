package com.freezy.publicapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Punto de entrada de la edicion publica.
 *
 * Solo deben migrarse a este modulo las funciones aprobadas para publicacion.
 * No depende del modulo :app para impedir que su menu, JNI o assets terminen en el APK.
 */
class PublicMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_public_main)
    }
}
