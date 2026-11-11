package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class TransactionEntryTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val month = YearMonth.of(2026, 11)
    private fun item(day: Int, cents: Long, kind: TransactionKind, category: String = "Shopping") = Transaction(
        fingerprint = "manual:$day-$kind-$cents", card = "1234",
        occurredAt = ZonedDateTime.of(month.atDay(day).atTime(12, 0), zone).toInstant().toEpochMilli(),
        description = kind.label, amountCents = cents, balanceCents = 0, originalSms = "", category = category,
        kind = kind, hasReportedBalance = false)

    @Test fun `manual entries validate exact cents and distinguish unknown balance from zero`() {
        val unknown = TransactionEntry.validate(TransactionKind.REFUND, "1234", "50.25", "2026-11-02", "12:30", "Shopping", " Returned item ", "", zone)
        assertEquals(5025L, unknown.amountCents)
        assertEquals("Returned item", unknown.name)
        assertNull(unknown.reportedBalanceCents)
        val zero = TransactionEntry.validate(TransactionKind.INCOME, "1234", "1000.01", "2026-11-02", "12:30", "Other", "", "0", zone)
        assertEquals(0L, zero.reportedBalanceCents)
        assertEquals(100001L, zero.amountCents)
    }

    @Test fun `invalid dates precision overflow nonpositive money and invalid cards are rejected`() {
        fun valid(amount: String = "1", date: String = "2026-11-02", time: String = "12:30", card: String = "1234", balance: String = "") =
            runCatching { TransactionEntry.validate(TransactionKind.REFUND, card, amount, date, time, UNCATEGORIZED, "", balance, zone) }.isSuccess
        listOf("0", "-1", "1.001", "1,2", "999999999999999999999").forEach { assertFalse(valid(amount = it)) }
        assertFalse(valid(date = "2026-02-30"))
        assertFalse(valid(time = "25:00"))
        assertFalse(valid(card = "banknumber"))
        assertFalse(valid(balance = "1.001"))
    }

    @Test fun `refunds reduce spending while incoming payments and excluded categories do not offset it`() {
        val transactions = listOf(item(1, 10000, TransactionKind.EXPENSE), item(2, 2500, TransactionKind.REFUND),
            item(2, 100000, TransactionKind.INCOME), item(2, 99999, TransactionKind.REFUND, "Transfers"))
        val net = SpendingPolicy.net(transactions, setOf("Transfers"))
        assertEquals(BigInteger.valueOf(7500), net)
        assertEquals(BigInteger.valueOf(100000), SpendingPolicy.totalOfKind(transactions, TransactionKind.INCOME, setOf("Transfers")))
        val alertRows = transactions.mapIndexed { index, it -> AlertPurchase(index.toLong(), it.occurredAt, it.amountCents, it.category, it.kind) }
        assertEquals(net, AlertPolicy.spending(alertRows, month, zone, setOf("Transfers")))
        assertTrue(AlertPolicy.reached(net, 10000, listOf(80, 100)).isEmpty())
    }

    @Test fun `refund-only days and months retain negative totals rather than clamping away credits`() {
        val transactions = listOf(item(1, 3000, TransactionKind.REFUND), item(2, 1000, TransactionKind.EXPENSE), item(2, 50000, TransactionKind.INCOME))
        val charts = SpendingChartData.build(transactions, month, zone, month.atDay(2))
        assertEquals(BigInteger.valueOf(-2000), charts.totalCents)
        assertEquals(BigInteger.valueOf(-3000), charts.days.first().cumulativeCents)
        assertEquals(BigInteger.valueOf(-2000), charts.days.last().cumulativeCents)
        assertEquals(BigInteger.valueOf(-2000), charts.categories.single().cents)
        assertEquals(BigInteger.valueOf(3000), charts.refundCents)
        assertEquals(BigInteger.valueOf(50000), charts.incomeCents)
        assertEquals(2, charts.purchaseCount)
        assertEquals(charts.totalCents, charts.months.last().cents)
    }

    @Test fun `refunds affect the refund month and large sums do not overflow`() {
        val old = item(1, Long.MAX_VALUE, TransactionKind.EXPENSE).copy(occurredAt = month.minusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val refund = item(1, 1000, TransactionKind.REFUND)
        val charts = SpendingChartData.build(listOf(old, refund), month, zone, month.atDay(2))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), charts.previousMonthCents)
        assertEquals(BigInteger.valueOf(-1000), charts.totalCents)
        val huge = listOf(item(1, Long.MAX_VALUE, TransactionKind.EXPENSE), item(2, Long.MAX_VALUE, TransactionKind.EXPENSE), refund)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO - BigInteger.valueOf(1000), SpendingPolicy.net(huge, emptySet()))
    }

    @Test fun `missing manual balances never erase a real observation and explicit zero remains valid`() {
        val bank = item(1, 1000, TransactionKind.EXPENSE).copy(fingerprint = "bank", balanceCents = 9000, hasReportedBalance = true)
        val refund = item(2, 500, TransactionKind.REFUND)
        assertEquals(9000L, RecordedBalances.histories(listOf(bank, refund)).single().latest.balanceCents)
        assertTrue(RecordedBalances.histories(listOf(refund)).isEmpty())
        val incoming = item(3, 1000, TransactionKind.INCOME).copy(hasReportedBalance = true)
        assertEquals(0L, RecordedBalances.histories(listOf(bank, refund, incoming)).single().latest.balanceCents)
        assertEquals("Refund", refund.displayName)
        assertEquals("Incoming payment", incoming.displayName)
        assertTrue(refund.signedAmountLabel.startsWith("+"))
    }
}
