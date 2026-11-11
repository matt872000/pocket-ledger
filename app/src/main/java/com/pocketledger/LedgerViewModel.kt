package com.pocketledger

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as LedgerApplication).repository
    private val preferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val transactions = repository.transactions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val rules = repository.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = repository.categories.map { list -> list.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultCategories)
    val categorySettings = repository.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultCategories.map { LedgerCategory(it) })
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _automatic = MutableStateFlow(preferences.getBoolean("automatic_sms", false))
    val automatic = _automatic.asStateFlow()
    private val _budget = MutableStateFlow(preferences.getLong("budget_cents", 0))
    val budget = _budget.asStateFlow()
    private val _cycleStartDay = MutableStateFlow(BudgetCyclePreferences.read(preferences))
    val cycleStartDay = _cycleStartDay.asStateFlow()
    private val _alerts = MutableStateFlow(LedgerNotifications.settings(preferences))
    val alerts = _alerts.asStateFlow()
    private val _themeMode = MutableStateFlow(ThemePreferences.read(preferences))
    val themeMode = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        ThemePreferences.save(preferences, mode)
        _themeMode.value = mode
    }

    fun setAutomatic(enabled: Boolean) {
        preferences.edit().putBoolean("automatic_sms", enabled).apply()
        _automatic.value = enabled
    }
    fun setBudget(cents: Long) {
        preferences.edit().putLong("budget_cents", cents).apply()
        _budget.value = cents
        refreshAlerts()
    }
    fun setCycleStartDay(day: Int) {
        if (day !in 1..31) { notify("Choose a start day from 1 to 31."); return }
        preferences.edit().putInt(BudgetCyclePreferences.KEY, day).apply()
        _cycleStartDay.value = day
        refreshAlerts()
        notify("Monthly cycle updated. Totals and reminders now use the new dates.")
    }
    fun setBudgetAlerts(enabled: Boolean) {
        preferences.edit().putBoolean("budget_alerts", enabled).apply()
        _alerts.value = LedgerNotifications.settings(preferences)
        refreshAlerts()
    }
    fun setCategorizationAlerts(enabled: Boolean) {
        preferences.edit().putBoolean("categorization_alerts", enabled).apply()
        _alerts.value = LedgerNotifications.settings(preferences)
        refreshAlerts()
    }
    fun setNotificationAmounts(enabled: Boolean) {
        preferences.edit().putBoolean("notification_amounts", enabled).apply()
        _alerts.value = LedgerNotifications.settings(preferences)
        refreshAlerts()
    }
    fun saveAlertThresholds(text: String) {
        val thresholds = AlertPolicy.parseThresholds(text)
        if (thresholds == null) { notify("Enter up to 10 percentages from 1 to 1000, separated by commas."); return }
        preferences.edit().putString("budget_thresholds", thresholds.joinToString(",")).apply()
        _alerts.value = LedgerNotifications.settings(preferences)
        refreshAlerts()
        notify("Budget alert levels saved.")
    }
    private fun refreshAlerts() { viewModelScope.launch { repository.refreshNotifications() } }
    fun clearNotice() { _notice.value = null }
    fun notify(message: String) { _notice.value = message }
    private fun work(action: suspend () -> String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { _notice.value = action() }
            catch (_: SecurityException) { _notice.value = "SMS access is unavailable. Grant permission in Android Settings or paste a message." }
            catch (error: IllegalArgumentException) { _notice.value = error.message ?: "Please check your entries." }
            catch (_: Exception) { _notice.value = "The operation could not be completed. Please try again." }
            finally { _busy.value = false }
        }
    }
    fun importText(body: String) = work {
        when (repository.importMessage(body, System.currentTimeMillis())) {
            ImportResult.ADDED -> "Transaction imported."
            ImportResult.DUPLICATE -> "This transaction is already in your ledger."
            ImportResult.UNSUPPORTED -> "Message not imported. Use a supported Bank of Communications message, or choose Add transaction."
        }
    }
    fun importInbox() = work {
        val summary = repository.importInbox()
        "${summary.added} added · ${summary.duplicate} duplicates · ${summary.unsupported} unsupported bank messages skipped."
    }
    fun updatePurchase(transaction: Transaction, category: String, purchaseName: String, remember: Boolean, kind: TransactionKind = transaction.kind) = work {
        repository.updatePurchase(transaction, category, purchaseName, remember, kind)
        if (remember) "Purchase saved. Category rule applies to future matching transactions." else "Purchase updated."
    }
    fun addManual(entry: ManualEntry) = work { repository.addManual(entry); "${entry.kind.label} recorded." }
    fun addCategory(name: String) = work { repository.addCategory(name); "Category added." }
    fun setCategoryExcluded(name: String, excluded: Boolean) = work {
        repository.setCategoryExcluded(name, excluded)
        if (excluded) "Category excluded from spending. Existing transactions updated." else "Category included in spending. Existing transactions updated."
    }
    fun removeCategory(name: String) = work { repository.removeCategory(name); "Category removed. Affected purchases are now Uncategorized; matching rules were removed." }
    fun deleteRule(description: String) = work { repository.deleteRule(description); "Rule removed." }
    fun deleteTransaction(id: Long) = work { repository.deleteTransaction(id); "Transaction deleted." }
}
