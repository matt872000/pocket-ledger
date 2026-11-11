import com.pocketledger.BankSmsParser;
import java.time.*;

public class ParserChecks {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final long RECEIVED = ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    static int checks;
    static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    static String sms(String date, String amount, String balance) {
        return "【交通银行】您尾号*1234的卡" + date + "在支付宝支付宝-消费网上支付" + amount + "元，现余额" + balance + "元";
    }
    public static void main(String[] args) {
        String body = sms("11月01日14:31", "28.50", "1,234.56");
        var p = BankSmsParser.parse(body, RECEIVED, ZONE);
        check(p != null, "supported format");
        check(p.getAmountCents() == 2850 && p.getBalanceCents() == 123456, "exact currency arithmetic");
        check(p.getCard().equals("1234"), "card extraction");
        check(p.getDescription().equals("支付宝支付宝-消费"), "description extraction");
        check(Instant.ofEpochMilli(p.getOccurredAt()).atZone(ZONE).getYear() == 2026, "year inference");
        check(p.getOriginalSms().equals(body), "original SMS preserved");
        check(p.getFingerprint().equals(BankSmsParser.parse("  " + body + "。", RECEIVED + 1000, ZONE).getFingerprint()), "duplicate fingerprint ignores receipt time and punctuation");
        check(!p.getFingerprint().equals(BankSmsParser.parse(body.replace("28.50", "28.51"), RECEIVED, ZONE).getFingerprint()), "different amounts remain distinct");
        long january = ZonedDateTime.of(2027, 1, 1, 1, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        var december = BankSmsParser.parse(sms("12月31日23:59", "1", "0"), january, ZONE);
        check(Instant.ofEpochMilli(december.getOccurredAt()).atZone(ZONE).getYear() == 2026, "New Year boundary");
        check(BankSmsParser.parse(sms("11月31日14:31", "1", "0"), RECEIVED, ZONE) == null, "invalid date");
        check(BankSmsParser.parse(sms("11月01日25:31", "1", "0"), RECEIVED, ZONE) == null, "invalid time");
        for (String amount : new String[]{"0", "-1", "1.999", "1,2", "9999999999999999999999", "amountchanged"})
            check(BankSmsParser.parse(sms("11月01日14:31", amount, "0"), RECEIVED, ZONE) == null, "reject malformed amount " + amount);
        check(BankSmsParser.parse(body.replace("【交通银行】", "【其他银行】"), RECEIVED, ZONE) == null, "other banks");
        check(BankSmsParser.parse(body.replace("网上支付", "退款"), RECEIVED, ZONE).getKind() == BankSmsParser.Kind.REFUND, "refund has its own type");
        check(BankSmsParser.parse(body.replace("网上支付", "转入"), RECEIVED, ZONE) == null, "income unsupported");
        check(BankSmsParser.parse(body + " click this link", RECEIVED, ZONE) == null, "unknown suffix unsupported");
        String incoming = "【交通银行】贵账户6230000000001234于2026年10月20日11:10在四川省分行跨行汇款转入资金1,000.50元，现余额2,000.00元，对方户名：示例公司，附言：工资Salary-示例";
        var credit = BankSmsParser.parse(incoming, RECEIVED, ZONE);
        check(credit != null && credit.getKind() == BankSmsParser.Kind.INCOME, "incoming message type");
        check(credit.getAmountCents() == 100050 && credit.getBalanceCents() == 200000, "incoming exact cents");
        check(credit.getCard().equals("1234"), "incoming account suffix only");
        check(credit.getFingerprint().equals(BankSmsParser.parse(incoming + "。", RECEIVED + 1000, ZONE).getFingerprint()), "incoming deduplication");
        String networkMessage = "【交通银行】您尾号*1234的卡于2026年11月06日07:54网络支付转入123.45元,交易后余额为456.78元。";
        var network = BankSmsParser.parse(networkMessage, RECEIVED, ZONE);
        check(network != null && network.getKind() == BankSmsParser.Kind.INCOME, "network credit recognized");
        check(network.getAmountCents() == 12345 && network.getBalanceCents() == 45678, "network credit exact cents");
        check(network.getFingerprint().equals(BankSmsParser.parse(networkMessage.replace(",交易", "，交易"), RECEIVED + 1000, ZONE).getFingerprint()), "network credit deduplication");
        System.out.println("Passed " + checks + " parser checks.");
    }
}
