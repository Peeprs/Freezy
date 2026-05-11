package com.freezy.network

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.os.Handler
import android.os.Looper

class FovOverlay(context: Context) : View(context) {

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            isForceDarkAllowed = false
        }
    }

    // 20% más visible (alpha de 100 a 150) y 10% más grueso (3f a 3.5f)
    private val paintWhite = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.5f 
        alpha = 150 
        isAntiAlias = true
    }

    // Paint para cuando el No-Recoil está disparando (Rojo)
    private val paintRed = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        alpha = 180 // Un poco más intenso
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, Color.RED) // Leve resplandor
    }
    
    var radius = 0f
    private var isFiring = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius > 0) {
            // Decidimos qué color usar basado en el estado
            val activePaint = if (isFiring) paintRed else paintWhite
            canvas.drawCircle(width / 2f, height / 2f, radius, activePaint)
        }
    }

    fun updateRadius(newRadius: Int) {
        radius = newRadius.toFloat()
        invalidate()
    }

    // Método que llamaremos desde C++ cuando el arma dispare
    fun setFiringState(firing: Boolean) {
        if (this.isFiring != firing) {
            this.isFiring = firing
            // Forzamos el repintado en el hilo principal
            mainHandler.post { invalidate() }
        }
    }
}