package io.github.gabrielaian.stickerita

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0A2E0D),
    secondary = Color(0xFF558B2F),
    background = Color(0xFFF8FBF5),
    surface = Color(0xFFF8FBF5),
    surfaceVariant = Color(0xFFE1EBDD),
    onSurfaceVariant = Color(0xFF424940),
    surfaceContainerHighest = Color(0xFFE1EBDD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0A3D0F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFAED581),
    background = Color(0xFF10150F),
    surface = Color(0xFF10150F),
    surfaceVariant = Color(0xFF3A443A),
    onSurfaceVariant = Color(0xFFC1CBBE),
    surfaceContainerHighest = Color(0xFF2C352B),
)

/** Abaixo disso o branco contrasta mais que o preto (ponto de igual contraste). */
private const val LUMINANCE_SPLIT = 0.179f

/** Cor de texto que melhor contrasta com [color]. */
private fun contentOn(color: Color): Color =
    if (color.luminance() > LUMINANCE_SPLIT) Color(0xFF1B1C1B) else Color.White

/** Razão de contraste (1 a 21) entre duas cores, como nas diretrizes de acessibilidade. */
private fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()
    return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
}

/**
 * Clareia ou escurece [color] (na direção de [toward]) só o necessário para ela ser legível como
 * texto sobre todas as [surfaces]. Se já for legível, devolve a mesma cor.
 */
private fun readable(color: Color, toward: Color, surfaces: List<Color>, ratio: Float = 4.5f): Color {
    var step = 0
    var candidate = color
    while (step < 20 && surfaces.any { contrastRatio(candidate, it) < ratio }) {
        step++
        candidate = lerp(color, toward, step * 0.05f)
    }
    return candidate
}

/**
 * Monta as cores a partir das escolhas do usuário. Com um fundo próprio, tudo que é "superfície"
 * (cartões, menus, diálogos) sai de uma mistura do fundo com a cor do texto, e o texto vira
 * claro ou escuro sozinho para continuar legível.
 */
private fun buildScheme(background: Int?, accent: Int?, systemDark: Boolean): ColorScheme {
    var scheme = if (systemDark) DarkColors else LightColors

    if (background != null) {
        val base = Color(background)
        val on = contentOn(base)
        fun tone(amount: Float) = lerp(base, on, amount)
        scheme = (if (on == Color.White) DarkColors else LightColors).copy(
            background = base,
            onBackground = on,
            surface = base,
            onSurface = on,
            surfaceVariant = tone(0.12f),
            onSurfaceVariant = lerp(on, base, 0.25f),
            surfaceContainerLowest = tone(0.02f),
            surfaceContainerLow = tone(0.04f),
            surfaceContainer = tone(0.07f),
            surfaceContainerHigh = tone(0.10f),
            surfaceContainerHighest = tone(0.13f),
            outline = lerp(on, base, 0.5f),
            outlineVariant = lerp(on, base, 0.75f),
        )
    }

    if (accent != null) {
        // A cor dos botões também é usada como texto (botões sem fundo, links): se ela for parecida
        // com o fundo das janelas, vira uma versão mais clara ou mais escura da mesma cor.
        val primary = readable(
            color = Color(accent),
            toward = contentOn(scheme.background),
            surfaces = listOf(scheme.background, scheme.surfaceContainer, scheme.surfaceContainerHigh),
        )
        // O "container" (fundo do botão flutuante, dos filtros marcados etc.) é a cor escolhida bem suave.
        val container = lerp(primary, scheme.background, 0.72f)
        scheme = scheme.copy(
            primary = primary,
            onPrimary = contentOn(primary),
            primaryContainer = container,
            onPrimaryContainer = contentOn(container),
            secondary = primary,
            secondaryContainer = container,
            onSecondaryContainer = contentOn(container),
            surfaceTint = primary,
        )
    }
    return scheme
}

@Composable
fun StickeritaTheme(content: @Composable () -> Unit) {
    val scheme = buildScheme(ThemeSettings.background, ThemeSettings.accent, isSystemInDarkTheme())

    // Ícones da barra de status e de navegação precisam acompanhar o fundo, que pode não ser o do sistema.
    val lightBackground = scheme.background.luminance() > LUMINANCE_SPLIT
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBackground
                isAppearanceLightNavigationBars = lightBackground
            }
        }
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
