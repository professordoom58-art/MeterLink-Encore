package com.utility.dlmsreader.dlms

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
        assertEquals(29, window.start.get(Calendar.DAY_OF_MONTH))
        assertMidnight(window.start)
    }

    @Test
    fun `DAYS_45 windows 45 days back and floors start to midnight`() {
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_45, meterRtc(), isBlockLoad = false)

        assertEquals(45, window.daysBack)
        assertEquals(45 * 96, window.maxEntries)
        assertEquals(Calendar.SEPTEMBER, window.start.get(Calendar.MONTH))
        assertEquals(14, window.start.get(Calendar.DAY_OF_MONTH))
        assertMidnight(window.start)
    }

    @Test
    fun `DAYS_75 windows 75 days back and floors start to midnight`() {
        val window = loadSurveyWindow(LoadSurveySelection.DAYS_75, meterRtc(), isBlockLoad = true)

        assertEquals(75, window.daysBack)
        assertEquals(75 * 96, window.maxEntries)
        assertEquals(Calendar.AUGUST, window.start.get(Calendar.MONTH))
        assertEquals(15, window.start.get(Calendar.DAY_OF_MONTH))
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
        // 45 calendar days inclusive, minus the final second of the last day.
        assertTrue("span was $spanDays days", spanDays in 44..45)
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
    fun `FULL_BUFFER bypasses windowing and post-read date slicing`() {
        // No window means the reader skips the RTC/Selector-1 range path and
        // never calls filterLoadSurveyRows() on the complete buffer.
        assertFalse(loadSurveyNeedsWindow(LoadSurveySelection.FULL_BUFFER))
        assertNull(LoadSurveySelection.FULL_BUFFER.days)
        assertEquals(Int.MAX_VALUE, LoadSurveySelection.FULL_BUFFER.maxEntries)

        listOf(
            LoadSurveySelection.RECENT,
            LoadSurveySelection.DAYS_30,
            LoadSurveySelection.DAYS_45,
            LoadSurveySelection.DAYS_75
        ).forEach { selection ->
            assertTrue("${selection.label} must use a day window", loadSurveyNeedsWindow(selection))
            val window = loadSurveyWindow(selection, meterRtc(), isBlockLoad = true)
            assertTrue("${selection.label} must have daysBack", window.daysBack != null)
        }
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

    @Test
    fun `meter 92717295 power-off gaps return the requested number of distinct days`() {
        val rows = meter92717295Rows()
        val rtc = Calendar.getInstance().apply {
            clear()
            set(2023, Calendar.DECEMBER, 28, 14, 0, 0)
        }

        assertEquals(3_646, rows.size)
        assertEquals(76, countDistinctDays(rows))

        fun filtered(selection: LoadSurveySelection): LoadSurveyFilterResult {
            val window = loadSurveyWindow(selection, rtc, isBlockLoad = true)
            return filterLoadSurveyRows(
                rows = rows,
                timeIndex = 0,
                windowStartMs = window.start.timeInMillis,
                windowEndMs = window.end.timeInMillis,
                requiredDays = window.daysBack,
                maxEntries = window.maxEntries
            )
        }

        val two = filtered(LoadSurveySelection.RECENT)
        assertEquals(95, two.keptRowCount)
        assertEquals(2, two.distinctDays)
        assertEquals(1, two.calendarWindowDays)
        assertTrue(two.usedGapFallback)
        assertEquals(
            listOf("2023-12-12", "2023-12-28"),
            two.rows.mapNotNull { profileRowDay(it.firstOrNull()) }.distinct().sorted()
        )

        val thirty = filtered(LoadSurveySelection.DAYS_30)
        assertEquals(1_439, thirty.keptRowCount)
        assertEquals(30, thirty.distinctDays)
        assertTrue(thirty.usedGapFallback)

        val fortyFive = filtered(LoadSurveySelection.DAYS_45)
        assertEquals(2_159, fortyFive.keptRowCount)
        assertEquals(45, fortyFive.distinctDays)
        assertTrue(fortyFive.usedGapFallback)

        val seventyFive = filtered(LoadSurveySelection.DAYS_75)
        assertEquals(3_599, seventyFive.keptRowCount)
        assertEquals(75, seventyFive.distinctDays)
        assertTrue(seventyFive.usedGapFallback)

        // The last 75 calendar days only contain 3 powered-on days.
        assertEquals(3, seventyFive.calendarWindowDays)

        val full = filterLoadSurveyRows(
            rows = rows,
            timeIndex = 0,
            windowStartMs = Long.MIN_VALUE,
            windowEndMs = Long.MAX_VALUE,
            requiredDays = null,
            maxEntries = Int.MAX_VALUE
        )
        assertEquals(3_646, full.keptRowCount)
        assertEquals(76, full.distinctDays)
        assertFalse(full.usedGapFallback)
    }

    @Test
    fun `cached full buffer re-filters instantly for every range chip`() {
        val rows = meter92717295Rows()
        val formatter = SimpleDateFormat("dd-MM-yy HH:mm", Locale.ENGLISH)
        val formattedRows = rows.map { row ->
            listOf(formatter.format(row.first() as Date))
        }
        val cached = ProfileSection(
            title = "Block Load Survey",
            profileObis = "1.0.99.1.0.255",
            columns = listOf(ProfileColumn("Timestamp", "0.0.1.0.0.255")),
            rows = formattedRows,
            totalRows = formattedRows.size,
            fullRows = formattedRows,
            fullTotalRows = formattedRows.size,
            fullBufferCached = true
        )

        assertEquals(95, applyLoadSurveySelection(cached, LoadSurveySelection.RECENT).rows.size)
        assertEquals(2, applyLoadSurveySelection(cached, LoadSurveySelection.RECENT).rows
            .mapNotNull { formattedProfileDay(it.firstOrNull()) }.distinct().size)

        val thirty = applyLoadSurveySelection(cached, LoadSurveySelection.DAYS_30)
        assertEquals(1_439, thirty.rows.size)
        assertEquals(30, thirty.rows.mapNotNull { formattedProfileDay(it.firstOrNull()) }.distinct().size)
        assertTrue(thirty.windowNote.contains("power-off gap fallback"))

        val fortyFive = applyLoadSurveySelection(cached, LoadSurveySelection.DAYS_45)
        assertEquals(2_159, fortyFive.rows.size)
        assertEquals(45, fortyFive.rows.mapNotNull { formattedProfileDay(it.firstOrNull()) }.distinct().size)

        val seventyFive = applyLoadSurveySelection(cached, LoadSurveySelection.DAYS_75)
        assertEquals(3_599, seventyFive.rows.size)
        assertEquals(75, seventyFive.rows.mapNotNull { formattedProfileDay(it.firstOrNull()) }.distinct().size)

        val full = applyLoadSurveySelection(cached, LoadSurveySelection.FULL_BUFFER)
        assertEquals(3_646, full.rows.size)
        assertEquals(76, full.rows.mapNotNull { formattedProfileDay(it.firstOrNull()) }.distinct().size)
        assertFalse(full.windowNote.contains("cached rows only"))
    }

    @Test
    fun `partial cache is labelled instead of pretending to be the full buffer`() {
        val rows = meter92717295Rows()
        val formatter = SimpleDateFormat("dd-MM-yy HH:mm", Locale.ENGLISH)
        val allFormatted = rows.map { row -> listOf(formatter.format(row.first() as Date)) }
        val recentKeys = allFormatted
            .mapNotNull { formattedProfileDay(it.firstOrNull()) }
            .distinct()
            .sortedDescending()
            .take(2)
            .toSet()
        val partialRows = allFormatted.filter {
            formattedProfileDay(it.firstOrNull()) in recentKeys
        }
        val partial = ProfileSection(
            title = "Block Load Survey",
            profileObis = "1.0.99.1.0.255",
            columns = listOf(ProfileColumn("Timestamp", "0.0.1.0.0.255")),
            rows = partialRows,
            totalRows = 3_646,
            fullRows = partialRows,
            fullTotalRows = 3_646
        )

        val thirty = applyLoadSurveySelection(partial, LoadSurveySelection.DAYS_30)
        assertEquals(
            2,
            thirty.rows.mapNotNull { formattedProfileDay(it.firstOrNull()) }.distinct().size
        )
        assertTrue(thirty.windowNote.contains("cached rows only"))

        val full = applyLoadSurveySelection(partial, LoadSurveySelection.FULL_BUFFER)
        assertEquals(partialRows.size, full.rows.size)
        assertTrue(full.windowNote.contains("cached rows only"))
    }

    /**
     * 76 distinct days: 63 continuous days (21-May-2022 .. 22-Jul-2022) and
     * 13 sparse test days through 28-Dec-2023. The newest and oldest days have
     * 47 rows; every other day has 48, yielding 3,646 rows total.
     */
    private fun meter92717295Rows(): List<List<Any?>> {
        val days = mutableListOf<Calendar>()
        val continuous = Calendar.getInstance().apply {
            clear()
            set(2022, Calendar.MAY, 21, 0, 0, 0)
        }
        repeat(63) {
            days += (continuous.clone() as Calendar)
            continuous.add(Calendar.DAY_OF_YEAR, 1)
        }
        val sparse = listOf(
            intArrayOf(2022, Calendar.SEPTEMBER, 7),
            intArrayOf(2022, Calendar.SEPTEMBER, 14),
            intArrayOf(2022, Calendar.SEPTEMBER, 21),
            intArrayOf(2022, Calendar.OCTOBER, 5),
            intArrayOf(2022, Calendar.OCTOBER, 19),
            intArrayOf(2022, Calendar.NOVEMBER, 2),
            intArrayOf(2022, Calendar.NOVEMBER, 16),
            intArrayOf(2022, Calendar.DECEMBER, 7),
            intArrayOf(2022, Calendar.DECEMBER, 20),
            intArrayOf(2023, Calendar.JULY, 22),
            intArrayOf(2023, Calendar.NOVEMBER, 9),
            intArrayOf(2023, Calendar.DECEMBER, 12),
            intArrayOf(2023, Calendar.DECEMBER, 28)
        )
        sparse.forEach { parts ->
            days += Calendar.getInstance().apply {
                clear()
                set(parts[0], parts[1], parts[2], 0, 0, 0)
            }
        }
        assertEquals(76, days.size)
        return days.flatMapIndexed { index, day ->
            val rowCount = if (index == 0 || index == days.lastIndex) 47 else 48
            (0 until rowCount).map { slot ->
                val timestamp = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, slot / 4)
                    set(Calendar.MINUTE, (slot % 4) * 15)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                listOf<Any?>(timestamp.time)
            }
        }
    }

    private fun assertMidnight(calendar: Calendar) {
        assertEquals(0, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }
}
