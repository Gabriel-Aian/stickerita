package io.github.gabrielaian.stickerita

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Converte qualquer imagem para o formato exigido pelo WhatsApp:
 * WebP 512x512 com transparência e no máximo 100 KB.
 */
object StickerConverter {
    const val SIZE = 512
    const val TRAY_SIZE = 96
    private const val APP_ICON_SIZE = 256

    /** O limite do WhatsApp é 100 KB; deixamos uma folga. */
    private const val MAX_BYTES = 95_000

    fun toSticker(context: Context, uri: Uri): ByteArray {
        val source = decode(context, uri)
        return encode(resizeToSquare(source))
    }

    /**
     * Ícone do pacote só para o app: qualquer imagem, cortada no centro em quadrado e reduzida.
     * Não precisa seguir as regras do WhatsApp porque nunca é enviado para lá.
     */
    fun toAppIcon(context: Context, uri: Uri): ByteArray {
        val source = decode(context, uri)
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val icon = scaleSmooth(square, APP_ICON_SIZE, APP_ICON_SIZE)
        val out = ByteArrayOutputStream()
        icon.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Gera o ícone do pacote (96x96 PNG) a partir de uma figurinha já pronta. */
    fun writeTray(stickerFile: File, out: File) {
        val source = BitmapFactory.decodeFile(stickerFile.path)
            ?: throw IOException("Figurinha ilegível: ${stickerFile.name}")
        val tray = Bitmap.createScaledBitmap(source, TRAY_SIZE, TRAY_SIZE, true)
        out.outputStream().use { tray.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * Lê a imagem para o editor, reduzida para no máximo [maxSide] px no lado maior: o editor
     * trabalha sobre esse bitmap leve, e o recorte final é feito sobre ele.
     */
    fun decodeForEditor(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap = decode(context, uri, maxSide)

    /** WebP final (até 100 KB) de um bitmap já em 512x512. */
    fun encodeBitmap(bitmap: Bitmap): ByteArray = encode(bitmap)

    private fun decode(context: Context, uri: Uri, maxSide: Int? = null): Bitmap {
        val resolver = context.contentResolver

        // Com inJustDecodeBounds o decodeStream sempre devolve null: o resultado está em `bounds`.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
            true
        } ?: false
        if (!opened) throw IOException("Não foi possível abrir a imagem")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Arquivo não é uma imagem válida")
        }

        // Decodifica já reduzida (entre 1024 e 2048 px no menor lado) para poupar memória.
        var sample = 1
        if (maxSide != null) {
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        } else {
            while (bounds.outWidth / (sample * 2) >= SIZE * 2 && bounds.outHeight / (sample * 2) >= SIZE * 2) {
                sample *= 2
            }
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Não foi possível ler a imagem")

        val rotation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: Exception) {
            0
        }
        if (rotation == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /**
     * Redimensiona para exatamente 512x512, sem manter a proporção (a imagem é esticada ou
     * comprimida), inclusive quando a original é menor que 512.
     */
    private fun resizeToSquare(source: Bitmap): Bitmap {
        val scaled = scaleSmooth(source, SIZE, SIZE)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Reduz pela metade em etapas, cada eixo por conta própria, para evitar serrilhado. */
    internal fun scaleSmooth(source: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var current = source
        while (current.width / 2 >= targetW || current.height / 2 >= targetH) {
            val w = if (current.width / 2 >= targetW) current.width / 2 else current.width
            val h = if (current.height / 2 >= targetH) current.height / 2 else current.height
            current = Bitmap.createScaledBitmap(current, w, h, true)
        }
        return if (current.width == targetW && current.height == targetH) {
            current
        } else {
            Bitmap.createScaledBitmap(current, targetW, targetH, true)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        var quality = 90
        while (quality >= 20) {
            val buffer = ByteArrayOutputStream()
            bitmap.compress(webpFormat(), quality, buffer)
            if (buffer.size() <= MAX_BYTES) return buffer.toByteArray()
            quality -= 10
        }
        throw IOException("Não consegui reduzir a figurinha para menos de 100 KB")
    }

    @Suppress("DEPRECATION")
    internal fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
}
