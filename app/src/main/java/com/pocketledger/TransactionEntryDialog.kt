package com.pocketledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TransactionKindSelector(kind: TransactionKind, onKind: (TransactionKind) -> Unit) {
    Column(Modifier.selectableGroup()) {
        TransactionKind.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = kind == option, onClick = { onKind(option) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = kind == option, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(option.label)
            }
        }
    }
    Text(when (kind) {
        TransactionKind.EXPENSE -> "Adds to spending unless its category is excluded."
        TransactionKind.REFUND -> "Reduces spending in this category on the refund date. Use the original purchase's category."
        TransactionKind.INCOME -> "Money received, shown separately from spending. Use an excluded category for transfers between your own accounts."
    }, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun TransactionEntryDialog(categories: List<String>, defaultCard: String, busy: Boolean, onDismiss: () -> Unit, onSave: (ManualEntry) -> Unit) {
    val now = remember { LocalDateTime.now() }
    var kind by remember { mutableStateOf(TransactionKind.REFUND) }
    var card by remember { mutableStateOf(defaultCard) }
    var amount by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(now.toLocalDate().toString()) }
    var time by remember { mutableStateOf(now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))) }
    var balance by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(UNCATEGORIZED) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add transaction") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TransactionKindSelector(kind) { kind = it }
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount in CNY") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(card, { card = it }, label = { Text("Card ending") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(time, { time = it }, label = { Text("Time (HH:mm)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Uses this phone's timezone.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(balance, { balance = it }, label = { Text("Reported balance in CNY (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text("Leave balance blank if unknown. No balance will be calculated or assumed.", style = MaterialTheme.typography.bodySmall)
            Text("Category", style = MaterialTheme.typography.titleSmall)
            Column(Modifier.selectableGroup()) {
                categories.forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = category == option, onClick = { category = option }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = category == option, onClick = null)
                        Text(option)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        runCatching { TransactionEntry.validate(kind, card, amount, date, time, category, name, balance) }
            .onSuccess(onSave).onFailure { error = it.message ?: "Check the transaction details." }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
