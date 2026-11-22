package io.github.gabrielaian.stickerita

import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val BACKGROUND_PRESETS = listOf(
    0xF8FBF5, 0xFFFFFF, 0xFFF8E1, 0xFCE4EC, 0xE3F2FD, 0xEDE7F6,
    0x121212, 0x000000, 0x10150F, 0x1A1A2E, 0x2B1B1B, 0x16262B,
)
private val ACCENT_PRESETS = listOf(
    0x2E7D32, 0x00897B, 0x1976D2, 0x5E35B1, 0xC2185B, 0xE64A19, 0xF9A825, 0x455A64,
)

private enum class ColorTarget { BACKGROUND, ACCENT }

private fun opaque(rgb: Int): Int = rgb or 0xFF000000.toInt()

private fun hexOf(argb: Int): String = String.format("%06X", argb and 0xFFFFFF)

/** Janela "Aparência": escolhe a cor de fundo e a cor dos botões do app. */
@Composable
internal fun AppearanceDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf<ColorTarget?>(null) }
    val target = picking

    if (target != null) {
        val isBackground = target == ColorTarget.BACKGROUND
        // Valor de antes, para "Cancelar" desfazer a prévia.
        val original = remember(target) { if (isBackground) ThemeSettings.background else ThemeSettings.accent }
        val current = if (isBackground) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.primary
        val change: (Int?) -> Unit = { color ->
            if (isBackground) {
                ThemeSettings.preview(color, ThemeSettings.accent)
            } else {
                ThemeSettings.preview(ThemeSettings.background, color)
            }
        }
        ColorPickerDialog(
            title = if (isBackground) "Cor de fundo" else "Cor dos botões",
            start = original ?: current.toArgb(),
            presets = if (isBackground) BACKGROUND_PRESETS else ACCENT_PRESETS,
            onChange = change,
            onApply = {
                ThemeSettings.save(context)
                picking = null
            },
            onReset = {
                change(null)
                ThemeSettings.save(context)
                picking = null
            },
            onCancel = {
                change(original)
                picking = null
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Aparência") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ColorRow(
                        label = "Cor de fundo",
                        color = MaterialTheme.colorScheme.background,
                        value = ThemeSettings.background?.let { "#" + hexOf(it) } ?: "Padrão",
                        onClick = { picking = ColorTarget.BACKGROUND },
                    )
                    ColorRow(
                        label = "Cor dos botões",
                        color = MaterialTheme.colorScheme.primary,
                        value = ThemeSettings.accent?.let { "#" + hexOf(it) } ?: "Padrão",
                        onClick = { picking = ColorTarget.ACCENT },
                    )
                    Text(
                        "O fundo vale para o tema claro e para o escuro. O texto muda sozinho para continuar legível.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
            dismissButton = {
                TextButton(
                    enabled = ThemeSettings.background != null || ThemeSettings.accent != null,
                    onClick = { ThemeSettings.reset(context) },
                ) { Text("Restaurar padrão") }
            },
        )
    }
}

@Composable
private fun ColorRow(label: String, color: Color, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Seletor de cor: amostras prontas, três controles (matiz, saturação, brilho) e o código hexadecimal.
 * Cada mudança é mostrada no app na hora, por trás da janela.
 */
@Composable
private fun ColorPickerDialog(
    title: String,
    start: Int,
    presets: List<Int>,
    onChange: (Int?) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    val initial = remember { FloatArray(3).also { AndroidColor.colorToHSV(start, it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var brightness by remember { mutableFloatStateOf(initial[2]) }
    var hex by remember { mutableStateOf(hexOf(start)) }

    fun push() {
        val color = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
        hex = hexOf(color)
        onChange(color)
    }

    fun load(argb: Int) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        hex = hexOf(argb)
        onChange(argb)
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { rgb ->
                        val argb = opaque(rgb)
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (hexOf(argb) == hex) 3.dp else 1.dp,
                                    color = if (hexOf(argb) == hex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { load(argb) },
                        )
                    }
                }

                Text("Matiz", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Slider(value = hue, onValueChange = { hue = it; push() }, valueRange = 0f..360f)
                Text("Saturação", style = MaterialTheme.typography.labelMedium)
                Slider(value = saturation, onValueChange = { saturation = it; push() })
                Text("Brilho", style = MaterialTheme.typography.labelMedium)
                Slider(value = brightness, onValueChange = { brightness = it; push() })

                OutlinedTextField(
                    value = hex,
                    onValueChange = { raw ->
                        val clean = raw.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        hex = clean
                        if (clean.length == 6) load(opaque(clean.toInt(16)))
                    },
                    label = { Text("Código da cor") },
                    prefix = { Text("#") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text("Aplicar") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Padrão") }
                TextButton(onClick = onCancel) { Text("Cancelar") }
            }
        },
    )
}

/** Janela para trocar o ícone do pacote no app. A imagem não entra no pacote nem vai ao WhatsApp. */
@Composable
internal fun PackIconDialog(vm: MainViewModel, pack: StickerPack, onClose: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setPackIcon(pack.id, uri)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ícone do pacote") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Thumb(file = vm.thumbFile(pack), modifier = Modifier.size(112.dp))
                Text(
                    "Pode ser qualquer imagem. Ela aparece só aqui no Stickerita e não entra no pacote: " +
                        "no WhatsApp o ícone continua sendo a primeira figurinha.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    ) { Text("Escolher imagem") }
                    if (pack.hasIcon) {
                        TextButton(onClick = { vm.clearPackIcon(pack.id) }) { Text("Voltar ao padrão") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Fechar") } },
    )
}
