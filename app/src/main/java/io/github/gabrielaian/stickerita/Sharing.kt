package io.github.gabrielaian.stickerita

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

/** Intents para mandar um pacote ou o próprio app a outra pessoa. */
object Sharing {
    const val RELEASES_URL = "https://github.com/Gabriel-Aian/stickerita/releases/latest"

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    private fun whatsAppPackage(context: Context): String? = WHATSAPP_PACKAGES.firstOrNull {
        try {
            context.packageManager.getPackageInfo(it, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /**
     * Envia o arquivo do pacote. Pelo WhatsApp quando ele está instalado; se [phone] vier, a
     * conversa com esse número já abre direto (só dígitos, com DDD; sem código do país, vale o do Brasil).
     */
    fun packIntent(context: Context, file: File, pack: StickerPack, viaWhatsApp: Boolean, phone: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(
                Intent.EXTRA_TEXT,
                "Pacote de figurinhas “${pack.name}”. Para usar: instale o Stickerita ($RELEASES_URL), " +
                    "toque em ⋮ › Importar pacote e escolha este arquivo.",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val whatsApp = if (viaWhatsApp) whatsAppPackage(context) else null
        if (whatsApp == null) return Intent.createChooser(send, "Compartilhar pacote")

        send.setPackage(whatsApp)
        val digits = phone.orEmpty().filter { it.isDigit() }.trimStart('0')
        if (digits.length >= 10) {
            val full = if (digits.length <= 11) "55$digits" else digits
            send.putExtra("jid", "$full@s.whatsapp.net")
        }
        return send
    }

    /** Texto com o link da versão mais recente. */
    fun appLinkIntent(): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "Stickerita: transforma imagens, GIFs e vídeos em figurinhas do WhatsApp, sem servidor. " +
                    "Baixe a versão mais recente: $RELEASES_URL",
            )
        }
        return Intent.createChooser(send, "Compartilhar o Stickerita")
    }

    /** Copia o APK instalado para o cache (o arquivo original fica em área protegida). */
    fun copyInstalledApk(context: Context, versionName: String): File {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "Stickerita-$versionName.apk")
        File(context.applicationInfo.sourceDir).copyTo(target, overwrite = true)
        return target
    }

    fun apkIntent(context: Context, apk: File): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, apk))
            putExtra(Intent.EXTRA_TEXT, "Stickerita: instalador (APK). Versão mais recente: $RELEASES_URL")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartilhar o Stickerita")
    }
}
