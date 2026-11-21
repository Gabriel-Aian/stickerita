package io.github.gabrielaian.stickerita

import java.text.BreakIterator

/** Divide o texto em "caracteres" visíveis (grafemas), para que um emoji composto conte como um só. */
fun splitGraphemes(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += text.substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

private fun looksLikeEmoji(grapheme: String): Boolean {
    var i = 0
    while (i < grapheme.length) {
        val cp = grapheme.codePointAt(i)
        if (Character.getType(cp) == Character.OTHER_SYMBOL.toInt()) return true
        i += Character.charCount(cp)
    }
    return false
}

/** Mantém no máximo 3 emojis válidos; se não sobrar nenhum, devolve o emoji padrão. */
fun sanitizeEmojis(input: String): String {
    val kept = splitGraphemes(input).filter { looksLikeEmoji(it) }.take(3)
    return if (kept.isEmpty()) DEFAULT_EMOJI else kept.joinToString("")
}

/** Formato esperado pelo WhatsApp na coluna sticker_emoji: emojis separados por vírgula. */
fun emojisForWhatsApp(stored: String): String =
    splitGraphemes(stored).filter { looksLikeEmoji(it) }.take(3)
        .ifEmpty { listOf(DEFAULT_EMOJI) }
        .joinToString(",")
