package io.github.gabrielaian.stickerita

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val version: String,
    val apkName: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Atualização direta pelas releases do GitHub. Única conexão de rede do app: as imagens
 * nunca são enviadas a lugar nenhum. O Android só aceita o APK novo se ele estiver assinado
 * com a mesma chave do app instalado, então um arquivo adulterado não consegue se instalar.
 */
object Updater {
    private const val LATEST_RELEASE = "https://api.github.com/repos/Gabriel-Aian/stickerita/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/Gabriel-Aian/stickerita/releases/download/"

    /** Devolve a release mais nova se ela for maior que a versão instalada; senão, null. */
    fun check(installedVersion: String): UpdateInfo? {
        val connection = open(LATEST_RELEASE).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            // 404 = ainda não há nenhuma release publicada.
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) throw IOException("GitHub respondeu ${connection.responseCode}")

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            if (compareVersions(tag, installedVersion) <= 0) return null

            val assets = json.getJSONArray("assets")
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.getString("name")
                val url = asset.getString("browser_download_url")
                if (name.endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX)) {
                    return UpdateInfo(tag.removePrefix("v"), name, url, asset.optLong("size"))
                }
            }
            // A release existe, mas o APK ainda está sendo anexado pelo CI.
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** Baixa o APK para a pasta de cache e devolve o arquivo. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        require(info.apkUrl.startsWith(DOWNLOAD_PREFIX)) { "Endereço de download inesperado" }

        val dir = File(context.cacheDir, "updates")
        dir.deleteRecursively()
        dir.mkdirs()
        val safeName = info.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, safeName)

        val connection = open(info.apkUrl)
        try {
            if (connection.responseCode != 200) throw IOException("Download falhou (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var lastPercent = -1
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            if (total > 0 && file.length() != total) throw IOException("Download incompleto")
        } finally {
            connection.disconnect()
        }
        return file
    }

    /** Intent que abre o instalador do Android para o APK baixado. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Compara versões como 0.1.2 e v0.1.10: positivo se a > b. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(version: String) = version.trim().removePrefix("v").split('.', '-')
            .map { piece -> piece.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

        val x = parts(a)
        val y = parts(b)
        for (index in 0 until maxOf(x.size, y.size)) {
            val diff = x.getOrElse(index) { 0 } - y.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun open(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Stickerita")
        }
}
