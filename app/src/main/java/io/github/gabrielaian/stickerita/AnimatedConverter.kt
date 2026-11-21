package io.github.gabrielaian.stickerita

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Converte GIF ou vídeo em figurinha animada do WhatsApp:
 * WebP animado 512x512, até 10 s e até 500 KB, esticado sem manter a proporção.
 *
 * Os quadros são lidos uma vez (15 por segundo) e guardados em um arquivo temporário sem
 * compressão. Depois o app tenta combinações cada vez mais leves de qualidade e de quadros
 * por segundo até o resultado caber no limite.
 */
object AnimatedConverter {
    private const val SIZE = StickerConverter.SIZE
    private const val FRAME_BYTES = SIZE * SIZE * 4
    private const val BASE_FPS = 15
    const val MAX_DURATION_MS = 10_000
    private const val MAX_FRAMES = BASE_FPS * MAX_DURATION_MS / 1000

    /** O limite do WhatsApp é 500 KB; deixamos uma folga. */
    private const val MAX_BYTES = 495_000

    /**
     * @param step usa 1 a cada [step] quadros (15, 7,5, 5 ou 3,75 quadros por segundo)
     * @param scale resolução de trabalho: o quadro é reduzido e volta para 512x512, o que o deixa
     *   mais suave e muito mais leve de comprimir (o arquivo final continua 512x512)
     */
    private class Attempt(val step: Int, val scale: Float, val quality: Int)

    /** Do mais fiel ao mais leve; a primeira que couber nos 500 KB vence. */
    private val ATTEMPTS = listOf(
        Attempt(1, 1.00f, 75), Attempt(1, 1.00f, 55), Attempt(1, 0.75f, 55), Attempt(2, 1.00f, 55),
        Attempt(1, 0.75f, 40), Attempt(2, 0.75f, 50), Attempt(2, 0.50f, 55), Attempt(1, 0.50f, 45),
        Attempt(2, 0.50f, 40), Attempt(3, 0.50f, 45), Attempt(3, 0.50f, 30), Attempt(2, 0.375f, 40),
        Attempt(3, 0.375f, 35), Attempt(3, 0.375f, 25), Attempt(4, 0.375f, 30), Attempt(4, 0.30f, 25),
        Attempt(4, 0.30f, 15),
    )

    class Result(
        val webp: ByteArray,
        /** Primeiro quadro, como WebP estático (para o ícone do pacote e miniaturas). */
        val preview: ByteArray,
        /** O que foi reduzido para caber nos 500 KB (null = nada além da qualidade). */
        val note: String?,
    )

    private enum class Kind { GIF, VIDEO }

