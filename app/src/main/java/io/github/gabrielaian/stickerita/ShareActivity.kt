package io.github.gabrielaian.stickerita

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Destino do "Compartilhar": recebe imagens, GIFs e vídeos de qualquer app e abre uma janela
 * rápida para escolher (ou criar) o pacote, sem precisar abrir o Stickerita.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeSettings.ensureLoaded(this)

        // O app do X compartilha só o link do post: lemos as mídias dele e deixamos você escolher.
        if (intent.type?.startsWith("text/") == true) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val postId = TweetLoader.postId(text)
            if (postId == null) {
                Toast.makeText(this, "Isso não parece um link de post do X", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            setContent {
                StickeritaTheme {
                    PostFlow(postId = postId, onFinish = { finish() })
                }
            }
            return
        }

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
    var finishing by remember { mutableStateOf(false) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var countBefore by remember { mutableStateOf(0) }
    var sendToWhatsApp by remember { mutableStateOf(prefs.getBoolean("send_to_whatsapp", true)) }

    val whatsApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val error = result.data?.getStringExtra("validation_error")
        val text = when {
            result.resultCode == Activity.RESULT_OK -> "Pacote atualizado no WhatsApp!"
            error != null -> "O WhatsApp recusou o pacote: $error"
            else -> null
        }
        text?.let { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() }
        onFinish()
    }

    val eligible = vm.packs.filter {
        it.stickers.size < MAX_STICKERS && if (it.animated) allMotion else !hasVideo
    }
    val creatingNew = vm.loaded && (creating || eligible.isEmpty())
    val selectedId = choice
        ?: prefs.getString("last_pack", null)?.takeIf { id -> eligible.any { it.id == id } }
        ?: eligible.firstOrNull()?.id
    val busyNow = submitting || vm.busy

    // Terminou quando a importação começou, parou e não há recorte pendente.
    LaunchedEffect(vm.busy, vm.trim, vm.edit) {
        if (vm.busy) {
            sawBusy = true
        } else if (sawBusy && vm.trim == null && vm.edit == null && !finishing) {
            finishing = true
            val pack = vm.packs.firstOrNull { it.id == targetId }
            val grew = pack != null && pack.stickers.size > countBefore
            var text = vm.message
            vm.message = null
            if (pack != null && grew && !pack.isReady) {
                val missing = MIN_STICKERS - pack.stickers.size
                text = listOfNotNull(text, "faltam $missing para enviar ao WhatsApp").joinToString(" · ")
            }
            text?.let { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() }

            if (pack != null && grew && pack.isReady && sendToWhatsApp) {
                // O WhatsApp sempre pede a sua confirmação para adicionar ou atualizar o pacote.
                try {
                    whatsApp.launch(whatsAppIntent(context, pack))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context.applicationContext, "WhatsApp não encontrado", Toast.LENGTH_LONG).show()
                    onFinish()
                }
            } else {
                onFinish()
            }
        }
    }

    fun start(packId: String, animated: Boolean) {
        prefs.edit().putString("last_pack", packId).apply()
        targetId = packId
        countBefore = vm.packs.firstOrNull { it.id == packId }?.stickers?.size ?: 0
        when {
            animated -> vm.startAnimatedImport(packId, uris, offerWhatsApp = sendToWhatsApp)
            // Uma imagem só abre o editor (recorte, legenda, emojis); várias entram direto.
            uris.size == 1 -> vm.startEdit(packId, uris.first())
            else -> vm.addImages(packId, uris)
        }
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

                if (!mixed) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busyNow) {
                                sendToWhatsApp = !sendToWhatsApp
                                prefs.edit().putBoolean("send_to_whatsapp", sendToWhatsApp).apply()
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = sendToWhatsApp,
                            onCheckedChange = null,
                            enabled = !busyNow,
                            modifier = Modifier.padding(12.dp),
                        )
                        Text(
                            if (allMotion) {
                                "Avisar para atualizar no WhatsApp quando terminar (com $MIN_STICKERS ou mais figurinhas)"
                            } else {
                                "Atualizar no WhatsApp em seguida (com $MIN_STICKERS ou mais figurinhas)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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

    ImageEditorHost(vm)
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


// ---------------------------------------------------------------------------------------------
// Link de post do X
// ---------------------------------------------------------------------------------------------

private sealed interface TweetState {
    data object Loading : TweetState
    data class Loaded(val media: List<TweetMedia>) : TweetState
    data class Failed(val reason: String) : TweetState
}

/** Busca as mídias do post do X, deixa escolher quais viram figurinha e segue para a janela normal. */
@Composable
private fun PostFlow(postId: String, onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<TweetState>(TweetState.Loading) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloaded by remember { mutableStateOf<List<Uri>?>(null) }

    LaunchedEffect(postId) {
        state = try {
            val media = withContext(Dispatchers.IO) { TweetLoader.fetch(postId) }
            if (media.size == 1) selected = setOf(0)
            TweetState.Loaded(media)
        } catch (e: Exception) {
            TweetState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    downloaded?.let { uris ->
        ShareScreen(uris = uris, onFinish = onFinish)
        return
    }

    val current = state
    AlertDialog(
        onDismissRequest = { if (!downloading) onFinish() },
        title = { Text("Mídias do post") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (current) {
                    TweetState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Buscando o post…", Modifier.padding(start = 12.dp))
                    }
                    is TweetState.Failed -> Text(
                        "Não consegui ler o post (${current.reason}).",
                        color = MaterialTheme.colorScheme.error,
                    )
                    is TweetState.Loaded -> {
                        if (current.media.isEmpty()) {
                            Text("Este post não tem imagens, GIFs ou vídeos que eu consiga baixar.")
                        } else {
                            if (current.media.size > 1) {
                                Text(
                                    "Toque nas mídias que quer transformar em figurinha.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Column(
                                Modifier
                                    .heightIn(max = 340.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                current.media.withIndex().chunked(2).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { (index, media) ->
                                            MediaTile(
                                                media = media,
                                                selected = index in selected,
                                                enabled = !downloading,
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    selected = if (index in selected) selected - index else selected + index
                                                },
                                            )
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (downloading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Baixando…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = current is TweetState.Loaded && selected.isNotEmpty() && !downloading,
                onClick = {
                    val loaded = current as? TweetState.Loaded
                    if (loaded != null) {
                        scope.launch {
                            downloading = true
                            error = null
                            try {
                                val picked = loaded.media.filterIndexed { index, _ -> index in selected }
                                downloaded = withContext(Dispatchers.IO) {
                                    picked.mapIndexed { index, media -> TweetLoader.download(context, media, index) }
                                }
                            } catch (e: Exception) {
                                error = "Não consegui baixar (${e.message ?: e.javaClass.simpleName})."
                                downloading = false
                            }
                        }
                    }
                },
            ) { Text("Continuar") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onFinish) { Text("Cancelar") }
        },
    )
}

@Composable
private fun MediaTile(
    media: TweetMedia,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        AsyncImage(
            model = media.previewUrl ?: media.url,
            imageLoader = rememberStickerLoader(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        val kindLabel = when (media.kind) {
            TweetMedia.Kind.PHOTO -> null
            TweetMedia.Kind.VIDEO -> "Vídeo"
            TweetMedia.Kind.GIF -> "GIF"
        }
        val label = listOfNotNull(kindLabel, if (media.fromQuote) "post citado" else null)
            .joinToString(" · ")
            .ifEmpty { null }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selected) {
            Text(
                "✓",
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}
