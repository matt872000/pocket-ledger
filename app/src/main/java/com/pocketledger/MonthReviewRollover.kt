package com.pocketledger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import java.time.ZoneId

object MonthReviewRollover {
    const val ACTION = "com.pocketledger.REVIEW_MONTH_ROLLOVER"

    fun schedule(context: Context, enabled: Boolean, now: Long, zone: ZoneId, startDay: Int = 1) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, MonthReviewReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(context, 2001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (enabled) {
            // One non-wakeup, inexact refresh; Android may defer it for battery saving.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC, CategorizationReview.nextMonthStart(now, zone, startDay), pending)
        } else alarms.cancel(pending)
    }
}

class MonthReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MonthReviewRollover.ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8000) { (context.applicationContext as LedgerApplication).repository.refreshNotifications() }
            } catch (_: Exception) {
                // Opening the app or the next ledger change also refreshes the month.
            } finally { pending.finish() }
        }
    }
}
