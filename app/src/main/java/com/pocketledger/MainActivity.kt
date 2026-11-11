package com.pocketledger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var notificationAction by mutableStateOf<String?>(null)
    private var notificationRequest by mutableLongStateOf(0L)

    private fun readNotificationIntent(intent: Intent) {
        if (intent.action in listOf(LedgerNotifications.REVIEW_CATEGORIES, LedgerNotifications.REVIEW_BUDGET)) {
            notificationAction = intent.action
            notificationRequest++
            intent.action = Intent.ACTION_MAIN
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { (application as LedgerApplication).repository.refreshNotifications() }
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
            setLayoutDirection(Locale.ENGLISH)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readNotificationIntent(intent)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[LedgerViewModel::class.java]
        setContent {
            val themeMode by model.themeMode.collectAsStateWithLifecycle()
            val dark = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark })
            }
            MaterialTheme(colorScheme = if (dark) ledgerDarkColors else ledgerLightColors) { LedgerApp(model) }
        }
    }

    @Composable
    private fun LedgerApp(model: LedgerViewModel) {
        val transactions by model.transactions.collectAsStateWithLifecycle()
        val rules by model.rules.collectAsStateWithLifecycle()
        val categorySettings by model.categorySettings.collectAsStateWithLifecycle()
        val categories = categorySettings.map { it.name }
        val excludedCategories = categorySettings.filter { it.excludeFromSpending }.map { it.name }.toSet()
        val busy by model.busy.collectAsStateWithLifecycle()
        val automatic by model.automatic.collectAsStateWithLifecycle()
        val budget by model.budget.collectAsStateWithLifecycle()
        val startDay by model.cycleStartDay.collectAsStateWithLifecycle()
        val alerts by model.alerts.collectAsStateWithLifecycle()
        val themeMode by model.themeMode.collectAsStateWithLifecycle()
        val notice by model.notice.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var exportFormat by rememberSaveable { mutableStateOf(ExportFormat.LEDGER.name) }
        var exporting by remember { mutableStateOf(false) }
        var exportHelp by remember { mutableStateOf(false) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var needsCategoryOnly by rememberSaveable { mutableStateOf(false) }
        var requestedAlert by rememberSaveable { mutableStateOf<String?>(null) }
        var notificationsAllowed by remember { mutableStateOf(LedgerNotifications.permissionGranted(this) && getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
        var importDialog by remember { mutableStateOf(false) }
        var manualDialog by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf<Transaction?>(null) }
        var month by remember { mutableStateOf(BudgetCycle.current(System.currentTimeMillis(), ZoneId.systemDefault(), startDay)) }
        var reviewMonth by remember { mutableStateOf(BudgetCycle.current(System.currentTimeMillis(), ZoneId.systemDefault(), startDay)) }
        var reviewZone by remember { mutableStateOf(ZoneId.systemDefault()) }
        val monthTransactions = transactions.filter { BudgetCycle.includes(it.occurredAt, month, reviewZone, startDay) }
        val monthSpending = SpendingPolicy.transactions(monthTransactions, excludedCategories)
        val pendingTransactions = transactions.filter { CategorizationReview.includes(it.category, it.occurredAt, reviewMonth, reviewZone, startDay) }
        val visibleTransactions = if (needsCategoryOnly) pendingTransactions else monthTransactions
        fun setAlertEnabled(kind: String, enabled: Boolean) {
            if (kind == "budget") model.setBudgetAlerts(enabled) else model.setCategorizationAlerts(enabled)
        }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            requestedAlert?.let { setAlertEnabled(it, granted) }
            requestedAlert = null
            notificationsAllowed = granted && getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            if (!granted) model.notify("Notifications were not allowed. You can enable them in Android settings.")
        }
        fun requestAlert(kind: String, enabled: Boolean) {
            if (enabled && Build.VERSION.SDK_INT >= 33 && !LedgerNotifications.permissionGranted(this)) {
                requestedAlert = kind
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else setAlertEnabled(kind, enabled)
        }
        fun refreshCycleClock(now: Long = System.currentTimeMillis()) {
            val zone = ZoneId.systemDefault()
            val current = BudgetCycle.current(now, zone, startDay)
            if (month == reviewMonth) month = current
            reviewMonth = current
            reviewZone = zone
        }
        LaunchedEffect(startDay) {
            refreshCycleClock()
            month = reviewMonth
        }
        DisposableEffect(lifecycle, startDay) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshCycleClock()
                    notificationsAllowed = LedgerNotifications.permissionGranted(this@MainActivity) &&
                        getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(lifecycle, startDay) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val now = System.currentTimeMillis()
                    val zone = ZoneId.systemDefault()
                    refreshCycleClock(now)
                    delay((BudgetCycle.nextStart(now, zone, startDay) - now).coerceAtLeast(1L))
                }
            }
        }
        LaunchedEffect(notificationRequest) {
            if (notificationAction != null) { importDialog = false; manualDialog = false; exportHelp = false }
            when (notificationAction) {
                LedgerNotifications.REVIEW_CATEGORIES -> { tab = 1; needsCategoryOnly = true; selected = null }
                LedgerNotifications.REVIEW_BUDGET -> { tab = 0; month = BudgetCycle.current(System.currentTimeMillis(), ZoneId.systemDefault(), startDay); selected = null }
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            model.setAutomatic(granted)
            model.notify(if (granted) "Automatic SMS import enabled. New supported bank messages will be added." else "SMS permission was not granted. You can still paste messages.")
        }
        val inboxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) model.importInbox() else model.notify("Inbox permission was not granted. You can still paste messages.")
        }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                val snapshot = transactions.toList()
                val excludedSnapshot = excludedCategories.toSet()
                val format = ExportFormat.valueOf(exportFormat)
                exporting = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                                LedgerCsvExporter.write(writer, snapshot, format, excludedCategories = excludedSnapshot)
                            } ?: error("Unable to open export")
                        }
                        model.notify(if (format == ExportFormat.FIREFLY)
                            "Firefly III CSV exported. Categories excluded from spending were skipped."
                        else "Transactions exported. Keep the CSV somewhere private.")
                    } catch (_: Exception) { model.notify("Export failed. Please choose another location.") }
                    finally { exporting = false }
                }
            }
        }
        LaunchedEffect(notice) {
            notice?.let { snackbar.showSnackbar(it); model.clearNotice() }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    listOf("Overview", "Transactions", "Charts", "Settings").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("◉", "≡", "▥", "⚙")[index]) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(16.dp))
                Text("POCKET LEDGER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(listOf("Your money, in view", "Your transactions", "Your spending patterns", "Make it yours")[tab], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Private by design · Stored on this phone", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (tab) {
                    0 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item { LatestBalanceCard(transactions) }
                        item { MonthSelector(month, { month = month.minusMonths(1) }, { month = month.plusMonths(1) }, startDay) }
                        item {
                            val spent = SpendingPolicy.net(monthTransactions, excludedCategories)
                            val budgetValue = BigInteger.valueOf(budget)
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                                    Text("TOTAL SPENDING", style = MaterialTheme.typography.labelMedium)
                                    Text(chartMoney(spent), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                                    Text("${monthSpending.size} spending/refund entries · CNY", style = MaterialTheme.typography.bodyMedium)
                                    Text("Refunds: ${chartMoney(SpendingPolicy.totalOfKind(monthTransactions, TransactionKind.REFUND, excludedCategories))} · Incoming: ${chartMoney(SpendingPolicy.totalOfKind(monthTransactions, TransactionKind.INCOME, excludedCategories))}", style = MaterialTheme.typography.bodySmall)
                                    if (budget > 0) {
                                        Spacer(Modifier.height(12.dp))
                                        LinearProgressIndicator(progress = { chartFraction(spent, budgetValue) }, modifier = Modifier.fillMaxWidth())
                                        Text(if (spent <= budgetValue) "${chartMoney(budgetValue - spent)} left of ${money(budget)} budget" else "${chartMoney(spent - budgetValue)} over your ${money(budget)} budget")
                                    } else Text("Set a monthly budget in Settings.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { Button(onClick = { importDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import a bank message") } }
                        item { OutlinedButton(onClick = { manualDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Add transaction") } }
                        item { Text("Spending by category", style = MaterialTheme.typography.titleLarge) }
                        if (monthTransactions.isEmpty()) item { EmptyState() }
                        else if (monthSpending.isEmpty()) item { Text("No spending this cycle. Excluded transactions remain in Transactions.") }
                        items(monthSpending.groupBy { it.category }.entries.sortedByDescending { entry -> SpendingPolicy.net(entry.value, excludedCategories) }) { entry ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(entry.key)
                                Text(chartMoney(SpendingPolicy.net(entry.value, excludedCategories)), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        item { Text("Spending is expenses minus refunds. Incoming payments are separate. Excluded categories are omitted.", style = MaterialTheme.typography.bodySmall) }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                    1 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !needsCategoryOnly, onClick = { needsCategoryOnly = false }, label = { Text("By cycle") })
                                FilterChip(selected = needsCategoryOnly, onClick = { needsCategoryOnly = true }, label = { Text("Needs categorization (${pendingTransactions.size})") })
                            }
                        }
                        if (needsCategoryOnly) item { Text("Uncategorized transactions · ${BudgetCycle.label(reviewMonth, startDay)}", style = MaterialTheme.typography.bodySmall) }
                        else item { MonthSelector(month, { month = month.minusMonths(1) }, { month = month.plusMonths(1) }, startDay) }
                        item { OutlinedButton(onClick = { importDialog = true }, enabled = !busy) { Text("Paste a message") } }
                        item { OutlinedButton(onClick = { manualDialog = true }, enabled = !busy) { Text("Add transaction") } }
                        if (visibleTransactions.isEmpty()) item { if (needsCategoryOnly) Text("Nothing needs categorization this cycle.") else EmptyState() }
                        items(visibleTransactions, key = { it.id }) { transaction ->
                            Card(Modifier.fillMaxWidth().clickable { selected = transaction }) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(transaction.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        Text(transaction.signedAmountLabel, fontWeight = FontWeight.Bold)
                                    }
                                    Text(transaction.category + if (SpendingPolicy.counts(transaction.category, excludedCategories)) "" else " · Excluded from spending", color = MaterialTheme.colorScheme.primary)
                                    Text(transaction.kind.label + if (transaction.isManual) " · Manual" else "", style = MaterialTheme.typography.labelSmall)
                                    Text("${dateLabel(transaction.occurredAt)} · Card ending ${transaction.card}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                    2 -> ChartsScreen(transactions, month, budget, excludedCategories, startDay, onMonth = { month = it })
                    3 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ThemeSettings(themeMode, model::setThemeMode)
                        HorizontalDivider()
                        Text("Bank messages", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Automatically import new SMS")
                                Text("Only supported Bank of Communications messages are saved.", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = automatic && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED, onCheckedChange = { enabled ->
                                if (!enabled) model.setAutomatic(false)
                                else if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) model.setAutomatic(true)
                                else permissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                            })
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) model.importInbox()
                            else inboxLauncher.launch(Manifest.permission.READ_SMS)
                        }) { Text("Import existing bank SMS") }
                        Text("Android may restrict background SMS delivery. Open the app once after installing; use inbox import to recover missed transactions.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        BudgetCycleEditor(startDay, model::setCycleStartDay)
                        HorizontalDivider()
                        BudgetEditor(budget, model::setBudget)
                        HorizontalDivider()
                        NotificationSettings(alerts, budget, notificationsAllowed,
                            onBudgetEnabled = { requestAlert("budget", it) },
                            onCategorizationEnabled = { requestAlert("categorization", it) },
                            onThresholds = model::saveAlertThresholds, onShowAmounts = model::setNotificationAmounts,
                            onAndroidSettings = { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) })
                        HorizontalDivider()
                        CategoryManager(categorySettings, busy, model::addCategory, model::removeCategory, model::setCategoryExcluded)
                        HorizontalDivider()
                        Text("Category rules", style = MaterialTheme.typography.titleLarge)
                        Text("Rules match the exact bank description and apply to future imports. Existing transactions keep their categories.", style = MaterialTheme.typography.bodySmall)
                        if (rules.isEmpty()) Text("No rules yet. Open a transaction to create one.")
                        rules.forEach { rule ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(englishDescription(rule.description)); Text(rule.category, style = MaterialTheme.typography.bodySmall) }
                                TextButton(onClick = { model.deleteRule(rule.description) }, enabled = !busy) { Text("Remove") }
                            }
                        }
                        HorizontalDivider()
                        Text("Export", style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(onClick = {
                            exportFormat = ExportFormat.LEDGER.name
                            exportLauncher.launch("pocket-ledger-${java.time.LocalDate.now()}.csv")
                        }, enabled = transactions.isNotEmpty() && !busy && !exporting) { Text("Export all transactions to CSV") }
                        OutlinedButton(onClick = {
                            exportFormat = ExportFormat.FIREFLY.name
                            exportLauncher.launch("pocket-ledger-firefly-${java.time.LocalDate.now()}.csv")
                        }, enabled = transactions.isNotEmpty() && !busy && !exporting) { Text("Export for Firefly III") }
                        Text("Save a CSV now and import it later using Firefly III Data Importer. Includes names, categories, CNY expenses/credits, and stable transaction IDs.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { exportHelp = true }) { Text("How to import into Firefly III") }
                        if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("No account, ads, analytics, or internet access. Android cloud backup is disabled. Uninstalling the app deletes its data; CSV export is a readable copy, not a restorable app backup.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        if (importDialog) ImportDialog(onDismiss = { importDialog = false }, onImport = { model.importText(it); importDialog = false })
        if (exportHelp) FireflyHelpDialog(onDismiss = { exportHelp = false })
        if (manualDialog) TransactionEntryDialog(categories, transactions.firstOrNull()?.card.orEmpty(), busy,
            onDismiss = { manualDialog = false }, onSave = { model.addManual(it); manualDialog = false })
        selected?.let { transaction -> CategoryDialog(transaction, categories,
            onDismiss = { selected = null },
            onSave = { category, name, remember, kind -> model.updatePurchase(transaction, category, name, remember, kind); selected = null },
            onDelete = { model.deleteTransaction(transaction.id); selected = null }) }
    }
}

@Composable
fun MonthSelector(month: YearMonth, previous: () -> Unit, next: () -> Unit, startDay: Int = 1) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = previous) { Text("Previous") }
        Text(BudgetCycle.label(month, startDay), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = next) { Text("Next") }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp)).padding(24.dp)) {
        Text("A fresh start", style = MaterialTheme.typography.titleMedium)
        Text("Import a bank message or add a transaction. Tap an entry to choose its type and category.")
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Import a bank message") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Paste one Bank of Communications expense, refund, or incoming-payment SMS. The original Chinese message is kept. For messages without a year, use inbox import for historical records.")
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Bank SMS") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            Text("Supports the demonstrated card payment/refund and account transfer-in formats. Unknown formats are skipped; you can use Add transaction instead.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun CategoryDialog(transaction: Transaction, categories: List<String>, onDismiss: () -> Unit, onSave: (String, String, Boolean, TransactionKind) -> Unit, onDelete: () -> Unit) {
    var category by remember(transaction.id) { mutableStateOf(transaction.category) }
    var purchaseName by remember(transaction.id) { mutableStateOf(transaction.purchaseName) }
    var kind by remember(transaction.id) { mutableStateOf(transaction.kind) }
    val nameError = purchaseNameError(purchaseName)
    var rememberRule by remember { mutableStateOf(false) }
    var deleteConfirmation by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Transaction details") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${transaction.copy(kind = kind).signedAmountLabel} · ${dateLabel(transaction.occurredAt)}", fontWeight = FontWeight.Bold)
            Text("Card ending ${transaction.card}" + if (transaction.hasReportedBalance) " · Balance ${money(transaction.balanceCents)}" else " · No reported balance")
            TransactionKindSelector(kind) { kind = it }
            OutlinedTextField(value = purchaseName, onValueChange = { purchaseName = it },
                label = { Text("Transaction name") }, placeholder = { Text(englishDescription(transaction.description)) },
                supportingText = { Text(nameError ?: "Optional. Leave blank to use the bank payment label.") },
                isError = nameError != null, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Category", style = MaterialTheme.typography.titleSmall)
            categories.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { category = option }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = category == option, onClick = { category = option })
                    Text(option)
                }
            }
            if (!transaction.isManual) Row(Modifier.fillMaxWidth().clickable { rememberRule = !rememberRule }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberRule, onCheckedChange = { rememberRule = it })
                Text("Use this category for future matching descriptions", modifier = Modifier.weight(1f))
            }
            Text("Alipay and WeChat descriptions may cover unrelated purchases. Save a rule only if that is useful for you.", style = MaterialTheme.typography.bodySmall)
            if (transaction.isManual) Text("Manually recorded transaction", style = MaterialTheme.typography.bodySmall)
            else {
                Text("Original bank SMS", style = MaterialTheme.typography.titleSmall)
                Text(transaction.originalSms, style = MaterialTheme.typography.bodySmall)
            }
            if (deleteConfirmation) {
                Text(if (transaction.isManual) "Delete this manual transaction? You would need to enter it again." else "Delete this transaction? Reimporting its SMS will add it again.")
                TextButton(onClick = onDelete) { Text("Confirm deletion", color = MaterialTheme.colorScheme.error) }
            } else TextButton(onClick = { deleteConfirmation = true }) { Text("Delete transaction", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(category.takeIf { it in categories } ?: UNCATEGORIZED, purchaseName, rememberRule && !transaction.isManual, kind) }, enabled = nameError == null) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun BudgetEditor(budget: Long, onSave: (Long) -> Unit) {
    var text by remember(budget) { mutableStateOf(if (budget == 0L) "" else BigDecimal.valueOf(budget, 2).toPlainString()) }
    var error by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Monthly budget", style = MaterialTheme.typography.titleLarge)
        Text("One spending limit, applied to each monthly cycle. Leave blank to turn it off.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = text, onValueChange = { text = it; error = false }, label = { Text("Budget in CNY") }, singleLine = true, isError = error, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        if (error) Text("Enter a positive amount with up to two decimal places.", color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val cents = if (text.isBlank()) 0L else runCatching { BigDecimal(text.trim()).movePointRight(2).longValueExact() }.getOrNull()
            if (cents == null || cents < 0) error = true else onSave(cents)
        }) { Text("Save budget") }
    }
}

private fun money(cents: Long): String = "¥" + NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal.valueOf(cents, 2))
private fun dateLabel(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.ENGLISH))
