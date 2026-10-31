package com.utility.dlmsreader.dlms

import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LoadSurveyWindowTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    /** 28-Oct-2026 14:37:22.481 IST */
    private fun meterRtc(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, 28, 14, 37, 22)
        set(Calendar.MILLISECOND, 481)
    }

    /** 29-Oct-2026 14:00:00 IST */
    private fun outageMeterRtc(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, 29, 14, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int = 12): Date =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.time

    @Test
    fun `DAYS_30 windows 30 days back and floors start to midnight`() {
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_30, meterRtc(), isBlockLoad = true)

        assertEquals(30, window.daysBack)
        assertEquals(30 * 96, window.maxEntries)
        assertEquals(2026, window.start.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, window.start.get(Calendar.MONTH))
        assertEquals(28, window.start.get(Calendar.DAY_OF_MONTH))
        assertMidnight(window.start)
    }

    @Test
    fun `DAYS_45 windows 45 days back and floors start to midnight`() {
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_45, meterRtc(), isBlockLoad = false)

        assertEquals(45, window.daysBack)
        assertEquals(45 * 96, window.maxEntries)
        assertEquals(Calendar.SEPTEMBER, window.start.get(Calendar.MONTH))
        assertEquals(13, window.start.get(Calendar.DAY_OF_MONTH))
        assertMidnight(window.start)
    }

    @Test
    fun `DAYS_75 windows 75 days back and floors start to midnight`() {
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_75, meterRtc(), isBlockLoad = true)

        assertEquals(75, window.daysBack)
        assertEquals(75 * 96, window.maxEntries)
        assertEquals(Calendar.AUGUST, window.start.get(Calendar.MONTH))
        assertEquals(14, window.start.get(Calendar.DAY_OF_MONTH))
        assertMidnight(window.start)
    }

    @Test
    fun `end of window is ceiled to the last second of the meter day`() {
        listOf(
            LoadSurveySelection.RECENT,
            LoadSurveySelection.DAYS_30,
            LoadSurveySelection.DAYS_45,
            LoadSurveySelection.DAYS_75
        ).forEach { selection ->
            val window = loadSurveyWindow(selection, meterRtc(), isBlockLoad = true)
            assertEquals(28, window.end.get(Calendar.DAY_OF_MONTH))
            assertEquals(23, window.end.get(Calendar.HOUR_OF_DAY))
            assertEquals(59, window.end.get(Calendar.MINUTE))
            assertEquals(59, window.end.get(Calendar.SECOND))
            assertEquals(0, window.end.get(Calendar.MILLISECOND))
        }
    }

    @Test
    fun `day window selections ignore the profile-specific recent defaults`() {
        val block = loadSurveyWindow(LoadSurveySelection.DAYS_30, meterRtc(), isBlockLoad = true)
        val daily = loadSurveyWindow(LoadSurveySelection.DAYS_30, meterRtc(), isBlockLoad = false)

        assertEquals(block.start.timeInMillis, daily.start.timeInMillis)
        assertEquals(block.end.timeInMillis, daily.end.timeInMillis)
        assertEquals(block.maxEntries, daily.maxEntries)
    }

    @Test
    fun `window spans the requested number of days inclusive`() {
        val rtc = meterRtc()
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_45, rtc, isBlockLoad = true)

        val spanDays = ((window.end.timeInMillis - window.start.timeInMillis) / 86_400_000L).toInt()
        // 45 full days back plus the remainder of the current day.
        assertTrue("span was $spanDays days", spanDays in 45..46)
    }

    @Test
    fun `RECENT is the 2 Days chip with 192 entries`() {
        val block = loadSurveyWindow(LoadSurveySelection.RECENT, meterRtc(), isBlockLoad = true)
        val daily = loadSurveyWindow(LoadSurveySelection.RECENT, meterRtc(), isBlockLoad = false)

        assertEquals(2, block.daysBack)
        assertEquals(2 * 96, block.maxEntries)
        assertEquals(2, daily.daysBack)
        assertEquals(2 * 96, daily.maxEntries)
        assertEquals("2 Days", LoadSurveySelection.RECENT.label)
    }

    @Test
    fun `FULL_BUFFER has no day limit and is not entry capped`() {
        val window = loadSurveyWindow(LoadSurveySelection.FULL_BUFFER, meterRtc(), isBlockLoad = true)

        assertNull(window.daysBack)
        assertEquals(Int.MAX_VALUE, window.maxEntries)
    }

    @Test
    fun `enum exposes the exact labels the UI renders`() {
        assertEquals("2 Days", LoadSurveySelection.RECENT.label)
        assertEquals("30 Days", LoadSurveySelection.DAYS_30.label)
        assertEquals("45 Days", LoadSurveySelection.DAYS_45.label)
        assertEquals("75 Days", LoadSurveySelection.DAYS_75.label)
        assertEquals("Full Buffer", LoadSurveySelection.FULL_BUFFER.label)
        assertEquals(
            listOf("2 Days", "30 Days", "45 Days", "75 Days", "Full Buffer"),
            LoadSurveySelection.entries.map { it.label }
        )
    }

    @Test
    fun `DAYS_30 power-off gap retains pre-outage days instead of dropping them`() {
        val window = loadSurveyWindow(
            LoadSurveySelection.DAYS_30,
            outageMeterRtc(),
            isBlockLoad = false
        )
        val rows = mutableListOf<List<Any?>>()
        // Ten recorded days before the long power-off in Nov 2025.
        for (day in 1..10) {
            rows += listOf(timestamp(2025, Calendar.NOVEMBER, day))
        }
        // Two recorded days after restoration in Oct 2026.
        rows += listOf(timestamp(2026, Calendar.OCTOBER, 28))
        rows += listOf(timestamp(2026, Calendar.OCTOBER, 29))
        // An unparseable row must never be silently discarded.
        rows += listOf("not-a-timestamp")

        val result = filterLoadSurveyRows(
            rows = rows,
            timeIndex = 0,
            windowStartMs = window.start.timeInMillis,
            windowEndMs = window.end.timeInMillis,
            requiredDays = window.daysBack,
            maxEntries = window.maxEntries
        )

        assertTrue("expected gap fallback", result.usedGapFallback)
        assertEquals(13, result.keptRowCount)
        assertEquals(12, result.distinctDays)
        val retainedDays = result.rows.mapNotNull { profileRowDay(it.getOrNull(0)) }
        assertTrue("Nov 2025 rows were dropped: $retainedDays", "2025-11-01" in retainedDays)
        assertTrue("Nov 2025 rows were dropped: $retainedDays", "2025-11-10" in retainedDays)
        assertTrue("Oct 2026 rows were dropped: $retainedDays", "2026-10-29" in retainedDays)
    }

    @Test
    fun `countDistinctDays parses ISO and dd-MM-yy timestamp prefixes`() {
        val rows = listOf(
            listOf<Any?>("2025-11-01 00:15"),
            listOf<Any?>("01-11-25 00:30"),
            listOf<Any?>("2025-11-02 00:00"),
            listOf<Any?>("not-a-timestamp")
        )

        assertEquals(2, countDistinctDays(rows))
    }

    @Test
    fun `DAYS_30 keeps only the window when enough distinct days are present`() {
        val rtc = outageMeterRtc()
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_30, rtc, isBlockLoad = false)
        val rows = (0 until 31).map { offset ->
            val day = (rtc.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -offset)
                set(Calendar.HOUR_OF_DAY, 12)
            }
            listOf<Any?>(day.time)
        }

        val result = filterLoadSurveyRows(
            rows = rows,
            timeIndex = 0,
            windowStartMs = window.start.timeInMillis,
            windowEndMs = window.end.timeInMillis,
            requiredDays = window.daysBack,
            maxEntries = window.maxEntries
        )

        assertFalse(result.usedGapFallback)
        assertTrue("kept ${result.keptRowCount} rows", result.keptRowCount in 30..31)
    }

    private fun assertMidnight(calendar: Calendar) {
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }
}
