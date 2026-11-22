package io.github.gabrielaian.stickerita

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * EXPERIMENTAL. Lê a mídia de um post público do TikTok a partir do link compartilhado, sem login.
 *
 * O TikTok não tem API aberta para isso. A página do post traz, embutido, um bloco de dados
 * (`__UNIVERSAL_DATA_FOR_REHYDRATION__`) com os endereços do vídeo ou das fotos; o app lê esse bloco
 * no próprio aparelho, sem passar por nenhum serviço de terceiros. Se o TikTok mudar a página ou pedir
 * verificação, a leitura falha e o app mostra o motivo.
 */
object TikTokLoader {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    private val LINK = Regex("""https?://(?:[A-Za-z0-9-]+\.)?tiktok\.com/[^\s]*""", RegexOption.IGNORE_CASE)

    /** Primeiro link do TikTok no texto compartilhado, ou null. */
    fun findLink(text: String): String? =
        LINK.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';')

    fun fetch(link: String): List<TweetMedia> {
        val cookies = linkedMapOf<String, String>()
        var url = link
        var html: String? = null

        // Os links curtos (vm.tiktok.com, vt.tiktok.com) redirecionam; seguimos à mão para guardar os
        // cookies que o TikTok manda e exigir que o caminho fique sempre dentro do tiktok.com.
        for (hop in 0 until 6) {
            if (!isTikTokHost(url)) throw IOException("o link saiu do TikTok")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookieHeader(cookies))
            }
            try {
                val code = connection.responseCode
                collectCookies(connection, cookies)
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("redirecionamento sem destino")
                    url = URL(URL(url), location).toString()
                    continue
                }
                if (code != 200) throw IOException("o TikTok respondeu $code")
                html = connection.inputStream.bufferedReader().use { it.readText() }
                break
            } finally {
                connection.disconnect()
            }
        }
        val page = html ?: throw IOException("redirecionamentos demais")
        return parse(page, cookies)
    }

    private fun parse(html: String, cookies: Map<String, String>): List<TweetMedia> {
        val marker = html.indexOf("__UNIVERSAL_DATA_FOR_REHYDRATION__")
        if (marker < 0) {
            throw IOException("o TikTok não entregou os dados do post (pode ter pedido verificação)")
        }
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) throw IOException("página do TikTok em formato inesperado")

        val scope = JSONObject(html.substring(start, end)).optJSONObject("__DEFAULT_SCOPE__")
            ?: throw IOException("página do TikTok em formato inesperado")
        val detail = scope.optJSONObject("webapp.video-detail")
            ?: throw IOException("post não encontrado")
        when (val status = detail.optInt("statusCode", 0)) {
            0 -> Unit
            10204 -> throw IOException("post não encontrado ou apagado")
            10222 -> throw IOException("post privado")
            else -> throw IOException("o TikTok recusou o post (código $status)")
        }
        val item = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: throw IOException("post sem dados")

        // O download só funciona com o mesmo navegador, os cookies e a origem da página.
        val headers = buildMap {
            put("User-Agent", USER_AGENT)
            put("Referer", "https://www.tiktok.com/")
            if (cookies.isNotEmpty()) put("Cookie", cookieHeader(cookies))
        }

        // Post de fotos (carrossel)
        val images = item.optJSONObject("imagePost")?.optJSONArray("images")
        if (images != null && images.length() > 0) {
            val photos = mutableListOf<TweetMedia>()
            for (i in 0 until images.length()) {
                val url = images.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0)
                if (!url.isNullOrBlank()) photos += TweetMedia(TweetMedia.Kind.PHOTO, checked(url), url, headers)
            }
            if (photos.isNotEmpty()) return photos
        }

        // Post de vídeo: playAddr costuma vir sem marca d'água; downloadAddr é o de reserva.
        val video = item.optJSONObject("video") ?: throw IOException("este post não tem vídeo nem fotos")
        var url = video.optString("playAddr")
        if (url.isBlank()) {
            url = video.optJSONArray("bitrateInfo")?.optJSONObject(0)
                ?.optJSONObject("PlayAddr")?.optJSONArray("UrlList")?.optString(0).orEmpty()
        }
        if (url.isBlank()) url = video.optString("downloadAddr")
        if (url.isBlank()) throw IOException("o TikTok não informou o endereço do vídeo")

        val preview = video.optString("cover").ifBlank { video.optString("originCover") }.takeIf { it.isNotBlank() }
        return listOf(TweetMedia(TweetMedia.Kind.VIDEO, checked(url), preview, headers))
    }

    /** Só aceitamos endereços de mídia em servidores conhecidos do TikTok, por HTTPS. */
    private fun checked(url: String): String {
        if (!TweetLoader.allowed(url)) {
            val host = try { URL(url).host } catch (e: Exception) { "?" }
            throw IOException("servidor de mídia desconhecido ($host)")
        }
        return url
    }

    private fun isTikTokHost(url: String): Boolean {
        val parsed = try { URL(url) } catch (e: Exception) { return false }
        return parsed.protocol == "https" && (parsed.host == "tiktok.com" || parsed.host.endsWith(".tiktok.com"))
    }

    private fun collectCookies(connection: HttpURLConnection, into: MutableMap<String, String>) {
        for ((key, values) in connection.headerFields) {
            if (key == null || !key.equals("Set-Cookie", ignoreCase = true)) continue
            for (value in values) {
                val pair = value.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty() && pair.contains('=')) into[name] = pair.substringAfter('=').trim()
            }
        }
    }

    private fun cookieHeader(cookies: Map<String, String>) = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
