package com.pocketledger

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigInteger
private fun balanceMoney(cents: Long) = chartMoney(BigInteger.valueOf(cents))

@Composable
fun LatestBalanceCard(transactions: List<Transaction>) {
    val histories = remember(transactions) { RecordedBalances.histories(transactions) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Latest reported balances", style = MaterialTheme.typography.titleMedium)
            if (histories.isEmpty()) Text("Import a bank message to see its reported balance here.", style = MaterialTheme.typography.bodySmall)
            histories.forEachIndexed { index, history ->
                if (index > 0) HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Card ending ${history.card} · CNY", style = MaterialTheme.typography.labelSmall)
                    BalanceValue(history.latest)
                }
            }
        }
    }
}

@Composable
private fun BalanceValue(point: RecordedBalancePoint) {
    val balance = point.balanceCents
    if (balance != null) Text(balanceMoney(balance), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    else {
        Text("Order unclear within this minute", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        point.balancesCents.forEach { value ->
            Text(balanceMoney(value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
    }
}
