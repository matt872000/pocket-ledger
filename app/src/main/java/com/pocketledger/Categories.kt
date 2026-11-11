package com.pocketledger

import java.math.BigInteger

const val UNCATEGORIZED = "Uncategorized"
val defaultCategories = listOf(UNCATEGORIZED, "Food & drink", "Groceries", "Transport", "Shopping", "Bills", "Health", "Entertainment", "Travel", "Other")

fun categoryNameError(value: String): String? = when {
    value.trim().isEmpty() -> "Enter a category name."
    value.trim().length > 40 || value.trim().any { it !in ' '..'~' } ->
        "Use English letters, numbers, and punctuation, up to 40 characters."
    else -> null
}

fun purchaseNameError(value: String): String? = when {
    value.trim().length > 120 -> "Use up to 120 characters for the purchase name."
    value.any { it.isISOControl() } -> "Use a single line for the purchase name."
    else -> null
}

val Transaction.displayName: String get() = purchaseName.ifBlank {
    if (isManual) kind.label else when (kind) {
        TransactionKind.INCOME -> if (description.contains("工资")) "Salary payment" else "Incoming payment"
        TransactionKind.REFUND -> englishDescription(description).removeSuffix(" payment") + " refund"
        TransactionKind.EXPENSE -> englishDescription(description)
    }
}

object SpendingPolicy {
    fun counts(category: String, excludedCategories: Set<String>): Boolean =
        excludedCategories.none { it.equals(category, ignoreCase = true) }

    fun transactions(transactions: List<Transaction>, excludedCategories: Set<String>): List<Transaction> =
        transactions.filter { it.kind != TransactionKind.INCOME && counts(it.category, excludedCategories) }

    fun delta(cents: Long, kind: TransactionKind): BigInteger = when (kind) {
        TransactionKind.EXPENSE -> BigInteger.valueOf(cents)
        TransactionKind.REFUND -> BigInteger.valueOf(cents).negate()
        TransactionKind.INCOME -> BigInteger.ZERO
    }

    fun net(transactions: List<Transaction>, excludedCategories: Set<String>): BigInteger =
        SpendingPolicy.transactions(transactions, excludedCategories).fold(BigInteger.ZERO) { total, entry -> total + delta(entry.amountCents, entry.kind) }

    fun totalOfKind(transactions: List<Transaction>, kind: TransactionKind, excludedCategories: Set<String>): BigInteger =
        transactions.filter { it.kind == kind && counts(it.category, excludedCategories) }
            .fold(BigInteger.ZERO) { total, entry -> total + BigInteger.valueOf(entry.amountCents) }
}

fun englishDescription(raw: String): String = when {
    raw.contains("支付宝") -> "Alipay payment"
    raw.contains("微信") || raw.contains("财付通") -> "WeChat Pay payment"
    raw.any { it.code in 0x3400..0x9fff } -> "Card payment"
    else -> raw
}
