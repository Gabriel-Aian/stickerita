@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.gabrielaian.stickerita

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.os.Build
import androidx.compose.material3.Switch
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StickeritaTheme {
                App()
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm.apkToInstall) {
        vm.apkToInstall?.let { apk ->
            vm.apkToInstall = null
            try {
                context.startActivity(Updater.installIntent(context, apk))
            } catch (e: Exception) {
                vm.message = "Não consegui abrir o instalador (${e.message ?: e.javaClass.simpleName})"
            }
        }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    BackHandler(enabled = vm.selectedId != null) { vm.selectedId = null }

    val pack = vm.selected
    if (pack == null) {
        PackListScreen(vm, snackbar)
    } else {
        PackScreen(vm, pack, snackbar)
    }
}

/** Carregador de imagens que também anima WebP/GIF (a animação exige Android 9 ou mais novo). */
@Composable
private fun rememberStickerLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    // Um só carregador para todas as miniaturas, para dividirem o mesmo cache.
    return stickerLoader ?: ImageLoader.Builder(context)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory())
        }
        .build()
        .also { stickerLoader = it }
}

private var stickerLoader: ImageLoader? = null

// ---------------------------------------------------------------------------------------------
// Lista de pacotes
// ---------------------------------------------------------------------------------------------

@Composable
private fun PackListScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Stickerita") },
                actions = {
                    IconButton(onClick = { vm.checkForUpdate(manual = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Verificar atualizações")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Novo pacote") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            UpdateBanner(vm)

            if (vm.packs.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Crie seu primeiro pacote, adicione suas imagens e envie para o WhatsApp.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.packs, key = { it.id }) { pack ->
                        PackRow(vm, pack)
                    }
                }
            }
        }
    }

    if (showNew) {
        NewPackDialog(
            defaultAuthor = vm.publisher,
            onDismiss = { showNew = false },
            onCreate = { name, author, animated ->
                showNew = false
                vm.createPack(name, author, animated)
            },
        )
    }
}

@Composable
private fun UpdateBanner(vm: MainViewModel) {
    val info = vm.update ?: return
    val context = LocalContext.current

    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    // O Android exige que o usuário libere, uma vez, a instalação de apps por este app.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstall()) {
            vm.startUpdate()
        } else {
            vm.message = "Para atualizar, permita a instalação de apps por aqui"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, end = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Nova versão ${info.version} disponível",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val progress = vm.updateProgress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            } else {
                Text(
                    "Você está na ${vm.installedVersion}. O download é feito direto do GitHub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = {
                        if (canInstall()) {
                            vm.startUpdate()
                        } else {
                            permission.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + context.packageName),
                                ),
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Atualizar agora") }
            }
        }
    }
}

@Composable
private fun PackRow(vm: MainViewModel, pack: StickerPack) {
    Card(
        onClick = { vm.selectedId = pack.id },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val first = pack.stickers.firstOrNull()
            Thumb(file = first?.let { vm.stickerFile(pack, it) }, size = 56.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pack.stickers.size}/$MAX_STICKERS figurinhas${if (pack.animated) " animadas" else ""} · ${pack.publisher}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Thumb(file: File?, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                imageLoader = rememberStickerLoader(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("🙂")
        }
    }
}

@Composable
private fun NewPackDialog(
    defaultAuthor: String,
    onDismiss: () -> Unit,
    onCreate: (name: String, author: String, animated: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf(defaultAuthor) }
    var animated by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo pacote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_TEXT) },
                    label = { Text("Nome do pacote") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it.take(MAX_TEXT) },
                    label = { Text("Autor") },
                    singleLine = true,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Figurinhas animadas", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Usa GIFs e vídeos. Não dá para misturar com figurinhas comuns no mesmo pacote.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = animated, onCheckedChange = { animated = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, author, animated) },
                enabled = name.isNotBlank() && author.isNotBlank(),
            ) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------------------------------------
// Um pacote
// ---------------------------------------------------------------------------------------------

@Composable
private fun PackScreen(vm: MainViewModel, pack: StickerPack, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Sticker?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addImages(pack.id, uris)
    }

    val whatsApp = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.message = "Pacote adicionado ao WhatsApp!"
        } else {
            val error = result.data?.getStringExtra("validation_error")
            if (error != null) vm.message = "O WhatsApp recusou o pacote: $error"
        }
    }

    fun addToWhatsApp() {
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", context.packageName + PROVIDER_SUFFIX)
            putExtra("sticker_pack_name", pack.name)
        }
        try {
            whatsApp.launch(intent)
        } catch (e: ActivityNotFoundException) {
            vm.message = "WhatsApp não encontrado. Instale ou atualize o WhatsApp."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pack.name) },
                navigationIcon = {
                    IconButton(onClick = { vm.selectedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Excluir pacote")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.busyText.isNotEmpty()) {
                    Text(
                        text = "Convertendo: ${vm.busyText}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = if (pack.stickers.size < MIN_STICKERS) {
                    "${pack.stickers.size}/$MAX_STICKERS · faltam ${MIN_STICKERS - pack.stickers.size} para poder enviar ao WhatsApp"
                } else {
                    "${pack.stickers.size}/$MAX_STICKERS figurinhas · toque em uma para editar"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pack.stickers.size < MAX_STICKERS) {
                    item(key = "add") {
                        AddCell(
                            enabled = !vm.busy,
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        if (pack.animated) {
                                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                        } else {
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                items(pack.stickers, key = { it.fileName }) { sticker ->
                    StickerCell(
                        file = vm.stickerFile(pack, sticker),
                        emoji = sticker.emoji,
                        onClick = { editing = sticker },
                    )
                }
            }

            Button(
                onClick = { addToWhatsApp() },
                enabled = pack.isReady && !vm.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("Adicionar ao WhatsApp")
            }
        }
    }

    editing?.let { sticker ->
        EditStickerDialog(
            sticker = sticker,
            onDismiss = { editing = null },
            onSave = { emoji ->
                editing = null
                vm.setEmoji(pack.id, sticker, emoji)
            },
            onRemove = {
                editing = null
                vm.removeSticker(pack.id, sticker)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir pacote?") },
            text = {
                Text("“${pack.name}” e suas figurinhas serão apagados do Stickerita. No WhatsApp, o pacote já adicionado continua até você removê-lo por lá.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deletePack(pack.id)
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun AddCell(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = "Adicionar imagens",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(36.dp),
        )
    }
}

@Composable
private fun StickerCell(file: File, emoji: String, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = file,
            imageLoader = rememberStickerLoader(),
            contentDescription = "Figurinha",
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            contentScale = ContentScale.Fit,
        )
        Text(
            emoji,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
        )
    }
}

@Composable
private fun EditStickerDialog(
    sticker: Sticker,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var emoji by remember { mutableStateOf(sticker.emoji) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar figurinha") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Os emojis (até 3) ajudam a achar a figurinha no teclado do WhatsApp.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(16) },
                    label = { Text("Emojis") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onRemove) { Text("Remover") }
                TextButton(onClick = { onSave(emoji) }) { Text("Salvar") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
