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

    fun create(name: String, publisher: String): StickerPack = synchronized(LOCK) {
        val id = "stickerita_" + UUID.randomUUID().toString().replace("-", "").take(12)
        File(root, id).mkdirs()
        val pack = StickerPack(
            id = id,
            name = name.trim().take(MAX_TEXT),
            publisher = publisher.trim().take(MAX_TEXT),
            version = 1,
            createdAt = System.currentTimeMillis(),
            stickers = emptyList(),
        )
        write(pack)
        pack
    }

    fun delete(id: String) {
        synchronized(LOCK) {
            if (SAFE_ID.matches(id)) File(root, id).deleteRecursively()
        }
    }

    fun addSticker(id: String, webp: ByteArray): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        if (pack.stickers.size >= MAX_STICKERS) return@synchronized null
        val fileName = "s_" + UUID.randomUUID().toString().replace("-", "").take(10) + ".webp"
        File(File(root, id), fileName).writeBytes(webp)
        save(pack.copy(stickers = pack.stickers + Sticker(fileName, DEFAULT_EMOJI)))
    }

    fun removeSticker(id: String, fileName: String): StickerPack? = synchronized(LOCK) {
        val pack = readLocked(id) ?: return@synchronized null
        if (pack.stickers.none { it.fileName == fileName }) return@synchronized pack
        File(File(root, id), fileName).delete()
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
                StickerConverter.writeTray(File(dir, first.fileName), tray)
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
    }
}
