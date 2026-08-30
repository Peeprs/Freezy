package com.freezy.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.freezy.publicapp.R

/**
 * Gestor unificado de colores del tema.
 * Lee directamente de res/values/colors.xml para permitir cambios globales instantáneos.
 */
object ThemeColors {
    fun getPrimary(context: Context, isRoot: Boolean): Int =
        ContextCompat.getColor(context, if (isRoot) R.color.theme_root_primary else R.color.theme_noroot_primary)

    fun getNeon(context: Context, isRoot: Boolean): Int =
        ContextCompat.getColor(context, if (isRoot) R.color.theme_root_neon else R.color.theme_noroot_neon)

    fun getGlow(context: Context, isRoot: Boolean): Int =
        ContextCompat.getColor(context, if (isRoot) R.color.theme_root_glow else R.color.theme_noroot_glow)

    fun getDark(context: Context, isRoot: Boolean): Int =
        ContextCompat.getColor(context, if (isRoot) R.color.theme_root_dark else R.color.theme_noroot_dark)

    fun getLight(context: Context, isRoot: Boolean): Int =
        ContextCompat.getColor(context, if (isRoot) R.color.theme_root_light else R.color.theme_noroot_light)

    fun getDanger(context: Context): Int =
        ContextCompat.getColor(context, R.color.theme_danger)

    fun getSuccess(context: Context): Int =
        ContextCompat.getColor(context, R.color.theme_success)

    fun getWarning(context: Context): Int =
        ContextCompat.getColor(context, R.color.theme_warning)

    fun getCardBg(context: Context): Int =
        ContextCompat.getColor(context, R.color.theme_card_bg)

    fun getTextPrimary(context: Context): Int =
        ContextCompat.getColor(context, R.color.theme_text_primary)

    fun getTextMuted(context: Context): Int =
        ContextCompat.getColor(context, R.color.theme_text_muted)
}
