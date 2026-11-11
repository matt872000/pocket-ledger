package com.pocketledger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class LedgerNotifications(private val context: Context, private val database: LedgerDatabase) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = context.getSharedPreferences("notification_state", Context.MODE_PRIVATE)

    companion object {
        const val BUDGET_CHANNEL = "monthly_budget"
        const val CATEGORIZATION_CHANNEL = "categorization"
        const val BUDGET_ID = 1001
        const val CATEGORIZATION_ID = 1002
        const val REVIEW_CATEGORIES = "com.pocketledger.REVIEW_CATEGORIES"
        const val REVIEW_BUDGET = "com.pocketledger.REVIEW_BUDGET"
        private val refreshMutex = Mutex()

        fun settings(preferences: SharedPreferences) = AlertSettings(
            budgetEnabled = preferences.getBoolean("budget_alerts", false),
            categorizationEnabled = preferences.getBoolean("categorization_alerts", false),
            thresholds = AlertPolicy.parseThresholds(preferences.getString("budget_thresholds", "80,100").orEmpty()) ?: listOf(80, 100),
            showBudgetAmounts = preferences.getBoolean("notification_amounts", false)
        )

        fun permissionGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun refresh(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            createChannels()
            val settings = settings(preferences)
            val budget = preferences.getLong("budget_cents", 0)
            val startDay = BudgetCyclePreferences.read(preferences)
            val month = BudgetCycle.current(now, zone, startDay)
            MonthReviewRollover.schedule(context, settings.categorizationEnabled || settings.budgetEnabled, now, zone, startDay)
            // Keep legacy day-1 threshold IDs intact; custom cycles get their own identity.
            val cycleKey = if (startDay == 1) month.toString() else "$month@day$startDay"
            val (purchases, excludedCategories) = database.withTransaction {
                database.dao().alertPurchases() to database.dao().excludedCategories().toSet()
            }
            val budgetContext = "$cycleKey|$budget|${settings.thresholds}|${settings.showBudgetAmounts}|${excludedCategories.sorted()}"
            if (!settings.budgetEnabled || budget <= 0 || state.getString("budget_context", null) != budgetContext) {
                manager.cancel(BUDGET_ID)
            }
            state.edit().putString("budget_context", budgetContext).apply()
            if (!settings.categorizationEnabled || state.getString("pending_month", null) != cycleKey) {
                manager.cancel(CATEGORIZATION_ID)
                state.edit().remove("pending_signature").remove("pending_count").putString("pending_month", cycleKey).apply()
            }
            if (!permissionGranted(context) || !manager.areNotificationsEnabled()) return@withLock
            if (!settings.budgetEnabled && !settings.categorizationEnabled) return@withLock
            if (settings.budgetEnabled && budget > 0 && channelEnabled(BUDGET_CHANNEL)) {
                val spent = AlertPolicy.spending(purchases, month, zone, excludedCategories, startDay)
                val reached = AlertPolicy.reached(spent, budget, settings.thresholds)
                if (state.getInt("budget_posted_threshold", 0) !in reached) manager.cancel(BUDGET_ID)
                val sent = state.getStringSet("budget_sent", emptySet()).orEmpty().toMutableSet()
                val due = reached.filter { "$cycleKey|$budget|$it" !in sent }
                if (due.isNotEmpty()) {
                    val threshold = due.last()
                    val text = "Spending reached $threshold% of this cycle's budget." +
                        if (settings.showBudgetAmounts) " CNY ${BigDecimal(spent, 2).toPlainString()} of ${BigDecimal.valueOf(budget, 2).toPlainString()}." else " Tap to review."
                    if (post(BUDGET_ID, builder(BUDGET_CHANNEL, REVIEW_BUDGET)
                            .setContentTitle("Monthly budget alert").setContentText(text)
                            .setStyle(Notification.BigTextStyle().bigText(text)).build())) {
                        due.forEach { sent.add("$cycleKey|$budget|$it") }
                        val oldest = month.minusMonths(12).toString()
                        sent.removeAll { it.take(7) < oldest }
                        state.edit().putStringSet("budget_sent", sent).putInt("budget_posted_threshold", threshold).commit()
                    }
                }
            }
            if (settings.categorizationEnabled && channelEnabled(CATEGORIZATION_CHANNEL)) {
                val pending = purchases.filter { CategorizationReview.includes(it.category, it.occurredAt, month, zone, startDay) }.map { it.id }.sorted()
                val signature = pending.joinToString(",")
                if (pending.isEmpty()) {
                    manager.cancel(CATEGORIZATION_ID)
                    state.edit().putString("pending_signature", "").putInt("pending_count", 0).apply()
                } else if (state.getString("pending_signature", null) != signature) {
                    val previousCount = state.getInt("pending_count", 0)
                    val active = manager.activeNotifications.any { it.id == CATEGORIZATION_ID }
                    // Do not bring back a dismissed review notification while the user clears its backlog.
                    if (!active && pending.size < previousCount) {
                        state.edit().putString("pending_signature", signature).putInt("pending_count", pending.size).apply()
                    } else {
                        val text = if (pending.size == 1) "1 transaction this cycle needs a category. Tap to review." else
                            "${pending.size} transactions this cycle need categories. Tap to review."
                        if (post(CATEGORIZATION_ID, builder(CATEGORIZATION_CHANNEL, REVIEW_CATEGORIES)
                                .setContentTitle("Transactions need categorization").setContentText(text)
                                .setNumber(pending.size).setOnlyAlertOnce(true).build())) {
                            state.edit().putString("pending_signature", signature).putInt("pending_count", pending.size).commit()
                        }
                    }
                }
            }
        }
    }

    private fun createChannels() {
        manager.createNotificationChannels(listOf(
            NotificationChannel(BUDGET_CHANNEL, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts at your chosen monthly spending percentages"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
            NotificationChannel(CATEGORIZATION_CHANNEL, "Needs categorization", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Transactions waiting for a category"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        ))
    }

    private fun channelEnabled(id: String) = manager.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun builder(channel: String, action: String): Notification.Builder {
        val intent = Intent(context, MainActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, if (action == REVIEW_BUDGET) BUDGET_ID else CATEGORIZATION_ID,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val publicVersion = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pocket Ledger").setContentText("Open Pocket Ledger to review your alerts.").build()
        return Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion).setCategory(Notification.CATEGORY_REMINDER)
    }

    private fun post(id: Int, notification: Notification): Boolean {
        if (!permissionGranted(context)) return false
        return try { manager.notify(id, notification); true } catch (_: SecurityException) { false }
    }
}
