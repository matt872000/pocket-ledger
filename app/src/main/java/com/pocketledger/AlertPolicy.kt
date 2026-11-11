package com.pocketledger

import java.math.BigInteger
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class AlertSettings(
    val budgetEnabled: Boolean = false,
    val categorizationEnabled: Boolean = false,
    val thresholds: List<Int> = listOf(80, 100),
    val showBudgetAmounts: Boolean = false
)

data class AlertPurchase(val id: Long, val occurredAt: Long, val amountCents: Long, val category: String, val kind: TransactionKind = TransactionKind.EXPENSE)

object AlertPolicy {
    fun parseThresholds(text: String): List<Int>? {
        val parts = text.split(',')
        if (parts.isEmpty() || parts.size > 10) return null
        val values = parts.map { part ->
            val trimmed = part.trim()
            if (!trimmed.matches(Regex("[0-9]{1,4}"))) return null
            trimmed.toIntOrNull()?.takeIf { it in 1..1000 } ?: return null
        }
        return values.distinct().sorted()
    }

    fun spending(purchases: List<AlertPurchase>, month: YearMonth, zone: ZoneId, excludedCategories: Set<String> = emptySet(), startDay: Int = 1): BigInteger =
        purchases.asSequence()
            .filter { SpendingPolicy.counts(it.category, excludedCategories) }
            .filter { BudgetCycle.includes(it.occurredAt, month, zone, startDay) }
            .fold(BigInteger.ZERO) { total, purchase -> total + SpendingPolicy.delta(purchase.amountCents, purchase.kind) }

    fun reached(spent: BigInteger, budgetCents: Long, thresholds: List<Int>): List<Int> {
        if (budgetCents <= 0) return emptyList()
        return thresholds.distinct().sorted().filter {
            spent * BigInteger.valueOf(100) >= BigInteger.valueOf(budgetCents) * BigInteger.valueOf(it.toLong())
        }
    }
}
