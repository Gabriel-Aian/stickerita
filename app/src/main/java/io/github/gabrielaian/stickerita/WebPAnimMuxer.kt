package io.github.gabrielaian.stickerita

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * O Android não tem codificador de WebP animado. Esta classe monta o contêiner animado
 * (VP8X + ANIM + um ANMF por quadro) reaproveitando os dados de imagem de WebPs estáticos
 * gerados por Bitmap.compress. Formato: https://developers.google.com/speed/webp/docs/riff_container
 */
object WebPAnimMuxer {

    private class Chunk(val fourcc: String, val payload: ByteArray)

    /**
     * @param stills WebPs estáticos, todos width x height
     * @param durationsMs duração de cada quadro, em milissegundos
     */
    fun mux(stills: List<ByteArray>, durationsMs: List<Int>, width: Int, height: Int): ByteArray {
        require(stills.isNotEmpty() && stills.size == durationsMs.size) { "Quadros e durações não batem" }

        var hasAlpha = false
        val frames = ByteArrayOutputStream()
        for (index in stills.indices) {
            val chunks = imageChunks(stills[index])
            if (chunks.any { it.fourcc == "ALPH" || it.fourcc == "VP8L" }) hasAlpha = true

            val anmf = ByteArrayOutputStream()
            anmf.write24(0) // posição X / 2
            anmf.write24(0) // posição Y / 2
            anmf.write24(width - 1)
            anmf.write24(height - 1)
            anmf.write24(durationsMs[index])
            anmf.write(0x02) // sem mistura com o quadro anterior, sem descarte
            chunks.forEach { anmf.writeChunk(it.fourcc, it.payload) }
            frames.writeChunk("ANMF", anmf.toByteArray())
        }

        val vp8x = ByteArrayOutputStream()
        vp8x.write((if (hasAlpha) 0x10 else 0x00) or 0x02) // alfa e animação
        vp8x.write24(0) // reservado
        vp8x.write24(width - 1)
        vp8x.write24(height - 1)

        val anim = ByteArrayOutputStream()
        anim.write32(0) // cor de fundo: transparente
        anim.write(0) // repetições (0 = infinito), 16 bits
        anim.write(0)

        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        body.writeChunk("VP8X", vp8x.toByteArray())
        body.writeChunk("ANIM", anim.toByteArray())
        body.write(frames.toByteArray())

        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write32(body.size())
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    /** Extrai de um WebP estático só os pedaços com os dados da imagem (ALPH, VP8, VP8L). */
    private fun imageChunks(webp: ByteArray): List<Chunk> {
        if (webp.size < 20 ||
            String(webp, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(webp, 8, 4, Charsets.US_ASCII) != "WEBP"
        ) {
            throw IOException("Quadro WebP inválido")
        }

        val result = mutableListOf<Chunk>()
        var position = 12
        while (position + 8 <= webp.size) {
            val fourcc = String(webp, position, 4, Charsets.US_ASCII)
            val size = readLe32(webp, position + 4)
            val start = position + 8
            val end = start + size
            if (size < 0 || end > webp.size) throw IOException("Quadro WebP truncado")
            if (fourcc == "ALPH" || fourcc == "VP8 " || fourcc == "VP8L") {
                result += Chunk(fourcc, webp.copyOfRange(start, end))
            }
            position = end + (size and 1) // pedaços de tamanho ímpar têm 1 byte de enchimento
        }
        if (result.none { it.fourcc == "VP8 " || it.fourcc == "VP8L" }) {
            throw IOException("Quadro WebP sem imagem")
        }
        return result
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArrayOutputStream.write24(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
    }

    private fun ByteArrayOutputStream.write32(value: Int) {
        write24(value)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeChunk(fourcc: String, payload: ByteArray) {
        write(fourcc.toByteArray(Charsets.US_ASCII))
        write32(payload.size)
        write(payload)
        if (payload.size % 2 == 1) write(0)
    }
}
