package io.github.gabrielaian.stickerita

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pacote em um arquivo só (.stickerita), para mandar a outra pessoa e importar no Stickerita dela.
 * É um ZIP com pack.json (nome, autor, emojis) e as figurinhas em WebP. O WhatsApp não tem como
 * enviar um pacote de figurinhas a um contato, então este arquivo é o que vai na conversa.
 */
object PackArchive {
    const val EXTENSION = "stickerita"

    private const val FORMAT = 1
    private const val MAX_ENTRY_BYTES = 800_000L
    private const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
    private const val MAX_ENTRIES = 100

    /** Nomes de itens aceitos: nada de pastas nem de "..". */
    private val SAFE_ENTRY = Regex("[A-Za-z0-9_.-]{1,40}")

    /** Grava o pacote em um arquivo temporário e devolve-o. */
    fun export(context: Context, repository: PackRepository, pack: StickerPack): File {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }

        val baseName = pack.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "pacote" }
        val file = File(directory, "$baseName.$EXTENSION")
        val source = repository.packDirectory(pack.id) ?: throw IOException("pacote inválido")

        val stickers = JSONArray()
        pack.stickers.forEachIndexed { index, sticker ->
            stickers.put(JSONObject().put("file", "$index.webp").put("emoji", sticker.emoji))
        }
        val meta = JSONObject()
            .put("format", FORMAT)
            .put("name", pack.name)
            .put("publisher", pack.publisher)
            .put("animated", pack.animated)
            .put("stickers", stickers)

        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(meta.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            pack.stickers.forEachIndexed { index, sticker ->
                zip.putNextEntry(ZipEntry("$index.webp"))
                File(source, sticker.fileName).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                // pacotes animados guardam o primeiro quadro, que vira o ícone
                val preview = File(source, sticker.fileName + ".preview")
                if (pack.animated && preview.isFile) {
                    zip.putNextEntry(ZipEntry("$index.preview"))
                    preview.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return file
    }

    /** Lê um arquivo .stickerita e cria o pacote. Confere tudo antes: o arquivo vem de fora. */
    fun import(context: Context, repository: PackRepository, uri: Uri): StickerPack {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("não consegui abrir o arquivo")
        stream.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_ENTRIES) throw IOException("arquivo com itens demais")
                    if (entry.isDirectory || !SAFE_ENTRY.matches(entry.name)) continue
                    val data = readLimited(zip, MAX_ENTRY_BYTES)
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) throw IOException("arquivo grande demais")
                    entries[entry.name] = data
                }
            }
        }

        val meta = try {
            JSONObject(String(entries["pack.json"] ?: throw IOException("não é um pacote do Stickerita"), Charsets.UTF_8))
        } catch (e: org.json.JSONException) {
            throw IOException("pacote danificado")
        }
        val name = meta.optString("name").trim().take(MAX_TEXT)
        val publisher = meta.optString("publisher").trim().take(MAX_TEXT)
        if (name.isEmpty() || publisher.isEmpty()) throw IOException("o pacote não tem nome ou autor")
        val animated = meta.optBoolean("animated", false)

        val list = meta.optJSONArray("stickers") ?: throw IOException("pacote sem figurinhas")
        if (list.length() > MAX_STICKERS) throw IOException("pacote com mais de $MAX_STICKERS figurinhas")

        val items = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index) ?: throw IOException("pacote danificado")
            val file = item.optString("file")
            val webp = entries[file] ?: throw IOException("figurinha ausente no arquivo")
            if (!isWebp(webp)) throw IOException("uma das figurinhas não é uma imagem WebP")
            val preview = entries[file.removeSuffix(".webp") + ".preview"]
            if (animated && (preview == null || !isWebp(preview))) throw IOException("pacote animado sem ícone")
            Triple(webp, if (animated) preview else null, item.optString("emoji", DEFAULT_EMOJI))
        }
        return repository.importPack(name, publisher, animated, items)
    }

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size > 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("item grande demais no arquivo")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
