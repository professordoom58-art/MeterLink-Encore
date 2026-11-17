package com.utility.dlmsreader.ui.dialogs

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import com.utility.dlmsreader.R
import com.utility.dlmsreader.dlms.LoadSurveySelection
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.applyLoadSurveySelectionToReport
import com.utility.dlmsreader.storage.SavedReadingSummary
import com.utility.dlmsreader.ui.PreviewSection
import com.utility.dlmsreader.ui.components.LoadSurveyRangeChips
import com.utility.dlmsreader.ui.components.ResultRow
import com.utility.dlmsreader.ui.components.SavedReadingMetrics
import com.utility.dlmsreader.ui.energyAmount
import com.utility.dlmsreader.ui.preview.SectionDataPreviewDialog
import com.utility.dlmsreader.ui.theme.*
import java.util.Locale

/** Scrollable list of every saved reading, with the detail dialog one tap away. */
@Composable
internal fun SavedReadingsHistoryDialog(
    readings: List<SavedReadingSummary>,
    onOpen: (SavedReadingSummary) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredReadings = remember(readings, searchQuery) {
        if (searchQuery.isBlank()) {
            readings
        } else {
            val query = searchQuery.trim().lowercase(Locale.getDefault())
            readings.filter { reading ->
                reading.serialNumber.lowercase(Locale.getDefault()).contains(query) ||
                    reading.scannedAtFormatted.lowercase(Locale.getDefault()).contains(query) ||
                    reading.readType.lowercase(Locale.getDefault()).contains(query)
            }
        }
    }

    MeterLinkDialog(
        title = "Saved Meter Readings (${readings.size})",
        icon = ImageVector.vectorResource(R.drawable.ic_menu_history),
        onDismiss = onDismiss,
        bodyMaxHeight = 560.dp,
        bodyScrollable = false
    ) {
        if (readings.isEmpty()) {
            Text(
                "No saved readings yet.",
                color = Color.Gray,
                fontSize = 12.sp
            )
        } else {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search serial, date or read type", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Muted
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = Muted
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )

            if (filteredReadings.isEmpty()) {
                Text(
                    "No readings matching '$searchQuery'",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredReadings, key = { it.id }) { reading ->
                        Surface(
                            color = Color(0xFFF8FAFC),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(reading) }
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    "Meter #${reading.serialNumber} — ${reading.scannedAtFormatted}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedReadingDialog(
    reading: SavedReadingSummary,
    report: MeterDataReport?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onExportPdf: (LoadSurveySelection) -> Unit,
    onExportExcel: (LoadSurveySelection) -> Unit,
    onDelete: () -> Unit
) {
    var selection by remember(reading.id) { mutableStateOf(LoadSurveySelection.FULL_BUFFER) }
    var previewSection by remember(reading.id) { mutableStateOf<PreviewSection?>(null) }
    val filteredReport = remember(report, selection) {
        report?.let { applyLoadSurveySelectionToReport(it, selection) }
    }

    MeterLinkDialog(
        title = "Meter #${reading.serialNumber}",
        icon = Icons.AutoMirrored.Filled.List,
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        bodyMaxHeight = 520.dp
    ) {
        Text(
            reading.scannedAtFormatted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Cobalt
        )
        reading.meterRtc
            .takeIf { it.isNotBlank() && it != "N/A" }
            ?.let { ResultRow("Meter RTC", it) }
        SavedReadingMetrics(
            activeEnergy = energyAmount(reading.kwhReading, "kWh"),
            apparentEnergy = energyAmount(reading.kvahReading, "kVAh"),
            nameplate = reading.nameplateCount,
            instantaneous = reading.instantaneousCount,
            billing = reading.billingCount,
            daily = reading.dailyCount,
            block = reading.blockCount,
            tamper = reading.tamperCount,
            onSectionClick = { previewSection = it }
        )
        if (filteredReport == null) {
            Text(
                "Loading saved section data...",
                fontSize = 11.sp,
                color = Muted
            )
        }
        LoadSurveyRangeChips(
            selected = selection,
            enabled = !busy,
            onSelect = { selection = it }
        )
        Button(
            onClick = { onExportPdf(selection) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Export PDF Report", fontSize = 12.sp)
        }
        Button(
            onClick = { onExportExcel(selection) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Export Excel (.xlsx)", fontSize = 12.sp)
        }
        OutlinedButton(
            onClick = onDelete,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Delete Saved Reading", fontSize = 12.sp)
        }
        if (busy) {
            Text(
                "Working — loading and exporting the saved reading...",
                color = Cobalt,
                fontSize = 11.sp
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }

    previewSection?.let { section ->
        filteredReport?.let { data ->
            SectionDataPreviewDialog(
                section = section,
                report = data,
                subtitle = "Meter #${reading.serialNumber}",
                onDismiss = { previewSection = null }
            )
        }
    }
}
