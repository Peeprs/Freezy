package com.freezy

import android.content.Context
import android.media.MediaPlayer
import com.system.network.ui.R

/**
 * Gestor de tonos de activación/desactivación.
 *
 * Los tonos son variantes del sonido original de la app (coin_on.wav), por lo que
 * conservan su misma estructura de "moneda". Todos duran lo mismo (~580 ms) y
 * suenan a un volumen suave y parejo. La preferencia se guarda en "FreezyPrefs"
 * bajo la clave "tone_type" (índice dentro de [tones]).
 */
object ToneManager {

    data class Tone(val id: Int, val name: String)

    val tones = listOf(
        Tone(0, "Clásico"),
        Tone(1, "Agudo"),
        Tone(2, "Grave"),
        Tone(3, "Doble"),
        Tone(4, "Eco")
    )

    private val resources = intArrayOf(
        R.raw.tone_0, // Clásico (sonido original)
        R.raw.tone_1, // Agudo
        R.raw.tone_2, // Grave
        R.raw.tone_3, // Doble
        R.raw.tone_4 // Eco
    )

    @Volatile
    private var active: MediaPlayer? = null

    fun nameOf(id: Int): String =
        tones.firstOrNull { it.id == id }?.name ?: tones.first().name

    fun play(context: Context, toneId: Int) {
        if (toneId !in 0 until resources.size) return
        try {
            stop()
            val mp = MediaPlayer.create(context, resources[toneId]) ?: return
            active = mp
            mp.setOnCompletionListener { release(it) }
            mp.start()
        } catch (_: Exception) {
            // Nunca romper la app por un fallo de audio
        }
    }

    private fun stop() {
        val old = active
        active = null
        try {
            old?.stop()
            old?.release()
        } catch (_: Exception) {
        }
    }

    private fun release(mp: MediaPlayer) {
        try {
            if (active === mp) active = null
            mp.release()
        } catch (_: Exception) {
        }
    }
}