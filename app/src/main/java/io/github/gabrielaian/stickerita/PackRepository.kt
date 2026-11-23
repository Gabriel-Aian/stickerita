package io.github.gabrielaian.stickerita

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
private val SAFE_FILE_NAME = Regex("[A-Za-z0-9_.-]{1,80}")

/**
 * Guarda os pacotes no armazenamento privado do app:
 * files/packs/<id>/pack.json, tray.png e as figurinhas .webp.
 * É usado tanto pela interface quanto pelo ContentProvider (que roda em outra thread).
 */
class PackRepository(context: Context) {

    private val root = File(context.applicationContext.filesDir, "packs").apply { mkdirs() }

    fun list(): List<StickerPack> = synchronized(LOCK) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { readLocked(it.name) }
            .sortedByDescending { it.createdAt }
    }

    fun get(id: String): StickerPack? = synchronized(LOCK) { readLocked(id) }

    fun create(name: String, publisher: String, animated: Boolean = false): StickerPack = synchronized(LOCK) {
        val id = "stickerita_" + UUID.randomUUID().toString().replace("-", "").take(12)
        File(root, id).mkdirs()
        val pack = StickerPack(
            id = id,
            name = name.trim().take(MAX_TEXT),
            publisher = publisher.trim().take(MAX_TEXT),
            version = 1,
            createdAt = System.currentTimeMillis(),
            stickers = emptyList(),
            animated = animated,
        )
        write(pack)
        pack
    }

    fun delete(id: String) {
        synchronized(LOCK) {
            if (SAFE_ID.matches(id)) File(root, id).deleteRecursively()
        }
    }

    /**
     * @param preview primeiro quadro como WebP estático; só para pacotes animados, onde serve
     * de base para o ícone do pacote (o ícone precisa ser estático).
     */
    fun addSticker(id: String, webp: ByteArray, preview: ByteArray? = null): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        if (pack.stickers.size >= MAX_STICKERS) return@synchronized null
        val fileName = "s_" + UUID.randomUUID().toString().replace("-", "").take(10) + ".webp"
        File(File(root, id), fileName).writeBytes(webp)
        if (preview != null) File(File(root, id), fileName + PREVIEW_SUFFIX).writeBytes(preview)
        save(pack.copy(stickers = pack.stickers + Sticker(fileName, DEFAULT_EMOJI)))
    }

    fun removeSticker(id: String, fileName: String): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        if (pack.stickers.none { it.fileName == fileName }) return@synchronized pack
        File(File(root, id), fileName).delete()
        File(File(root, id), fileName + PREVIEW_SUFFIX).delete()
        save(pack.copy(stickers = pack.stickers.filterNot { it.fileName == fileName }))
    }

    fun setEmoji(id: String, fileName: String, emoji: String): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        val clean = sanitizeEmojis(emoji)
        save(
            pack.copy(
                stickers = pack.stickers.map {
                    if (it.fileName == fileName) it.copy(emoji = clean) else it
                },
            ),
        )
    }

    /**
     * Troca o ícone do pacote no app. Não mexe na versão do pacote: o WhatsApp não vê esse ícone.
     * Cada troca usa um nome novo, o que faz as miniaturas na tela serem recarregadas.
     */
    fun setIcon(id: String, png: ByteArray) {
        synchronized(LOCK) {
            if (!SAFE_ID.matches(id) || !File(root, id).isDirectory) return
            val dir = File(root, id)
            removeIcons(dir)
            File(dir, ICON_PREFIX + System.currentTimeMillis() + ".png").writeBytes(png)
        }
    }

    fun clearIcon(id: String) {
        synchronized(LOCK) {
            if (SAFE_ID.matches(id)) removeIcons(File(root, id))
        }
    }

    private fun removeIcons(dir: File) {
        dir.listFiles()?.filter { it.name.startsWith(ICON_PREFIX) }?.forEach { it.delete() }
    }

    /** Muda o nome e o autor do pacote. */
    fun rename(id: String, name: String, publisher: String): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        save(pack.copy(name = name.trim().take(MAX_TEXT), publisher = publisher.trim().take(MAX_TEXT)))
    }

    /** Coloca a figurinha em primeiro lugar, que é a que vira o ícone do pacote no WhatsApp. */
    fun moveToFront(id: String, fileName: String): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        val sticker = pack.stickers.firstOrNull { it.fileName == fileName } ?: return@synchronized pack
        save(pack.copy(stickers = listOf(sticker) + pack.stickers.filterNot { it.fileName == fileName }))
    }

    /**
     * Troca a imagem de uma figurinha (depois de editada), mantendo posição e emojis. A figurinha
     * ganha um arquivo novo, o que atualiza a miniatura na tela.
     */
    fun replaceSticker(id: String, fileName: String, webp: ByteArray): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        val old = pack.stickers.firstOrNull { it.fileName == fileName } ?: return@synchronized null
        val newName = "s_" + UUID.randomUUID().toString().replace("-", "").take(10) + ".webp"
        File(File(root, id), newName).writeBytes(webp)
        File(File(root, id), fileName).delete()
        File(File(root, id), fileName + PREVIEW_SUFFIX).delete()
        save(pack.copy(stickers = pack.stickers.map { if (it.fileName == fileName) Sticker(newName, old.emoji) else it }))
    }

    /** Cria um pacote inteiro de uma vez (importação). Cada item: imagem, prévia (animadas) e emojis. */
    fun importPack(
        name: String,
        publisher: String,
        animated: Boolean,
        items: List<Triple<ByteArray, ByteArray?, String>>,
    ): StickerPack = synchronized(LOCK) {
        val created = create(name, publisher, animated)
        val dir = File(root, created.id)
        val stickers = items.take(MAX_STICKERS).map { (webp, preview, emoji) ->
            val fileName = "s_" + UUID.randomUUID().toString().replace("-", "").take(10) + ".webp"
            File(dir, fileName).writeBytes(webp)
            if (preview != null) File(dir, fileName + PREVIEW_SUFFIX).writeBytes(preview)
            Sticker(fileName, sanitizeEmojis(emoji))
        }
        save(created.copy(stickers = stickers))
    }

    /** Arquivos do pacote para exportar: figurinhas e prévias. */
    fun packDirectory(id: String): File? = if (SAFE_ID.matches(id)) File(root, id) else null

    /** Arquivo de uma figurinha, para exibir na interface. */
    fun stickerFile(id: String, fileName: String): File = File(File(root, id), fileName)

    /** Arquivo servido ao WhatsApp. Só entrega o ícone e as figurinhas .webp do pacote. */
    fun assetFile(id: String, name: String): File? {
        if (!SAFE_ID.matches(id) || !SAFE_FILE_NAME.matches(name) || name.startsWith(".")) return null
        if (name != TRAY_FILE_NAME && !name.endsWith(".webp")) return null
        val file = File(File(root, id), name)
        return if (file.isFile) file else null
    }

    // ---- internos (sempre chamados com LOCK) ----

    private fun readLocked(id: String): StickerPack? {
        if (!SAFE_ID.matches(id)) return null
        val file = File(File(root, id), "pack.json")
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val array = json.getJSONArray("stickers")
            StickerPack(
                id = json.getString("id"),
                name = json.getString("name"),
                publisher = json.getString("publisher"),
                version = json.getInt("version"),
                createdAt = json.optLong("createdAt", 0L),
                stickers = (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    Sticker(item.getString("file"), item.optString("emoji", DEFAULT_EMOJI))
                },
                animated = json.optBoolean("animated", false),
                iconName = File(root, id).listFiles()?.firstOrNull { it.name.startsWith(ICON_PREFIX) }?.name,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Sobe a versão, refaz o ícone do pacote e grava. */
    private fun save(pack: StickerPack): StickerPack {
        val updated = pack.copy(version = pack.version + 1)
        val dir = File(root, updated.id)
        val tray = File(dir, TRAY_FILE_NAME)
        val first = updated.stickers.firstOrNull()
        if (first == null) {
            tray.delete()
        } else {
            try {
                // Em pacotes animados o ícone sai do primeiro quadro, que é estático.
                val source = if (updated.animated) File(dir, first.fileName + PREVIEW_SUFFIX) else File(dir, first.fileName)
                StickerConverter.writeTray(source, tray)
            } catch (e: Exception) {
                // sem ícone o pacote simplesmente não fica pronto para o WhatsApp
                tray.delete()
            }
        }
        write(updated)
        return updated
    }

    private fun write(pack: StickerPack) {
        val array = JSONArray()
        pack.stickers.forEach {
            array.put(JSONObject().put("file", it.fileName).put("emoji", it.emoji))
        }
        val json = JSONObject()
            .put("id", pack.id)
            .put("name", pack.name)
            .put("publisher", pack.publisher)
            .put("version", pack.version)
            .put("createdAt", pack.createdAt)
            .put("animated", pack.animated)
            .put("stickers", array)

        val dir = File(root, pack.id)
        val temp = File(dir, "pack.json.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(File(dir, "pack.json"))) {
            File(dir, "pack.json").writeText(json.toString())
            temp.delete()
        }
    }

    private companion object {
        val LOCK = Any()
        const val PREVIEW_SUFFIX = ".preview"
        const val ICON_PREFIX = "app_icon_"
    }
}
