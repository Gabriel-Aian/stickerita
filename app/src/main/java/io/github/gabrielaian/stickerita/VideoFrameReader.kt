package io.github.gabrielaian.stickerita

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException

/**
 * Lê os quadros de um vídeo decodificando-o uma única vez, em ordem (MediaCodec), em vez de pedir
 * cada quadro ao MediaMetadataRetriever, que a cada pedido volta ao quadro-chave anterior e
 * decodifica tudo de novo. Para 150 quadros, isso troca dezenas de segundos por poucos.
 *
 * Cada quadro sai já em 512x512 (esticado, como o resto do app), com cada pixel calculado a partir
 * de 4 amostras do original para não serrilhar. Se o aparelho não entregar imagens YUV comuns
 * (vídeos HDR de 10 bits, por exemplo), a função falha e quem chama usa o método lento.
 */
internal object VideoFrameReader {
    private const val SIZE = StickerConverter.SIZE
    private const val TIMEOUT_US = 10_000L
    private const val MAX_WAIT_NS = 90_000_000_000L

    /** Tolerância ao escolher o quadro de cada instante (meio quadro de um vídeo de 30 fps). */
    private const val SLACK_US = 20_000L

    /**
     * Entrega, por [onFrame], [frames] quadros a [fps] quadros por segundo a partir de [startMs].
     * O mesmo bitmap é reaproveitado: [onFrame] precisa copiá-lo ou guardá-lo na hora.
     * @return quantos quadros foram entregues (menos que [frames] se o vídeo acabou antes).
     */
    fun read(
        context: Context,
        uri: Uri,
        startMs: Int,
        frames: Int,
        fps: Int,
        onFrame: (Bitmap) -> Unit,
        onProgress: (Int) -> Unit,
    ): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    extractor.selectTrack(index)
                    format = candidate
                    break
                }
            }
            if (format == null) throw IOException("vídeo sem faixa de imagem")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("vídeo sem tipo")
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            fun targetUs(index: Int): Long = startMs * 1000L + index * 1_000_000L / fps

            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(SIZE * SIZE)
            val info = MediaCodec.BufferInfo()
            val begin = System.nanoTime()
            var inputDone = false
            var outputDone = false
            var next = 0

            while (!outputDone && next < frames) {
                if (System.nanoTime() - begin > MAX_WAIT_NS) throw IOException("a leitura do vídeo demorou demais")

                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("sem buffer de entrada")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out < 0) continue // nada pronto ainda, ou o formato de saída mudou

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                val pts = info.presentationTimeUs
                if (info.size > 0 && pts + SLACK_US >= targetUs(next)) {
                    val image = decoder.getOutputImage(out) ?: throw IOException("o decodificador não entregou imagem")
                    try {
                        if (image.format != ImageFormat.YUV_420_888) throw IOException("formato de imagem ${image.format}")
                        toPixels(image, rotation, pixels)
                    } finally {
                        image.close()
                    }
                    bitmap.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
                    // Um quadro pode servir a vários instantes seguidos (vídeo com menos de 15 quadros/s).
                    while (next < frames && targetUs(next) <= pts + SLACK_US) {
                        onFrame(bitmap)
                        next++
                        onProgress(next)
                    }
                }
                decoder.releaseOutputBuffer(out, false)
            }
            return next
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // já parado
            }
            codec?.release()
            extractor.release()
        }
    }

    /** Converte o quadro YUV para 512x512 ARGB, já girado conforme o vídeo pede. */
    private fun toPixels(image: Image, rotation: Int, out: IntArray) {
        val crop = image.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val planes = image.planes
        val yBuf = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vBuf = planes[2].buffer
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride

        val sideways = rotation == 90 || rotation == 270
        val uw = if (sideways) ch else cw // tamanho do quadro já na posição certa
        val uh = if (sideways) cw else ch

        // BT.709 para HD, BT.601 para o resto (faixa limitada), em ponto fixo com 8 bits.
        val hd = maxOf(cw, ch) >= 1280
        val rv = if (hd) 459 else 409
        val gu = if (hd) 55 else 100
        val gv = if (hd) 136 else 208
        val bu = if (hd) 541 else 516

        // Posições de amostra: 2 por eixo em cada pixel de saída (a 1/4 e a 3/4 dele).
        val xs = IntArray(SIZE * 2) { ((it / 2) * 4 + 1 + 2 * (it % 2)).toLong().times(uw).div(SIZE * 4L).toInt().coerceIn(0, uw - 1) }
        val ys = IntArray(SIZE * 2) { ((it / 2) * 4 + 1 + 2 * (it % 2)).toLong().times(uh).div(SIZE * 4L).toInt().coerceIn(0, uh - 1) }

        var o = 0
        for (dy in 0 until SIZE) {
            for (dx in 0 until SIZE) {
                var ySum = 0
                var cu = 0
                var cv = 0
                for (sy in 0..1) {
                    for (sx in 0..1) {
                        val ux = xs[dx * 2 + sx]
                        val uy = ys[dy * 2 + sy]
                        val px: Int
                        val py: Int
                        when (rotation) {
                            90 -> { px = uy; py = ch - 1 - ux }
                            180 -> { px = cw - 1 - ux; py = ch - 1 - uy }
                            270 -> { px = cw - 1 - uy; py = ux }
                            else -> { px = ux; py = uy }
                        }
                        val row = crop.top + py
                        val col = crop.left + px
                        ySum += yBuf.get(row * yRow + col * yPix).toInt() and 0xFF
                        if (sx == 1 && sy == 1) {
                            // a cor vem de uma só amostra por pixel de saída (o croma já é a meia resolução)
                            cu = uBuf.get((row shr 1) * uRow + (col shr 1) * uPix).toInt() and 0xFF
                            cv = vBuf.get((row shr 1) * vRow + (col shr 1) * vPix).toInt() and 0xFF
                        }
                    }
                }
                val c = ((ySum + 2) shr 2) - 16
                val y = 298 * (if (c < 0) 0 else c) + 128
                val u = cu - 128
                val v = cv - 128
                val r = (y + rv * v) shr 8
                val g = (y - gu * u - gv * v) shr 8
                val b = (y + bu * u) shr 8
                out[o++] = (0xFF shl 24) or
                    ((if (r < 0) 0 else if (r > 255) 255 else r) shl 16) or
                    ((if (g < 0) 0 else if (g > 255) 255 else g) shl 8) or
                    (if (b < 0) 0 else if (b > 255) 255 else b)
            }
        }
    }
}
