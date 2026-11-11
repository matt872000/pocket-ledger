package com.pocketledger

import java.io.Writer
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ExportFormat { LEDGER, FIREFLY }

object LedgerCsvExporter {
    fun write(writer: Writer, transactions: List<Transaction>, format: ExportFormat, zone: ZoneId = ZoneId.systemDefault(), excludedCategories: Set<String> = emptySet()) {
        val headers = if (format == ExportFormat.FIREFLY)
            listOf("Date", "Description", "Amount", "Currency", "Asset account", "Opposing account", "Category", "External ID")
        else listOf("Date", "Card", "Description", "Category", "Amount CNY", "Balance CNY", "Type", "Spending effect CNY", "Counts as spending")
        writer.write(headers.joinToString(",") + "\r\n")
        transactions.forEach { transaction ->
            val includedCategory = SpendingPolicy.counts(transaction.category, excludedCategories)
            val countsAsSpending = includedCategory && transaction.kind != TransactionKind.INCOME
            if (format == ExportFormat.FIREFLY && !includedCategory) return@forEach
            val amount = BigDecimal.valueOf(transaction.amountCents, 2)
            val date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zone)
            val fields = if (format == ExportFormat.FIREFLY) listOf(
                cell(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH))),
                cell(transaction.displayName),
                cell((if (transaction.kind == TransactionKind.EXPENSE) amount.negate() else amount).toPlainString(), protectFormula = false),
                cell("CNY"), cell("Bank of Communications ${transaction.card}"), cell(if (transaction.kind == TransactionKind.INCOME) "Unknown income" else "Unknown merchant"),
                cell(transaction.category), cell("pocket-ledger:${transaction.fingerprint}")
            ) else listOf(
                cell(date.toOffsetDateTime().toString()), cell("Card ending ${transaction.card}"),
                cell(transaction.displayName), cell(transaction.category),
                cell(BigDecimal.valueOf(transaction.amountCents, 2).toPlainString(), protectFormula = false),
                cell(if (transaction.hasReportedBalance) BigDecimal.valueOf(transaction.balanceCents, 2).toPlainString() else "", protectFormula = false),
                cell(transaction.kind.label),
                cell(if (includedCategory) BigDecimal(SpendingPolicy.delta(transaction.amountCents, transaction.kind), 2).toPlainString() else "0.00", protectFormula = false),
                cell(if (countsAsSpending) "Yes" else "No")
            )
            writer.write(fields.joinToString(",") + "\r\n")
        }
    }

    private fun cell(value: String, protectFormula: Boolean = true): String {
        val dangerous = value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') ||
            value.firstOrNull() in listOf('\t', '\r', '\n')
        val safe = if (protectFormula && dangerous) "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
}
