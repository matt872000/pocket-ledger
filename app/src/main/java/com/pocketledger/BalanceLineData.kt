package com.pocketledger

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class BalanceLinePoint(val date: LocalDate, val dayOffset: Float, val snapshot: RecordedBalancePoint)

/** Observations only: missing days and ambiguous payment minutes never get invented balances. */
object BalanceLineData {
    fun build(history: CardBalanceHistory?, days: List<ChartDay>, zone: ZoneId): List<BalanceLinePoint> {
        if (history == null || days.isEmpty()) return emptyList()
        val first = days.first().date
        val last = days.last().date
        return history.points.mapNotNull { snapshot ->
            val time = Instant.ofEpochMilli(snapshot.occurredAt).atZone(zone)
            val date = time.toLocalDate()
            if (date < first || date > last) null else BalanceLinePoint(
                date, ChronoUnit.DAYS.between(first, date).toFloat() + time.toLocalTime().toSecondOfDay() / 86400f, snapshot)
        }
    }

    fun latestOnDay(points: List<BalanceLinePoint>, date: LocalDate): RecordedBalancePoint? =
        points.lastOrNull { it.date == date }?.snapshot
}
