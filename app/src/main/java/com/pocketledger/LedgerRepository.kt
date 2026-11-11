package com.pocketledger

import android.app.Application
import android.content.Context
import android.provider.Telephony
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class LedgerApplication : Application() {
    val repository by lazy { LedgerRepository(this) }
}

class LedgerRepository(private val context: Context, private val database: LedgerDatabase = LedgerDatabase.get(context)) {
    private val dao = database.dao()
    private val notifications = LedgerNotifications(context.applicationContext, database)
    val transactions = dao.observeTransactions()
    val rules = dao.observeRules()
    val categories = dao.observeCategories()

    suspend fun importMessage(body: String, receivedAt: Long): ImportResult {
        val result = importOne(body, receivedAt)
        if (result == ImportResult.ADDED) refreshNotifications()
        return result
    }

    private suspend fun importOne(body: String, receivedAt: Long): ImportResult = withContext(Dispatchers.IO) {
        val parsed = BankSmsParser.parse(body, receivedAt) ?: return@withContext ImportResult.UNSUPPORTED
        database.withTransaction {
            val category = dao.categoryFor(parsed.description) ?: "Uncategorized"
            val id = dao.insert(Transaction(
                fingerprint = parsed.fingerprint, card = parsed.card, occurredAt = parsed.occurredAt,
                description = parsed.description, amountCents = parsed.amountCents,
                balanceCents = parsed.balanceCents, originalSms = parsed.originalSms, category = category,
                kind = TransactionKind.valueOf(parsed.kind.name)
            ))
            if (id == -1L) ImportResult.DUPLICATE else ImportResult.ADDED
        }
    }

    suspend fun importInbox(): ImportSummary = withContext(Dispatchers.IO) {
        var added = 0
        var duplicate = 0
        var unsupported = 0
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.BODY} LIKE ?", arrayOf("%【交通银行】%"), "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                when (importOne(cursor.getString(bodyColumn) ?: "", cursor.getLong(dateColumn))) {
                    ImportResult.ADDED -> added++
                    ImportResult.DUPLICATE -> duplicate++
                    ImportResult.UNSUPPORTED -> unsupported++
                }
            }
        } ?: error("Could not open your SMS inbox.")
        if (added > 0) refreshNotifications()
        ImportSummary(added, duplicate, unsupported)
    }

    suspend fun updatePurchase(transaction: Transaction, category: String, purchaseName: String, remember: Boolean, kind: TransactionKind = transaction.kind) {
        database.withTransaction {
            val selectedCategory = requireNotNull(dao.categoryNamed(category)) { "This category was removed. Choose another category." }
            require(purchaseNameError(purchaseName) == null) { purchaseNameError(purchaseName).orEmpty() }
            check(dao.updatePurchase(transaction.id, selectedCategory, purchaseName.trim()) == 1) { "This transaction no longer exists." }
            check(dao.updateKind(transaction.id, kind) == 1)
            if (remember) dao.saveRule(CategoryRule(transaction.description, selectedCategory))
        }
        refreshNotifications()
    }
    suspend fun addManual(entry: ManualEntry) {
        require(entry.amountCents > 0 && entry.card.matches(Regex("[0-9]{3,6}"))) { "Enter a positive amount and valid card digits." }
        require(purchaseNameError(entry.name) == null) { purchaseNameError(entry.name).orEmpty() }
        database.withTransaction {
            val category = requireNotNull(dao.categoryNamed(entry.category)) { "This category was removed. Choose another category." }
            dao.insert(Transaction(fingerprint = "manual:${java.util.UUID.randomUUID()}", card = entry.card,
                occurredAt = entry.occurredAt, description = entry.kind.label, amountCents = entry.amountCents,
                balanceCents = entry.reportedBalanceCents ?: 0, originalSms = "", category = category,
                purchaseName = entry.name, kind = entry.kind, hasReportedBalance = entry.reportedBalanceCents != null))
        }
        refreshNotifications()
    }
    suspend fun addCategory(name: String) = database.withTransaction {
        require(categoryNameError(name) == null) { categoryNameError(name).orEmpty() }
        require(dao.insertCategory(LedgerCategory(name.trim())) != -1L) { "A category with that name already exists." }
    }
    suspend fun removeCategory(name: String) {
        database.withTransaction {
            require(!name.equals(UNCATEGORIZED, ignoreCase = true)) { "Uncategorized is kept for purchases without a category." }
            val existing = dao.categoryNamed(name) ?: return@withTransaction
            dao.uncategorize(existing)
            dao.deleteRulesForCategory(existing)
            dao.deleteCategory(existing)
        }
        refreshNotifications()
    }
    suspend fun setCategoryExcluded(name: String, excluded: Boolean) {
        database.withTransaction {
            require(!name.equals(UNCATEGORIZED, ignoreCase = true)) { "Uncategorized transactions count as spending until categorized." }
            require(dao.setCategoryExcluded(name, excluded) == 1) { "This category was removed. Choose another category." }
        }
        refreshNotifications()
    }
    suspend fun deleteRule(description: String) = dao.deleteRule(description)
    suspend fun deleteTransaction(id: Long) { dao.deleteTransaction(id); refreshNotifications() }

    suspend fun refreshNotifications() {
        try { notifications.refresh() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Notification failures must not undo a committed ledger change. */ }
    }
}
enum class ImportResult { ADDED, DUPLICATE, UNSUPPORTED }
data class ImportSummary(val added: Int, val duplicate: Int, val unsupported: Int)