    /** Duração, em ms, do GIF ou do vídeo. */
    @Suppress("DEPRECATION")
    fun durationMs(context: Context, uri: Uri): Int {
        val kind = detectKind(context, uri)
            ?: throw IOException("Para figurinha animada, use um GIF ou um vídeo")
        val duration = when (kind) {
            Kind.GIF -> openMovie(context, uri).duration()
            Kind.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                } finally {
                    retriever.release()
                }
            }
        }
        if (duration <= 0) throw IOException("Não consegui ler a duração do arquivo")
        return duration
    }

    /** Quadro do GIF ou do vídeo no instante [timeMs], reduzido para a tela de recorte. */
    @Suppress("DEPRECATION")
    fun frameAt(context: Context, uri: Uri, timeMs: Int): Bitmap? = try {
        val full = when (detectKind(context, uri)) {
            Kind.GIF -> {
                val movie = openMovie(context, uri)
                val bitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
                movie.setTime(timeMs.coerceAtLeast(0) % movie.duration().coerceAtLeast(1))
                movie.draw(Canvas(bitmap), 0f, 0f)
                bitmap
            }
            Kind.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.getFrameAtTime(timeMs.toLong() * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }
            null -> null
        }
        full?.let {
            val scale = 240f / maxOf(it.width, it.height)
            if (scale >= 1f) it else Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1), true)
        }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun openMovie(context: Context, uri: Uri): Movie {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Não consegui abrir o GIF")
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Não consegui ler o GIF")
        if (movie.duration() <= 0 || movie.width() <= 0 || movie.height() <= 0) {
            throw IOException("O GIF não tem animação")
        }
        return movie
    }

    /** Converte o trecho de [startMs] a [endMs] (no máximo 10 s). */
    fun convert(context: Context, uri: Uri, startMs: Int, endMs: Int, onProgress: (String) -> Unit): Result {
        val kind = detectKind(context, uri)
            ?: throw IOException("Para figurinha animada, use um GIF ou um vídeo")

        val dir = File(context.cacheDir, "anim")
        dir.deleteRecursively()
        dir.mkdirs()
        val store = FrameStore(File(dir, "frames.raw"))
        try {
            when (kind) {
                Kind.GIF -> readGif(context, uri, startMs, endMs, store, onProgress)
                Kind.VIDEO -> readVideo(context, uri, startMs, endMs, store, onProgress)
            }
            if (store.count < 2) throw IOException("A animação precisa de pelo menos 2 quadros")
            return compress(store, opaque = kind == Kind.VIDEO, onProgress)
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    private fun detectKind(context: Context, uri: Uri): Kind? {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type == "image/gif") return Kind.GIF
        if (type.startsWith("video/")) return Kind.VIDEO
        // Alguns provedores não informam o tipo: confere o início do arquivo.
        val header = ByteArray(6)
        val read = context.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: return null
        return if (read == 6 && String(header, Charsets.US_ASCII).startsWith("GIF8")) Kind.GIF else null
    }

    // ---- leitura dos quadros ----

    @Suppress("DEPRECATION")
    private fun readGif(
        context: Context, uri: Uri, startMs: Int, endMs: Int, store: FrameStore, onProgress: (String) -> Unit,
    ) {
        val movie = openMovie(context, uri)
        val duration = movie.duration()

        val span = (endMs - startMs).coerceIn(1, MAX_DURATION_MS)
        val frames = (span * BASE_FPS / 1000).coerceIn(2, MAX_FRAMES)

        val source = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        val sourceCanvas = Canvas(source)
        val target = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val targetCanvas = Canvas(target)

        for (index in 0 until frames) {
            onProgress("lendo quadros ${index + 1}/$frames")
            source.eraseColor(Color.TRANSPARENT)
            movie.setTime(((startMs + frameTimeMs(index)) % duration).toInt())
            movie.draw(sourceCanvas, 0f, 0f)
            drawSquare(targetCanvas, target, source)
            store.append(target)
        }
    }

    private fun readVideo(
        context: Context, uri: Uri, startMs: Int, endMs: Int, store: FrameStore, onProgress: (String) -> Unit,
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val span = (endMs - startMs).coerceIn(1, MAX_DURATION_MS)
            val frames = (span * BASE_FPS / 1000).coerceIn(2, MAX_FRAMES)

            val target = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val targetCanvas = Canvas(target)

            for (index in 0 until frames) {
                onProgress("lendo quadros ${index + 1}/$frames")
                val frame = retriever.getFrameAtTime(
                    (startMs + frameTimeMs(index)) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: continue
                drawSquare(targetCanvas, target, frame)
                store.append(target)
            }
        } finally {
            retriever.release()
        }
    }

    /** Instante, em ms, do quadro [index] ao longo da animação. */
    private fun frameTimeMs(index: Int): Long = index * 1000L / BASE_FPS

    /** Estica a origem para 512x512 (sem manter proporção) sobre o fundo transparente. */
    private fun drawSquare(canvas: Canvas, target: Bitmap, source: Bitmap) {
        target.eraseColor(Color.TRANSPARENT)
        canvas.drawBitmap(StickerConverter.scaleSmooth(source, SIZE, SIZE), 0f, 0f, null)
    }

    // ---- compressão ----

    /** Codifica o quadro [index] segundo a tentativa: reduz, volta para 512x512 e comprime. */
    private fun encodeFrame(store: FrameStore, index: Int, attempt: Attempt, bitmap: Bitmap, opaque: Boolean): ByteArray {
        store.read(index, bitmap)
        // Vídeo não tem transparência: sem canal alfa o quadro fica menor.
        if (opaque) bitmap.setHasAlpha(false)

        var source = bitmap
        if (attempt.scale < 1f) {
            val reduced = ((SIZE * attempt.scale).toInt() / 2 * 2).coerceAtLeast(16)
            val small = Bitmap.createScaledBitmap(bitmap, reduced, reduced, true)
            source = Bitmap.createScaledBitmap(small, SIZE, SIZE, true)
            if (opaque) source.setHasAlpha(false)
        }
        val out = ByteArrayOutputStream()
        source.compress(StickerConverter.webpFormat(), attempt.quality, out)
        return out.toByteArray()
    }

    private fun compress(store: FrameStore, opaque: Boolean, onProgress: (String) -> Unit): Result {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

        for ((attemptIndex, attempt) in ATTEMPTS.withIndex()) {
            onProgress("compactando (${attemptIndex + 1}/${ATTEMPTS.size})")

            val indices = (0 until store.count step attempt.step).toList()
            if (indices.size < 2) continue

            // Estima o tamanho por amostra: pula as tentativas que claramente não cabem.
            val stride = maxOf(1, indices.size / 6)
            val sample = indices.filterIndexed { position, _ -> position % stride == 0 }.take(8)
            val average = sample.map { encodeFrame(store, it, attempt, bitmap, opaque).size + 32 }.average()
            if (average * indices.size > MAX_BYTES * 1.3) continue

            val stills = ArrayList<ByteArray>()
            val durations = ArrayList<Int>()
            var total = 0
            var fits = true
            for ((position, index) in indices.withIndex()) {
                val still = encodeFrame(store, index, attempt, bitmap, opaque)
                total += still.size + 32 // 32 = cabeçalhos do pedaço de quadro
                if (total > MAX_BYTES) {
                    fits = false
                    break
                }
                // A duração sai da diferença entre instantes, para somar exatamente o tempo real.
                val next = if (position + 1 < indices.size) indices[position + 1] else store.count
                stills += still
                durations += (frameTimeMs(next) - frameTimeMs(index)).toInt()
            }
            if (!fits) continue

            val webp = WebPAnimMuxer.mux(stills, durations, SIZE, SIZE)
            if (webp.size <= MAX_BYTES) return Result(webp, stills[0], describe(attempt))
        }
        throw IOException(
            "ficou pesada demais para o WhatsApp (limite de 500 KB) mesmo reduzida: use um trecho mais curto",
        )
    }

    /** Texto curto do que foi reduzido, ou null se só a qualidade mudou. */
    private fun describe(attempt: Attempt): String? {
        if (attempt.step == 1 && attempt.scale >= 1f) return null
        val fps = BASE_FPS.toFloat() / attempt.step
        val fpsText = if (fps % 1f == 0f) fps.toInt().toString() else String.format("%.1f", fps)
        val pixels = (SIZE * attempt.scale).toInt() / 2 * 2
        val parts = mutableListOf<String>()
        if (attempt.step > 1) parts += "$fpsText quadros/s"
        if (attempt.scale < 1f) parts += "resolução ${pixels}px"
        return "reduzida para caber nos 500 KB (${parts.joinToString(", ")})"
    }

    /** Arquivo temporário com os quadros 512x512 sem compressão, lidos por posição. */
    private class FrameStore(file: File) {
        private val raf = RandomAccessFile(file, "rw")
        private val channel = raf.channel
        private val buffer = ByteBuffer.allocateDirect(FRAME_BYTES)

        var count = 0
            private set

        fun append(bitmap: Bitmap) {
            buffer.clear()
            bitmap.copyPixelsToBuffer(buffer)
            buffer.rewind()
            while (buffer.hasRemaining()) channel.write(buffer)
            count++
        }

        fun read(index: Int, into: Bitmap) {
            buffer.clear()
            var position = index.toLong() * FRAME_BYTES
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) throw IOException("Quadro temporário ilegível")
                position += read
            }
            buffer.rewind()
            into.copyPixelsFromBuffer(buffer)
        }

        fun close() {
            channel.close()
            raf.close()
        }
    }
}
