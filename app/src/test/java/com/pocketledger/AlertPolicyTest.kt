package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class AlertPolicyTest {
    @Test fun `levels are adjustable sorted unique and support overspending`() {
        assertEquals(listOf(50, 80, 100, 125), AlertPolicy.parseThresholds("100, 50, 125, 80, 80"))
        assertEquals(listOf(1, 1000), AlertPolicy.parseThresholds("1,1000"))
    }

    @Test fun `empty malformed out of range and excessive levels are rejected`() {
        listOf("", " ", "80,", ",100", "0", "1001", "-50", "80.5", "80%", "one", (1..11).joinToString(","))
            .forEach { assertNull(it, AlertPolicy.parseThresholds(it)) }
    }

    @Test fun `budget thresholds use exact cents including equality and overspending`() {
        assertEquals(emptyList<Int>(), AlertPolicy.reached(BigInteger.valueOf(7999), 10000, listOf(80, 100)))
        assertEquals(listOf(80), AlertPolicy.reached(BigInteger.valueOf(8000), 10000, listOf(80, 100)))
        assertEquals(listOf(80, 100, 125), AlertPolicy.reached(BigInteger.valueOf(12500), 10000, listOf(80, 100, 125)))
        assertTrue(AlertPolicy.reached(BigInteger.TEN, 0, listOf(80)).isEmpty())
    }

    @Test fun `spending uses the current month in the phones timezone without excluding uncategorized purchases`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val before = ZonedDateTime.of(2026, 10, 31, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val after = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val purchases = listOf(AlertPurchase(1, before, 5000, UNCATEGORIZED), AlertPurchase(2, after, 2850, "Groceries"), AlertPurchase(3, after, 50, UNCATEGORIZED))
        assertEquals(BigInteger.valueOf(2900), AlertPolicy.spending(purchases, YearMonth.of(2026, 11), zone))
    }

    @Test fun `large ledgers cannot overflow budget comparisons`() {
        val huge = BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO
        assertEquals(listOf(80, 100, 200), AlertPolicy.reached(huge, Long.MAX_VALUE, listOf(80, 100, 200, 201)))
    }
}
