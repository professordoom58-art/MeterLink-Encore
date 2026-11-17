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
 * [ObisProfileCsvParser], and raw DLMS dumps through [FramedBinaryDumpParser].
 * Every successful route is relabelled [MeterDataReport.SOURCE_IMPORTED] so PDF
 * and XLSX exports clearly identify the reading as a file import rather than a
 * live optical extraction.
 */
object MeterFileImportRouter {

    /** Raw dumps may carry a timestamp or other header garbage before the frame. */
    private const val HEADER_SCAN_LIMIT = 2048

    /** HDLC frame start: flag byte followed by the address field. */
    private val HDLC_HEADER = byteArrayOf(0x7E, 0xA0.toByte())

    /** DLMS/COSEM wrapper header. */
    private val WRAPPER_HEADER = byteArrayOf(0x00, 0x01, 0x00)

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
            MeterFileFormat.FRAMED_BINARY -> FramedBinaryDumpParser.parse(framePayload(bytes))
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
        val lowerText = text.lowercase(Locale.US)

        if (findBinaryHeader(bytes) >= 0) {
            return MeterFileFormat.FRAMED_BINARY
        }
        if (looksLikeJson(text) || extension in JSON_EXTENSIONS) {
            return MeterFileFormat.JSON
        }
        if (looksLikeTaggedCsv(lowerText) || extension in CSV_EXTENSIONS) {
            return MeterFileFormat.TAGGED_OBIS_CSV
        }
        val mime = mimeType?.lowercase(Locale.US).orEmpty()
        return when {
            mime.contains("json") -> MeterFileFormat.JSON
            mime.startsWith("text/") || mime.contains("csv") -> MeterFileFormat.TAGGED_OBIS_CSV
            mime.contains("octet-stream") -> MeterFileFormat.FRAMED_BINARY
            extension in BINARY_EXTENSIONS -> MeterFileFormat.FRAMED_BINARY
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

    /**
     * Returns the dump beginning at the first DLMS binary header. Files
     * exported by meter vendors often prefix the payload with a timestamp or
     * other ASCII metadata, so the header is not guaranteed to be at offset
     * zero.
     */
    private fun framePayload(bytes: ByteArray): ByteArray {
        val match = findBinaryHeader(bytes)
        return if (match > 0) bytes.copyOfRange(match, bytes.size) else bytes
    }

    /**
     * Index of the earliest HDLC or wrapper header within the first 2048
     * bytes, or -1 when the file has no binary framing.
     */
    private fun findBinaryHeader(bytes: ByteArray): Int {
        val probe = bytes.copyOfRange(0, minOf(bytes.size, HEADER_SCAN_LIMIT))
        val hdlc = findSequence(probe, HDLC_HEADER)
        val wrapper = findSequence(probe, WRAPPER_HEADER)
        return when {
            hdlc < 0 -> wrapper
            wrapper < 0 -> hdlc
            else -> minOf(hdlc, wrapper)
        }
    }

    /** First index of [inner] inside [outer], or -1 when there is no match. */
    internal fun findSequence(outer: ByteArray, inner: ByteArray): Int {
        if (inner.isEmpty() || outer.size < inner.size) return -1
        val last = outer.size - inner.size
        for (index in 0..last) {
            var matched = true
            for (offset in inner.indices) {
                if (outer[index + offset] != inner[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }

    private fun looksLikeJson(text: String): Boolean =
        text.startsWith("{") || text.startsWith("[")

    /**
     * Tagged CSV files are identified by their section vocabulary rather than
     * by line layout, which keeps the sniffer tolerant of mixed-case headers.
     */
    private fun looksLikeTaggedCsv(lowerText: String): Boolean =
        lowerText.contains("name plate") ||
            lowerText.contains("nameplate") ||
            lowerText.contains("obis")
}
