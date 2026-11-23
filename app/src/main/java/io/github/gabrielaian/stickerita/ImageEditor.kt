package io.github.gabrielaian.stickerita

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Imagem que o editor vai abrir. [replaceFile] != null quando se edita uma figurinha que já está no pacote. */
class EditRequest(val packId: String, val source: Uri, val replaceFile: String?)

/** Mostra o editor quando há uma imagem para editar; usado na tela do pacote e na janela de compartilhar. */
@Composable
internal fun ImageEditorHost(vm: MainViewModel) {
    val request = vm.edit ?: return
    ImageEditorDialog(
        request = request,
        onDone = { vm.confirmEdit(it) },
        onSkip = if (request.replaceFile == null) ({ vm.skipEdit() }) else null,
        onCancel = { vm.cancelEdit() },
    )
}

/** Como o recorte entra nos 512x512 da figurinha. */
enum class FitMode(val label: String) {
    /** Estica até preencher tudo (como o app sempre fez). */
    FILL("Preencher 512×512"),

    /** Mantém a proporção do recorte; o que sobra fica transparente. */
    KEEP("Manter proporção"),
}

/** Texto ou emoji sobre a figurinha. Posição e tamanho em fração da figurinha (0 a 1). */
data class Overlay(
    val id: Int,
    val text: String,
    val color: Int,
    val size: Float,
    val x: Float,
    val y: Float,
    val emoji: Boolean,
)

/**
 * Desenha a figurinha editada. A mesma função faz a prévia (sempre em 512 px, num bitmap
 * reaproveitado) e o resultado final, então o que se vê é o que sai.
 */
object EditorRenderer {

