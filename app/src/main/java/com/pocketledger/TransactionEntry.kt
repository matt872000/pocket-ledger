package com.pocketledger

import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class TransactionKind(val label: String) {
    EXPENSE("Expense"), REFUND("Refund"), INCOME("Incoming payment")
}

data class ManualEntry(
    val kind: TransactionKind, val card: String, val amountCents: Long, val occurredAt: Long,
    val category: String, val name: String, val reportedBalanceCents: Long?
)

object TransactionEntry {
    fun validate(kind: TransactionKind, card: String, amount: String, date: String, time: String,
                 category: String, name: String, balance: String, zone: ZoneId = ZoneId.systemDefault()): ManualEntry {
        require(card.trim().matches(Regex("[0-9]{3,6}"))) { "Enter the card's last 3 to 6 digits." }
        require(amount.trim().matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "Enter a positive CNY amount with up to two decimal places." }
        val cents = runCatching { BigDecimal(amount.trim()).movePointRight(2).longValueExact() }.getOrNull()
        require(cents != null && cents > 0) { "Enter a positive CNY amount within the supported range." }
        require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && time.matches(Regex("[0-9]{2}:[0-9]{2}"))) { "Use date YYYY-MM-DD and time HH:mm." }
        val local = runCatching { LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)) }.getOrNull()
        require(local != null) { "Enter a valid date and time." }
        val resolved = local.atZone(zone)
        require(resolved.toLocalDateTime() == local) { "That local time does not exist. Choose another time." }
        require(purchaseNameError(name) == null) { purchaseNameError(name).orEmpty() }
        val reported = if (balance.isBlank()) null else {
            require(balance.trim().matches(Regex("-?[0-9]+(?:\\.[0-9]{1,2})?"))) { "Enter a reported balance with up to two decimal places, or leave it blank." }
            requireNotNull(runCatching { BigDecimal(balance.trim()).movePointRight(2).longValueExact() }.getOrNull()) { "Reported balance is outside the supported range." }
        }
        return ManualEntry(kind, card.trim(), cents, resolved.toInstant().toEpochMilli(), category, name.trim(), reported)
    }
}

val Transaction.isManual: Boolean get() = fingerprint.startsWith("manual:")
val Transaction.signedAmountLabel: String get() = (if (kind == TransactionKind.EXPENSE) "−" else "+") + chartMoney(BigInteger.valueOf(amountCents))
