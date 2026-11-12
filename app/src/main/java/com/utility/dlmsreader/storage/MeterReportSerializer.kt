package com.utility.dlmsreader.storage

import com.utility.dlmsreader.dlms.IdentitySection
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.ParameterRow
import com.utility.dlmsreader.dlms.ProfileColumn
import com.utility.dlmsreader.dlms.ProfileSection
import com.utility.dlmsreader.dlms.RegisterReading
import com.utility.dlmsreader.dlms.TamperEventRow
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lossless JSON + GZIP serialization for [MeterDataReport].
 *
 * The report is stored as a single compressed JSON document so a saved reading
 * can be re-opened and exported to PDF or XLSX exactly like the live session.
 * Every field is written explicitly, including the cached `fullRows`,
 * `fullTotalRows` and `fullBufferCached` load-survey buffers. There are no
 * external dependencies beyond the JSON and GZIP classes shipped with Android.
 */
object MeterReportSerializer {

    fun toJsonBytesGzip(report: MeterDataReport): ByteArray {
        val json = toJson(report).toString().toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream(json.size)
        GZIPOutputStream(output).use { it.write(json) }
        return output.toByteArray()
    }

    fun fromJsonBytesGzip(bytes: ByteArray): MeterDataReport {
        val json = GZIPInputStream(ByteArrayInputStream(bytes)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        return fromJson(JSONObject(json))
    }

    fun toJson(report: MeterDataReport): JSONObject = JSONObject().apply {
        report.identity?.let { put("identity", identityToJson(it)) }
        put("cumulativeEnergies", JSONArray().apply {
            report.cumulativeEnergies.forEach { put(registerToJson(it)) }
        })
        report.instantaneous?.let { put("instantaneous", profileToJson(it)) }
        report.billing?.let { put("billing", profileToJson(it)) }
        report.blockLoad?.let { put("blockLoad", profileToJson(it)) }
        report.dailyLoad?.let { put("dailyLoad", profileToJson(it)) }
        put("tamperEvents", JSONArray().apply {
            report.tamperEvents.forEach { put(tamperToJson(it)) }
        })
        put("eventLogStatus", JSONArray().apply {
            report.eventLogStatus.forEach { (compartment, status) ->
                put(JSONArray().put(compartment).put(status))
            }
        })
        put("errors", JSONArray().apply { report.errors.forEach { put(it) } })
    }

    fun fromJson(json: JSONObject): MeterDataReport = MeterDataReport(
        identity = json.optJSONObject("identity")?.let(::identityFromJson),
        cumulativeEnergies = json.optJSONArray("cumulativeEnergies")
            .objects { registerFromJson(it) },
        instantaneous = json.optJSONObject("instantaneous")?.let(::profileFromJson),
        billing = json.optJSONObject("billing")?.let(::profileFromJson),
        blockLoad = json.optJSONObject("blockLoad")?.let(::profileFromJson),
        dailyLoad = json.optJSONObject("dailyLoad")?.let(::profileFromJson),
        tamperEvents = json.optJSONArray("tamperEvents").objects { tamperFromJson(it) },
        eventLogStatus = json.optJSONArray("eventLogStatus").let { array ->
            if (array == null) emptyList() else (0 until array.length()).map { index ->
                val pair = array.getJSONArray(index)
                pair.optString(0) to pair.optString(1)
            }
        },
        errors = json.optJSONArray("errors").strings()
    )

    private fun identityToJson(identity: IdentitySection): JSONObject = JSONObject().apply {
        put("rows", JSONArray().apply {
            identity.rows.forEach { put(parameterToJson(it)) }
        })
        identity.nameplate?.let { put("nameplate", profileToJson(it)) }
    }

    private fun identityFromJson(json: JSONObject): IdentitySection = IdentitySection(
        rows = json.optJSONArray("rows").objects { parameterFromJson(it) },
        nameplate = json.optJSONObject("nameplate")?.let(::profileFromJson)
    )

    private fun parameterToJson(row: ParameterRow): JSONObject = JSONObject().apply {
        put("parameterName", row.parameterName)
        put("obisCode", row.obisCode)
        put("formattedValue", row.formattedValue)
        put("unit", row.unit)
    }

    private fun parameterFromJson(json: JSONObject): ParameterRow = ParameterRow(
        parameterName = json.optString("parameterName"),
        obisCode = json.optString("obisCode"),
        formattedValue = json.optString("formattedValue"),
        unit = json.optString("unit")
    )

    private fun registerToJson(row: RegisterReading): JSONObject = JSONObject().apply {
        put("parameterName", row.parameterName)
        put("obisCode", row.obisCode)
        put("reading", row.reading)
        put("unit", row.unit)
        put("captureTimestamp", row.captureTimestamp)
    }

    private fun registerFromJson(json: JSONObject): RegisterReading = RegisterReading(
        parameterName = json.optString("parameterName"),
        obisCode = json.optString("obisCode"),
        reading = json.optString("reading"),
        unit = json.optString("unit"),
        captureTimestamp = json.optString("captureTimestamp")
    )

    private fun profileToJson(section: ProfileSection): JSONObject = JSONObject().apply {
        put("title", section.title)
        put("profileObis", section.profileObis)
        put("columns", JSONArray().apply {
            section.columns.forEach { put(columnToJson(it)) }
        })
        put("rows", rowsToJson(section.rows))
        put("totalRows", section.totalRows)
        put("fullRows", rowsToJson(section.fullRows))
        put("fullTotalRows", section.fullTotalRows)
        put("fullBufferCached", section.fullBufferCached)
        put("selectionLabel", section.selectionLabel)
        put("windowNote", section.windowNote)
    }

    private fun profileFromJson(json: JSONObject): ProfileSection = ProfileSection(
        title = json.optString("title"),
        profileObis = json.optString("profileObis"),
        columns = json.optJSONArray("columns").objects { columnFromJson(it) },
        rows = rowsFromJson(json.optJSONArray("rows")),
        totalRows = json.optInt("totalRows"),
        fullRows = rowsFromJson(json.optJSONArray("fullRows")),
        fullTotalRows = json.optInt("fullTotalRows"),
        fullBufferCached = json.optBoolean("fullBufferCached"),
        selectionLabel = json.optString("selectionLabel"),
        windowNote = json.optString("windowNote")
    )

    private fun columnToJson(column: ProfileColumn): JSONObject = JSONObject().apply {
        put("parameterName", column.parameterName)
        put("obisCode", column.obisCode)
        put("unit", column.unit)
        put("attributeIndex", column.attributeIndex)
    }

    private fun columnFromJson(json: JSONObject): ProfileColumn = ProfileColumn(
        parameterName = json.optString("parameterName"),
        obisCode = json.optString("obisCode"),
        unit = json.optString("unit"),
        attributeIndex = json.optInt("attributeIndex", 2)
    )

    private fun tamperToJson(row: TamperEventRow): JSONObject = JSONObject().apply {
        put("compartment", row.compartment)
        put("dateTime", row.dateTime)
        put("eventCode", row.eventCode)
        put("eventDescription", row.eventDescription)
        put("voltage", row.voltage)
        put("current", row.current)
        put("powerFactor", row.powerFactor)
        put("energy", row.energy)
        put("kvah", row.kvah)
        put("neutralCurrent", row.neutralCurrent)
    }

    private fun tamperFromJson(json: JSONObject): TamperEventRow = TamperEventRow(
        compartment = json.optString("compartment"),
        dateTime = json.optString("dateTime"),
        eventCode = json.optString("eventCode"),
        eventDescription = json.optString("eventDescription"),
        voltage = json.optString("voltage"),
        current = json.optString("current"),
        powerFactor = json.optString("powerFactor"),
        energy = json.optString("energy"),
        kvah = json.optString("kvah"),
        neutralCurrent = json.optString("neutralCurrent")
    )

    private fun rowsToJson(rows: List<List<String>>): JSONArray = JSONArray().apply {
        rows.forEach { row ->
            put(JSONArray().apply { row.forEach { put(it) } })
        }
    }

    private fun rowsFromJson(array: JSONArray?): List<List<String>> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            val row = array.getJSONArray(index)
            (0 until row.length()).map { column -> row.optString(column) }
        }
    }

    private inline fun <T> JSONArray?.objects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> transform(getJSONObject(index)) }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { index -> optString(index) }
    }
}
