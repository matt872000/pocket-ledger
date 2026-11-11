package com.pocketledger

import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A period is identified by the calendar month in which it starts. */
object BudgetCycle {
    fun start(month: YearMonth, startDay: Int): LocalDate {
        require(startDay in 1..31)
        return month.atDay(minOf(startDay, month.lengthOfMonth()))
    }

    fun periodFor(date: LocalDate, startDay: Int): YearMonth {
        val calendar = YearMonth.from(date)
        return if (date >= start(calendar, startDay)) calendar else calendar.minusMonths(1)
    }

    fun current(now: Long, zone: ZoneId, startDay: Int): YearMonth =
        periodFor(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), startDay)

    fun includes(occurredAt: Long, period: YearMonth, zone: ZoneId, startDay: Int): Boolean =
        current(occurredAt, zone, startDay) == period

    fun nextStart(now: Long, zone: ZoneId, startDay: Int): Long =
        start(current(now, zone, startDay).plusMonths(1), startDay).atStartOfDay(zone).toInstant().toEpochMilli()

    fun label(period: YearMonth, startDay: Int): String {
        val format = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        return "${start(period, startDay).format(format)} – ${start(period.plusMonths(1), startDay).minusDays(1).format(format)}"
    }
}

object BudgetCyclePreferences {
    const val KEY = "budget_start_day"
    const val DEFAULT_DAY = 21
    fun read(preferences: SharedPreferences): Int = preferences.getInt(KEY, DEFAULT_DAY).takeIf { it in 1..31 } ?: DEFAULT_DAY
}
