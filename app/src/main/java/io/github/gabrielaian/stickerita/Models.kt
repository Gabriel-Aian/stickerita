package io.github.gabrielaian.stickerita

/** Limites do contrato de sticker packs do WhatsApp. */
const val MIN_STICKERS = 3
const val MAX_STICKERS = 30
const val MAX_TEXT = 50

const val TRAY_FILE_NAME = "tray.png"
const val DEFAULT_EMOJI = "😀"
const val PROVIDER_SUFFIX = ".stickercontentprovider"

data class Sticker(
    val fileName: String,
    /** Até 3 emojis colados, sem separador (ex.: "😀🎉"). */
    val emoji: String,
)

data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String,
    /** Sobe a cada alteração para o WhatsApp saber que precisa recarregar o pacote. */
    val version: Int,
    val createdAt: Long,
    val stickers: List<Sticker>,
    /** Pacote de figurinhas animadas. O WhatsApp não aceita pacotes que misturam os dois tipos. */
    val animated: Boolean = false,
) {
    val isReady: Boolean
        get() = stickers.size in MIN_STICKERS..MAX_STICKERS
}
