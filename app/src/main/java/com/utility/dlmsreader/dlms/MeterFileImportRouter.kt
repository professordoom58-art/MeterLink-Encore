package com.utility.dlmsreader.dlms

import com.utility.dlmsreader.storage.MeterReportSerializer
import java.io.InputStream
import java.util.Locale
import org.json.JSONObject

/** File formats accepted by the Import from Storage picker. */
enum class MeterFileFormat {
    JSON,
    TAGGED_OBIS_CSV,
    FRAMED_BINARY,
    UNKNOWN
}

data class MeterFileImportResult(
    val report: MeterDataReport,
    val format: MeterFileFormat
)

/**
 * Single entry point for the Import from Storage flow.
 *
 * JSON is parsed as a MeterLink report, tagged CSV through
 * [ObisProfileCsvParser], and raw dumps with the `S$S` marker through
 * [FramedBinaryDumpParser]. Every successful route is relabelled
 * [MeterDataReport.SOURCE_IMPORTED] so PDF and XLSX exports clearly identify
 * the reading as a file import rather than a live optical extraction.
 */
object MeterFileImportRouter {

    private val JSON_EXTENSIONS = setOf("json")
    private val CSV_EXTENSIONS = setOf("csv", "txt")
    private val BINARY_EXTENSIONS = setOf("bin", "dat")

    fun route(
        bytes: ByteArray,
        fileName: String = "",
        mimeType: String? = null
    ): MeterFileImportResult {
        val format = detectFormat(fileName, mimeType, bytes)
        val report = when (format) {
            MeterFileFormat.JSON -> parseJson(bytes)
            MeterFileFormat.TAGGED_OBIS_CSV -> ObisProfileCsvParser.parse(bytes)
            MeterFileFormat.FRAMED_BINARY -> FramedBinaryDumpParser.parse(bytes)
            MeterFileFormat.UNKNOWN -> null
        } ?: throw IllegalArgumentException(
            "The selected file is not a supported meter file. " +
                "Choose a JSON report, a tagged OBIS CSV, or a framed binary dump."
        )
        return MeterFileImportResult(
            report = report.copy(sourceLabel = MeterDataReport.SOURCE_IMPORTED),
            format = format
        )
    }

    fun route(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray
    ): MeterFileImportResult = route(bytes, fileName, mimeType)

    fun route(fileName: String, bytes: ByteArray): MeterFileImportResult =
        route(bytes, fileName, null)

    fun route(input: InputStream, fileName: String = "", mimeType: String? = null): MeterFileImportResult =
        route(input.readBytes(), fileName, mimeType)

    fun import(
        bytes: ByteArray,
        fileName: String = "",
        mimeType: String? = null
    ): MeterDataReport = route(bytes, fileName, mimeType).report

    fun import(
        input: InputStream,
        fileName: String = "",
        mimeType: String? = null
    ): MeterDataReport = route(input, fileName, mimeType).report

    /**
     * Sniffs the format from content first, then falls back to the file name
     * and MIME type. Raw-byte header detection makes the picker work when the
     * provider reports an octet-stream or wildcard MIME type.
     */
    fun detectFormat(
        fileName: String = "",
        mimeType: String? = null,
        bytes: ByteArray
    ): MeterFileFormat {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()

        if (FramedBinaryDumpParser.containsFrameHeader(bytes) ||
            (extension in BINARY_EXTENSIONS && !looksLikeJson(text) &&
                !ObisProfileCsvParser.looksLikeTaggedCsv(text))
        ) {
            return MeterFileFormat.FRAMED_BINARY
        }
        if (looksLikeJson(text) || extension in JSON_EXTENSIONS) {
            return MeterFileFormat.JSON
        }
        if (ObisProfileCsvParser.looksLikeTaggedCsv(text) || extension in CSV_EXTENSIONS) {
            return MeterFileFormat.TAGGED_OBIS_CSV
        }
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        return when {
            mime.contains("json") -> MeterFileFormat.JSON
            mime.startsWith("text/") || mime.contains("csv") -> MeterFileFormat.TAGGED_OBIS_CSV
            mime.contains("octet-stream") -> MeterFileFormat.FRAMED_BINARY
            else -> MeterFileFormat.UNKNOWN
        }
    }

    private fun parseJson(bytes: ByteArray): MeterDataReport? {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return runCatching {
            val root = JSONObject(text)
            val report = root.optJSONObject("report") ?: root
            MeterReportSerializer.fromJson(report)
        }.getOrNull()
    }

    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[")
}
