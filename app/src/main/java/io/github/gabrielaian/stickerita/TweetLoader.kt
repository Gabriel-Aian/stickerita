package io.github.gabrielaian.stickerita

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class TweetMedia(
    val kind: Kind,
    /** Arquivo da mídia (foto ou MP4; os GIFs do X também são MP4). */
    val url: String,
    /** Imagem de prévia para a tela de escolha. */
    val previewUrl: String?,
    /** Cabeçalhos exigidos para baixar (o TikTok pede navegador, origem e cookies). */
    val headers: Map<String, String> = emptyMap(),
) {
    enum class Kind { PHOTO, VIDEO, GIF }
}

/**
 * Lê as mídias de um post do X a partir do link compartilhado.
 *
 * O X não tem API gratuita, então a lista de mídias vem do serviço público api.fxtwitter.com
 * (FxEmbed). Só o número do post é enviado a ele; o download das mídias é direto dos servidores
 * do X (twimg.com). Nenhuma conta ou chave é usada.
 */
object TweetLoader {
    private val LINK = Regex(
        """https?://(?:www\.|mobile\.|m\.)?(?:x|twitter|fxtwitter|vxtwitter|fixupx|fixvx)\.com/(?:[A-Za-z0-9_]+/){1,2}status(?:es)?/(\d{5,25})""",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_DOWNLOAD_BYTES = 80L * 1024 * 1024

    /** Número do post, se o texto compartilhado tiver um link de post do X. */
    fun postId(text: String): String? = LINK.find(text)?.groupValues?.get(1)

    fun fetch(postId: String): List<TweetMedia> {
        var lastError: Exception? = null
        // v2 primeiro; o endereço antigo fica de reserva.
        for (endpoint in listOf("https://api.fxtwitter.com/2/status/$postId", "https://api.fxtwitter.com/i/status/$postId")) {
            try {
                val connection = open(endpoint)
                try {
                    when (val code = connection.responseCode) {
                        200 -> {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            return parse(JSONObject(body))
                        }
                        404 -> throw IOException("post não encontrado, apagado ou privado")
                        else -> throw IOException("serviço respondeu $code")
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
                if (e.message?.startsWith("post não") == true) break
            }
        }
        throw IOException(lastError?.message ?: "falha de conexão")
    }

    private fun parse(root: JSONObject): List<TweetMedia> {
        val post = root.optJSONObject("status") ?: root.optJSONObject("tweet")
            ?: throw IOException("resposta inesperada do serviço")
        val media = post.optJSONObject("media") ?: return emptyList()

        val items = media.optJSONArray("all") ?: JSONArray().also { merged ->
            for (key in listOf("photos", "videos")) {
                val array = media.optJSONArray(key) ?: continue
                for (i in 0 until array.length()) merged.put(array.get(i))
            }
        }

        val result = mutableListOf<TweetMedia>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isBlank() || !allowed(url)) continue
            val type = item.optString("type")
            val kind = when {
                type == "photo" -> TweetMedia.Kind.PHOTO
                type == "gif" -> TweetMedia.Kind.GIF
                type == "video" -> TweetMedia.Kind.VIDEO
                url.substringBefore('?').endsWith(".mp4") -> TweetMedia.Kind.VIDEO
                else -> TweetMedia.Kind.PHOTO
            }
            val preview = item.optString("thumbnail_url").takeIf { it.isNotBlank() && allowed(it) }
            result += TweetMedia(kind, if (kind == TweetMedia.Kind.PHOTO) photoUrl(url) else url, preview)
        }
        return result
    }

    /** Só baixamos de servidores de mídia do X, por HTTPS. */
    internal fun allowed(url: String): Boolean {
        val parsed = try { URL(url) } catch (e: Exception) { return false }
        return parsed.protocol == "https" && MEDIA_HOSTS.any { parsed.host == it || parsed.host.endsWith(".$it") }
    }

    /** Servidores de mídia do X e do TikTok. */
    private val MEDIA_HOSTS = listOf(
        "twimg.com",
        "tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com", "tiktokv.com", "tiktokv.us",
        "byteoversea.com", "ibytedtos.com", "ibyteimg.com", "muscdn.com",
    )

    /** Pede a foto em tamanho grande (até 2048 px), que sobra para os 512 da figurinha. */
    private fun photoUrl(url: String): String =
        if (url.contains("pbs.twimg.com/media") && !url.contains('?')) "$url?name=large" else url

    /**
     * Baixa a mídia para o cache e devolve um endereço que o resto do app lê normalmente
     * (a extensão faz o Android reconhecer se é imagem ou vídeo).
     */
    fun download(context: Context, media: TweetMedia, index: Int): Uri {
        if (!allowed(media.url)) throw IOException("endereço de mídia não permitido")

        val dir = File(context.cacheDir, "tweets").apply { mkdirs() }
        // limpa o que sobrou de usos antigos
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() }

        val extension = when (media.kind) {
            TweetMedia.Kind.PHOTO -> {
                val parsed = Uri.parse(media.url)
                val format = parsed.getQueryParameter("format")
                    ?: parsed.lastPathSegment?.substringAfterLast('.', "")
                format?.lowercase()?.takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
            }
            else -> "mp4"
        }
        val file = File(dir, "m_${System.nanoTime()}_$index.$extension")

        val connection = open(media.url)
        media.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        try {
            if (connection.responseCode != 200) throw IOException("o servidor da mídia respondeu ${connection.responseCode}")
            var total = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) throw IOException("arquivo grande demais")
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Stickerita")
        }
}
