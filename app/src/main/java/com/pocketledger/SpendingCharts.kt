package com.pocketledger

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

data class ChartMonth(val month: YearMonth, val cents: BigInteger, val purchases: Int)
data class ChartCategory(val name: String, val cents: BigInteger, val purchases: Int)
data class ChartDay(val date: LocalDate, val cents: BigInteger, val cumulativeCents: BigInteger, val purchases: Int)
data class SpendingCharts(
    val month: YearMonth,
    val totalCents: BigInteger,
    val purchaseCount: Int,
    val previousMonthCents: BigInteger,
    val months: List<ChartMonth>,
    val categories: List<ChartCategory>,
    val days: List<ChartDay>,
    val refundCents: BigInteger = BigInteger.ZERO,
    val incomeCents: BigInteger = BigInteger.ZERO
)

object SpendingChartData {
    fun build(transactions: List<Transaction>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone), excludedCategories: Set<String> = emptySet(), startDay: Int = 1): SpendingCharts {
        val monthAmounts = mutableMapOf<YearMonth, BigInteger>()
        val monthCounts = mutableMapOf<YearMonth, Int>()
        val categoryAmounts = mutableMapOf<String, BigInteger>()
        val categoryCounts = mutableMapOf<String, Int>()
        val dayAmounts = mutableMapOf<LocalDate, BigInteger>()
        val dayCounts = mutableMapOf<LocalDate, Int>()
        val firstDate = BudgetCycle.start(month, startDay)
        val endDate = BudgetCycle.start(month.plusMonths(1), startDay).minusDays(1)
        var lastRecordedDate = firstDate
        var refunds = BigInteger.ZERO
        var income = BigInteger.ZERO
        transactions.forEach { transaction ->
            val date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zone).toLocalDate()
            val purchaseMonth = BudgetCycle.periodFor(date, startDay)
            if (purchaseMonth == month) lastRecordedDate = maxOf(lastRecordedDate, date)
            if (!SpendingPolicy.counts(transaction.category, excludedCategories)) return@forEach
            if (purchaseMonth == month) {
                if (transaction.kind == TransactionKind.REFUND) refunds += BigInteger.valueOf(transaction.amountCents)
                if (transaction.kind == TransactionKind.INCOME) income += BigInteger.valueOf(transaction.amountCents)
            }
            if (transaction.kind == TransactionKind.INCOME) return@forEach
            val amount = SpendingPolicy.delta(transaction.amountCents, transaction.kind)
            monthAmounts[purchaseMonth] = monthAmounts.getOrDefault(purchaseMonth, BigInteger.ZERO) + amount
            monthCounts[purchaseMonth] = monthCounts.getOrDefault(purchaseMonth, 0) + 1
            if (purchaseMonth == month) {
                categoryAmounts[transaction.category] = categoryAmounts.getOrDefault(transaction.category, BigInteger.ZERO) + amount
                categoryCounts[transaction.category] = categoryCounts.getOrDefault(transaction.category, 0) + 1
                dayAmounts[date] = dayAmounts.getOrDefault(date, BigInteger.ZERO) + amount
                dayCounts[date] = dayCounts.getOrDefault(date, 0) + 1
            }
        }
        val lastDate = if (month == BudgetCycle.periodFor(today, startDay)) maxOf(today, lastRecordedDate) else endDate
        var cumulative = BigInteger.ZERO
        val days = generateSequence(firstDate) { it.plusDays(1) }.takeWhile { it <= lastDate }.map { day ->
            val amount = dayAmounts.getOrDefault(day, BigInteger.ZERO)
            cumulative += amount
            ChartDay(day, amount, cumulative, dayCounts.getOrDefault(day, 0))
        }.toList()
        return SpendingCharts(
            month, monthAmounts.getOrDefault(month, BigInteger.ZERO), monthCounts.getOrDefault(month, 0),
            monthAmounts.getOrDefault(month.minusMonths(1), BigInteger.ZERO),
            (5L downTo 0L).map { offset ->
                val current = month.minusMonths(offset)
                ChartMonth(current, monthAmounts.getOrDefault(current, BigInteger.ZERO), monthCounts.getOrDefault(current, 0))
            },
            categoryAmounts.map { (name, cents) -> ChartCategory(name, cents, categoryCounts.getValue(name)) }
                .sortedWith(compareByDescending<ChartCategory> { it.cents }.thenBy { it.name }),
            days, refunds, income
        )
    }
}

fun chartMoney(cents: BigInteger): String = "¥" + NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}.format(BigDecimal(cents, 2))

fun chartFraction(value: BigInteger, maximum: BigInteger): Float = if (maximum.signum() <= 0) 0f else
    BigDecimal(value).divide(BigDecimal(maximum), 10, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)

fun chartPercent(value: BigInteger, total: BigInteger): String = if (total.signum() <= 0) "0.0%" else
    BigDecimal(value).multiply(BigDecimal(100)).divide(BigDecimal(total), 1, RoundingMode.HALF_UP).toPlainString() + "%"
