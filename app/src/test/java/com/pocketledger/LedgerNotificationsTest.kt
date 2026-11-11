package com.pocketledger

import android.Manifest
import android.app.Application
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LedgerNotificationsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var database: LedgerDatabase
    private lateinit var alerts: LedgerNotifications
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val december = ZonedDateTime.of(2026, 12, 2, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Before fun setup() {
        preferences.edit().clear().putInt(BudgetCyclePreferences.KEY, 1).commit()
        context.getSharedPreferences("notification_state", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(manager).setNotificationsEnabled(true)
        manager.cancelAll()
        database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .addCallback(LedgerDatabase.SEED_CATEGORIES).allowMainThreadQueries().build()
        alerts = LedgerNotifications(context, database)
    }
    @After fun close() { database.close() }

    private suspend fun insert(amount: Long, date: Long = now, category: String = UNCATEGORIZED): Long =
        database.dao().insert(Transaction(fingerprint = java.util.UUID.randomUUID().toString(), card = "1234",
            occurredAt = date, description = "支付宝支付宝-消费", amountCents = amount, balanceCents = 123456,
            originalSms = "PRIVATE ORIGINAL SMS", category = category, purchaseName = "Private purchase"))
    private fun budgetNotification() = shadowOf(manager).getNotification(LedgerNotifications.BUDGET_ID)
    private fun categoryNotification() = shadowOf(manager).getNotification(LedgerNotifications.CATEGORIZATION_ID)
    private fun text(notification: Notification) = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test fun `alerts are opt in and do not affect ledger imports`() = runBlocking {
        insert(9000)
        preferences.edit().putLong("budget_cents", 10000).commit()
        alerts.refresh(now, zone)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertFalse(LedgerNotifications.settings(preferences).budgetEnabled)
        assertFalse(LedgerNotifications.settings(preferences).categorizationEnabled)
    }

    @Test fun `budget levels alert once across restarts with higher thresholds and new months`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000).commit()
        insert(8000)
        alerts.refresh(now, zone)
        val first = requireNotNull(budgetNotification())
        assertTrue(text(first).contains("80%"))
        assertFalse(text(first).contains("CNY"))
        assertEquals(Notification.VISIBILITY_PRIVATE, first.visibility)
        assertEquals("Open Pocket Ledger to review your alerts.", text(first.publicVersion))
        assertEquals(LedgerNotifications.REVIEW_BUDGET, shadowOf(first.contentIntent).savedIntent.action)
        LedgerNotifications(context, database).refresh(now, zone)
        assertSame(first, budgetNotification())
        insert(2000)
        alerts.refresh(now, zone)
        val full = requireNotNull(budgetNotification())
        assertTrue(text(full).contains("100%"))
        alerts.refresh(now, zone)
        assertSame(full, budgetNotification())
        insert(8000, december)
        alerts.refresh(december, zone)
        assertTrue(text(requireNotNull(budgetNotification())).contains("80%"))
    }

    @Test fun `batch crossings use one highest alert and newly configured levels can notify`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000).commit()
        insert(13000)
        alerts.refresh(now, zone)
        val first = requireNotNull(budgetNotification())
        assertEquals(1, shadowOf(manager).size())
        assertTrue(text(first).contains("100%"))
        alerts.refresh(now, zone)
        assertSame(first, budgetNotification())
        preferences.edit().putString("budget_thresholds", "50,80,100,125").commit()
        alerts.refresh(now, zone)
        assertTrue(text(requireNotNull(budgetNotification())).contains("125%"))
        preferences.edit().putLong("budget_cents", 20000).commit()
        alerts.refresh(now, zone)
        assertTrue(text(requireNotNull(budgetNotification())).contains("50%"))
        preferences.edit().putLong("budget_cents", 0).commit()
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
    }

    @Test fun `pending summary includes only current month hides details and leaves past transactions uncategorized`() = runBlocking {
        preferences.edit().putBoolean("categorization_alerts", true).commit()
        insert(2850)
        insert(100, now - 40L * 24 * 60 * 60 * 1000)
        insert(500, category = "Groceries")
        alerts.refresh(now, zone)
        val notification = requireNotNull(categoryNotification())
        assertEquals(1, notification.number)
        assertTrue(text(notification).contains("1 transaction this cycle"))
        assertFalse(text(notification).contains("2850"))
        assertFalse(text(notification).contains("Private"))
        assertFalse(text(notification).contains("SMS"))
        assertEquals(LedgerNotifications.REVIEW_CATEGORIES, shadowOf(notification.contentIntent).savedIntent.action)
        assertEquals("Open Pocket Ledger to review your alerts.", text(notification.publicVersion))
        val repository = LedgerRepository(context, database)
        val current = repository.transactions.first().single { it.category == UNCATEGORIZED && it.occurredAt == now }
        repository.updatePurchase(current, "Groceries", "Lunch", false)
        assertNull(categoryNotification())
        assertEquals(1, repository.transactions.first().count { it.category == UNCATEGORIZED })
        assertEquals(3, repository.transactions.first().size)
    }

    @Test fun `past and future uncategorized records do not create a reminder`() = runBlocking {
        preferences.edit().putBoolean("categorization_alerts", true).commit()
        insert(100, now - 40L * 86400000)
        insert(100, december)
        alerts.refresh(now, zone)
        assertNull(categoryNotification())
        assertEquals(2, database.dao().alertPurchases().count { it.category == UNCATEGORIZED })
    }

    @Test fun `month rollover reviews the new month despite a dismissed larger backlog and later clears it`() = runBlocking {
        preferences.edit().putBoolean("categorization_alerts", true).commit()
        insert(100)
        insert(200)
        insert(300, december)
        alerts.refresh(now, zone)
        assertEquals(2, requireNotNull(categoryNotification()).number)
        manager.cancel(LedgerNotifications.CATEGORIZATION_ID)
        alerts.refresh(december, zone)
        assertEquals(1, requireNotNull(categoryNotification()).number)
        val january = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        alerts.refresh(january, zone)
        assertNull(categoryNotification())
        assertEquals(3, database.dao().alertPurchases().count { it.category == UNCATEGORIZED })
    }

    @Test fun `month refresh uses one non-wakeup alarm and disabling categorization cancels it`() = runBlocking {
        preferences.edit().putBoolean("categorization_alerts", true).commit()
        alerts.refresh(now, zone)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), alarm.triggerAtTime)
        assertEquals(MonthReviewRollover.ACTION, shadowOf(alarm.operation).savedIntent.action)
        assertEquals(MonthReviewReceiver::class.java.name, shadowOf(alarm.operation).savedIntent.component?.className)
        alerts.refresh(now + 1000, zone)
        assertEquals(1, alarms.scheduledAlarms.size)
        preferences.edit().putBoolean("categorization_alerts", false).commit()
        alerts.refresh(now, zone)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun `duplicate imports do not repost and removed categories trigger a new review summary`() = runBlocking {
        preferences.edit().putBoolean("categorization_alerts", true).commit()
        val repository = LedgerRepository(context, database)
        val sms = "【交通银行】您尾号*1234的卡11月01日14:31在支付宝支付宝-消费网上支付28.50元，现余额1,234.56元"
        assertEquals(ImportResult.ADDED, repository.importMessage(sms, now))
        val first = requireNotNull(categoryNotification())
        assertEquals(ImportResult.DUPLICATE, repository.importMessage(sms, now))
        assertSame(first, categoryNotification())
        val purchase = repository.transactions.first().single()
        repository.updatePurchase(purchase, "Shopping", "Shoes", true)
        assertNull(categoryNotification())
        repository.importMessage(sms.replace("28.50", "30.00"), now)
        assertNull(categoryNotification())
        repository.removeCategory("Shopping")
        assertEquals(2, requireNotNull(categoryNotification()).number)
    }

    @Test fun `turning off either alert clears it and does not disable the other`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000)
            .putBoolean("categorization_alerts", true).commit()
        insert(8000)
        alerts.refresh(now, zone)
        assertEquals(2, shadowOf(manager).size())
        preferences.edit().putBoolean("budget_alerts", false).commit()
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        assertNotNull(categoryNotification())
        preferences.edit().putBoolean("categorization_alerts", false).commit()
        alerts.refresh(now, zone)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `amounts require opt in and turning them off removes an existing detailed alert`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000)
            .putBoolean("notification_amounts", true).commit()
        insert(8000)
        alerts.refresh(now, zone)
        val detailed = requireNotNull(budgetNotification())
        assertTrue(text(detailed).contains("CNY 80.00 of 100.00"))
        assertFalse(text(detailed.publicVersion).contains("80.00"))
        preferences.edit().putBoolean("notification_amounts", false).commit()
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
    }

    @Test fun `Android global blocking preserves the next budget alert until reenabled`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000).commit()
        insert(8000)
        shadowOf(manager).setNotificationsEnabled(false)
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        shadowOf(manager).setNotificationsEnabled(true)
        alerts.refresh(now, zone)
        assertTrue(text(requireNotNull(budgetNotification())).contains("80%"))
    }

    @Test fun `Android channel blocking does not consume a budget threshold`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000).commit()
        insert(8000)
        manager.createNotificationChannel(NotificationChannel(LedgerNotifications.BUDGET_CHANNEL, "Budget alerts", NotificationManager.IMPORTANCE_NONE))
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        assertTrue(context.getSharedPreferences("notification_state", Context.MODE_PRIVATE).getStringSet("budget_sent", emptySet()).orEmpty().isEmpty())
    }

    @Test fun `excluded transfers do not trigger budget alerts while uncategorized spending is still reviewed`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000)
            .putBoolean("categorization_alerts", true).commit()
        database.dao().insertCategory(LedgerCategory("My transfers", excludeFromSpending = true))
        insert(20000, category = "My transfers")
        insert(1000)
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        assertNotNull(categoryNotification())
        assertEquals(1, categoryNotification().number)
        assertTrue(context.getSharedPreferences("notification_state", Context.MODE_PRIVATE).getStringSet("budget_sent", emptySet()).orEmpty().isEmpty())
    }

    @Test fun `reclassifying a purchase as a transfer clears stale budget alerts and preserves once-per-month markers`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000).commit()
        database.dao().insertCategory(LedgerCategory("My transfers", excludeFromSpending = true))
        val id = insert(11000, category = "Groceries")
        alerts.refresh(now, zone)
        assertNotNull(budgetNotification())
        database.dao().updatePurchase(id, "My transfers", "Move money")
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        database.dao().setCategoryExcluded("My transfers", false)
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        val markers = context.getSharedPreferences("notification_state", Context.MODE_PRIVATE).getStringSet("budget_sent", emptySet()).orEmpty()
        assertEquals(2, markers.size)
    }

    @Test fun `refund clears an obsolete budget alert and incoming payments do not trigger spending alerts`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putLong("budget_cents", 10000).commit()
        insert(11000, category = "Shopping")
        alerts.refresh(now, zone)
        assertNotNull(budgetNotification())
        val refundId = insert(4000, category = "Shopping")
        database.dao().updateKind(refundId, TransactionKind.REFUND)
        val incomeId = insert(1000000, category = "Other")
        database.dao().updateKind(incomeId, TransactionKind.INCOME)
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
    }

    @Test fun `income alone does not reach a budget threshold and both credit types need categorization`() = runBlocking {
        preferences.edit().putBoolean("budget_alerts", true).putBoolean("categorization_alerts", true).putLong("budget_cents", 10000).commit()
        val incomeId = insert(1000000)
        database.dao().updateKind(incomeId, TransactionKind.INCOME)
        val refundId = insert(1000)
        database.dao().updateKind(refundId, TransactionKind.REFUND)
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        assertEquals(2, categoryNotification().number)
        assertTrue(text(categoryNotification()).contains("2 transactions"))
    }

    @Test fun `default rollover day is 21 and saved valid choices override it`() {
        preferences.edit().remove(BudgetCyclePreferences.KEY).commit()
        assertEquals(21, BudgetCyclePreferences.read(preferences))
        preferences.edit().putInt(BudgetCyclePreferences.KEY, 31).commit()
        assertEquals(31, BudgetCyclePreferences.read(preferences))
        preferences.edit().putInt(BudgetCyclePreferences.KEY, 0).commit()
        assertEquals(21, BudgetCyclePreferences.read(preferences))
    }

    @Test fun `custom cycle drops old review backlog at midnight and replaces its alarm`() = runBlocking {
        preferences.edit().putInt(BudgetCyclePreferences.KEY, 21).putBoolean("categorization_alerts", true).commit()
        val before = ZonedDateTime.of(2026, 11, 20, 23, 59, 59, 0, zone).toInstant().toEpochMilli()
        val after = before + 1000
        insert(100, before)
        insert(100, before)
        insert(100, after)
        alerts.refresh(before, zone)
        assertEquals(2, categoryNotification().number)
        manager.cancel(LedgerNotifications.CATEGORIZATION_ID)
        alerts.refresh(after, zone)
        assertEquals(1, categoryNotification().number)
        assertEquals(3, database.dao().alertPurchases().size)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(ZonedDateTime.of(2026, 12, 21, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), alarms.single().triggerAtTime)
    }

    @Test fun `calendar month change does not reset custom cycle budget thresholds`() = runBlocking {
        preferences.edit().putInt(BudgetCyclePreferences.KEY, 21).putBoolean("budget_alerts", true).putLong("budget_cents", 10000).commit()
        val october = ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        insert(8000, october)
        alerts.refresh(october, zone)
        assertNotNull(budgetNotification())
        manager.cancel(LedgerNotifications.BUDGET_ID)
        alerts.refresh(now, zone)
        assertNull(budgetNotification())
        val next = ZonedDateTime.of(2026, 11, 21, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        insert(8000, next)
        alerts.refresh(next, zone)
        assertNotNull(budgetNotification())
    }

    @Test fun `changing rollover day refreshes review range without deleting past records`() = runBlocking {
        preferences.edit().putBoolean("categorization_alerts", true).commit()
        insert(100)
        insert(100)
        val old = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        insert(100, old)
        alerts.refresh(now, zone)
        assertEquals(2, categoryNotification().number)
        preferences.edit().putInt(BudgetCyclePreferences.KEY, 21).commit()
        alerts.refresh(now, zone)
        assertEquals(3, categoryNotification().number)
        assertEquals(3, database.dao().alertPurchases().size)
    }

    @Test @Config(sdk = [33]) fun `Android 13 permission denial preserves imports and allows alerts after permission is granted`() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        preferences.edit().putBoolean("categorization_alerts", true).putBoolean("budget_alerts", true)
            .putLong("budget_cents", 10000).commit()
        insert(8000)
        alerts.refresh(now, zone)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        val repository = LedgerRepository(context, database)
        val sms = "【交通银行】您尾号*1234的卡11月01日14:31在支付宝支付宝-消费网上支付28.50元，现余额1,234.56元"
        assertEquals(ImportResult.ADDED, repository.importMessage(sms, now))
        assertEquals(2, repository.transactions.first().size)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        alerts.refresh(now, zone)
        assertEquals(2, shadowOf(manager).size())
        assertTrue(text(requireNotNull(budgetNotification())).contains("100%"))
    }
}
