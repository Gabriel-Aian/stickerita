package io.github.gabrielaian.stickerita

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

data class ChangelogEntry(val version: String, val items: List<String>)

/**
 * Notas de atualização. A fonte é o arquivo app/src/main/assets/changelog.md, que vai dentro do
 * APK (para mostrar o que mudou depois de atualizar) e também pode ser lido do GitHub (para
 * mostrar o que mudou antes de atualizar, sem precisar baixar o APK novo).
 *
 * Formato: "## 0.2.1" abre uma versão e cada linha "- texto" é uma novidade dela.
 */
object Changelog {
    private const val REMOTE =
        "https://raw.githubusercontent.com/Gabriel-Aian/stickerita/main/app/src/main/assets/changelog.md"

    fun parse(text: String): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var version: String? = null
        var items = mutableListOf<String>()

        fun close() {
            version?.let { entries += ChangelogEntry(it, items.toList()) }
            items = mutableListOf()
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    close()
                    version = line.removePrefix("## ").trim().removePrefix("v")
                }
                version != null && (line.startsWith("- ") || line.startsWith("* ")) ->
                    items += line.drop(2).trim()
            }
        }
        close()
        return entries
    }

    fun loadLocal(context: Context): List<ChangelogEntry> =
        try {
            parse(context.assets.open("changelog.md").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            emptyList()
        }

    /** Lê o histórico mais novo do GitHub; falha em silêncio devolvendo vazio. */
    fun fetchRemote(): List<ChangelogEntry> {
        val connection = (URL(REMOTE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (connection.responseCode != 200) emptyList()
            else parse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    /** Versões depois de [from] (exclusive) até [to] (inclusive), da mais nova para a mais antiga. */
    fun between(entries: List<ChangelogEntry>, from: String?, to: String): List<ChangelogEntry> =
        entries
            .filter {
                (from == null || Updater.compareVersions(it.version, from) > 0) &&
                    Updater.compareVersions(it.version, to) <= 0
            }
            .sortedWith { a, b -> Updater.compareVersions(b.version, a.version) }
}
