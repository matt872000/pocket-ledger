package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class RecordedBalancesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun transaction(time: String, balance: Long, card: String = "1234", id: Long = 0) = Transaction(
        id = id, fingerprint = "$card-$time-$balance", card = card,
        occurredAt = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli(),
        description = "支付宝支付宝-消费", amountCents = 2850, balanceCents = balance, originalSms = "Original SMS")

    @Test fun `latest balance follows SMS payment time rather than import order or row ID`() {
        val latest = transaction("2026-11-02T12:00", 123456, id = 1)
        val old = transaction("2026-10-01T12:00", 999999, id = 100)
        val inputs = listOf(latest, old)
        assertEquals(123456L, RecordedBalances.histories(inputs).single().latest.balanceCents)
        assertEquals(RecordedBalances.histories(inputs), RecordedBalances.histories(inputs.reversed()))
    }

    @Test fun `cards have independent latest balances without combining account values`() {
        val histories = RecordedBalances.histories(listOf(transaction("2026-11-02T12:00", 100, "5678"), transaction("2026-11-01T12:00", 200, "1234")))
        assertEquals(listOf("1234", "5678"), histories.map { it.card })
        assertEquals(listOf(200L, 100L), histories.map { it.latest.balanceCents })
    }

    @Test fun `a zero balance is an observed balance and an empty ledger has no invented value`() {
        assertEquals(0L, RecordedBalances.histories(listOf(transaction("2026-11-02T12:00", 0))).single().latest.balanceCents)
        assertTrue(RecordedBalances.histories(emptyList()).isEmpty())
    }

    @Test fun `conflicting same minute messages retain every reported value without guessing order`() {
        val first = transaction("2026-11-02T12:00", 1000, id = 2)
        val second = transaction("2026-11-02T12:00", 2000, id = 1)
        val duplicateValue = second.copy(id = 3, amountCents = 3000, fingerprint = "different-payment")
        val latest = RecordedBalances.histories(listOf(first, second, duplicateValue)).single().latest
        assertNull(latest.balanceCents)
        assertEquals(listOf(1000L, 2000L), latest.balancesCents)
        assertEquals(3, latest.messageCount)
    }

    @Test fun `same minute messages with an identical balance remain unambiguous`() {
        val first = transaction("2026-11-02T12:00", 1000)
        val latest = RecordedBalances.histories(listOf(first, first.copy(fingerprint = "other-payment", amountCents = 500))).single().latest
        assertEquals(1000L, latest.balanceCents)
        assertEquals(2, latest.messageCount)
    }

    @Test fun `history is chronological and monthly filtering uses the phone timezone`() {
        val boundary = transaction("2026-11-01T00:30", 100)
        val later = transaction("2026-11-02T12:00", 200)
        val history = RecordedBalances.histories(listOf(later, boundary)).single()
        assertEquals(listOf(boundary.occurredAt, later.occurredAt), history.points.map { it.occurredAt })
        assertEquals(2, RecordedBalances.inMonth(history, YearMonth.of(2026, 11), zone).size)
        assertEquals(1, RecordedBalances.inMonth(history, YearMonth.of(2026, 11), ZoneId.of("UTC")).size)
        assertEquals(100L, RecordedBalances.inMonth(history, YearMonth.of(2026, 10), ZoneId.of("UTC")).single().balanceCents)
    }

    @Test fun `renaming and categorizing do not alter balances and deletion falls back to previous snapshot`() {
        val older = transaction("2026-11-01T12:00", 9000)
        val newer = transaction("2026-11-02T12:00", 5000)
        val original = RecordedBalances.histories(listOf(older, newer))
        val edited = RecordedBalances.histories(listOf(older.copy(category = "Groceries"), newer.copy(purchaseName = "Lunch")))
        assertEquals(original, edited)
        assertEquals(9000L, RecordedBalances.histories(listOf(older)).single().latest.balanceCents)
    }
}
