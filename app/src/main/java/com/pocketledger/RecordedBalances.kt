package com.pocketledger

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class RecordedBalancePoint(val occurredAt: Long, val balancesCents: List<Long>, val messageCount: Int) {
    val balanceCents: Long? get() = balancesCents.singleOrNull()
}

data class CardBalanceHistory(val card: String, val points: List<RecordedBalancePoint>) {
    val latest: RecordedBalancePoint get() = points.last()
}

object RecordedBalances {
    fun histories(transactions: List<Transaction>): List<CardBalanceHistory> = transactions.filter { it.hasReportedBalance }.groupBy { it.card }
        .toSortedMap().map { (card, purchases) ->
            CardBalanceHistory(card, purchases.groupBy { it.occurredAt }.toSortedMap().map { (time, messages) ->
                RecordedBalancePoint(time, messages.map { it.balanceCents }.distinct().sorted(), messages.size)
            })
        }

    fun inMonth(history: CardBalanceHistory, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): List<RecordedBalancePoint> =
        history.points.filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zone)) == month }
}
