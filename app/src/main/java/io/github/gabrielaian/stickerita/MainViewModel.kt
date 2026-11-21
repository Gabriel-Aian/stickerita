package io.github.gabrielaian.stickerita

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class Import(val added: Int, val failed: Int, val overflow: Int, val reason: String?)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PackRepository(app)
    private val prefs = app.getSharedPreferences("stickerita", Context.MODE_PRIVATE)

    var packs by mutableStateOf<List<StickerPack>>(emptyList())
        private set

    /** Pacote aberto na tela de detalhes (null = lista). */
    var selectedId by mutableStateOf<String?>(null)

    var busy by mutableStateOf(false)
        private set

    /** Mensagem curta para a barra de avisos; a interface a consome e zera. */
    var message by mutableStateOf<String?>(null)

    /** Último autor usado, para já vir preenchido ao criar outro pacote. */
    var publisher by mutableStateOf(prefs.getString("publisher", "") ?: "")
        private set

    val selected: StickerPack?
        get() = packs.firstOrNull { it.id == selectedId }

    /** Versão instalada, lida do próprio pacote. */
    val installedVersion: String = readInstalledVersion(app)

    /** Release mais nova encontrada no GitHub (null = nada novo). */
    var update by mutableStateOf<UpdateInfo?>(null)
        private set

    /** Progresso do download de 0 a 1 (null = não está baixando). */
    var updateProgress by mutableStateOf<Float?>(null)
        private set

    /** APK baixado, à espera de ser entregue ao instalador pela interface. */
    var apkToInstall by mutableStateOf<File?>(null)

    init {
        reload()
        checkForUpdate()
    }

    @Suppress("DEPRECATION")
    private fun readInstalledVersion(app: Application): String =
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"

    fun checkForUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { Updater.check(installedVersion) }
                update = found
                if (manual) {
                    message = if (found == null) {
                        "Você já está na versão mais recente ($installedVersion)"
                    } else {
                        "Nova versão ${found.version} disponível"
                    }
                }
            } catch (e: Exception) {
                if (manual) message = "Não consegui verificar atualizações (${e.message ?: e.javaClass.simpleName})"
            }
        }
    }

    fun startUpdate() {
        val info = update ?: return
        if (updateProgress != null) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            updateProgress = 0f
            try {
                apkToInstall = withContext(Dispatchers.IO) {
                    Updater.download(app, info) { updateProgress = it }
                }
            } catch (e: Exception) {
                message = "Falha ao baixar a atualização (${e.message ?: e.javaClass.simpleName})"
            }
            updateProgress = null
        }
    }

    fun stickerFile(pack: StickerPack, sticker: Sticker): File =
        repository.stickerFile(pack.id, sticker.fileName)

    fun reload() {
        viewModelScope.launch { reloadNow() }
    }

    private suspend fun reloadNow() {
        packs = withContext(Dispatchers.IO) { repository.list() }
    }

    fun createPack(name: String, author: String) {
        val cleanAuthor = author.trim()
        publisher = cleanAuthor
        prefs.edit().putString("publisher", cleanAuthor).apply()
        viewModelScope.launch {
            val pack = withContext(Dispatchers.IO) { repository.create(name, cleanAuthor) }
            reloadNow()
            selectedId = pack.id
        }
    }

    fun deletePack(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(id) }
            selectedId = null
            reloadNow()
        }
    }

    fun removeSticker(packId: String, sticker: Sticker) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.removeSticker(packId, sticker.fileName) }
            reloadNow()
        }
    }

    fun setEmoji(packId: String, sticker: Sticker, emoji: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setEmoji(packId, sticker.fileName, emoji) }
            reloadNow()
        }
    }

    fun addImages(packId: String, uris: List<Uri>) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                val room = MAX_STICKERS - (repository.get(packId)?.stickers?.size ?: 0)
                var added = 0
                var failed = 0
                var reason: String? = null
                uris.take(room.coerceAtLeast(0)).forEach { uri ->
                    try {
                        val webp = StickerConverter.toSticker(app, uri)
                        if (repository.addSticker(packId, webp) != null) {
                            added++
                        } else {
                            failed++
                            reason = "não consegui salvar no pacote"
                        }
                    } catch (e: Exception) {
                        failed++
                        reason = e.message ?: e.javaClass.simpleName
                    } catch (e: OutOfMemoryError) {
                        failed++
                        reason = "memória insuficiente"
                    }
                }
                Import(added, failed, uris.size - room.coerceAtLeast(0), reason)
            }
            reloadNow()
            busy = false

            val (added, failed, overflow, reason) = result
            val parts = mutableListOf<String>()
            if (added > 0) parts += if (added == 1) "1 figurinha adicionada" else "$added figurinhas adicionadas"
            if (failed > 0) {
                val label = if (failed == 1) "1 imagem falhou" else "$failed imagens falharam"
                parts += if (reason != null) "$label ($reason)" else label
            }
            if (overflow > 0) parts += "limite de $MAX_STICKERS por pacote"
            message = parts.joinToString(" · ").ifEmpty { null }
        }
    }
}
