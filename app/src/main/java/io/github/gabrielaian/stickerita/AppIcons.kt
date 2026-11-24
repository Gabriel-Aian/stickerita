package io.github.gabrielaian.stickerita

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
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
    /** Paleta: disco com furos para a tinta e para o polegar. */
    val Palette: ImageVector by lazy {
        ImageVector.Builder(
            name = "Palette",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 9.5f)
                circle(8f, 10f, 1.5f)
                circle(12f, 7.5f, 1.5f)
                circle(16.2f, 10f, 1.5f)
                circle(7.8f, 14.6f, 1.5f)
                circle(16.5f, 16f, 2.4f)
            }
        }.build()
    }

    /** Moldura com sol e montanhas: escolher imagem. */
    val Picture: ImageVector by lazy {
        ImageVector.Builder(
            name = "Picture",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 20f)
                lineTo(3f, 20f)
                close()
                // sol
                circle(8.5f, 9f, 1.8f)
                // montanhas
                moveTo(5f, 18f)
                lineTo(10f, 12f)
                lineTo(13.5f, 16f)
                lineTo(16f, 13.5f)
                lineTo(19f, 18f)
                close()
            }
        }.build()
    }

    private fun icon(name: String, evenOdd: Boolean = false, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                pathBuilder = draw,
            )
        }.build()

    private fun PathBuilder.polygon(vararg points: Float) {
        moveTo(points[0], points[1])
        for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
        close()
    }

    /** Triângulo com uma barra: seguir sem editar. */
    val Skip: ImageVector by lazy {
        icon("Skip") {
            polygon(5f, 5f, 5f, 19f, 15f, 12f)
            polygon(16.5f, 5f, 19f, 5f, 19f, 19f, 16.5f, 19f)
        }
    }

    /** Dois cantos de esquadro: recortar. */
    val Crop: ImageVector by lazy {
        icon("Crop") {
            polygon(17f, 15f, 19f, 15f, 19f, 7f, 17f, 5f, 9f, 5f, 9f, 7f, 17f, 7f)
            polygon(7f, 17f, 7f, 1f, 5f, 1f, 5f, 5f, 1f, 5f, 1f, 7f, 5f, 7f, 5f, 17f, 7f, 19f, 17f, 19f, 17f, 23f, 19f, 23f, 19f, 19f, 23f, 19f, 23f, 17f)
        }
    }

    /** Letra T: adicionar texto. */
    val TextT: ImageVector by lazy {
        icon("TextT") {
            polygon(5f, 4f, 19f, 4f, 19f, 7f, 13.5f, 7f, 13.5f, 20f, 10.5f, 20f, 10.5f, 7f, 5f, 7f)
        }
    }

    /** Carinha sorrindo: adicionar emoji. */
    val Smile: ImageVector by lazy {
        icon("Smile", evenOdd = true) {
            circle(12f, 12f, 10f)
            circle(12f, 12f, 8.2f)
            circle(8.8f, 10f, 1.3f)
            circle(15.2f, 10f, 1.3f)
            moveTo(7f, 14f)
            quadTo(12f, 20f, 17f, 14f)
            quadTo(12f, 17f, 7f, 14f)
            close()
        }
    }

    /** Quadrado cheio: o recorte preenche os 512x512. */
    val FitFill: ImageVector by lazy {
        icon("FitFill") { polygon(3f, 3f, 21f, 3f, 21f, 21f, 3f, 21f) }
    }

    /** Quadro com um retângulo dentro: o recorte mantém a proporção. */
    val FitKeep: ImageVector by lazy {
        icon("FitKeep", evenOdd = true) {
            polygon(3f, 3f, 21f, 3f, 21f, 21f, 3f, 21f)
            polygon(4.5f, 4.5f, 19.5f, 4.5f, 19.5f, 19.5f, 4.5f, 19.5f)
            polygon(7f, 9f, 17f, 9f, 17f, 15f, 7f, 15f)
        }
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }
}
