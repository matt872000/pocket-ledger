package com.pocketledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun CategoryManager(categorySettings: List<LedgerCategory>, busy: Boolean, onAdd: (String) -> Unit, onRemove: (String) -> Unit, onExcluded: (String, Boolean) -> Unit) {
    val categories = categorySettings.map { it.name }
    var name by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val error = categoryNameError(name) ?: if (categories.any { it.equals(name.trim(), ignoreCase = true) })
        "A category with that name already exists." else null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Categories", style = MaterialTheme.typography.titleLarge)
        Text("Create your own categories. Removing a category keeps its purchases and moves them to Uncategorized.", style = MaterialTheme.typography.bodySmall)
        Text("For transfers, turn on Exclude from spending. This applies to existing and future transactions; balances and transaction history stay available.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = name, onValueChange = { name = it; attempted = false },
            label = { Text("New category") }, singleLine = true, enabled = !busy,
            isError = attempted && error != null, modifier = Modifier.fillMaxWidth(),
            supportingText = { if (attempted && error != null) Text(error) })
        Button(enabled = !busy, onClick = {
            attempted = true
            if (error == null) { onAdd(name.trim()); name = ""; attempted = false }
        }) { Text("Add category") }
        categorySettings.forEach { setting ->
            val category = setting.name
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(category, modifier = Modifier.weight(1f))
                    if (category != UNCATEGORIZED)
                        TextButton(enabled = !busy, onClick = { removing = category }) { Text("Remove") }
                }
                if (category != UNCATEGORIZED) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Exclude from spending", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = setting.excludeFromSpending, enabled = !busy, onCheckedChange = { onExcluded(category, it) },
                        modifier = Modifier.semantics { contentDescription = "Exclude $category from spending" })
                }
            }
        }
    }
    removing?.let { category ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove category?") },
            text = { Text("Remove \"$category\"? Its transactions will move to Uncategorized and count as spending. Saved category rules using it will be removed. Transactions will not be deleted.") },
            confirmButton = { TextButton(enabled = !busy, onClick = { onRemove(category); removing = null }) { Text("Remove category") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
fun FireflyHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Import into Firefly III") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("You can export before setting up a server. Later, open Firefly III Data Importer, choose CSV, and upload the exported file.")
            Text("Choose comma as the delimiter, enable the header row, and use date format Y-m-d H:i:s. Use the same timezone as this phone when importing.")
            Text("Assign these column roles:")
            Text("Date → Transaction date\nDescription → Description\nAmount → Amount\nCurrency → Currency code\nAsset account → Asset account name\nOpposing account → Opposing account name\nCategory → Category name\nExternal ID → External ID")
            Text("Map each Bank of Communications card to your own CNY asset account. Unknown merchant is a placeholder opposing account for expenses/refunds. Unknown income is a placeholder source for incoming payments. Review the account mapping in the importer.")
            Text("Categories excluded from spending are skipped in Firefly exports because a destination account is needed to export a real transfer. Ordinary CSV keeps all transactions and includes Counts as spending. Previously imported Firefly records are not removed by changing this setting.")
            Text("Expenses export negative; refunds and incoming payments export positive. The importer may treat refunds as deposits; verify its preview. Keep duplicate detection enabled and use External ID to identify repeated exports. Renaming a purchase keeps its ID; CSV imports do not synchronize edits to purchases already in Firefly III.")
            Text("Review the import preview and totals before confirming. The app exports expenses and credits, excluding flagged categories, and does not connect to your server.")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
