package io.github.gabrielaian.stickerita

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Destino do "Compartilhar": recebe imagens, GIFs e vídeos de qualquer app e abre uma janela
 * rápida para escolher (ou criar) o pacote, sem precisar abrir o Stickerita.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Não recebi nenhuma imagem ou vídeo", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            StickeritaTheme {
                ShareScreen(uris = uris, onFinish = { finish() })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val found = mutableListOf<Uri>()
        val tiramisu = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (tiramisu) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (uri != null) found += uri
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (tiramisu) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (list != null) found += list
            }
        }
        if (found.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) {
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let { found += it }
            }
        }
        return found.distinct().take(MAX_STICKERS)
    }
}

private enum class Source { IMAGE, GIF, VIDEO }

private fun sourceOf(context: Context, uri: Uri): Source {
    val type = context.contentResolver.getType(uri).orEmpty()
    return when {
        type.startsWith("video/") -> Source.VIDEO
        type == "image/gif" -> Source.GIF
        else -> Source.IMAGE
    }
}

@Composable
private fun ShareScreen(uris: List<Uri>, onFinish: () -> Unit, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("stickerita", Context.MODE_PRIVATE) }

    val sources = remember(uris) { uris.map { sourceOf(context, it) } }
    val hasVideo = sources.any { it == Source.VIDEO }
    // GIFs e vídeos só entram em pacotes animados; imagens comuns, em pacotes estáticos.
    val allMotion = sources.all { it != Source.IMAGE }
    val mixed = hasVideo && !allMotion

    var choice by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var author by remember(vm.publisher) { mutableStateOf(vm.publisher) }
    var submitting by remember { mutableStateOf(false) }
    var sawBusy by remember { mutableStateOf(false) }

    val eligible = vm.packs.filter {
        it.stickers.size < MAX_STICKERS && if (it.animated) allMotion else !hasVideo
    }
    val creatingNew = vm.loaded && (creating || eligible.isEmpty())
    val selectedId = choice
        ?: prefs.getString("last_pack", null)?.takeIf { id -> eligible.any { it.id == id } }
        ?: eligible.firstOrNull()?.id
    val busyNow = submitting || vm.busy

    // Terminou quando a importação começou, parou e não há recorte pendente.
    LaunchedEffect(vm.busy, vm.trim) {
        if (vm.busy) {
            sawBusy = true
        } else if (sawBusy && vm.trim == null) {
            vm.message?.let {
                Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show()
                vm.message = null
            }
            onFinish()
        }
    }

    fun start(packId: String, animated: Boolean) {
        prefs.edit().putString("last_pack", packId).apply()
        if (animated) vm.startAnimatedImport(packId, uris) else vm.addImages(packId, uris)
    }

    val canAdd = !busyNow && !mixed && vm.loaded &&
        if (creatingNew) name.isNotBlank() && author.isNotBlank() else selectedId != null

    AlertDialog(
        onDismissRequest = { if (!busyNow) onFinish() },
        title = { Text(if (uris.size == 1) "Criar figurinha" else "Criar ${uris.size} figurinhas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SharedThumb(uris.first(), sources.first())

                if (mixed) {
                    Text(
                        "Compartilhe só imagens ou só GIFs e vídeos de cada vez.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Column(
                    Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    eligible.forEach { pack ->
                        ChoiceRow(
                            selected = !creatingNew && selectedId == pack.id,
                            enabled = !busyNow,
                            label = "${pack.name} (${pack.stickers.size}/$MAX_STICKERS)" +
                                if (pack.animated) " · animado" else "",
                            onClick = {
                                choice = pack.id
                                creating = false
                            },
                        )
                    }
                    if (vm.loaded && !mixed) {
                        ChoiceRow(
                            selected = creatingNew,
                            enabled = !busyNow,
                            label = if (allMotion) "Novo pacote animado" else "Novo pacote",
                            onClick = { creating = true },
                        )
                    }
                }

                if (creatingNew && !mixed) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_TEXT) },
                        label = { Text("Nome do pacote") },
                        singleLine = true,
                        enabled = !busyNow,
                    )
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it.take(MAX_TEXT) },
                        label = { Text("Autor") },
                        singleLine = true,
                        enabled = !busyNow,
                    )
                }

                if (busyNow) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.busyText.isNotEmpty()) {
                        Text(vm.busyText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canAdd,
                onClick = {
                    submitting = true
                    if (creatingNew) {
                        vm.createPack(name, author, allMotion) { id -> start(id, allMotion) }
                    } else {
                        val pack = eligible.first { it.id == selectedId }
                        start(pack.id, pack.animated)
                    }
                },
            ) { Text("Adicionar") }
        },
        dismissButton = {
            TextButton(enabled = !busyNow, onClick = onFinish) { Text("Cancelar") }
        },
    )

    vm.trim?.let { request ->
        TrimDialog(
            request = request,
            onConfirm = { start, end -> vm.confirmTrim(start, end) },
            onSkip = { vm.skipTrim() },
            onCancel = { vm.cancelImport() },
        )
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Miniatura do primeiro arquivo compartilhado. */
@Composable
private fun SharedThumb(uri: Uri, source: Source) {
    val context = LocalContext.current
    val frame by produceState<Bitmap?>(null, uri) {
        if (source != Source.IMAGE) {
            value = withContext(Dispatchers.IO) { AnimatedConverter.frameAt(context, uri, 0) }
        }
    }
    Box(
        Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (source == Source.IMAGE) {
            AsyncImage(
                model = uri,
                imageLoader = rememberStickerLoader(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            frame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
