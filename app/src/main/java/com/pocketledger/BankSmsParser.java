package com.pocketledger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.regex.*;

/** Strict recognition of the demonstrated Bank of Communications message templates. */
public final class BankSmsParser {
    private BankSmsParser() {}
    public enum Kind { EXPENSE, REFUND, INCOME }
    private static final String CARD = "【交通银行】您尾号\\s*\\*?([0-9]{3,6})的卡\\s*(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})";
    private static final String MONEY = "([\\d,]+(?:\\.\\d{1,2})?)";
    private static final String END = "元[，,]\\s*现余额" + MONEY + "元[。\\s]*";
    private static final Pattern EXPENSE = Pattern.compile(CARD + "在(.+?)(?:网上支付|支付)" + MONEY + END);
    private static final Pattern REFUND = Pattern.compile(CARD + "在(.+?)退款" + MONEY + END);
    private static final Pattern INCOME = Pattern.compile(
        "【交通银行】贵账户([0-9*]{3,32})于(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})在(.+?)转入资金" + MONEY +
        "元[，,]\\s*现余额" + MONEY + "元[，,]\\s*对方户名[：:]\\s*(.+?)[，,]\\s*附言[：:]\\s*(.+)");

    private static final Pattern NETWORK_INCOME = Pattern.compile(
        "【交通银行】您尾号\\s*\\*?([0-9]{3,6})的卡于(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})网络支付转入" + MONEY +
        "元[，,]\\s*交易后余额为" + MONEY + "元[。\\s]*");
    public static ParsedTransaction parse(String body, long receivedAt) { return parse(body, receivedAt, ZoneId.systemDefault()); }
    public static ParsedTransaction parse(String body, long receivedAt, ZoneId zone) {
        if (body == null) return null;
        String text = body.trim();
        try {
            Matcher match = EXPENSE.matcher(text);
            Kind kind = Kind.EXPENSE;
            if (!match.matches()) {
                match = REFUND.matcher(text);
                kind = Kind.REFUND;
                if (!match.matches()) return parseIncome(text, zone);
            }
            String card = match.group(1);
            long occurred = nearestTime(receivedAt, zone, Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)),
                Integer.parseInt(match.group(4)), Integer.parseInt(match.group(5)));
            String description = match.group(6).trim();
            long amount = cents(match.group(7));
            long balance = cents(match.group(8));
            if (amount <= 0) return null;
            // Keep all original expense fingerprints stable across upgrades.
            String key = card + "|" + occurred + "|" + description + "|" + amount + "|" + balance;
            if (kind != Kind.EXPENSE) key = kind.name() + "|" + key;
            return create(key, card, occurred, description, amount, balance, text, kind);
        } catch (Exception ignored) { return null; }
    }

    private static ParsedTransaction parseIncome(String text, ZoneId zone) throws Exception {
        Matcher network = NETWORK_INCOME.matcher(text);
        if (network.matches()) {
            String card = network.group(1);
            long occurred = LocalDateTime.of(Integer.parseInt(network.group(2)), Integer.parseInt(network.group(3)), Integer.parseInt(network.group(4)),
                Integer.parseInt(network.group(5)), Integer.parseInt(network.group(6))).atZone(zone).toInstant().toEpochMilli();
            long amount = cents(network.group(7));
            long balance = cents(network.group(8));
            if (amount <= 0) return null;
            String description = "网络支付转入";
            String key = "INCOME|CARD|" + card + "|" + occurred + "|" + description + "|" + amount + "|" + balance;
            return create(key, card, occurred, description, amount, balance, text, Kind.INCOME);
        }
        Matcher match = INCOME.matcher(text);
        if (!match.matches()) return null;
        String account = match.group(1);
        String card = account.substring(Math.max(0, account.length() - 4));
        if (!card.matches("[0-9]{3,4}")) return null;
        long occurred = LocalDateTime.of(Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)),
            Integer.parseInt(match.group(5)), Integer.parseInt(match.group(6))).atZone(zone).toInstant().toEpochMilli();
        long amount = cents(match.group(8));
        long balance = cents(match.group(9));
        if (amount <= 0) return null;
        String description = match.group(7).trim() + " · " + match.group(10).trim() + " · " + match.group(11).replaceFirst("[。\\s]+$", "").trim();
        String key = "INCOME|" + account + "|" + occurred + "|" + description + "|" + amount + "|" + balance;
        return create(key, card, occurred, description, amount, balance, text, Kind.INCOME);
    }

    private static long nearestTime(long receivedAt, ZoneId zone, int month, int day, int hour, int minute) {
        int year = Instant.ofEpochMilli(receivedAt).atZone(zone).getYear();
        Instant nearest = null;
        long distance = Long.MAX_VALUE;
        for (int candidate = year - 1; candidate <= year + 1; candidate++) {
            try {
                Instant instant = LocalDateTime.of(candidate, month, day, hour, minute).atZone(zone).toInstant();
                long difference = Math.abs(instant.toEpochMilli() - receivedAt);
                if (difference < distance) { nearest = instant; distance = difference; }
            } catch (DateTimeException ignored) { }
        }
        if (nearest == null) throw new DateTimeException("Invalid payment time");
        return nearest.toEpochMilli();
    }

    private static ParsedTransaction create(String key, String card, long occurred, String description, long amount, long balance, String text, Kind kind) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return new ParsedTransaction(hash.toString(), card, occurred, description, amount, balance, text, kind);
    }
    private static long cents(String value) {
        if (!value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
        return new BigDecimal(value.replace(",", "")).movePointRight(2).longValueExact();
    }
    public static final class ParsedTransaction {
        private final String fingerprint, card, description, originalSms;
        private final long occurredAt, amountCents, balanceCents;
        private final Kind kind;
        ParsedTransaction(String fingerprint, String card, long occurredAt, String description, long amountCents, long balanceCents, String originalSms, Kind kind) {
            this.fingerprint = fingerprint; this.card = card; this.occurredAt = occurredAt;
            this.description = description; this.amountCents = amountCents; this.balanceCents = balanceCents; this.originalSms = originalSms; this.kind = kind;
        }
        public String getFingerprint() { return fingerprint; }
        public String getCard() { return card; }
        public long getOccurredAt() { return occurredAt; }
        public String getDescription() { return description; }
        public long getAmountCents() { return amountCents; }
        public long getBalanceCents() { return balanceCents; }
        public String getOriginalSms() { return originalSms; }
        public Kind getKind() { return kind; }
    }
}
