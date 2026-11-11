package com.pocketledger

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object CategorizationReview {
    fun includes(category: String, occurredAt: Long, month: YearMonth, zone: ZoneId, startDay: Int = 1): Boolean =
        category == UNCATEGORIZED && BudgetCycle.includes(occurredAt, month, zone, startDay)

    fun nextMonthStart(now: Long, zone: ZoneId, startDay: Int = 1): Long = BudgetCycle.nextStart(now, zone, startDay)
}
