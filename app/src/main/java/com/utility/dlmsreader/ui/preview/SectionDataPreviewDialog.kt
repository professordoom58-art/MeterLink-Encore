package com.utility.dlmsreader.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.ProfileSection
import com.utility.dlmsreader.ui.PreviewSection
import com.utility.dlmsreader.ui.theme.*

/**
 * Full-width modal showing the actual extracted rows behind one section box.
 * Tables scroll horizontally and vertically; key/value sections scroll
 * vertically inside the fixed 620 dp card.
 */
@Composable
internal fun SectionDataPreviewDialog(
    section: PreviewSection,
    report: MeterDataReport,
    subtitle: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = Cobalt,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            section.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            subtitle,
                            fontSize = 11.sp,
                            color = Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    when (section) {
                        PreviewSection.NAMEPLATE -> NameplatePreview(report)
                        PreviewSection.INSTANTANEOUS -> InstantaneousPreview(report)
                        PreviewSection.BILLING -> ProfilePreview(report.billing, "No billing rows in this reading.")
                        PreviewSection.DAILY -> ProfilePreview(report.dailyLoad, "No daily load rows in this reading.")
                        PreviewSection.BLOCK -> ProfilePreview(report.blockLoad, "No block load rows in this reading.")
                        PreviewSection.TAMPER -> TamperPreview(report)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = SoftBlue,
                            contentColor = Cobalt
                        )
                    ) {
                        Text("Close", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun NameplatePreview(report: MeterDataReport) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val identityRows = report.identity?.rows.orEmpty()
        if (identityRows.isEmpty() && report.cumulativeEnergies.isEmpty()) {
            PreviewEmpty("No nameplate data in this reading.")
            return@Column
        }
        identityRows.forEach { row ->
            PreviewValueRow(
                label = row.parameterName,
                value = row.formattedValue,
                caption = row.obisCode
            )
        }
        if (report.cumulativeEnergies.isNotEmpty()) {
            Text(
                "Cumulative Energy & MD",
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Cobalt
            )
            report.cumulativeEnergies.forEach { register ->
                PreviewValueRow(
                    label = register.parameterName,
                    value = listOf(register.reading, register.unit)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    caption = register.obisCode
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InstantaneousPreview(report: MeterDataReport) {
    val profile = report.instantaneous
    val row = profile?.rows?.firstOrNull()
    if (profile == null || row == null) {
        PreviewEmpty("No instantaneous parameters in this reading.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        profile.columns.forEachIndexed { index, column ->
            val value = row.getOrNull(index).orEmpty()
            PreviewValueRow(
                label = column.parameterName,
                value = listOf(value, column.unit)
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
                caption = column.obisCode
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProfilePreview(section: ProfileSection?, emptyText: String) {
    if (section == null || section.columns.isEmpty() || section.rows.isEmpty()) {
        PreviewEmpty(emptyText)
        return
    }
    val headers = section.columns.map { column ->
        column.parameterName to previewColumnWidth(column.parameterName)
    }
    PreviewDataTable(
        headers = headers,
        rows = section.rows,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun TamperPreview(report: MeterDataReport) {
    val statuses = report.eventLogStatus
    val events = report.tamperEvents
    if (statuses.isEmpty() && events.isEmpty()) {
        PreviewEmpty("No tamper events in this reading.")
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        statuses.forEach { (compartment, status) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    compartment,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(status, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Cobalt)
            }
        }
        if (statuses.isNotEmpty() && events.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = Hairline
            )
        }
        PreviewDataTable(
            headers = listOf(
                "Compartment" to 104.dp,
                "Date / Time" to 132.dp,
                "Code" to 64.dp,
                "Event Description" to 180.dp,
                "V" to 58.dp,
                "I" to 58.dp,
                "PF" to 54.dp,
                "kWh" to 86.dp
            ),
            rows = events.map {
                listOf(
                    it.compartment,
                    it.dateTime,
                    it.eventCode,
                    it.eventDescription,
                    it.voltage,
                    it.current,
                    it.powerFactor,
                    it.energy
                )
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun PreviewDataTable(
    headers: List<Pair<String, Dp>>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier
) {
    val horizontal = rememberScrollState()
    Column(modifier = modifier.horizontalScroll(horizontal)) {
        Row(modifier = Modifier.background(SoftBlue)) {
            headers.forEach { (title, width) ->
                PreviewTableCell(text = title, width = width, header = true)
            }
        }
        if (rows.isEmpty()) {
            Text(
                "No rows available.",
                modifier = Modifier.padding(10.dp),
                fontSize = 11.sp,
                color = Muted
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(rows) { index, row ->
                    Row(
                        modifier = Modifier.background(
                            if (index % 2 == 0) CardSurface else TileFill
                        )
                    ) {
                        headers.forEachIndexed { columnIndex, (_, width) ->
                            PreviewTableCell(
                                text = row.getOrNull(columnIndex).orEmpty(),
                                width = width,
                                header = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTableCell(text: String, width: Dp, header: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        fontSize = if (header) 10.sp else 10.5.sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (header) FontFamily.Default else FontFamily.Monospace,
        color = if (header) Cobalt else Ink,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun PreviewValueRow(label: String, value: String, caption: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 11.sp,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            caption?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Muted)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Cobalt,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PreviewEmpty(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, fontSize = 12.sp, color = Muted, textAlign = TextAlign.Center)
    }
}

private fun previewColumnWidth(title: String): Dp = when {
    title.contains("date", ignoreCase = true) ||
        title.contains("time", ignoreCase = true) -> 138.dp
    title.length > 24 -> 176.dp
    title.length > 16 -> 146.dp
    title.length > 9 -> 118.dp
    else -> 78.dp
}
