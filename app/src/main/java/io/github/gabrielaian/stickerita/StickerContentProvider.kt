package io.github.gabrielaian.stickerita

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Implementa o contrato de sticker packs do WhatsApp:
 *   /metadata                      -> todos os pacotes prontos
 *   /metadata/<id>                 -> um pacote
 *   /stickers/<id>                 -> as figurinhas do pacote
 *   /stickers_asset/<id>/<arquivo> -> o arquivo da figurinha ou o ícone do pacote
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var repository: PackRepository
    private lateinit var authority: String
    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authority = ctx.packageName + PROVIDER_SUFFIX
        repository = PackRepository(ctx)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "metadata", METADATA)
            addURI(authority, "metadata/*", METADATA_SINGLE)
            addURI(authority, "stickers/*", STICKERS)
            addURI(authority, "stickers_asset/*/*", STICKER_FILE)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val cursor = when (matcher.match(uri)) {
            METADATA -> packsCursor(repository.list().filter { it.isReady })
            METADATA_SINGLE -> packsCursor(
                listOfNotNull(uri.lastPathSegment?.let { repository.get(it) }?.takeIf { it.isReady }),
            )
            STICKERS -> stickersCursor(uri.lastPathSegment?.let { repository.get(it) })
            else -> throw IllegalArgumentException("URI desconhecida: $uri")
        }
        context?.let { cursor.setNotificationUri(it.contentResolver, uri) }
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != STICKER_FILE) throw FileNotFoundException("URI inválida: $uri")
        val segments = uri.pathSegments
        val file = repository.assetFile(segments[1], segments[2])
            ?: throw FileNotFoundException("Arquivo não encontrado: $uri")
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        METADATA -> "vnd.android.cursor.dir/vnd.$authority.metadata"
        METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.metadata"
        STICKERS -> "vnd.android.cursor.dir/vnd.$authority.stickers"
        STICKER_FILE -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("URI desconhecida: $uri")
    }

    private fun packsCursor(packs: List<StickerPack>): MatrixCursor {
        val cursor = MatrixCursor(PACK_COLUMNS)
        packs.forEach { pack ->
            cursor.newRow()
                .add(pack.id)
                .add(pack.name)
                .add(pack.publisher)
                .add(TRAY_FILE_NAME)
                .add("") // android_play_store_link
                .add("") // ios_app_download_link
                .add("") // sticker_pack_publisher_email
                .add("") // sticker_pack_publisher_website
                .add("") // sticker_pack_privacy_policy_website
                .add("") // sticker_pack_license_agreement_website
                .add(pack.version.toString())
                .add(0) // whatsapp_will_not_cache_stickers
                .add(0) // animated_sticker_pack
        }
        return cursor
    }

    private fun stickersCursor(pack: StickerPack?): MatrixCursor {
        val cursor = MatrixCursor(STICKER_COLUMNS)
        pack?.stickers?.forEach { sticker ->
            cursor.newRow()
                .add(sticker.fileName)
                .add(emojisForWhatsApp(sticker.emoji))
                .add("Figurinha")
        }
        return cursor
    }

    // Somente leitura.
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Somente leitura")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Somente leitura")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Somente leitura")

    private companion object {
        const val METADATA = 1
        const val METADATA_SINGLE = 2
        const val STICKERS = 3
        const val STICKER_FILE = 4

        val PACK_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack",
        )

        val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text",
        )
    }
}
