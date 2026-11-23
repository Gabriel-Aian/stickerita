package io.github.gabrielaian.stickerita

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** O que a interface precisa saber da conversão que está rodando em segundo plano. */
object ConversionState {
    /** Há conversão em andamento. */
    var active by mutableStateOf(false)
        internal set

    /** Texto do progresso (ex.: "2 de 3 · lendo quadros 40/150"). */
    var text by mutableStateOf("")
        internal set

    /** Pacote que está recebendo a figurinha agora. */
    var packId by mutableStateOf<String?>(null)
        internal set

    /** Sobe a cada arquivo terminado; a interface relê os pacotes quando muda. */
    var completed by mutableIntStateOf(0)
        internal set
}

/**
 * Converte GIFs e vídeos em figurinhas animadas fora da tela, em um serviço em primeiro plano, para
 * você poder fechar a janela de compartilhar (ou sair do app) em vez de esperar. Ao terminar,
 * mostra uma notificação e, se você pediu, oferece atualizar o pacote no WhatsApp.
 */
class ConversionService : Service() {

    class Job(
        val packId: String,
        val packName: String,
        val uri: Uri,
        val startMs: Int,
        val endMs: Int,
        /** Oferecer "atualizar no WhatsApp" na notificação final. */
        val offerWhatsApp: Boolean,
    )

    /** Resultado acumulado por pacote, para uma notificação só no fim. */
    private class Batch(val packName: String) {
        var added = 0
        var failed = 0
        var reason: String? = null
        var offerWhatsApp = false
        val notes = mutableListOf<String>()
    }

    @Volatile
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        createChannels(this)
        ServiceCompat.startForeground(
            this,
            PROGRESS_ID,
            progressNotification(this, "Preparando…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        startWorker()
        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (!running.compareAndSet(false, true)) return
        val repository = PackRepository(applicationContext)
        Thread {
            val batches = LinkedHashMap<String, Batch>()
            var done = 0
            while (true) {
                val job = queue.poll()
                if (job == null) {
                    postResults(batches, repository)
                    batches.clear()
                    running.set(false)
                    // Um arquivo pode ter entrado entre o último poll e agora: assume de novo.
                    if (queue.isEmpty() || !running.compareAndSet(false, true)) break
                    continue
                }
                done++
                convert(job, batches, repository, position = done, total = done + queue.size)
            }
            ConversionState.active = false
            ConversionState.packId = null
            ConversionState.text = ""
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
        }.start()
    }

    private fun convert(job: Job, batches: MutableMap<String, Batch>, repository: PackRepository, position: Int, total: Int) {
        val batch = batches.getOrPut(job.packId) { Batch(job.packName) }
        if (job.offerWhatsApp) batch.offerWhatsApp = true

        val prefix = if (total > 1) "$position de $total · " else ""
        ConversionState.active = true
        ConversionState.packId = job.packId
        ConversionState.text = prefix.trimEnd(' ', '·')
        updateProgress("Convertendo figurinha para “${job.packName}”", prefix)

        try {
            val converted = AnimatedConverter.convert(applicationContext, job.uri, job.startMs, job.endMs) { step ->
                ConversionState.text = prefix + step
                updateProgress("Convertendo figurinha para “${job.packName}”", prefix + step)
            }
            if (repository.addSticker(job.packId, converted.webp, converted.preview) != null) {
                batch.added++
                converted.note?.let { batch.notes += it }
            } else {
                batch.failed++
                batch.reason = "o pacote não existe mais ou está cheio"
            }
        } catch (e: Exception) {
            batch.failed++
            batch.reason = e.message ?: e.javaClass.simpleName
        } catch (e: OutOfMemoryError) {
            batch.failed++
            batch.reason = "memória insuficiente"
        }
        ConversionState.completed++
    }

    private fun updateProgress(title: String, detail: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(PROGRESS_ID, progressNotification(this, detail, title))
    }

    private fun postResults(batches: Map<String, Batch>, repository: PackRepository) {
        val manager = getSystemService(NotificationManager::class.java)
        for ((packId, batch) in batches) {
            val pack = repository.get(packId)
            val parts = mutableListOf<String>()
            if (batch.added > 0) {
                parts += if (batch.added == 1) "1 figurinha adicionada" else "${batch.added} figurinhas adicionadas"
            }
            when (batch.notes.size) {
                0 -> Unit
                1 -> parts += batch.notes.first()
                else -> parts += "${batch.notes.size} reduzidas para caber nos 500 KB"
            }
            if (batch.failed > 0) {
                val label = if (batch.failed == 1) "1 falhou" else "${batch.failed} falharam"
                parts += batch.reason?.let { "$label ($it)" } ?: label
            }
            val summary = parts.joinToString(" · ")

            val canUpdate = batch.offerWhatsApp && batch.added > 0 && pack != null && pack.isReady
            val open = PendingIntent.getActivity(
                this, packId.hashCode(),
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(this, RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sticker)
                .setContentTitle(batch.packName)
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setContentIntent(open)
                .setAutoCancel(true)
            if (canUpdate && pack != null) {
                val whatsApp = PendingIntent.getActivity(
                    this, packId.hashCode() + 1,
                    whatsAppIntent(this, pack).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.addAction(0, "Atualizar no WhatsApp", whatsApp)
            }
            manager.notify(RESULT_ID_BASE + (packId.hashCode() and 0xFFFF), builder.build())

            // Sem permissão de notificação o aviso não aparece: o texto na tela garante que você veja.
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "${batch.packName}: $summary", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val PROGRESS_CHANNEL = "conversion"
        private const val RESULT_CHANNEL = "result"
        private const val PROGRESS_ID = 1001
        private const val RESULT_ID_BASE = 2000

        private val queue = ConcurrentLinkedQueue<Job>()
        private val running = AtomicBoolean(false)

        /** Coloca o arquivo na fila e liga o serviço se ele não estiver rodando. */
        fun enqueue(context: Context, job: Job) {
            queue.add(job)
            ConversionState.active = true
            ConversionState.packId = job.packId
            val intent = Intent(context, ConversionService::class.java).apply {
                // O serviço precisa ler o arquivo mesmo depois que a janela de compartilhar fechar.
                clipData = ClipData.newRawUri("media", job.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // O Android recusou ligar o serviço: desfaz, para a tela não ficar "convertendo" para sempre.
                queue.remove(job)
                if (queue.isEmpty() && !running.get()) {
                    ConversionState.active = false
                    ConversionState.packId = null
                }
                throw e
            }
        }

        private fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(PROGRESS_CHANNEL, "Conversão em andamento", NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL, "Figurinhas prontas", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        private fun progressNotification(context: Context, detail: String, title: String = "Convertendo figurinhas"): Notification =
            NotificationCompat.Builder(context, PROGRESS_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sticker)
                .setContentTitle(title)
                .setContentText(detail)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
    }
}
