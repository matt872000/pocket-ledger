package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class BankSmsParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val received = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val sms = "【交通银行】您尾号*1234的卡11月01日14:31在支付宝支付宝-消费网上支付28.50元，现余额1,234.56元"

    @Test fun `extracts exact amounts and raw description`() {
        val parsed = requireNotNull(BankSmsParser.parse(sms, received, zone))
        assertEquals(2850L, parsed.amountCents)
        assertEquals(123456L, parsed.balanceCents)
        assertEquals("1234", parsed.card)
        assertEquals("支付宝支付宝-消费", parsed.description)
        assertEquals(BankSmsParser.Kind.EXPENSE, parsed.kind)
        assertEquals("3da7078591d28e2f6929c4176bd508eaa3da4e0bd74428c1f948b4d127743c79", parsed.fingerprint)
    }
    @Test fun `duplicates ignore receipt time and trailing punctuation`() {
        val first = requireNotNull(BankSmsParser.parse(sms, received, zone))
        val second = requireNotNull(BankSmsParser.parse(" $sms。", received + 1000, zone))
        assertEquals(first.fingerprint, second.fingerprint)
    }
    @Test fun `does not count refunds as expenses or accept malformed amounts`() {
        assertEquals(BankSmsParser.Kind.REFUND, BankSmsParser.parse(sms.replace("网上支付", "退款"), received, zone)?.kind)
        assertNull(BankSmsParser.parse(sms.replace("28.50", "1,2"), received, zone))
        assertNull(BankSmsParser.parse(sms.replace("28.50", "28.501"), received, zone))
        assertNull(BankSmsParser.parse(sms.replace("11月01日", "11月31日"), received, zone))
    }
    @Test fun `uses English labels for Chinese payment descriptions`() {
        assertEquals("Alipay payment", englishDescription("支付宝支付宝-消费"))
        assertEquals("WeChat Pay payment", englishDescription("财付通-消费"))
        assertEquals("Card payment", englishDescription("某商户-消费"))
    }

    private val refund = "【交通银行】您尾号1234的卡10月15日16:03在支付宝示例生活用品馆(个人独资）退款50.25元，现余额1,500.00元"
    private val incoming = "【交通银行】贵账户6230000000001234于2026年10月20日11:10在四川省分行跨行汇款转入资金12,345.67元，现余额20,000.00元，对方户名：企业网银代发工资专用内部账户，附言：工资Salary-示例学校"

    @Test fun `recognizes the provided card refund template with exact amount balance and merchant`() {
        val parsed = requireNotNull(BankSmsParser.parse(refund, received, zone))
        assertEquals(BankSmsParser.Kind.REFUND, parsed.kind)
        assertEquals(5025L, parsed.amountCents)
        assertEquals(150000L, parsed.balanceCents)
        assertEquals("支付宝示例生活用品馆(个人独资）", parsed.description)
        assertEquals(LocalDateTime.of(2026, 10, 15, 16, 3).atZone(zone).toInstant().toEpochMilli(), parsed.occurredAt)
        assertEquals(refund, parsed.originalSms)
        val expense = requireNotNull(BankSmsParser.parse(refund.replace("退款", "支付"), received, zone))
        assertNotEquals(expense.fingerprint, parsed.fingerprint)
    }

    @Test fun `incoming transfers preserve explicit year and memo while displaying only account suffix`() {
        val parsed = requireNotNull(BankSmsParser.parse(incoming, received + 730L * 86400000, zone))
        assertEquals(BankSmsParser.Kind.INCOME, parsed.kind)
        assertEquals("1234", parsed.card)
        assertEquals(1234567L, parsed.amountCents)
        assertEquals(2000000L, parsed.balanceCents)
        assertTrue(parsed.description.contains("工资Salary-示例学校"))
        assertFalse(parsed.description.contains("6230000000001234"))
        assertEquals(LocalDateTime.of(2026, 10, 20, 11, 10).atZone(zone).toInstant().toEpochMilli(), parsed.occurredAt)
    }

    @Test fun `refund and incoming duplicates ignore receipt delays and punctuation`() {
        for (message in listOf(refund, incoming)) {
            val first = requireNotNull(BankSmsParser.parse(message, received, zone))
            val duplicate = requireNotNull(BankSmsParser.parse(" $message。 ", received + 10000, zone))
            assertEquals(first.fingerprint, duplicate.fingerprint)
        }
        assertEquals("1234", BankSmsParser.parse(incoming.replace("6230000000001234", "********1234"), received, zone)?.card)
    }

    @Test fun `unsupported credits placeholders and malformed new message values stay rejected`() {
        val invalid = listOf(refund.replace("50.25", "refund amount"), refund.replace("50.25", "-1.00"),
            refund.replace("50.25", "1,2"), refund.replace("10月15日", "10月32日"),
            incoming.replace("2026年10月20日", "2026年02月30日"), incoming.replace("11:10", "25:10"),
            incoming.replace("12,345.67", "0"), incoming.replace("20,000.00", "1,2"),
            incoming.replace("转入资金", "转出资金"), incoming.replace("6230000000001234", "banknumber"),
            incoming.substringBefore("，对方户名"), refund + " click this link")
        invalid.forEach { assertNull(it, BankSmsParser.parse(it, received, zone)) }
    }
    private val networkIncome = "【交通银行】您尾号*1234的卡于2026年11月06日07:54网络支付转入123.45元,交易后余额为456.78元。"

    @Test fun `network payment credit recognizes the new balance wording and explicit timestamp`() {
        val parsed = requireNotNull(BankSmsParser.parse(networkIncome, received + 730L * 86400000, zone))
        assertEquals(BankSmsParser.Kind.INCOME, parsed.kind)
        assertEquals("1234", parsed.card)
        assertEquals(12345L, parsed.amountCents)
        assertEquals(45678L, parsed.balanceCents)
        assertEquals("网络支付转入", parsed.description)
        assertEquals(LocalDateTime.of(2026, 11, 6, 7, 54).atZone(zone).toInstant().toEpochMilli(), parsed.occurredAt)
        assertEquals(networkIncome, parsed.originalSms)
    }

    @Test fun `network credit duplicates normalize punctuation mask marker and receipt time`() {
        val first = requireNotNull(BankSmsParser.parse(networkIncome, received, zone))
        val variant = networkIncome.replace("*1234", "1234").replace(",交易", "，交易").removeSuffix("。")
        val second = requireNotNull(BankSmsParser.parse(" $variant ", received + 1000, zone))
        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test fun `network credit rejects malformed amounts dates outflows other banks and extra suffixes`() {
        val invalid = listOf(networkIncome.replace("123.45", "0"), networkIncome.replace("123.45", "-1.00"),
            networkIncome.replace("123.45", "1,2"), networkIncome.replace("456.78", "1.234"),
            networkIncome.replace("11月06日", "02月30日"), networkIncome.replace("07:54", "25:54"),
            networkIncome.replace("网络支付转入", "网络支付转出"), networkIncome.replace("【交通银行】", "【其他银行】"),
            networkIncome + " click this link")
        invalid.forEach { assertNull(it, BankSmsParser.parse(it, received, zone)) }
    }
}
