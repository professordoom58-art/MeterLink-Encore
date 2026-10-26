package com.utility.dlmsreader.dlms

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeterLinkPdfExporter(private val context: Context) {
    companion object {
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val LEFT = 36f
        private const val RIGHT = 559f
        private const val BOTTOM = 780f
        private val NAVY = Color.rgb(30, 58, 138)
        private val LIGHT_ROW = Color.rgb(248, 250, 252)
        private val BORDER = Color.rgb(203, 213, 225)
    }

    fun export(report: MeterDataReport): File {
        require(report.hasData) { "No meter data is available for export." }
        val generated = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date())
        val serial = identityValue(report, "0.0.96.1.0.255")
            .ifBlank { "UnknownSerial" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.filesDir
        directory.mkdirs()
        val file = File(directory, "MeterLink_Report_${serial}_$stamp.pdf")

        val layout = ReportLayout(
            generated = generated,
            serial = serial,
            logicalDevice = identityValue(report, "0.0.42.0.0.255"),
            manufacturer = identityValue(report, "0.0.96.1.1.255")
        )
        buildReport(layout, report)

        val document = PdfDocument()
        layout.render(document)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    fun share(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share MeterLink PDF Report").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun buildReport(layout: ReportLayout, report: MeterDataReport) {
        report.identity?.let { identity ->
            layout.section("Section 1: Meter Identity & Nameplate Summary")
            layout.table(
                listOf("Parameter", "OBIS Code", "Value", "Unit"),
                identity.rows.map { listOf(it.parameterName, it.obisCode, it.formattedValue, it.unit) }
            )
            identity.nameplate?.let { drawProfile(layout, it) }
        }

        if (report.cumulativeEnergies.isNotEmpty()) {
            layout.section("Section 2: Cumulative Energy & Maximum Demand Registers")
            layout.table(
                listOf("Parameter", "OBIS Code", "Reading", "Unit", "Capture Timestamp"),
                report.cumulativeEnergies.map {
                    listOf(it.parameterName, it.obisCode, it.reading, it.unit, it.captureTimestamp)
                }
            )
        }
        report.instantaneous?.let {
            layout.section("Section 3: Instantaneous Parameters Snapshot")
            drawProfile(layout, it)
        }
        report.billing?.let {
            layout.section("Section 4: Billing History Profile")
            drawWideProfile(layout, it)
        }
        report.dailyLoad?.let {
            layout.section("Section 5: Daily Load Survey Profile")
            drawWideProfile(layout, it)
        }
        report.blockLoad?.let {
            layout.section("Section 6: Block Load Survey Profile")
            drawWideProfile(layout, it)
        }
        if (report.tamperEvents.isNotEmpty()) {
            layout.section("Section 7: Tamper & Event Logs")
            layout.table(
                listOf("Compartment", "Date/Time", "Event Code", "Event Description", "V", "I", "PF", "kWh"),
                report.tamperEvents.map {
                    listOf(it.compartment, it.dateTime, it.eventCode, it.eventDescription, it.voltage, it.current, it.powerFactor, it.energy)
                }
            )
        }
        if (report.errors.isNotEmpty()) {
            layout.section("Read Notes")
            layout.table(listOf("Section", "Status"), report.errors.map { listOf("MeterLink", it) })
        }
    }

    private fun drawProfile(layout: ReportLayout, section: ProfileSection) {
        val rows = section.rows.flatMap { row ->
            section.columns.mapIndexed { index, column ->
                listOf(column.parameterName, column.obisCode, row.getOrNull(index) ?: "N/A", column.unit)
            }
        }
        layout.table(listOf("Parameter", "OBIS Code", "Value", "Unit"), rows)
    }

    private fun drawWideProfile(layout: ReportLayout, section: ProfileSection) {
        val headers = section.columns.map { it.parameterName }
        if (headers.isEmpty()) {
            layout.table(listOf("Profile", "Status"), listOf(listOf(section.profileObis, "No rows returned")))
        } else {
            layout.table(headers, section.rows)
        }
    }

    private fun identityValue(report: MeterDataReport, obis: String): String =
        report.identity?.rows?.firstOrNull { it.obisCode == obis }?.formattedValue ?: ""

    private class ReportLayout(
        private val generated: String,
        private val serial: String,
        private val logicalDevice: String,
        private val manufacturer: String
    ) {
        private sealed class Op {
            data class Text(
                val value: String,
                val x: Float,
                val baseline: Float,
                val size: Float,
                val color: Int,
                val bold: Boolean = false,
                val align: Paint.Align = Paint.Align.LEFT
            ) : Op()

            data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int) : Op()
            data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int, val fill: Boolean) : Op()
        }

        private val pages = mutableListOf<MutableList<Op>>()
        private var current = mutableListOf<Op>()
        private var y = 104f

        init {
            pages += current
        }

        fun section(title: String) {
            ensure(34f)
            add(Op.Text(title, LEFT, y + 18f, 13f, NAVY, true))
            y += 32f
        }

        fun table(headers: List<String>, rows: List<List<String>>) {
            if (headers.isEmpty()) return
            val widths = widths(headers.size)
            tableHeader(headers, widths)
            rows.forEachIndexed { rowIndex, row ->
                if (y + 20f > BOTTOM) {
                    newPage()
                    tableHeader(headers, widths)
                }
                val top = y
                add(Op.Rect(LEFT, top, RIGHT, top + 20f, if (rowIndex % 2 == 0) LIGHT_ROW else Color.WHITE, true))
                var x = LEFT
                headers.forEachIndexed { index, _ ->
                    val value = truncate(row.getOrNull(index) ?: "", widths[index] - 8f, 8f)
                    val numeric = value.matches(Regex("[-+]?([0-9][0-9,]*)(\\.[0-9]+)?(.*)"))
                    val align = if (numeric && index > 0) Paint.Align.RIGHT else Paint.Align.LEFT
                    val textX = if (align == Paint.Align.RIGHT) x + widths[index] - 4f else x + 4f
                    add(Op.Text(value, textX, top + 14f, 8f, Color.rgb(30, 41, 59), false, align))
                    add(Op.Rect(x, top, x + widths[index], top + 20f, BORDER, false))
                    x += widths[index]
                }
                y += 20f
            }
            y += 8f
        }

        fun render(document: PdfDocument) {
            pages.forEachIndexed { index, ops ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                drawHeader(canvas)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                ops.forEach { op ->
                    when (op) {
                        is Op.Text -> drawText(canvas, paint, op)
                        is Op.Line -> {
                            paint.color = op.color
                            paint.strokeWidth = 1f
                            canvas.drawLine(op.x1, op.y1, op.x2, op.y2, paint)
                        }
                        is Op.Rect -> {
                            paint.color = op.color
                            paint.style = if (op.fill) Paint.Style.FILL else Paint.Style.STROKE
                            paint.strokeWidth = 1f
                            canvas.drawRect(op.left, op.top, op.right, op.bottom, paint)
                            paint.style = Paint.Style.FILL
                        }
                    }
                }
                paint.color = Color.DKGRAY
                paint.textSize = 8f
                canvas.drawText("MeterLink  |  Page ${index + 1} of ${pages.size}", LEFT, 818f, paint)
                document.finishPage(page)
            }
        }

        private fun tableHeader(headers: List<String>, widths: List<Float>) {
            ensure(22f)
            val top = y
            add(Op.Rect(LEFT, top, RIGHT, top + 22f, NAVY, true))
            var x = LEFT
            headers.forEachIndexed { index, header ->
                add(Op.Text(truncate(header, widths[index] - 8f, 8f), x + 4f, top + 15f, 8f, Color.WHITE, true))
                x += widths[index]
            }
            y += 22f
        }

        private fun widths(count: Int): List<Float> {
            val width = (RIGHT - LEFT) / count
            return List(count) { width }
        }

        private fun ensure(height: Float) {
            if (y + height > BOTTOM) newPage()
        }

        private fun newPage() {
            current = mutableListOf()
            pages += current
            y = 104f
        }

        private fun add(op: Op) {
            current += op
        }

        private fun drawHeader(canvas: Canvas) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = NAVY
            canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 48f, paint)
            paint.color = Color.WHITE
            paint.textSize = 14f
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            canvas.drawText("MeterLink — IS 15959 DLMS Meter Reading Report", LEFT, 29f, paint)
            paint.color = Color.rgb(51, 65, 85)
            paint.textSize = 8f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("Generated: $generated    Serial: $serial    LDN: $logicalDevice    Manufacturer: $manufacturer", LEFT, 65f, paint)
            paint.color = BORDER
            canvas.drawLine(LEFT, 76f, RIGHT, 76f, paint)
        }

        private fun drawText(canvas: Canvas, paint: Paint, op: Op.Text) {
            paint.color = op.color
            paint.textSize = op.size
            paint.typeface = if (op.bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            paint.textAlign = op.align
            canvas.drawText(op.value, op.x, op.baseline, paint)
            paint.textAlign = Paint.Align.LEFT
        }

        private fun truncate(value: String, width: Float, size: Float): String {
            val maxChars = (width / (size * 0.52f)).toInt().coerceAtLeast(4)
            return if (value.length <= maxChars) value else value.take(maxChars - 1) + "…"
        }
    }
}
