package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class LedgerCsvExporterTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val purchase = Transaction(id = 1, fingerprint = "stable-id", card = "1234",
        occurredAt = ZonedDateTime.of(2026, 11, 1, 14, 31, 0, 0, zone).toInstant().toEpochMilli(),
        description = "支付宝支付宝-消费", amountCents = 2850, balanceCents = 123456,
        originalSms = "Original Chinese SMS", category = "Food & drink", purchaseName = "Lunch, \"with friends\"")
    private fun export(format: ExportFormat, item: Transaction = purchase): String = StringWriter().also {
        LedgerCsvExporter.write(it, listOf(item), format, zone)
    }.toString()

    @Test fun `firefly expenses have exact negative amounts CNY account roles and escaped names`() {
        assertEquals("Date,Description,Amount,Currency,Asset account,Opposing account,Category,External ID\r\n" +
            "\"2026-11-01 14:31:00\",\"Lunch, \"\"with friends\"\"\",\"-28.50\",\"CNY\",\"Bank of Communications 1234\",\"Unknown merchant\",\"Food & drink\",\"pocket-ledger:stable-id\"\r\n",
            export(ExportFormat.FIREFLY))
    }

    @Test fun `renames and category changes retain firefly external identity`() {
        val before = export(ExportFormat.FIREFLY).lineSequence().drop(1).first().substringAfterLast(',')
        val after = export(ExportFormat.FIREFLY, purchase.copy(purchaseName = "Dinner", category = "Other"))
            .lineSequence().drop(1).first().substringAfterLast(',')
        assertEquals(before, after)
    }

    @Test fun `CSV escapes untrusted text without turning expense numbers into text formulas`() {
        val csv = export(ExportFormat.FIREFLY, purchase.copy(purchaseName = "  =2+2"))
        assertTrue(csv.contains("\"'  =2+2\""))
        assertTrue(csv.contains("\"-28.50\""))
        assertFalse(csv.contains("\"'-28.50\""))
    }

    @Test fun `ordinary export uses renamed purchases and retains exact balance`() {
        val csv = export(ExportFormat.LEDGER)
        assertTrue(csv.contains("\"Lunch, \"\"with friends\"\"\""))
        assertTrue(csv.contains("\"28.50\",\"1234.56\""))
        assertFalse(csv.contains(purchase.originalSms))
    }

    @Test fun `Chinese phone locale does not change exported amounts dates or fallback labels`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            val csv = export(ExportFormat.FIREFLY, purchase.copy(purchaseName = ""))
            assertTrue(csv.contains("\"2026-11-01 14:31:00\",\"Alipay payment\",\"-28.50\""))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `empty export contains only the documented header`() {
        val writer = StringWriter()
        LedgerCsvExporter.write(writer, emptyList(), ExportFormat.FIREFLY, zone)
        assertEquals(1, writer.toString().trimEnd().lines().size)
    }

    @Test fun `refunds and incoming payments export as credits without changing expense IDs`() {
        val refund = export(ExportFormat.FIREFLY, purchase.copy(kind = TransactionKind.REFUND))
        val income = export(ExportFormat.FIREFLY, purchase.copy(kind = TransactionKind.INCOME))
        assertTrue(refund.contains("\"28.50\",\"CNY\""))
        assertTrue(refund.contains("\"Unknown merchant\""))
        assertTrue(income.contains("\"28.50\",\"CNY\""))
        assertTrue(income.contains("\"Unknown income\""))
        assertTrue(refund.contains("pocket-ledger:stable-id"))
        assertTrue(income.contains("pocket-ledger:stable-id"))
    }

    @Test fun `ordinary CSV distinguishes credit types effects and absent balances`() {
        val refund = export(ExportFormat.LEDGER, purchase.copy(kind = TransactionKind.REFUND, hasReportedBalance = false))
        assertTrue(refund.contains("\"28.50\",\"\",\"Refund\",\"-28.50\",\"Yes\""))
        val income = export(ExportFormat.LEDGER, purchase.copy(kind = TransactionKind.INCOME))
        assertTrue(income.contains("\"Incoming payment\",\"0.00\",\"No\""))
        assertTrue(refund.lineSequence().first().contains("Spending effect CNY"))
    }

    @Test fun `ordinary CSV retains excluded transfers while Firefly skips them instead of inventing an expense`() {
        val transfer = purchase.copy(fingerprint = "transfer-id", category = "My transfers", purchaseName = "Move money")
        val items = listOf(purchase, transfer)
        val ledger = StringWriter().also { LedgerCsvExporter.write(it, items, ExportFormat.LEDGER, zone, setOf("My transfers")) }.toString()
        assertTrue(ledger.lineSequence().first().endsWith("Counts as spending"))
        assertEquals(3, ledger.trimEnd().lines().size)
        assertTrue(ledger.trimEnd().lines()[1].endsWith("\"Yes\""))
        assertTrue(ledger.trimEnd().lines()[2].endsWith("\"No\""))
        assertTrue(ledger.contains("\"28.50\",\"1234.56\""))
        val firefly = StringWriter().also { LedgerCsvExporter.write(it, items, ExportFormat.FIREFLY, zone, setOf("my TRANSFERS")) }.toString()
        assertEquals(export(ExportFormat.FIREFLY), firefly)
        assertFalse(firefly.contains("transfer-id"))
    }
}
