package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.math.BigInteger

class BudgetCycleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun stamp(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    private fun tx(date: String, amount: Long, kind: TransactionKind = TransactionKind.EXPENSE, category: String = UNCATEGORIZED) = Transaction(
        fingerprint = "$date-$amount-$kind", card = "1234", occurredAt = stamp(date + "T12:00:00"),
        amountCents = amount, balanceCents = 0, description = "Synthetic", originalSms = "", kind = kind, category = category)

    @Test fun `cycle switches exactly at local midnight on the configured day`() {
        assertEquals(YearMonth.of(2026, 10), BudgetCycle.current(stamp("2026-11-20T23:59:59"), zone, 21))
        assertEquals(YearMonth.of(2026, 11), BudgetCycle.current(stamp("2026-11-21T00:00:00"), zone, 21))
        assertEquals(stamp("2026-11-21T00:00:00"), BudgetCycle.nextStart(stamp("2026-11-20T23:59:59"), zone, 21))
        assertEquals(YearMonth.of(2026, 10), BudgetCycle.current(stamp("2026-11-21T00:00:00"), ZoneId.of("UTC"), 21))
        assertEquals("Nov 21, 2026 – Dec 20, 2026", BudgetCycle.label(YearMonth.of(2026, 11), 21))
    }

    @Test fun `year boundaries and short months form contiguous periods`() {
        assertEquals(YearMonth.of(2026, 12), BudgetCycle.periodFor(LocalDate.of(2027, 1, 20), 21))
        assertEquals(LocalDate.of(2027, 2, 28), BudgetCycle.start(YearMonth.of(2027, 2), 31))
        assertEquals(LocalDate.of(2028, 2, 29), BudgetCycle.start(YearMonth.of(2028, 2), 31))
        for (day in 1..31) for (year in 2026..2028) for (month in 1..12) {
            val period = YearMonth.of(year, month)
            val start = BudgetCycle.start(period, day)
            val next = BudgetCycle.start(period.plusMonths(1), day)
            assertEquals(period.minusMonths(1), BudgetCycle.periodFor(start.minusDays(1), day))
            assertEquals(period, BudgetCycle.periodFor(start, day))
            assertEquals(period, BudgetCycle.periodFor(next.minusDays(1), day))
        }
    }

    @Test fun `charts alerts and spending use identical custom cycle net amounts`() {
        val period = YearMonth.of(2026, 10)
        val entries = listOf(tx("2026-10-20", 9999), tx("2026-10-21", 10000), tx("2026-11-20", 2000, TransactionKind.REFUND),
            tx("2026-11-01", 50000, TransactionKind.INCOME), tx("2026-11-02", 30000, category = "Transfers"), tx("2026-11-21", 9999))
        val excluded = setOf("Transfers")
        val chart = SpendingChartData.build(entries, period, zone, LocalDate.of(2026, 11, 20), excluded, 21)
        val selected = entries.filter { BudgetCycle.includes(it.occurredAt, period, zone, 21) }
        val alerts = entries.mapIndexed { index, t -> AlertPurchase(index.toLong(), t.occurredAt, t.amountCents, t.category, t.kind) }
        assertEquals(BigInteger.valueOf(8000), chart.totalCents)
        assertEquals(chart.totalCents, SpendingPolicy.net(selected, excluded))
        assertEquals(chart.totalCents, AlertPolicy.spending(alerts, period, zone, excluded, 21))
        assertEquals(chart.totalCents, chart.days.last().cumulativeCents)
        assertEquals(BigInteger.valueOf(50000), chart.incomeCents)
        assertEquals(LocalDate.of(2026, 10, 21), chart.days.first().date)
        assertEquals(LocalDate.of(2026, 11, 20), chart.days.last().date)
        assertEquals(31, chart.days.size)
    }

    @Test fun `clamped cycle graphs keep identical day numbers in separate months distinct`() {
        val chart = SpendingChartData.build(listOf(tx("2027-02-28", 100), tx("2027-03-28", 200)),
            YearMonth.of(2027, 2), zone, LocalDate.of(2027, 4, 1), startDay = 31)
        assertEquals(BigInteger.valueOf(100), chart.days.first().cents)
        assertEquals(BigInteger.valueOf(200), chart.days.single { it.date == LocalDate.of(2027, 3, 28) }.cents)
        assertEquals(BigInteger.valueOf(300), chart.days.last().cumulativeCents)
        assertEquals(LocalDate.of(2027, 3, 30), chart.days.last().date)
    }
}
