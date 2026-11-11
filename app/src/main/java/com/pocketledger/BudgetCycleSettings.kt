package com.pocketledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneId

@Composable
fun BudgetCycleEditor(startDay: Int, onSave: (Int) -> Unit) {
    var text by remember(startDay) { mutableStateOf(startDay.toString()) }
    val day = text.toIntOrNull()?.takeIf { it in 1..31 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Monthly rollover", style = MaterialTheme.typography.titleLarge)
        Text("Start each cycle at 00:00 on this day, using your phone's time zone. Default: 21. In shorter months, the last day is used.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Start day (1–31)") },
            singleLine = true, isError = day == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        if (day == null) Text("Enter a whole day from 1 to 31.", color = MaterialTheme.colorScheme.error)
        else Text("Current cycle: ${BudgetCycle.label(BudgetCycle.current(System.currentTimeMillis(), ZoneId.systemDefault(), day), day)}", style = MaterialTheme.typography.bodySmall)
        Text("Applies to budgets, charts, and categorization reminders. Older transactions stay in history. Saving recalculates totals and refreshes alerts.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { day?.let(onSave) }, enabled = day != null && day != startDay) { Text("Save rollover day") }
    }
}
