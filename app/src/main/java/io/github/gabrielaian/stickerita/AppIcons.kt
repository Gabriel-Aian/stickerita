package io.github.gabrielaian.stickerita

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Ícones desenhados à mão (o conjunto básico de ícones do Material não os traz). */
object AppIcons {

    /** Três linhas, cada uma com um quadradinho e uma barra: visualização em lista. */
    val ViewList: ImageVector by lazy {
        ImageVector.Builder(
            name = "ViewList",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                for (top in floatArrayOf(4f, 10f, 16f)) {
                    // quadradinho
                    moveTo(3f, top)
                    lineTo(7f, top)
                    lineTo(7f, top + 4f)
                    lineTo(3f, top + 4f)
                    close()
                    // barra
                    moveTo(9.5f, top)
                    lineTo(21f, top)
                    lineTo(21f, top + 4f)
                    lineTo(9.5f, top + 4f)
                    close()
                }
            }
        }.build()
    }

    /** Quatro quadrados: visualização em grade. */
    val ViewGrid: ImageVector by lazy {
        ImageVector.Builder(
            name = "ViewGrid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                for (left in floatArrayOf(3f, 13.5f)) {
                    for (top in floatArrayOf(3f, 13.5f)) {
                        moveTo(left, top)
                        lineTo(left + 7.5f, top)
                        lineTo(left + 7.5f, top + 7.5f)
                        lineTo(left, top + 7.5f)
                        close()
                    }
                }
            }
        }.build()
    }

    /** Estrela de quatro pontas: novidades. */
    val Sparkle: ImageVector by lazy {
        ImageVector.Builder(
            name = "Sparkle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(14.6f, 9.4f)
                lineTo(22f, 12f)
                lineTo(14.6f, 14.6f)
                lineTo(12f, 22f)
                lineTo(9.4f, 14.6f)
                lineTo(2f, 12f)
                lineTo(9.4f, 9.4f)
                close()
            }
        }.build()
    }
}
