package com.utility.dlmsreader.dlms

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun `DAYS_36 windows 36 days back and floors start to midnight`() {
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_36, meterRtc(), isBlockLoad = true)

        assertEquals(36, window.daysBack)
        assertEquals(36 * 96, window.maxEntries)
        assertEquals(2026, window.start.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, window.start.get(Calendar.MONTH))
        assertEquals(22, window.start.get(Calendar.DAY_OF_MONTH))
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
            LoadSurveySelection.DAYS_36,
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
        val block = loadSurveyWindow(LoadSurveySelection.DAYS_36, meterRtc(), isBlockLoad = true)
        val daily = loadSurveyWindow(LoadSurveySelection.DAYS_36, meterRtc(), isBlockLoad = false)

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
    fun `RECENT keeps the verified block and daily defaults`() {
        val block = loadSurveyWindow(LoadSurveySelection.RECENT, meterRtc(), isBlockLoad = true)
        val daily = loadSurveyWindow(LoadSurveySelection.RECENT, meterRtc(), isBlockLoad = false)

        assertEquals(2, block.daysBack)
        assertEquals(BLOCK_LOAD_MAX_ENTRIES, block.maxEntries)
        assertEquals(15, daily.daysBack)
        assertEquals(DAILY_LOAD_MAX_ENTRIES, daily.maxEntries)
    }

    @Test
    fun `FULL_BUFFER has no day limit and is not entry capped`() {
        val window = loadSurveyWindow(LoadSurveySelection.FULL_BUFFER, meterRtc(), isBlockLoad = true)

        assertNull(window.daysBack)
        assertEquals(Int.MAX_VALUE, window.maxEntries)
    }

    @Test
    fun `enum exposes the labels the UI renders`() {
        assertEquals("Recent (2d)", LoadSurveySelection.RECENT.label)
        assertEquals("36 Days", LoadSurveySelection.DAYS_36.label)
        assertEquals("45 Days", LoadSurveySelection.DAYS_45.label)
        assertEquals("75 Days", LoadSurveySelection.DAYS_75.label)
        assertEquals("Full Buffer", LoadSurveySelection.FULL_BUFFER.label)
    }

    private fun assertMidnight(calendar: Calendar) {
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }
}
