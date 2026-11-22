package io.github.gabrielaian.stickerita

/** Limites do contrato de sticker packs do WhatsApp. */
const val MIN_STICKERS = 3
const val MAX_STICKERS = 30
const val MAX_TEXT = 50

const val TRAY_FILE_NAME = "tray.png"
const val DEFAULT_EMOJI = "😀"
const val PROVIDER_SUFFIX = ".stickercontentprovider"

/** Como a lista de pacotes é mostrada. */
enum class PackView(val label: String) { LIST("Lista"), GRID("Grade") }

enum class PackFilter(val label: String) {
    ALL("Todos"),
    STATIC("Estáticos"),
    ANIMATED("Animados"),
}

enum class PackSort(val label: String, val short: String) {
    RECENT("Mais recentes primeiro", "Recentes"),
    NAME_ASC("Nome (A–Z)", "A–Z"),
    NAME_DESC("Nome (Z–A)", "Z–A"),
}

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
    /**
     * Quando o ícone do pacote no app foi trocado (0 = sem ícone próprio). Vale só dentro do
     * Stickerita: nunca vai para o WhatsApp. Muda a cada troca para a miniatura se atualizar.
     */
    val iconStamp: Long = 0L,
) {
    val isReady: Boolean
        get() = stickers.size in MIN_STICKERS..MAX_STICKERS

    val hasIcon: Boolean
        get() = iconStamp != 0L
}
