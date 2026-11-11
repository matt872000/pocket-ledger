package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class BalanceLineDataTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val month = YearMonth.of(2026, 11)
    private fun purchase(time: String, balance: Long, card: String = "1234") = Transaction(
        fingerprint = "$card-$time-$balance", card = card,
        occurredAt = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli(),
        description = "Payment", amountCents = 100, balanceCents = balance, originalSms = "SMS")

    private fun days(transactions: List<Transaction>) = SpendingChartData.build(transactions, month, zone, month.atDay(3)).days

    @Test fun `balances align with payment times within days and exclude other months`() {
        val transactions = listOf(purchase("2026-10-31T23:59", 900), purchase("2026-11-01T00:00", 800),
            purchase("2026-11-02T12:00", 700), purchase("2026-12-01T00:00", 600))
        val points = BalanceLineData.build(RecordedBalances.histories(transactions).single(), days(transactions), zone)
        assertEquals(2, points.size)
        assertEquals(0f, points[0].dayOffset, 0.00001f)
        assertEquals(1.5f, points[1].dayOffset, 0.00001f)
        assertEquals(listOf(800L, 700L), points.map { it.snapshot.balanceCents })
    }

    @Test fun `day inspection uses latest payment rather than import order and never carries into missing days`() {
        val transactions = listOf(purchase("2026-11-01T22:00", 500), purchase("2026-11-01T08:00", 800))
        val points = BalanceLineData.build(RecordedBalances.histories(transactions).single(), days(transactions), zone)
        assertEquals(500L, BalanceLineData.latestOnDay(points, month.atDay(1))?.balanceCents)
        assertNull(BalanceLineData.latestOnDay(points, month.atDay(2)))
        assertNull(BalanceLineData.latestOnDay(points, month.minusMonths(1).atEndOfMonth()))
    }

    @Test fun `ambiguous latest minute keeps all values while zero and earlier observations remain valid`() {
        val transactions = listOf(purchase("2026-11-01T08:00", 0), purchase("2026-11-01T22:00", 700), purchase("2026-11-01T22:00", 500))
        val points = BalanceLineData.build(RecordedBalances.histories(transactions).single(), days(transactions), zone)
        assertEquals(0L, points.first().snapshot.balanceCents)
        val latest = BalanceLineData.latestOnDay(points, month.atDay(1))!!
        assertNull(latest.balanceCents)
        assertEquals(listOf(500L, 700L), latest.balancesCents)
        assertEquals(2, latest.messageCount)
    }

    @Test fun `only the selected card contributes balances and empty selections are empty`() {
        val transactions = listOf(purchase("2026-11-01T12:00", 1000), purchase("2026-11-01T12:00", 9000, "5678"))
        val selected = RecordedBalances.histories(transactions).first { it.card == "1234" }
        assertEquals(1000L, BalanceLineData.build(selected, days(transactions), zone).single().snapshot.balanceCents)
        assertTrue(BalanceLineData.build(null, days(transactions), zone).isEmpty())
        assertTrue(BalanceLineData.build(selected, emptyList(), zone).isEmpty())
    }

    @Test fun `calendar day and horizontal position use the phone timezone`() {
        val transactions = listOf(purchase("2026-11-01T00:30", 100), purchase("2026-11-02T12:00", 200))
        val history = RecordedBalances.histories(transactions).single()
        val localPoints = BalanceLineData.build(history, days(transactions), zone)
        assertEquals(2, localPoints.size)
        val utc = ZoneId.of("UTC")
        val utcPoints = BalanceLineData.build(history, days(transactions), utc)
        assertEquals(1, utcPoints.size)
        assertEquals(month.atDay(2), utcPoints.single().date)
        assertEquals(1f + 4f / 24f, utcPoints.single().dayOffset, 0.00001f)
    }
}
