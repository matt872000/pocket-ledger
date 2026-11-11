package com.pocketledger

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigInteger
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.floor

private val chartColors = listOf(Color(0xFF21665B), Color(0xFF396EAF), Color(0xFFAD5933), Color(0xFF78569C), Color(0xFF8A7520), Color(0xFF9E4667), Color(0xFF437C88), Color(0xFF647C3C))
private val darkChartColors = listOf(Color(0xFF93D5BF), Color(0xFFA8C8FF), Color(0xFFFFB596), Color(0xFFD6B8F2), Color(0xFFE1CF7D), Color(0xFFF2B0CE), Color(0xFF9DD5DF), Color(0xFFBBD49A))
@Composable
private fun categoryColor(name: String): Color {
    val colors = if (MaterialTheme.colorScheme.background == ledgerDarkColors.background) darkChartColors else chartColors
    return colors[Math.floorMod(name.hashCode(), colors.size)]
}
private val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val fullMonth = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val fullDay = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

@Composable
fun ChartsScreen(transactions: List<Transaction>, month: YearMonth, budget: Long, excludedCategories: Set<String>, startDay: Int, onMonth: (YearMonth) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val charts = remember(transactions, month, zone, today, excludedCategories, startDay) { SpendingChartData.build(transactions, month, zone, today, excludedCategories, startDay) }
    var tableVisible by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { MonthSelector(month, { onMonth(month.minusMonths(1)) }, { onMonth(month.plusMonths(1)) }, startDay) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(BudgetCycle.label(month, startDay), style = MaterialTheme.typography.labelLarge)
                    Text(chartMoney(charts.totalCents), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("${charts.purchaseCount} spending/refund entries · CNY")
                    Text("Refunds: ${chartMoney(charts.refundCents)} · Incoming: ${chartMoney(charts.incomeCents)}", style = MaterialTheme.typography.bodySmall)
                    if (charts.previousMonthCents.signum() != 0) {
                        val difference = charts.totalCents - charts.previousMonthCents
                        Text(if (difference.signum() == 0) "Same recorded spending as the previous cycle." else
                            "${chartMoney(difference.abs())} ${if (difference.signum() > 0) "more" else "less"} than the previous cycle.", style = MaterialTheme.typography.bodySmall)
                        Text("Compares cycle totals; the selected cycle may still be in progress.", style = MaterialTheme.typography.bodySmall)
                    } else Text("Previous cycle net spending: ¥0.00", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { ChartPanel("Six-cycle spending") {
            Text("Through ${month.format(fullMonth)} · Labels show the starting month. Tap a bar to explore a cycle.", style = MaterialTheme.typography.bodySmall)
            MonthlyBars(charts.months, month, onMonth)
            TextButton(onClick = { tableVisible = !tableVisible }) { Text(if (tableVisible) "Hide cycle totals" else "Show cycle totals") }
            if (tableVisible) charts.months.forEach { point ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(point.month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)))
                    Text(chartMoney(point.cents))
                }
            }
        } }
        item { CategoryChart(charts) }
        item { DailySpendingChart(charts, budget, transactions, zone, startDay) }
        item { Text("Spending is expenses minus refunds on their recorded dates. Incoming payments are separate; excluded categories are omitted. Balance snapshots use only reported values.", style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ChartPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun MonthlyBars(months: List<ChartMonth>, selected: YearMonth, onMonth: (YearMonth) -> Unit) {
    val maximum = maxOf(BigInteger.ZERO, months.maxOfOrNull { it.cents } ?: BigInteger.ZERO)
    val minimum = minOf(BigInteger.ZERO, months.minOfOrNull { it.cents } ?: BigInteger.ZERO)
    val range = maximum - minimum
    val primary = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Text("Net spending scale: ${chartMoney(minimum)} to ${chartMoney(maximum)}", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth().height(174.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        months.forEach { point ->
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onMonth(point.month) }
                .semantics { contentDescription = "${point.month.format(fullMonth)}: ${chartMoney(point.cents)}, ${point.purchases} purchases. Select month." },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.weight(1f).fillMaxWidth()) {
                    fun y(value: BigInteger): Float = size.height * (if (range.signum() == 0) 0.5f else 1f - chartFraction(value - minimum, range))
                    val zero = y(BigInteger.ZERO)
                    val value = y(point.cents)
                    drawLine(baseline, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx())
                    drawRect(if (point.month == selected) primary else primary.copy(alpha = 0.48f),
                        topLeft = Offset(size.width * 0.11f, minOf(zero, value)),
                        size = Size(size.width * 0.78f, maxOf(1.dp.toPx(), kotlin.math.abs(value - zero))))
                }
                Spacer(Modifier.height(8.dp))
                Text(point.month.format(shortMonth), style = MaterialTheme.typography.labelMedium)
                Text(point.month.year.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (range.signum() == 0) Text("Net spending is zero in these six cycles.", style = MaterialTheme.typography.bodySmall)
    if (minimum.signum() < 0) Text("Below zero: refunds exceed expenses.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CategoryChart(charts: SpendingCharts) {
    var selected by remember(charts.month) { mutableStateOf<String?>(null) }
    val categoryColors = charts.categories.associate { it.name to categoryColor(it.name) }
    val positiveCategories = charts.categories.filter { it.cents.signum() > 0 }
    val shareTotal = positiveCategories.fold(BigInteger.ZERO) { total, category -> total + category.cents }
    ChartPanel("Spending by category") {
        if (charts.categories.isEmpty()) {
            Text("No spending recorded this cycle. Excluded categories remain in Transactions.")
        } else {
            val selectedCategory = positiveCategories.firstOrNull { it.name == selected }
            if (shareTotal.signum() > 0) {
            Box(Modifier.fillMaxWidth().height(204.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(190.dp).semantics { contentDescription = "Category breakdown for ${charts.month.format(fullMonth)}. Exact amounts and shares are listed below." }) {
                    val stroke = 26.dp.toPx()
                    val diameter = size.minDimension - stroke
                    val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    var angle = -90f
                    positiveCategories.forEach { category ->
                        val sweep = chartFraction(category.cents, shareTotal) * 360f
                        drawArc(categoryColors.getValue(category.name).copy(alpha = if (selectedCategory == null || selected == category.name) 1f else 0.25f),
                            angle, sweep, false, origin, Size(diameter, diameter), style = Stroke(stroke))
                        angle += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (selectedCategory == null) positiveCategories.size.toString() else chartPercent(selectedCategory.cents, shareTotal),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (selectedCategory == null) "categories" else "of spending", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Doughnut shares use positive category totals after refunds. Net refunds are listed below.", style = MaterialTheme.typography.bodySmall)
            } else Text("Refunds fully offset spending in these categories. Net amounts are listed below.", style = MaterialTheme.typography.bodySmall)
            charts.categories.forEach { category ->
                Row(Modifier.fillMaxWidth().clickable { selected = if (selected == category.name) null else category.name }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(12.dp).background(categoryColors.getValue(category.name), CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(category.name, fontWeight = if (selected == category.name) FontWeight.Bold else FontWeight.Normal)
                        Text("${category.purchases} entries · ${if (category.cents.signum() < 0) "Net refund" else chartPercent(category.cents, shareTotal)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(chartMoney(category.cents), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DailySpendingChart(charts: SpendingCharts, budget: Long, transactions: List<Transaction>, zone: ZoneId, startDay: Int) {
    var selectedDay by remember(charts.month, charts.days.size) { mutableIntStateOf(charts.days.lastIndex) }
    val histories = remember(transactions) { RecordedBalances.histories(transactions) }
    var chosenCard by rememberSaveable { mutableStateOf<String?>(null) }
    var showBalance by rememberSaveable { mutableStateOf(true) }
    val history = histories.firstOrNull { it.card == chosenCard } ?: histories.firstOrNull()
    val balances = remember(history, charts.days, zone) { BalanceLineData.build(history, charts.days, zone) }
    val visibleBalances = if (showBalance) balances else emptyList()
    val hasMonthlyRecords = remember(transactions, charts.month, zone, startDay) {
        transactions.any { BudgetCycle.includes(it.occurredAt, charts.month, zone, startDay) }
    }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val budgetColor = MaterialTheme.colorScheme.secondary
    val balanceColor = MaterialTheme.colorScheme.tertiary
    ChartPanel("Spending and balance over time") {
        if (!hasMonthlyRecords) {
            Text("No transactions recorded this cycle. Spending and reported balance lines will appear after an import.")
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Show reported balance", Modifier.weight(1f))
                Switch(checked = showBalance, onCheckedChange = { showBalance = it })
            }
            if (showBalance) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    histories.forEach { item ->
                        FilterChip(selected = item.card == history?.card, onClick = { chosenCard = item.card }, label = { Text("Card ${item.card}") })
                    }
                }
                Text("Balance: selected card only. Spending: all cards.", style = MaterialTheme.typography.bodySmall)
                if (balances.isEmpty()) Text("No reported balance for this card in the selected cycle.", style = MaterialTheme.typography.bodySmall)
            }
            val balanceValues = visibleBalances.flatMap { it.snapshot.balancesCents }.map { BigInteger.valueOf(it) }
            val minimum = minOf(BigInteger.ZERO, charts.days.minOfOrNull { it.cumulativeCents } ?: BigInteger.ZERO, balanceValues.minOrNull() ?: BigInteger.ZERO)
            val maximum = maxOf(charts.days.maxOfOrNull { it.cumulativeCents } ?: BigInteger.ZERO, BigInteger.valueOf(budget.coerceAtLeast(0)), balanceValues.maxOrNull() ?: BigInteger.ZERO)
            val range = maximum - minimum
            Text("Shared scale · CNY", style = MaterialTheme.typography.bodySmall)
            ChartLineLegend(primary, "Cumulative spending · all cards")
            if (showBalance) ChartLineLegend(balanceColor, "Reported balance · card ${history?.card.orEmpty()}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Low: ${chartMoney(minimum)}", style = MaterialTheme.typography.labelSmall)
                Text("Top: ${chartMoney(maximum)}", style = MaterialTheme.typography.labelSmall)
            }
            Canvas(Modifier.fillMaxWidth().height(180.dp)
                .semantics { contentDescription = "Cumulative spending and ${if (showBalance) "reported balance for card ${history?.card}" else "balance hidden"} in ${charts.month.format(fullMonth)}. Use the day slider below for exact daily values." }
                .pointerInput(charts.month, charts.days.size) {
                    detectTapGestures { position ->
                        val inset = 12.dp.toPx()
                        selectedDay = floor(((position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)) * charts.days.size)
                            .toInt().coerceIn(0, charts.days.lastIndex)
                    }
                }) {
                val inset = 12.dp.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(1f)
                val height = (size.height - 2 * inset).coerceAtLeast(1f)
                fun position(dayOffset: Float, cents: BigInteger): Offset = Offset(
                    inset + width * dayOffset / charts.days.size,
                    inset + height * (if (range.signum() == 0) 0.5f else 1f - chartFraction(cents - minimum, range)))
                fun point(index: Int) = position(index + 1f, charts.days[index].cumulativeCents)
                listOf(0f, 0.5f, 1f).forEach { part ->
                    val y = inset + height * part
                    drawLine(grid, Offset(inset, y), Offset(inset + width, y), strokeWidth = 1.dp.toPx())
                }
                if (budget > 0) {
                    val y = position(0f, BigInteger.valueOf(budget)).y
                    drawLine(budgetColor, Offset(inset, y), Offset(inset + width, y), strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())))
                }
                val path = Path()
                val start = position(0f, BigInteger.ZERO)
                path.moveTo(start.x, start.y)
                charts.days.indices.forEach { index ->
                    val position = point(index)
                    path.lineTo(position.x, position.y)
                }
                drawPath(path, primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                visibleBalances.forEachIndexed { index, observation ->
                    val current = observation.snapshot.balanceCents
                    val previous = visibleBalances.getOrNull(index - 1)
                    val previousValue = previous?.snapshot?.balanceCents
                    if (current != null && previousValue != null) {
                        drawLine(balanceColor, position(previous.dayOffset, BigInteger.valueOf(previousValue)), position(observation.dayOffset, BigInteger.valueOf(current)),
                            strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                    }
                    observation.snapshot.balancesCents.forEach { cents ->
                        drawCircle(balanceColor, if (observation.date == charts.days[selectedDay].date) 5.dp.toPx() else 3.dp.toPx(), position(observation.dayOffset, BigInteger.valueOf(cents)))
                    }
                }
                val highlighted = point(selectedDay)
                drawLine(primary.copy(alpha = 0.35f), highlighted, Offset(highlighted.x, inset + height), strokeWidth = 1.dp.toPx())
                drawCircle(primary, 5.dp.toPx(), highlighted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(charts.days.first().date.format(fullDay), style = MaterialTheme.typography.labelSmall)
                if (charts.days.size > 1) Text(charts.days.last().date.format(fullDay), style = MaterialTheme.typography.labelSmall)
            }
            if (budget > 0) Text("Dashed line: monthly budget ${chartMoney(BigInteger.valueOf(budget))}", style = MaterialTheme.typography.bodySmall)
            if (charts.days.size > 1) Slider(value = selectedDay.toFloat(), onValueChange = { selectedDay = it.roundToInt() },
                valueRange = 0f..charts.days.lastIndex.toFloat(), steps = (charts.days.size - 2).coerceAtLeast(0),
                modifier = Modifier.semantics { contentDescription = "Day of selected cycle" })
            val day = charts.days[selectedDay]
            Text(day.date.format(fullDay), fontWeight = FontWeight.SemiBold)
            Text("${chartMoney(day.cents)} net spending · ${day.purchases} entries")
            Text("Running total: ${chartMoney(day.cumulativeCents)}", style = MaterialTheme.typography.bodySmall)
            if (showBalance) {
                val snapshot = BalanceLineData.latestOnDay(balances, day.date)
                if (snapshot == null) Text("No balance reported on this day for card ${history?.card.orEmpty()}.", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Last reported balance on this day · card ${history?.card}", fontWeight = FontWeight.SemiBold)
                    Text(snapshot.balancesCents.joinToString(" · ") { chartMoney(BigInteger.valueOf(it)) })
                    Text("Report time: ${Instant.ofEpochMilli(snapshot.occurredAt).atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.ENGLISH))}", style = MaterialTheme.typography.bodySmall)
                    if (snapshot.balanceCents == null) Text("Order unclear within this minute; all reported values are shown.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Blue dots are reported balances; dashed blue lines connect observations only. Missing balances are not assumed. Same-minute conflicts break the line.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChartLineLegend(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
