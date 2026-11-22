package io.github.gabrielaian.stickerita

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Cores escolhidas pelo usuário (RGB opaco no formato ARGB). `null` significa "usar o tema padrão".
 * Fica num objeto só para a tela principal e a janela de compartilhar mostrarem as mesmas cores.
 */
object ThemeSettings {
    private const val PREFS = "stickerita"
    private const val KEY_BACKGROUND = "theme_background"
    private const val KEY_ACCENT = "theme_accent"

    var background by mutableStateOf<Int?>(null)
        private set
    var accent by mutableStateOf<Int?>(null)
        private set

    private var loaded = false

    /** Lê as cores salvas. Chamar nas activities antes de montar a tela. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        background = if (prefs.contains(KEY_BACKGROUND)) prefs.getInt(KEY_BACKGROUND, 0) else null
        accent = if (prefs.contains(KEY_ACCENT)) prefs.getInt(KEY_ACCENT, 0) else null
    }

    /** Mostra as cores na hora, sem gravar (usado enquanto os controles estão sendo mexidos). */
    fun preview(background: Int?, accent: Int?) {
        this.background = background
        this.accent = accent
    }

    /** Grava as cores que estão em uso agora. */
    fun save(context: Context) {
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        background?.let { editor.putInt(KEY_BACKGROUND, it) } ?: editor.remove(KEY_BACKGROUND)
        accent?.let { editor.putInt(KEY_ACCENT, it) } ?: editor.remove(KEY_ACCENT)
        editor.apply()
    }

    fun reset(context: Context) {
        preview(null, null)
        save(context)
    }
}
