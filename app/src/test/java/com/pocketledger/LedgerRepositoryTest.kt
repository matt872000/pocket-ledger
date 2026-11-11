package com.pocketledger

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LedgerRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var database: LedgerDatabase? = null
    private val filename = "repository-test.db"
    private val received = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val sms = "【交通银行】您尾号*1234的卡11月01日14:31在支付宝支付宝-消费网上支付28.50元，现余额1,234.56元"

    private fun open(): LedgerDatabase = Room.databaseBuilder(context, LedgerDatabase::class.java, filename)
        .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4).addCallback(LedgerDatabase.SEED_CATEGORIES)
        .allowMainThreadQueries().build().also { database = it }

    @After fun cleanup() { database?.close(); context.deleteDatabase(filename) }

    @Test fun `upgrade preserves existing purchases rules and fingerprints`() = runBlocking {
        val parsed = requireNotNull(BankSmsParser.parse(sms, received))
        context.openOrCreateDatabase(filename, 0, null).use { old ->
            old.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fingerprint TEXT NOT NULL, card TEXT NOT NULL, occurredAt INTEGER NOT NULL, description TEXT NOT NULL, amountCents INTEGER NOT NULL, balanceCents INTEGER NOT NULL, originalSms TEXT NOT NULL, category TEXT NOT NULL)")
            old.execSQL("CREATE UNIQUE INDEX index_transactions_fingerprint ON transactions (fingerprint)")
            old.execSQL("CREATE TABLE rules (description TEXT NOT NULL, category TEXT NOT NULL, PRIMARY KEY(description))")
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42, '0866c57323ddc0e7632b86ef8559b73f')")
            old.execSQL("INSERT INTO transactions VALUES(7, ?, '1234', ?, ?, 2850, 123456, ?, 'Food & drink')",
                arrayOf(parsed.fingerprint, parsed.occurredAt, parsed.description, sms))
            old.execSQL("INSERT INTO rules VALUES(?, 'Shopping')", arrayOf(parsed.description))
            old.version = 1
        }
        val db = open()
        val repository = LedgerRepository(context, db)
        val purchase = repository.transactions.first().single()
        assertEquals(7L, purchase.id)
        assertEquals(sms, purchase.originalSms)
        assertEquals(parsed.fingerprint, purchase.fingerprint)
        assertEquals(2850L, purchase.amountCents)
        assertEquals("Food & drink", purchase.category)
        assertEquals("", purchase.purchaseName)
        assertEquals(TransactionKind.EXPENSE, purchase.kind)
        assertTrue(purchase.hasReportedBalance)
        assertEquals("Alipay payment", purchase.displayName)
        assertEquals("Shopping", repository.rules.first().single().category)
        assertEquals(defaultCategories.toSet(), repository.categories.first().map { it.name }.toSet())
        assertTrue(repository.categories.first().none { it.excludeFromSpending })
        assertEquals(ImportResult.DUPLICATE, repository.importMessage(sms, received))
    }

    @Test fun `custom categories and purchase names persist after reopening without affecting deduplication`() = runBlocking {
        var db = open()
        var repository = LedgerRepository(context, db)
        repository.addCategory("  Coffee  ")
        assertEquals(ImportResult.ADDED, repository.importMessage(sms, received))
        val purchase = repository.transactions.first().single()
        repository.updatePurchase(purchase, "Coffee", "  Morning coffee  ", true)
        db.close()
        db = open()
        repository = LedgerRepository(context, db)
        val saved = repository.transactions.first().single()
        assertEquals("Coffee", saved.category)
        assertEquals("Morning coffee", saved.displayName)
        assertEquals(sms, saved.originalSms)
        assertEquals(purchase.fingerprint, saved.fingerprint)
        assertEquals(ImportResult.DUPLICATE, repository.importMessage(sms, received))
        assertTrue(repository.categories.first().any { it.name == "Coffee" })
        assertEquals(ImportResult.ADDED, repository.importMessage(sms.replace("28.50", "30.00"), received))
        val next = repository.transactions.first().first { it.amountCents == 3000L }
        assertEquals("Coffee", next.category)
        assertEquals("Alipay payment", next.displayName)
    }

    @Test fun `removing a used category preserves purchases and removes its rules permanently`() = runBlocking {
        val db = open()
        var repository = LedgerRepository(context, db)
        repository.importMessage(sms, received)
        val purchase = repository.transactions.first().single()
        repository.updatePurchase(purchase, "Shopping", "New shoes", true)
        repository.removeCategory("shopping")
        val saved = repository.transactions.first().single()
        assertEquals(UNCATEGORIZED, saved.category)
        assertEquals("New shoes", saved.displayName)
        assertEquals(purchase.fingerprint, saved.fingerprint)
        assertTrue(repository.rules.first().isEmpty())
        db.close()
        repository = LedgerRepository(context, open())
        assertFalse(repository.categories.first().any { it.name == "Shopping" })
        repository.importMessage(sms.replace("28.50", "30.00"), received)
        assertTrue(repository.transactions.first().all { it.category == UNCATEGORIZED })
    }

    @Test fun `reserved category duplicate names and invalid names are rejected`() = runBlocking {
        val repository = LedgerRepository(context, open())
        assertTrue(runCatching { repository.removeCategory("uncategorized") }.isFailure)
        assertTrue(runCatching { repository.addCategory("  groceries  ") }.isFailure)
        assertTrue(runCatching { repository.addCategory(" ") }.isFailure)
        assertTrue(runCatching { repository.addCategory("Food\nOther") }.isFailure)
        assertTrue(runCatching { repository.addCategory("购物") }.isFailure)
        assertTrue(runCatching { repository.setCategoryExcluded("uncategorized", true) }.isFailure)
        assertTrue(runCatching { repository.setCategoryExcluded("Missing", true) }.isFailure)
        assertEquals(defaultCategories.size, repository.categories.first().size)
        repository.importMessage(sms, received)
        val purchase = repository.transactions.first().single()
        assertTrue(runCatching { repository.updatePurchase(purchase, "Missing", "Lunch", false) }.isFailure)
        assertTrue(runCatching { repository.updatePurchase(purchase, UNCATEGORIZED, "Lunch\nDinner", false) }.isFailure)
        assertEquals("", repository.transactions.first().single().purchaseName)
    }

    @Test fun `clearing a purchase name restores the payment label`() = runBlocking {
        val repository = LedgerRepository(context, open())
        repository.importMessage(sms, received)
        val purchase = repository.transactions.first().single()
        repository.updatePurchase(purchase, "Groceries", "Weekly shopping", false)
        repository.updatePurchase(purchase, "Groceries", "  ", false)
        assertEquals("Alipay payment", repository.transactions.first().single().displayName)
        assertEquals("Groceries", repository.transactions.first().single().category)
    }

    @Test fun `version two migration keeps custom categories names rules and balances included by default`() = runBlocking {
        val parsed = requireNotNull(BankSmsParser.parse(sms, received))
        context.openOrCreateDatabase(filename, 0, null).use { old ->
            old.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fingerprint TEXT NOT NULL, card TEXT NOT NULL, occurredAt INTEGER NOT NULL, description TEXT NOT NULL, amountCents INTEGER NOT NULL, balanceCents INTEGER NOT NULL, originalSms TEXT NOT NULL, category TEXT NOT NULL, purchaseName TEXT NOT NULL DEFAULT '')")
            old.execSQL("CREATE UNIQUE INDEX index_transactions_fingerprint ON transactions (fingerprint)")
            old.execSQL("CREATE TABLE rules (description TEXT NOT NULL, category TEXT NOT NULL, PRIMARY KEY(description))")
            old.execSQL("CREATE TABLE categories (name TEXT COLLATE NOCASE NOT NULL, PRIMARY KEY(name))")
            old.execSQL("INSERT INTO categories VALUES('My transfers')")
            old.execSQL("INSERT INTO transactions VALUES(7, ?, '1234', ?, ?, 2850, 123456, ?, 'My transfers', 'Move money')",
                arrayOf(parsed.fingerprint, parsed.occurredAt, parsed.description, sms))
            old.execSQL("INSERT INTO rules VALUES(?, 'My transfers')", arrayOf(parsed.description))
            old.version = 2
        }
        val repository = LedgerRepository(context, open())
        val saved = repository.transactions.first().single()
        assertEquals("Move money", saved.purchaseName)
        assertEquals("My transfers", saved.category)
        assertEquals(123456L, saved.balanceCents)
        assertEquals(parsed.fingerprint, saved.fingerprint)
        assertEquals(sms, saved.originalSms)
        assertFalse(repository.categories.first().single().excludeFromSpending)
        assertEquals("My transfers", repository.rules.first().single().category)
        assertEquals(ImportResult.DUPLICATE, repository.importMessage(sms, received))
    }

    @Test fun `version three migration preserves excluded categories and adds expense defaults without changing fingerprints`() = runBlocking {
        val parsed = requireNotNull(BankSmsParser.parse(sms, received))
        context.openOrCreateDatabase(filename, 0, null).use { old ->
            old.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fingerprint TEXT NOT NULL, card TEXT NOT NULL, occurredAt INTEGER NOT NULL, description TEXT NOT NULL, amountCents INTEGER NOT NULL, balanceCents INTEGER NOT NULL, originalSms TEXT NOT NULL, category TEXT NOT NULL, purchaseName TEXT NOT NULL DEFAULT '')")
            old.execSQL("CREATE UNIQUE INDEX index_transactions_fingerprint ON transactions (fingerprint)")
            old.execSQL("CREATE TABLE rules (description TEXT NOT NULL, category TEXT NOT NULL, PRIMARY KEY(description))")
            old.execSQL("CREATE TABLE categories (name TEXT COLLATE NOCASE NOT NULL, excludeFromSpending INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(name))")
            old.execSQL("INSERT INTO categories VALUES('My transfers', 1)")
            old.execSQL("INSERT INTO transactions VALUES(7, ?, '1234', ?, ?, 2850, 123456, ?, 'My transfers', 'Move money')",
                arrayOf(parsed.fingerprint, parsed.occurredAt, parsed.description, sms))
            old.execSQL("INSERT INTO rules VALUES(?, 'My transfers')", arrayOf(parsed.description))
            old.version = 3
        }
        val repository = LedgerRepository(context, open())
        val saved = repository.transactions.first().single()
        assertEquals(TransactionKind.EXPENSE, saved.kind)
        assertTrue(saved.hasReportedBalance)
        assertEquals("Move money", saved.purchaseName)
        assertEquals(parsed.fingerprint, saved.fingerprint)
        assertTrue(repository.categories.first().single().excludeFromSpending)
        assertEquals("My transfers", repository.rules.first().single().category)
        assertEquals(ImportResult.DUPLICATE, repository.importMessage(sms, received))
    }

    @Test fun `refund and incoming messages import with their types deduplicate and retain balance observations`() = runBlocking {
        val repository = LedgerRepository(context, open())
        val refund = sms.replace("网上支付", "退款").replace("28.50", "5.00")
        val income = "【交通银行】贵账户6230000000001234于2026年10月20日11:10在四川省分行跨行汇款转入资金1,000.00元，现余额2,000.00元，对方户名：示例公司，附言：工资Salary-示例"
        for (message in listOf(sms, refund, income)) {
            assertEquals(ImportResult.ADDED, repository.importMessage(message, received))
            assertEquals(ImportResult.DUPLICATE, repository.importMessage(message, received + 1000))
        }
        val records = repository.transactions.first()
        assertEquals(TransactionKind.entries.toSet(), records.map { it.kind }.toSet())
        assertEquals(java.math.BigInteger.valueOf(2350), SpendingPolicy.net(records, emptySet()))
        assertTrue(records.all { it.hasReportedBalance && !it.isManual })
        assertEquals("Alipay refund", records.first { it.kind == TransactionKind.REFUND }.displayName)
        assertEquals("Salary payment", records.first { it.kind == TransactionKind.INCOME }.displayName)
        assertEquals(3, records.size)
    }

    @Test fun `manual credits persist types and unknown balances while identical intentional entries remain distinct`() = runBlocking {
        var db = open()
        var repository = LedgerRepository(context, db)
        val refund = TransactionEntry.validate(TransactionKind.REFUND, "1234", "10.25", "2026-11-02", "12:30", "Shopping", "Returned item", "")
        val income = refund.copy(kind = TransactionKind.INCOME, amountCents = 20000, reportedBalanceCents = 0)
        repository.addManual(refund)
        repository.addManual(refund)
        repository.addManual(income)
        db.close()
        db = open()
        repository = LedgerRepository(context, db)
        val saved = repository.transactions.first()
        assertEquals(3, saved.map { it.fingerprint }.toSet().size)
        assertTrue(saved.all { it.isManual && it.originalSms.isEmpty() })
        assertTrue(saved.filter { it.kind == TransactionKind.REFUND }.none { it.hasReportedBalance })
        assertTrue(saved.single { it.kind == TransactionKind.INCOME }.hasReportedBalance)
        assertEquals(0L, RecordedBalances.histories(saved).single().latest.balanceCents)
        assertEquals(java.math.BigInteger.valueOf(-2050), SpendingPolicy.net(saved, emptySet()))
    }

    @Test fun `changing type keeps original SMS fingerprint and amount while updating net spending`() = runBlocking {
        val repository = LedgerRepository(context, open())
        repository.importMessage(sms, received)
        val before = repository.transactions.first().single()
        repository.updatePurchase(before, "Shopping", "Returned item", false, TransactionKind.REFUND)
        val after = repository.transactions.first().single()
        assertEquals(before.fingerprint, after.fingerprint)
        assertEquals(before.originalSms, after.originalSms)
        assertEquals(before.amountCents, after.amountCents)
        assertEquals(before.balanceCents, after.balanceCents)
        assertEquals(java.math.BigInteger.valueOf(-2850), SpendingPolicy.net(listOf(after), emptySet()))
        assertEquals(ImportResult.DUPLICATE, repository.importMessage(sms, received))
    }

    @Test fun `exclusion persists and applies to future rules without altering records or surviving category deletion`() = runBlocking {
        var db = open()
        var repository = LedgerRepository(context, db)
        repository.addCategory("My transfers")
        repository.importMessage(sms, received)
        repository.updatePurchase(repository.transactions.first().single(), "My transfers", "Move money", true)
        val before = repository.transactions.first().single()
        repository.setCategoryExcluded("my transfers", true)
        assertEquals(before, repository.transactions.first().single())
        db.close()
        db = open()
        repository = LedgerRepository(context, db)
        assertEquals(listOf("My transfers"), db.dao().excludedCategories())
        assertEquals(ImportResult.ADDED, repository.importMessage(sms.replace("28.50", "30.00"), received))
        val transactions = repository.transactions.first()
        assertTrue(transactions.all { it.category == "My transfers" })
        assertTrue(SpendingPolicy.transactions(transactions, db.dao().excludedCategories().toSet()).isEmpty())
        repository.setCategoryExcluded("My transfers", false)
        assertEquals(2, SpendingPolicy.transactions(transactions, db.dao().excludedCategories().toSet()).size)
        repository.setCategoryExcluded("My transfers", true)
        repository.removeCategory("My transfers")
        assertTrue(db.dao().excludedCategories().isEmpty())
        assertTrue(repository.transactions.first().all { it.category == UNCATEGORIZED })
        repository.addCategory("My transfers")
        assertFalse(repository.categories.first().first { it.name == "My transfers" }.excludeFromSpending)
    }
    @Test fun `network payment credit imports once updates the reported balance and does not increase spending`() = runBlocking {
        val repository = LedgerRepository(context, open())
        repository.importMessage(sms, received)
        val incoming = "【交通银行】您尾号*1234的卡于2026年11月06日07:54网络支付转入123.45元,交易后余额为456.78元。"
        assertEquals(ImportResult.ADDED, repository.importMessage(incoming, received))
        assertEquals(ImportResult.DUPLICATE, repository.importMessage(incoming, received + 1000))
        val entries = repository.transactions.first()
        val credit = entries.single { it.kind == TransactionKind.INCOME }
        assertEquals("Incoming payment", credit.displayName)
        assertEquals(12345L, credit.amountCents)
        assertTrue(credit.hasReportedBalance)
        assertEquals(java.math.BigInteger.valueOf(2850), SpendingPolicy.net(entries, emptySet()))
        assertEquals(45678L, RecordedBalances.histories(entries).single().latest.balanceCents)
    }
}
