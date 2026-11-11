package com.pocketledger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NotificationSettings(
    settings: AlertSettings,
    budget: Long,
    notificationsAllowed: Boolean,
    onBudgetEnabled: (Boolean) -> Unit,
    onCategorizationEnabled: (Boolean) -> Unit,
    onThresholds: (String) -> Unit,
    onShowAmounts: (Boolean) -> Unit,
    onAndroidSettings: () -> Unit
) {
    var thresholds by remember(settings.thresholds) { mutableStateOf(settings.thresholds.joinToString(", ")) }
    var attempted by remember { mutableStateOf(false) }
    val valid = AlertPolicy.parseThresholds(thresholds) != null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Notifications", style = MaterialTheme.typography.titleLarge)
        Text("Choose the alerts you want. Amounts are hidden by default; lock-screen previews use a private summary.", style = MaterialTheme.typography.bodySmall)
        NotificationToggle("Budget alerts", "Notify at your chosen percentages of this cycle's budget.", settings.budgetEnabled, onBudgetEnabled)
        if (budget == 0L) Text("Set a monthly budget above to receive budget alerts.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = thresholds, onValueChange = { thresholds = it; attempted = false },
            label = { Text("Alert levels (%)") }, placeholder = { Text("80, 100") },
            supportingText = { Text(if (attempted && !valid) "Use up to 10 whole percentages from 1 to 1000, separated by commas." else "Example: 50, 80, 100, 120. Levels over 100% warn about overspending.") },
            isError = attempted && !valid, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { attempted = true; if (valid) onThresholds(thresholds) }) { Text("Save alert levels") }
        Text("Each level alerts once per cycle and budget amount. A newly added level or changed budget can alert immediately if already reached. Crossing several levels together sends one alert for the highest.", style = MaterialTheme.typography.bodySmall)
        NotificationToggle("Needs categorization", "Review Uncategorized transactions from the current cycle only. Past cycles can stay uncategorized and remain in your history.", settings.categorizationEnabled, onCategorizationEnabled)
        NotificationToggle("Show amounts in budget notifications", "Optional. Lock-screen previews still hide financial details.", settings.showBudgetAmounts, onShowAmounts)
        if (!notificationsAllowed && (settings.budgetEnabled || settings.categorizationEnabled))
            Text("Notifications are blocked in Android settings. Your alert choices are saved.", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onAndroidSettings) { Text("Open Android notification settings") }
        Text("Alerts refresh after ledger/settings changes and when the app opens. A cycle refresh clears the previous cycle's categorization reminder; Android may delay it while saving battery. New SMS alerts require automatic import.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NotificationToggle(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
