package com.freezy.publicapp

import android.content.Context
import android.media.MediaPlayer
import com.freezy.publicapp.R

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
        R.raw.tone_0,
        R.raw.tone_1,
        R.raw.tone_2,
        R.raw.tone_3,
        R.raw.tone_4
    )

    @Volatile
    private var active: MediaPlayer? = null

    fun nameOf(id: Int): String =
        tones.firstOrNull { it.id == id }?.name ?: "Clásico"

    @Synchronized
    fun play(context: Context, id: Int) {
        stop()
        val res = resources.getOrNull(id) ?: resources[0]
        try {
            active = MediaPlayer.create(context.applicationContext, res)?.apply {
                setOnCompletionListener {
                    it.release()
                    if (active === it) active = null
                }
                start()
            }
        } catch (_: Throwable) {
            // Failsafe contra errores de audio del sistema
        }
    }

    @Synchronized
    fun stop() {
        try {
            active?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {
        } finally {
            active = null
        }
    }
}