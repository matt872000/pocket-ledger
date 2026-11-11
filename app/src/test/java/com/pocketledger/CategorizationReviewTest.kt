package com.pocketledger

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class CategorizationReviewTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val month = YearMonth.of(2026, 11)
    private fun time(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()

    @Test fun `review includes uncategorized records only in the current calendar month`() {
        val current = time("2026-11-11T12:00:00+08:00[Asia/Shanghai]")
        assertTrue(CategorizationReview.includes(UNCATEGORIZED, current, month, zone))
        assertFalse(CategorizationReview.includes("Groceries", current, month, zone))
        assertFalse(CategorizationReview.includes(UNCATEGORIZED, time("2026-10-31T23:59:00+08:00[Asia/Shanghai]"), month, zone))
        assertFalse(CategorizationReview.includes(UNCATEGORIZED, time("2026-12-01T00:00:00+08:00[Asia/Shanghai]"), month, zone))
    }

    @Test fun `month boundaries follow the phone timezone instead of UTC`() {
        val boundary = time("2026-12-01T00:30:00+08:00[Asia/Shanghai]")
        assertFalse(CategorizationReview.includes(UNCATEGORIZED, boundary, month, zone))
        assertTrue(CategorizationReview.includes(UNCATEGORIZED, boundary, month, ZoneId.of("UTC")))
        assertTrue(CategorizationReview.includes(UNCATEGORIZED, boundary, month.plusMonths(1), zone))
    }

    @Test fun `next refresh handles calendar year and short months at local midnight`() {
        assertEquals(time("2027-01-01T00:00:00+08:00[Asia/Shanghai]"),
            CategorizationReview.nextMonthStart(time("2026-12-31T23:59:00+08:00[Asia/Shanghai]"), zone))
        assertEquals(time("2028-03-01T00:00:00+08:00[Asia/Shanghai]"),
            CategorizationReview.nextMonthStart(time("2028-02-29T12:00:00+08:00[Asia/Shanghai]"), zone))
    }
}
