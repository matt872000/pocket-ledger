package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class TransferSpendingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val month = YearMonth.of(2026, 11)
    private fun item(day: Int, amount: Long, category: String, period: YearMonth = month) = Transaction(
        fingerprint = "$period-$day-$amount-$category", card = "1234",
        occurredAt = ZonedDateTime.of(period.atDay(day).atTime(12, 0), zone).toInstant().toEpochMilli(),
        description = "Payment", amountCents = amount, balanceCents = 100000 - amount, originalSms = "SMS", category = category)

    @Test fun `overview charts and alerts exclude custom transfer categories consistently across months`() {
        val transactions = listOf(item(1, 1000, "Groceries"), item(2, 9000, "My transfers"),
            item(1, 500, UNCATEGORIZED), item(1, 2000, "Groceries", month.minusMonths(1)), item(2, 5000, "My transfers", month.minusMonths(1)))
        val excluded = setOf("my TRANSFERS")
        val charts = SpendingChartData.build(transactions, month, zone, month.atDay(2), excluded)
        assertEquals(BigInteger.valueOf(1500), charts.totalCents)
        assertEquals(BigInteger.valueOf(2000), charts.previousMonthCents)
        assertEquals(2, charts.purchaseCount)
        assertEquals(setOf("Groceries", UNCATEGORIZED), charts.categories.map { it.name }.toSet())
        assertEquals(BigInteger.valueOf(1500), charts.days.last().cumulativeCents)
        assertEquals(BigInteger.ZERO, charts.days[1].cents)
        assertEquals(0, charts.days[1].purchases)
        assertEquals(charts.totalCents, charts.months.last().cents)
        val alerts = transactions.mapIndexed { index, it -> AlertPurchase(index.toLong(), it.occurredAt, it.amountCents, it.category) }
        assertEquals(charts.totalCents, AlertPolicy.spending(alerts, month, zone, excluded))
        assertEquals(3500L, SpendingPolicy.transactions(transactions, excluded).sumOf { it.amountCents })
        assertTrue(AlertPolicy.reached(charts.totalCents, 10000, listOf(80, 100)).isEmpty())
    }

    @Test fun `a transfer-only month still has its reported balance timeline and a zero spending line`() {
        val transactions = listOf(item(1, 5000, "My transfers"), item(5, 6000, "My transfers"))
        val charts = SpendingChartData.build(transactions, month, zone, month.atDay(2), setOf("My transfers"))
        assertEquals(BigInteger.ZERO, charts.totalCents)
        assertEquals(0, charts.purchaseCount)
        assertTrue(charts.categories.isEmpty())
        assertTrue(charts.days.all { it.cumulativeCents == BigInteger.ZERO })
        assertEquals(5, charts.days.size)
        val history = RecordedBalances.histories(transactions).single()
        val balances = BalanceLineData.build(history, charts.days, zone)
        assertEquals(2, balances.size)
        assertEquals(94000L, balances.last().snapshot.balanceCents)
    }

    @Test fun `restoring spending inclusion or recategorizing preserves amounts fingerprints and observations`() {
        val transfer = item(1, 5000, "My transfers")
        assertTrue(SpendingPolicy.transactions(listOf(transfer), setOf("My transfers")).isEmpty())
        assertEquals(listOf(transfer), SpendingPolicy.transactions(listOf(transfer), emptySet()))
        val reclassified = transfer.copy(category = "Groceries")
        assertEquals(listOf(reclassified), SpendingPolicy.transactions(listOf(reclassified), setOf("My transfers")))
        assertEquals(transfer.fingerprint, reclassified.fingerprint)
        assertEquals(RecordedBalances.histories(listOf(transfer)), RecordedBalances.histories(listOf(reclassified)))
    }
}
