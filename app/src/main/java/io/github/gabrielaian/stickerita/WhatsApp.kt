package io.github.gabrielaian.stickerita

import android.content.Context
import android.content.Intent

/** Intent do contrato oficial que pede ao WhatsApp para adicionar (ou atualizar) um pacote. */
fun whatsAppIntent(context: Context, pack: StickerPack): Intent =
    Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
        putExtra("sticker_pack_id", pack.id)
        putExtra("sticker_pack_authority", context.packageName + PROVIDER_SUFFIX)
        putExtra("sticker_pack_name", pack.name)
    }
