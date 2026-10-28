package com.utility.dlmsreader.dlms

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One spreadsheet cell. [numeric] cells are written as OpenXML numbers so the
 * workbook can be summed and charted; everything else is an inline string.
 */
internal data class XlsxCell(val text: String, val numeric: Boolean = false)

internal class XlsxSheet(
    val name: String,
    val rows: MutableList<List<XlsxCell>> = mutableListOf()
) {
    fun row(vararg cells: XlsxCell) {
        rows += cells.toList()
    }

    fun row(cells: List<XlsxCell>) {
        rows.add(cells)
    }

    fun text(vararg values: String) {
        rows.add(values.map { XlsxCell(it) })
    }

    fun blank() {
        rows.add(emptyList())
    }

    fun header(vararg values: String) {
        rows.add(values.map { XlsxCell(it) })
    }
}

/**
 * Zero-dependency OpenXML `.xlsx` writer.
 *
 * Deliberately avoids poi-ooxml: it drags in a large DEX footprint and the
 * `javax.xml` namespace conflicts that come with it. A workbook is just a ZIP
 * of small XML parts, so we write those directly with [ZipOutputStream].
 *
 * This object is pure JVM (no Android types) so it can be unit tested.
 */
object MeterLinkXlsxWriter {

    const val SHEET_IDENTITY = "Identity_Nameplate"
    const val SHEET_ENERGY = "Energy_MD"
    const val SHEET_INSTANTANEOUS = "Instantaneous"
    const val SHEET_BILLING = "Billing_History"
    const val SHEET_DAILY = "Daily_Load"
    const val SHEET_BLOCK = "Block_Load"
    const val SHEET_TAMPER = "Tamper_Events"

    val SHEET_NAMES = listOf(
        SHEET_IDENTITY,
        SHEET_ENERGY,
        SHEET_INSTANTANEOUS,
        SHEET_BILLING,
        SHEET_DAILY,
        SHEET_BLOCK,
        SHEET_TAMPER
    )

    // Cell style indexes, matching the cellXfs order in [stylesXml].
    private const val STYLE_DEFAULT = 0
    private const val STYLE_HEADER = 1
    private const val STYLE_TEXT = 2
    private const val STYLE_NUMBER = 3
    private const val STYLE_TITLE = 4

    private val NUMERIC = Regex("^-?\\d{1,15}(\\.\\d{1,10})?$")

    private const val MARK_HEADER = "\u0001H\u0001"
    private const val MARK_TITLE = "\u0001T\u0001"

