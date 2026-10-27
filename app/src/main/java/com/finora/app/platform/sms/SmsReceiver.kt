package com.finora.app.platform.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.finora.app.FinoraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reads incoming SMS on the device and hands them to the local parser. Messages are processed
 * entirely on the device; nothing is forwarded or uploaded. Does nothing unless the user turned
 * SMS capture on in Settings.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives in parts; join them per sender before parsing.
        val joined = messages.groupBy { it.originatingAddress ?: "" }.map { (sender, parts) ->
            Triple(sender, parts.joinToString("") { it.messageBody ?: "" }, parts.minOf { it.timestampMillis })
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as FinoraApp).container
                if (!container.settings.current().smsCaptureEnabled) return@launch
                joined.forEach { (sender, body, at) -> container.transactions.importSms(sender, body, at) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reads past messages from the inbox for a one-off import the user starts. */
class SmsInboxImporter(private val context: Context) {
    data class Summary(val scanned: Int, val added: Int, val duplicates: Int, val unrecognized: Int)

    suspend fun import(sinceMillis: Long, onEach: suspend (sender: String, body: String, at: Long) -> com.finora.app.data.repo.ImportOutcome): Summary {
        var scanned = 0; var added = 0; var dup = 0; var unrec = 0
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?", arrayOf(sinceMillis.toString()),
            "${Telephony.Sms.DATE} ASC",
        ) ?: return Summary(0, 0, 0, 0)
        cursor.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                val body = c.getString(1) ?: continue
                // Bank and UPI messages come from alphanumeric sender ids (e.g. "AX-HDFCBK"), not phone numbers.
                if (sender.count(Char::isDigit) >= 10 && sender.none(Char::isLetter)) continue
                scanned++
                when (onEach(sender, body, c.getLong(2))) {
                    is com.finora.app.data.repo.ImportOutcome.Added -> added++
                    is com.finora.app.data.repo.ImportOutcome.Duplicate -> dup++
                    com.finora.app.data.repo.ImportOutcome.Unrecognized -> unrec++
                    com.finora.app.data.repo.ImportOutcome.Ignored -> Unit
                }
            }
        }
        return Summary(scanned, added, dup, unrec)
    }
}
