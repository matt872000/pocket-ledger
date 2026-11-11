package com.pocketledger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.*

class BankSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("automatic_sms", false)
        if (!enabled) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        if (!body.contains("【交通银行】")) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8000) {
                    (context.applicationContext as LedgerApplication).repository.importMessage(body, parts.first().timestampMillis)
                }
            } catch (_: Exception) {
                // Inbox import provides recovery if receipt processing fails or Android stops the app.
            } finally { pending.finish() }
        }
    }
}