    fun render(
        source: Bitmap,
        crop: RectF,
        mode: FitMode,
        overlays: List<Overlay>,
        size: Int,
        into: Bitmap? = null,
    ): Bitmap {
        val out = into ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        out.eraseColor(AndroidColor.TRANSPARENT)
        val canvas = AndroidCanvas(out)

        val src = Rect(
            crop.left.roundToInt().coerceIn(0, source.width - 1),
            crop.top.roundToInt().coerceIn(0, source.height - 1),
            crop.right.roundToInt().coerceIn(1, source.width),
            crop.bottom.roundToInt().coerceIn(1, source.height),
        )
        val side = size.toFloat()
        val dst = when (mode) {
            FitMode.FILL -> RectF(0f, 0f, side, side)
            FitMode.KEEP -> {
                val scale = min(side / src.width(), side / src.height())
                val w = src.width() * scale
                val h = src.height() * scale
                RectF((side - w) / 2f, (side - h) / 2f, (side + w) / 2f, (side + h) / 2f)
            }
        }
        canvas.drawBitmap(source, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        overlays.forEach { drawOverlay(canvas, it, size) }
        return out
    }

    private fun drawOverlay(canvas: AndroidCanvas, overlay: Overlay, size: Int) {
        if (overlay.text.isEmpty()) return
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = overlay.color
            textAlign = Paint.Align.CENTER
            typeface = if (overlay.emoji) Typeface.DEFAULT else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = overlay.size * size
        }
        val lines = overlay.text.split('\n')
        // Se a linha mais larga passar da figurinha, reduz o texto em vez de cortá-lo.
        val widest = lines.maxOf { fill.measureText(it) }
        val limit = size * 0.96f
        if (widest > limit) fill.textSize = fill.textSize * limit / widest

        val lineHeight = fill.fontSpacing
        var baseline = overlay.y * size - lineHeight * lines.size / 2f - fill.ascent()
        val outline = if (overlay.emoji) {
            null
        } else {
            Paint(fill).apply {
                style = Paint.Style.STROKE
                strokeWidth = fill.textSize * 0.14f
                strokeJoin = Paint.Join.ROUND
                color = if (ColorUtils.calculateLuminance(overlay.color) > 0.5) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        for (line in lines) {
            outline?.let { canvas.drawText(line, overlay.x * size, baseline, it) }
            canvas.drawText(line, overlay.x * size, baseline, fill)
            baseline += lineHeight
        }
    }
}

private enum class Grip { NONE, MOVE, TL, TR, BL, BR, L, T, R, B }

private val RATIOS = listOf<Pair<String, Float?>>(
    "Livre" to null,
    "1:1" to 1f,
    "4:3" to 4f / 3f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
    "9:16" to 9f / 16f,
)

private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFF5252, 0xFF40C4FF, 0xFF69F0AE, 0xFFFF80AB,
).map { it.toInt() }

private val EMOJIS = listOf(
    "😀", "😂", "🤣", "😍", "😎", "🥹", "😭", "😡", "🤔", "🙄", "😴", "🥳",
    "👍", "👎", "👏", "🙏", "💪", "🔥", "❤️", "💔", "✨", "🎉", "💀", "👀",
    "🍕", "☕", "🍺", "🐱", "🐶", "🌈", "⭐", "💯",
)

private const val PREVIEW = 512
private const val MIN_CROP = 48f

/** Ponto de partida: o maior recorte com a proporção [ratio] dentro de [from], com o mesmo centro. */
private fun fitRatio(from: RectF, ratio: Float): RectF {
    var w = from.width()
    var h = w / ratio
    if (h > from.height()) {
        h = from.height()
        w = h * ratio
    }
    return RectF(from.centerX() - w / 2f, from.centerY() - h / 2f, from.centerX() + w / 2f, from.centerY() + h / 2f)
}

private fun pickGrip(pos: Offset, crop: RectF, scale: Float, slop: Float, edges: Boolean): Grip {
    val l = crop.left * scale
    val t = crop.top * scale
    val r = crop.right * scale
    val b = crop.bottom * scale
    fun near(x: Float, y: Float) = abs(pos.x - x) <= slop && abs(pos.y - y) <= slop
    return when {
        near(l, t) -> Grip.TL
        near(r, t) -> Grip.TR
        near(l, b) -> Grip.BL
        near(r, b) -> Grip.BR
        edges && abs(pos.x - l) <= slop && pos.y in t..b -> Grip.L
        edges && abs(pos.x - r) <= slop && pos.y in t..b -> Grip.R
        edges && abs(pos.y - t) <= slop && pos.x in l..r -> Grip.T
        edges && abs(pos.y - b) <= slop && pos.x in l..r -> Grip.B
        pos.x in l..r && pos.y in t..b -> Grip.MOVE
        else -> Grip.NONE
    }
}

/** Aplica o arrasto (em pixels da imagem) ao recorte, sem passar dos limites. */
private fun dragCrop(crop: RectF, grip: Grip, dx: Float, dy: Float, w: Float, h: Float, ratio: Float?): RectF {
    val floor = min(MIN_CROP, min(w, h))
    var l = crop.left
    var t = crop.top
    var r = crop.right
    var b = crop.bottom

    val corner = grip == Grip.TL || grip == Grip.TR || grip == Grip.BL || grip == Grip.BR
    if (ratio != null && corner) {
        // Com proporção fixa, o canto oposto fica parado e a largura manda na altura.
        val left = grip == Grip.TL || grip == Grip.BL
        val top = grip == Grip.TL || grip == Grip.TR
        val anchorX = if (left) r else l
        val anchorY = if (top) b else t
        val px = (if (left) l else r) + dx
        val py = (if (top) t else b) + dy
        var nw = abs(px - anchorX)
        var nh = abs(py - anchorY)
        // segue o eixo em que o dedo andou mais
        if (nw / ratio >= nh) nh = nw / ratio else nw = nh * ratio
        val room = Size(if (left) anchorX else w - anchorX, if (top) anchorY else h - anchorY)
        val k = min(1f, min(room.width / nw, room.height / nh))
        nw *= k
        nh *= k
        if (nw < floor) {
            nw = floor
            nh = nw / ratio
        }
        return RectF(
            if (left) anchorX - nw else anchorX,
            if (top) anchorY - nh else anchorY,
            if (left) anchorX else anchorX + nw,
            if (top) anchorY else anchorY + nh,
        )
    }

    when (grip) {
        Grip.MOVE -> {
            val mx = dx.coerceIn(-l, w - r)
            val my = dy.coerceIn(-t, h - b)
            l += mx; r += mx; t += my; b += my
        }
        Grip.L -> l = (l + dx).coerceIn(0f, r - floor)
        Grip.R -> r = (r + dx).coerceIn(l + floor, w)
        Grip.T -> t = (t + dy).coerceIn(0f, b - floor)
        Grip.B -> b = (b + dy).coerceIn(t + floor, h)
        Grip.TL -> { l = (l + dx).coerceIn(0f, r - floor); t = (t + dy).coerceIn(0f, b - floor) }
        Grip.TR -> { r = (r + dx).coerceIn(l + floor, w); t = (t + dy).coerceIn(0f, b - floor) }
        Grip.BL -> { l = (l + dx).coerceIn(0f, r - floor); b = (b + dy).coerceIn(t + floor, h) }
        Grip.BR -> { r = (r + dx).coerceIn(l + floor, w); b = (b + dy).coerceIn(t + floor, h) }
        Grip.NONE -> Unit
    }
    return RectF(l, t, r, b)
}

/**
 * Editor de imagem em tela cheia: recorte (livre ou com proporção), preenchimento ou proporção
 * mantida, e textos e emojis sobre a figurinha. Trabalha sobre um bitmap reduzido (até 1600 px),
 * então continua leve mesmo com fotos grandes; o recorte final sai dele numa só passada.
 */
@Composable
internal fun ImageEditorDialog(
    request: EditRequest,
    onDone: (ByteArray) -> Unit,
    onSkip: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // null = carregando; Result.failure = não abriu
    val loaded by produceState<Result<Bitmap>?>(null, request) {
        value = withContext(Dispatchers.IO) {
            runCatching { StickerConverter.decodeForEditor(context, request.source) }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val result = loaded
            val source = result?.getOrNull()
            when {
                result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                source == null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Não consegui abrir a imagem (${result.exceptionOrNull()?.message ?: "erro desconhecido"}).",
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onCancel) { Text("Fechar") }
                }
                else -> EditorBody(
                    source = source,
                    editingExisting = request.replaceFile != null,
                    onDone = { crop, mode, overlays, finish ->
                        scope.launch {
                            val bytes = try {
                                withContext(Dispatchers.Default) {
                                    StickerConverter.encodeBitmap(EditorRenderer.render(source, crop, mode, overlays, StickerConverter.SIZE))
                                }
                            } catch (e: Exception) {
                                null
                            }
                            if (bytes != null) onDone(bytes) else finish()
                        }
                    },
                    onSkip = onSkip,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun EditorBody(
    source: Bitmap,
    editingExisting: Boolean,
    onDone: (RectF, FitMode, List<Overlay>, () -> Unit) -> Unit,
    onSkip: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var crop by remember(source) { mutableStateOf(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())) }
    var ratioIndex by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(FitMode.FILL) }
    var overlays by remember { mutableStateOf(listOf<Overlay>()) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var nextId by remember { mutableIntStateOf(1) }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(enabled = !saving, onClick = onCancel) { Text("Cancelar") }
            Text(
                if (editingExisting) "Editar figurinha" else "Nova figurinha",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (onSkip != null) TextButton(enabled = !saving, onClick = onSkip) { Text("Sem editar") }
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    onDone(crop, mode, overlays) { saving = false }
                },
            ) { Text(if (saving) "Salvando…" else "Pronto") }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Cortar") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Legenda e emojis") })
        }

        if (tab == 0) {
            CropPane(
                source = source,
                crop = crop,
                ratio = RATIOS[ratioIndex].second,
                onCrop = { crop = it },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RATIOS.forEachIndexed { index, (label, ratio) ->
                        FilterChip(
                            selected = ratioIndex == index,
                            onClick = {
                                ratioIndex = index
                                if (ratio != null) crop = fitRatio(crop, ratio)
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Text(
                    if (mode == FitMode.FILL) {
                        "O recorte é esticado até preencher os 512×512 da figurinha."
                    } else {
                        "O recorte mantém a proporção e o que sobra fica transparente."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            OverlayPane(
                source = source,
                crop = crop,
                mode = mode,
                overlays = overlays,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                onChange = { overlays = it },
                onAdd = { emoji, text ->
                    val overlay = Overlay(
                        id = nextId++,
                        text = text,
                        color = TEXT_COLORS[0],
                        size = if (emoji) 0.28f else 0.14f,
                        x = 0.5f,
                        y = if (emoji) 0.5f else 0.85f,
                        emoji = emoji,
                    )
                    overlays = overlays + overlay
                    selectedId = overlay.id
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CropPane(
    source: Bitmap,
    crop: RectF,
    ratio: Float?,
    onCrop: (RectF) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val image = remember(source) { source.asImageBitmap() }
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(ratio)
    val currentOnCrop by rememberUpdatedState(onCrop)
    var grip by remember { mutableStateOf(Grip.NONE) }
    val accent = MaterialTheme.colorScheme.primary
    val handleRadius = with(density) { 10.dp.toPx() }
    val slop = with(density) { 28.dp.toPx() }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val scale = min(constraints.maxWidth / source.width.toFloat(), constraints.maxHeight / source.height.toFloat())
        val widthDp = with(density) { (source.width * scale).toDp() }
        val heightDp = with(density) { (source.height * scale).toDp() }

        Canvas(
            Modifier
                .size(widthDp, heightDp)
                .pointerInput(source) {
                    detectDragGestures(
                        onDragStart = { start ->
                            grip = pickGrip(start, currentCrop, scale, slop, edges = currentRatio == null)
                        },
                        onDrag = { change, drag ->
                            if (grip != Grip.NONE) {
                                change.consume()
                                currentOnCrop(
                                    dragCrop(
                                        currentCrop, grip, drag.x / scale, drag.y / scale,
                                        source.width.toFloat(), source.height.toFloat(), currentRatio,
                                    ),
                                )
                            }
                        },
                        onDragEnd = { grip = Grip.NONE },
                        onDragCancel = { grip = Grip.NONE },
                    )
                },
        ) {
            drawImage(
                image,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            val l = crop.left * scale
            val t = crop.top * scale
            val r = crop.right * scale
            val b = crop.bottom * scale
            val shade = Color(0xAA000000)
            // escurece o que fica fora do recorte
            drawRect(shade, Offset(0f, 0f), Size(size.width, t))
            drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(shade, Offset(0f, t), Size(l, b - t))
            drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2f))
            // grade dos terços
            for (i in 1..2) {
                val gx = l + (r - l) * i / 3f
                val gy = t + (b - t) * i / 3f
                drawLine(Color(0x88FFFFFF), Offset(gx, t), Offset(gx, b), strokeWidth = 1f)
                drawLine(Color(0x88FFFFFF), Offset(l, gy), Offset(r, gy), strokeWidth = 1f)
            }
            for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) {
                drawCircle(Color.White, handleRadius, Offset(cx, cy))
                drawCircle(accent, handleRadius * 0.6f, Offset(cx, cy))
            }
        }
    }
}

@Composable
private fun OverlayPane(
    source: Bitmap,
    crop: RectF,
    mode: FitMode,
    overlays: List<Overlay>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    onChange: (List<Overlay>) -> Unit,
    onAdd: (emoji: Boolean, text: String) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val previewBitmap = remember { Bitmap.createBitmap(PREVIEW, PREVIEW, Bitmap.Config.ARGB_8888) }
    val previewImage = remember(previewBitmap) { previewBitmap.asImageBitmap() }
    val currentOverlays by rememberUpdatedState(overlays)
    val currentSelected by rememberUpdatedState(selectedId)
    val currentChange by rememberUpdatedState(onChange)
    val currentSelect by rememberUpdatedState(onSelect)
    var showEmojis by remember { mutableStateOf(false) }
    val selected = overlays.firstOrNull { it.id == selectedId }

    fun update(id: Int, change: (Overlay) -> Overlay) =
        onChange(overlays.map { if (it.id == id) change(it) else it })

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val sidePx = min(constraints.maxWidth, constraints.maxHeight)
            val sideDp = with(density) { sidePx.toDp() }

            Canvas(
                Modifier
                    .size(sideDp)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(sidePx) {
                        // toque escolhe o item mais perto; arrastar move o escolhido
                        fun nearest(pos: Offset): Overlay? =
                            currentOverlays.minByOrNull { abs(it.x * sidePx - pos.x) + abs(it.y * sidePx - pos.y) }
                                ?.takeIf { abs(it.x * sidePx - pos.x) + abs(it.y * sidePx - pos.y) < sidePx * 0.35f }
                        detectTapGestures { pos -> currentSelect(nearest(pos)?.id) }
                    }
                    .pointerInput(sidePx) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val hit = currentOverlays.minByOrNull { abs(it.x * sidePx - pos.x) + abs(it.y * sidePx - pos.y) }
                                if (hit != null && abs(hit.x * sidePx - pos.x) + abs(hit.y * sidePx - pos.y) < sidePx * 0.35f) {
                                    currentSelect(hit.id)
                                }
                            },
                            onDrag = { change, drag ->
                                val id = currentSelected ?: return@detectDragGestures
                                change.consume()
                                currentChange(
                                    currentOverlays.map {
                                        if (it.id == id) {
                                            it.copy(
                                                x = (it.x + drag.x / sidePx).coerceIn(0f, 1f),
                                                y = (it.y + drag.y / sidePx).coerceIn(0f, 1f),
                                            )
                                        } else {
                                            it
                                        }
                                    },
                                )
                            },
                        )
                    },
            ) {
                EditorRenderer.render(source, crop, mode, overlays, PREVIEW, into = previewBitmap)
                drawImage(
                    previewImage,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
                // marca o item escolhido
                selected?.let {
                    drawCircle(
                        Color.White, 6f, Offset(it.x * size.width, it.y * size.height),
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAdd(false, "Texto") }) { Text("+ Texto") }
                TextButton(onClick = { showEmojis = !showEmojis }) { Text(if (showEmojis) "Fechar emojis" else "+ Emoji") }
                if (selected != null) TextButton(onClick = {
                    onChange(overlays.filterNot { it.id == selected.id })
                    onSelect(null)
                }) { Text("Excluir") }
            }

            if (showEmojis) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    EMOJIS.forEach { emoji ->
                        Text(
                            emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.clickable { onAdd(true, emoji) }.padding(8.dp),
                        )
                    }
                }
            }

            if (selected != null) {
                if (!selected.emoji) {
                    OutlinedTextField(
                        value = selected.text,
                        onValueChange = { text -> update(selected.id) { it.copy(text = text.take(80)) } },
                        label = { Text("Texto") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TEXT_COLORS.forEach { argb ->
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .background(Color(argb), CircleShape)
                                    .border(
                                        width = if (selected.color == argb) 3.dp else 1.dp,
                                        color = if (selected.color == argb) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable { update(selected.id) { it.copy(color = argb) } },
                            )
                        }
                    }
                }
                Text("Tamanho", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = selected.size,
                    onValueChange = { value -> update(selected.id) { it.copy(size = value) } },
                    valueRange = 0.06f..0.6f,
                )
            } else {
                Text(
                    "Toque em um texto ou emoji para editar. Arraste para mudar a posição.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
