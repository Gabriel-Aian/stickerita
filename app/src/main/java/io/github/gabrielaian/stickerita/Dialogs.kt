package io.github.gabrielaian.stickerita

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Linha clicável de uma ação, com um título e uma explicação curta. */
@Composable
private fun ActionRow(title: String, detail: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tudo que dá para fazer com uma figurinha: emojis, editar a imagem, mover e remover. */
@Composable
internal fun StickerDialog(vm: MainViewModel, pack: StickerPack, sticker: Sticker, onDismiss: () -> Unit) {
    var emoji by remember(sticker.fileName) { mutableStateOf(sticker.emoji) }
    var confirmRemove by remember(sticker.fileName) { mutableStateOf(false) }
    val isFirst = pack.stickers.firstOrNull()?.fileName == sticker.fileName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Figurinha") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Thumb(file = vm.stickerFile(pack, sticker), modifier = Modifier.size(120.dp))
                }
                Text(
                    "Os emojis (até 3) ajudam a achar a figurinha no teclado do WhatsApp.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(16) },
                    label = { Text("Emojis") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!pack.animated) {
                    ActionRow("Editar imagem", "Recortar, ajustar a proporção, legenda e emojis") {
                        onDismiss()
                        vm.startEditSticker(pack, sticker)
                    }
                }
                if (!isFirst) {
                    ActionRow("Mover para o início", "A primeira figurinha é o ícone do pacote no WhatsApp") {
                        onDismiss()
                        vm.moveToFront(pack.id, sticker)
                    }
                }
                ActionRow("Remover do pacote", "Apaga só esta figurinha; as outras ficam", danger = true) {
                    confirmRemove = true
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                vm.setEmoji(pack.id, sticker, emoji)
            }) { Text("Salvar emojis") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remover figurinha?") },
            text = { Text("Só esta figurinha sai do pacote “${pack.name}”. Para o WhatsApp mostrar a mudança, atualize o pacote lá.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onDismiss()
                    vm.removeSticker(pack.id, sticker)
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
internal fun RenamePackDialog(pack: StickerPack, onSave: (name: String, author: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(pack.id) { mutableStateOf(pack.name) }
    var author by remember(pack.id) { mutableStateOf(pack.publisher) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nome e autor do pacote") },
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
                Text(
                    "Depois de salvar, atualize o pacote no WhatsApp para ele mostrar o novo nome.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && author.isNotBlank(), onClick = { onSave(name, author) }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Envia o pacote como arquivo .stickerita, pelo WhatsApp (a um número, se quiser) ou por outro app. */
@Composable
internal fun SharePackDialog(vm: MainViewModel, pack: StickerPack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var phone by remember { mutableStateOf("") }

    fun send(viaWhatsApp: Boolean) {
        vm.sharePack(pack, viaWhatsApp, phone.takeIf { viaWhatsApp }) { intent: Intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                vm.message = "Não encontrei um app para enviar"
            }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compartilhar pacote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "O WhatsApp não envia pacotes de figurinhas direto a um contato. O Stickerita manda o pacote " +
                        "como um arquivo (.stickerita); quem recebe importa no próprio Stickerita, em ⋮ › Importar pacote.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.take(20) },
                    label = { Text("Número do WhatsApp (opcional)") },
                    placeholder = { Text("DDD + número") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Sem número, o WhatsApp deixa você escolher o contato. Sem código do país, vale o do Brasil (55).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { send(viaWhatsApp = true) }) { Text("Enviar pelo WhatsApp") } },
        dismissButton = {
            Column {
                TextButton(onClick = { send(viaWhatsApp = false) }) { Text("Outro app") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}

/** Compartilhar o próprio app: link da versão mais recente ou o APK instalado. */
@Composable
internal fun ShareAppDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current

    fun send(asApk: Boolean) {
        vm.shareApp(asApk) { intent: Intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                vm.message = "Não encontrei um app para enviar"
            }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Compartilhar o Stickerita") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ActionRow(
                    "Enviar o link",
                    "Manda o endereço da versão mais recente no GitHub. É a melhor opção: o link sempre leva à versão nova.",
                ) { send(asApk = false) }
                ActionRow(
                    "Enviar o APK (versão ${vm.installedVersion})",
                    "Manda o instalador desta versão que está no seu celular. O WhatsApp pode recusar arquivos APK; nesse caso use o link.",
                ) { send(asApk = true) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
