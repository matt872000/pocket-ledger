package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class SpendingChartsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun purchase(date: String, cents: Long, category: String = UNCATEGORIZED) = Transaction(
        fingerprint = "$date-$cents-$category", card = "1234",
        occurredAt = LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        description = "支付宝支付宝-消费", amountCents = cents, balanceCents = 0,
        originalSms = "Original SMS", category = category)
    private fun build(transactions: List<Transaction>, month: String = "2026-11", today: String = "2026-12-05") =
        SpendingChartData.build(transactions, YearMonth.parse(month), zone, LocalDate.parse(today))

    @Test fun `six month series includes empty months in order across a year boundary`() {
        val data = build(listOf(purchase("2025-09-01", 99999), purchase("2025-10-01", 100)), "2026-03")
        assertEquals(listOf("2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03"), data.months.map { it.month.toString() })
        assertEquals(BigInteger.valueOf(100), data.months.first().cents)
        assertTrue(data.months.drop(1).all { it.cents == BigInteger.ZERO && it.purchases == 0 })
        assertEquals(BigInteger.ZERO, data.totalCents)
        assertTrue(data.categories.isEmpty())
    }

    @Test fun `category shares and counts include uncategorized spending in the selected month only`() {
        val data = build(listOf(purchase("2026-11-01", 1999, "Groceries"), purchase("2026-11-02", 1, "Groceries"),
            purchase("2026-11-03", 1000, "Food & drink"), purchase("2026-11-04", 500), purchase("2026-10-01", 999)))
        assertEquals(BigInteger.valueOf(3500), data.totalCents)
        assertEquals(4, data.purchaseCount)
        assertEquals(listOf("Groceries", "Food & drink", UNCATEGORIZED), data.categories.map { it.name })
        assertEquals(2, data.categories.first().purchases)
        assertEquals(data.totalCents, data.categories.fold(BigInteger.ZERO) { total, category -> total + category.cents })
        assertEquals(BigInteger.valueOf(999), data.previousMonthCents)
    }

    @Test fun `daily graph fills gaps with zero and carries forward the exact running total`() {
        val data = build(listOf(purchase("2026-11-01", 100), purchase("2026-11-03", 250), purchase("2026-11-03", 50)))
        assertEquals(30, data.days.size)
        assertEquals(BigInteger.ZERO, data.days[1].cents)
        assertEquals(BigInteger.valueOf(100), data.days[1].cumulativeCents)
        assertEquals(BigInteger.valueOf(300), data.days[2].cents)
        assertEquals(2, data.days[2].purchases)
        assertEquals(BigInteger.valueOf(400), data.days.last().cumulativeCents)
        assertEquals(data.totalCents, data.days.last().cumulativeCents)
    }

    @Test fun `current month graph stops at today while retaining any later dated imported purchases`() {
        val data = build(listOf(purchase("2026-11-01", 100)), today = "2026-11-02")
        assertEquals(2, data.days.size)
        val future = build(listOf(purchase("2026-11-03", 100)), today = "2026-11-02")
        assertEquals(3, future.days.size)
        assertEquals(future.totalCents, future.days.last().cumulativeCents)
    }

    @Test fun `leap months and a completely empty ledger remain valid chart inputs`() {
        val data = build(emptyList(), "2024-02")
        assertEquals(29, data.days.size)
        assertEquals(LocalDate.of(2024, 2, 29), data.days.last().date)
        assertTrue(data.days.all { it.cents == BigInteger.ZERO && it.cumulativeCents == BigInteger.ZERO && it.purchases == 0 })
        assertEquals(6, data.months.size)
        assertEquals(0f, chartFraction(BigInteger.ZERO, BigInteger.ZERO), 0f)
        assertEquals("0.0%", chartPercent(BigInteger.ZERO, BigInteger.ZERO))
    }

    @Test fun `month and day boundaries follow the phones timezone`() {
        val item = purchase("2026-11-01", 100).copy(occurredAt = LocalDate.of(2026, 11, 1).atTime(0, 30).atZone(zone).toInstant().toEpochMilli())
        val shanghai = build(listOf(item))
        val utc = SpendingChartData.build(listOf(item), YearMonth.of(2026, 11), ZoneId.of("UTC"), LocalDate.of(2026, 12, 5))
        assertEquals(BigInteger.valueOf(100), shanghai.totalCents)
        assertEquals(BigInteger.valueOf(100), shanghai.days.first().cents)
        assertEquals(BigInteger.ZERO, utc.totalCents)
        assertEquals(BigInteger.valueOf(100), utc.previousMonthCents)
    }

    @Test fun `large totals cannot overflow and renamed purchases do not change spending statistics`() {
        val first = purchase("2026-11-01", Long.MAX_VALUE, "Groceries")
        val second = purchase("2026-11-02", Long.MAX_VALUE, "Groceries").copy(purchaseName = "Renamed purchase")
        val data = build(listOf(first, second))
        val total = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO
        assertEquals(total, data.totalCents)
        assertEquals(total, data.categories.single().cents)
        assertEquals(total, data.days.last().cumulativeCents)
        assertEquals(0.5f, chartFraction(BigInteger.valueOf(Long.MAX_VALUE), total), 0.000001f)
    }

    @Test fun `chart money and percentages retain English formatting under Chinese locale`() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("¥1,234.56", chartMoney(BigInteger.valueOf(123456)))
            assertEquals("33.3%", chartPercent(BigInteger.ONE, BigInteger.valueOf(3)))
            assertEquals(1f, chartFraction(BigInteger.TEN, BigInteger.ONE), 0f)
        } finally { Locale.setDefault(old) }
    }
}