    fun write(report: MeterDataReport, out: OutputStream) {
        val sheets = buildSheets(report)
        ZipOutputStream(out).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes())
            put(zip, "_rels/.rels", rootRels())
            put(zip, "xl/workbook.xml", workbookXml())
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            put(zip, "xl/styles.xml", stylesXml())
            sheets.forEachIndexed { index, sheet ->
                put(zip, "xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet))
            }
        }
    }

    private fun put(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // ---------------------------------------------------------------- package

    private fun contentTypes(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        SHEET_NAMES.indices.forEach { index ->
            append("""<Override PartName="/xl/worksheets/sheet${index + 1}.xml" """)
            append("""ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        append("""<Relationship Id="rId1" """)
        append("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" """)
        append("""Target="xl/workbook.xml"/>""")
        append("</Relationships>")
    }

    private fun workbookXml(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<sheets>""")
        SHEET_NAMES.forEachIndexed { index, name ->
            append("""<sheet name="${escape(name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        SHEET_NAMES.indices.forEach { index ->
            append("""<Relationship Id="rId${index + 1}" """)
            append("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """)
            append("""Target="worksheets/sheet${index + 1}.xml"/>""")
        }
        append("""<Relationship Id="rId${SHEET_NAMES.size + 1}" """)
        append("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" """)
        append("""Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun stylesXml(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        // fonts: 0 body, 1 bold, 2 bold white (header)
        append("""<fonts count="3">""")
        append("""<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>""")
        append("""<font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/></font>""")
        append("""<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""")
        append("""</fonts>""")
        // fills: 0 none and 1 gray125 are required placeholders
        append("""<fills count="3">""")
        append("""<fill><patternFill patternType="none"/></fill>""")
        append("""<fill><patternFill patternType="gray125"/></fill>""")
        append("""<fill><patternFill patternType="solid">""")
        append("""<fgColor rgb="FF1E3A8A"/><bgColor indexed="64"/></patternFill></fill>""")
        append("""</fills>""")
        append("""<borders count="2">""")
        append("""<border><left/><right/><top/><bottom/><diagonal/></border>""")
        append("""<border><left style="thin"><color rgb="FFCBD5E1"/></left>""")
        append("""<right style="thin"><color rgb="FFCBD5E1"/></right>""")
        append("""<top style="thin"><color rgb="FFCBD5E1"/></top>""")
        append("""<bottom style="thin"><color rgb="FFCBD5E1"/></bottom><diagonal/></border>""")
        append("""</borders>""")
        append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""")
        append("""<cellXfs count="5">""")
        append("""<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""")
        append("""<xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1">""")
        append("""<alignment vertical="center" wrapText="1"/></xf>""")
        append("""<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1">""")
        append("""<alignment vertical="top" wrapText="1"/></xf>""")
        append("""<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyNumberFormat="1">""")
        append("""<alignment vertical="top" horizontal="right"/></xf>""")
        append("""<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""")
        append("""</cellXfs>""")
        append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
        append("""</styleSheet>""")
    }

    // ---------------------------------------------------------------- sheets

    private fun sheetXml(sheet: XlsxSheet): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append(columnWidths(sheet))
        append("""<sheetData>""")
        sheet.rows.forEachIndexed { rowIndex, cells ->
            val rowNumber = rowIndex + 1
            if (cells.isEmpty()) {
                append("""<row r="$rowNumber"/>""")
                return@forEachIndexed
            }
            append("""<row r="$rowNumber">""")
            cells.forEachIndexed { colIndex, cell ->
                append(cellXml(cell, colLetter(colIndex), rowNumber))
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun cellXml(cell: XlsxCell, column: String, row: Int): String {
        val ref = "$column$row"
        if (cell.text.isEmpty()) return """<c r="$ref" s="$STYLE_TEXT"/>"""
        if (cell.numeric) {
            return """<c r="$ref" s="$STYLE_NUMBER"><v>${cell.text}</v></c>"""
        }
        val style = when {
            cell.text.startsWith(MARK_TITLE) -> STYLE_TITLE
            cell.text.startsWith(MARK_HEADER) -> STYLE_HEADER
            else -> STYLE_TEXT
        }
        val value = displayText(cell.text)
        return """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${escape(value)}</t></is></c>"""
    }

    /** Strips the internal style markers before text reaches the workbook. */
    internal fun displayText(value: String): String =
        value.removePrefix(MARK_TITLE).removePrefix(MARK_HEADER)

    private fun columnWidths(sheet: XlsxSheet): String {
        val widest = mutableMapOf<Int, Int>()
        sheet.rows.forEach { cells ->
            cells.forEachIndexed { index, cell ->
                val length = displayText(cell.text).length
                if (length > (widest[index] ?: 0)) widest[index] = length
            }
        }
        if (widest.isEmpty()) return ""
        return buildString {
            append("<cols>")
            widest.toSortedMap().forEach { (index, length) ->
                val width = (length + 2).coerceIn(10, 60)
                append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
            }
            append("</cols>")
        }
    }

    // --------------------------------------------------------------- helpers

    /** Converts a zero-based column index to its spreadsheet letter (0 -> A, 26 -> AA). */
    internal fun colLetter(index: Int): String {
        var value = index
        val letters = StringBuilder()
        while (true) {
            letters.insert(0, ('A' + value % 26))
            value = value / 26 - 1
            if (value < 0) break
        }
        return letters.toString()
    }

    /** Escapes the five XML metacharacters. */
    internal fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    /**
     * True when the text is a plain number that should be stored as a number.
     * Identifiers such as "007" or meter serials keep their leading zeros and
     * stay text, as do OBIS codes and values with units appended.
     */
    internal fun isNumeric(value: String): Boolean {
        val trimmed = value.trim()
        if (!NUMERIC.matches(trimmed)) return false
        val unsigned = trimmed.removePrefix("-")
        if (unsigned.length > 1 && unsigned[0] == '0' && unsigned[1].isDigit()) return false
        return true
    }

    internal fun cell(value: String?): XlsxCell {
        val text = value?.trim().orEmpty()
        return XlsxCell(text, numeric = isNumeric(text))
    }

    private fun headerCell(value: String): XlsxCell = XlsxCell("$MARK_HEADER$value")

    private fun titleRow(sheet: XlsxSheet, title: String) {
        sheet.rows += listOf(XlsxCell("$MARK_TITLE$title"))
    }

    // ------------------------------------------------------------- builders

    private fun buildSheets(report: MeterDataReport): List<XlsxSheet> {
        val generated = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(Date())
        return listOf(
            identitySheet(report, generated),
            energySheet(report),
            instantaneousSheet(report),
            billingSheet(report),
            dailyLoadSheet(report),
            blockLoadSheet(report),
            tamperSheet(report)
        )
    }

    private fun identitySheet(report: MeterDataReport, generated: String): XlsxSheet {
        val sheet = XlsxSheet(SHEET_IDENTITY)
        titleRow(sheet, "MeterLink — IS 15959 DLMS Meter Reading Report")
        sheet.rows += listOf(headerCell("Generated (phone time)"), cell(generated))
        sheet.rows += listOf(headerCell("Serial Number"), cell(identityValue(report, "0.0.96.1.0.255")))
        sheet.rows += listOf(headerCell("Logical Device Name"), cell(identityValue(report, "0.0.42.0.0.255")))
        sheet.rows += listOf(headerCell("Manufacturer"), cell(identityValue(report, "0.0.96.1.1.255")))
        sheet.blank()

        val identity = report.identity
        if (identity == null || identity.rows.isEmpty()) {
            sheet.text("No identity rows were read.")
        } else {
            sheet.rows += listOf(
                headerCell("Parameter"),
                headerCell("OBIS Code"),
                headerCell("Value"),
                headerCell("Unit")
            )
            identity.rows.forEach { row ->
                sheet.rows += listOf(
                    cell(row.parameterName),
                    cell(row.obisCode),
                    cell(row.formattedValue),
                    cell(row.unit)
                )
            }
        }

        identity?.nameplate?.let { nameplate ->
            sheet.blank()
            titleRow(sheet, "Nameplate Profile")
            appendProfileRows(sheet, nameplate)
        }

        if (report.errors.isNotEmpty()) {
            sheet.blank()
            titleRow(sheet, "Read Notes")
            sheet.rows += listOf(headerCell("Status"))
            report.errors.forEach { sheet.rows += listOf(cell(it)) }
        }
        return sheet
    }

    private fun energySheet(report: MeterDataReport): XlsxSheet {
        val sheet = XlsxSheet(SHEET_ENERGY)
        titleRow(sheet, "Cumulative Energy & Maximum Demand Registers")
        if (report.cumulativeEnergies.isEmpty()) {
            sheet.text("No energy registers were read.")
            return sheet
        }
        sheet.rows += listOf(
            headerCell("Parameter"),
            headerCell("OBIS Code"),
            headerCell("Reading"),
            headerCell("Unit"),
            headerCell("Capture Timestamp")
        )
        report.cumulativeEnergies.forEach { reading ->
            sheet.rows += listOf(
                cell(reading.parameterName),
                cell(reading.obisCode),
                cell(reading.reading),
                cell(reading.unit),
                cell(reading.captureTimestamp)
            )
        }
        return sheet
    }

    private fun instantaneousSheet(report: MeterDataReport): XlsxSheet {
        val sheet = XlsxSheet(SHEET_INSTANTANEOUS)
        titleRow(sheet, "Instantaneous Parameters Snapshot")
        val section = report.instantaneous
        if (section == null) {
            sheet.text("No instantaneous profile was read.")
            return sheet
        }
        appendProfileRows(sheet, section)
        return sheet
    }

    private fun billingSheet(report: MeterDataReport): XlsxSheet {
        val sheet = XlsxSheet(SHEET_BILLING)
        titleRow(sheet, "Billing History Profile")
        val section = report.billing
        if (section == null) {
            sheet.text("No billing profile was read.")
            return sheet
        }
        appendProfileRows(sheet, section)
        appendTruncationNote(sheet, section)
        return sheet
    }

    private fun dailyLoadSheet(report: MeterDataReport): XlsxSheet {
        val sheet = XlsxSheet(SHEET_DAILY)
        titleRow(sheet, "Daily Load Survey Profile")
        val section = report.dailyLoad
        if (section == null) {
            sheet.text("No daily load survey was read.")
            return sheet
        }
        appendProfileRows(sheet, section)
        appendTruncationNote(sheet, section)
        return sheet
    }

    private fun blockLoadSheet(report: MeterDataReport): XlsxSheet {
        val sheet = XlsxSheet(SHEET_BLOCK)
        titleRow(sheet, "Block Load Survey Profile")
        val section = report.blockLoad
        if (section == null) {
            sheet.text("No block load survey was read.")
            return sheet
        }
        appendProfileRows(sheet, section)
        appendTruncationNote(sheet, section)
        sheet.blank()
        sheet.text("Timestamps are interval-end. Zero rows before a power restoration are meter zero-fill.")
        return sheet
    }

    private fun tamperSheet(report: MeterDataReport): XlsxSheet {
        val sheet = XlsxSheet(SHEET_TAMPER)
        titleRow(sheet, "Tamper & Event Logs")

        if (report.eventLogStatus.isNotEmpty()) {
            sheet.rows += listOf(headerCell("Compartment"), headerCell("Status"))
            report.eventLogStatus.forEach { (compartment, status) ->
                sheet.rows += listOf(cell(compartment), cell(status))
            }
            sheet.blank()
        }

        if (report.tamperEvents.isEmpty()) {
            sheet.text("No tamper events were read.")
            return sheet
        }

        report.tamperEvents.groupBy { it.compartment }.forEach { (compartment, events) ->
            titleRow(sheet, compartment)
            val compact = events.all { event ->
                listOf(
                    event.voltage,
                    event.current,
                    event.powerFactor,
                    event.energy,
                    event.kvah,
                    event.neutralCurrent
                ).all { it == "N/A" }
            }
            if (compact) {
                sheet.rows += listOf(
                    headerCell("Compartment"),
                    headerCell("Date/Time"),
                    headerCell("Event Code"),
                    headerCell("Event Description")
                )
                events.forEach { event ->
                    sheet.rows += listOf(
                        cell(event.compartment),
                        cell(event.dateTime),
                        cell(event.eventCode),
                        cell(event.eventDescription)
                    )
                }
            } else {
                sheet.rows += listOf(
                    headerCell("Compartment"),
                    headerCell("Date/Time"),
                    headerCell("Event Code"),
                    headerCell("Event Description"),
                    headerCell("V"),
                    headerCell("I"),
                    headerCell("PF"),
                    headerCell("kWh"),
                    headerCell("kVAh"),
                    headerCell("Neutral I")
                )
                events.forEach { event ->
                    sheet.rows += listOf(
                        cell(event.compartment),
                        cell(event.dateTime),
                        cell(event.eventCode),
                        cell(event.eventDescription),
                        cell(event.voltage),
                        cell(event.current),
                        cell(event.powerFactor),
                        cell(event.energy),
                        cell(event.kvah),
                        cell(event.neutralCurrent)
                    )
                }
            }
            sheet.blank()
        }
        return sheet
    }

    /** Writes a profile as one wide, unsplit table: columns across, rows down. */
    private fun appendProfileRows(sheet: XlsxSheet, section: ProfileSection) {
        if (section.columns.isEmpty()) {
            sheet.text("No rows returned")
            return
        }
        sheet.rows += section.columns.map { headerCell(it.parameterName) }
        section.rows.forEach { row ->
            sheet.rows += section.columns.indices.map { index ->
                cell(row.getOrNull(index))
            }
        }
    }

    private fun appendTruncationNote(sheet: XlsxSheet, section: ProfileSection) {
        if (section.rows.size >= section.totalRows) return
        val oldest = section.rows.firstOrNull()?.firstOrNull() ?: "N/A"
        sheet.blank()
        sheet.text("Showing ${section.rows.size} of ${section.totalRows} entries (oldest shown: $oldest)")
    }

    private fun identityValue(report: MeterDataReport, obis: String): String =
        report.identity?.rows?.firstOrNull { it.obisCode == obis }?.formattedValue ?: ""
}

/**
 * Writes the meter report to an `.xlsx` workbook in the app Documents directory
 * and shares it through [FileProvider].
 */
class MeterLinkExcelExporter(private val context: Context) {

    companion object {
        const val MIME_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    fun export(report: MeterDataReport): File {
        require(report.hasData) { "No meter data is available for export." }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        val serial = report.identity?.rows
            ?.firstOrNull { it.obisCode == "0.0.96.1.0.255" }
            ?.formattedValue
            ?.ifBlank { "Meter" }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?: "Meter"
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        directory.mkdirs()
        val file = File(directory, "MeterLink_Report_${serial}_$stamp.xlsx")
        file.outputStream().use { out -> MeterLinkXlsxWriter.write(report, out) }
        return file
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share MeterLink Excel Report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
