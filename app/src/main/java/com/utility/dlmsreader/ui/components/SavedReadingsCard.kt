package com.utility.dlmsreader.ui.components

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
import com.utility.dlmsreader.storage.SavedReadingSummary
import com.utility.dlmsreader.ui.PreviewSection
import com.utility.dlmsreader.ui.energyAmount
import com.utility.dlmsreader.ui.theme.*

/** Energy strip + tappable section grid shared by saved cards and the dialog. */
@Composable
internal fun SavedReadingMetrics(
    activeEnergy: String,
    apparentEnergy: String,
    nameplate: Int,
    instantaneous: Int,
    billing: Int,
    daily: Int,
    block: Int,
    tamper: Int,
    onSectionClick: (PreviewSection) -> Unit
) {
    val sections = listOf(
        PreviewSection.NAMEPLATE to nameplate,
        PreviewSection.INSTANTANEOUS to instantaneous,
        PreviewSection.BILLING to billing,
        PreviewSection.DAILY to daily,
        PreviewSection.BLOCK to block,
        PreviewSection.TAMPER to tamper
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EnergyReadout(
                label = "ACTIVE ENERGY",
                value = activeEnergy,
                unit = "kWh",
                modifier = Modifier.weight(1f)
            )
            EnergyReadout(
                label = "APPARENT ENERGY",
                value = apparentEnergy,
                unit = "kVAh",
                modifier = Modifier.weight(1f)
            )
        }
        sections.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (section, count) ->
                    SectionCountBadge(
                        label = section.shortLabel,
                        count = count,
                        modifier = Modifier.weight(1f),
                        onClick = { onSectionClick(section) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SavedReadingsCard(
    readings: List<SavedReadingSummary>,
    busy: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onSelect: (SavedReadingSummary) -> Unit,
    onSectionClick: (SavedReadingSummary, PreviewSection) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Saved Meter Readings",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    fontSize = 14.sp
                )
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Cobalt
                    )
                } else {
                    IconButton(
                        onClick = onRefresh,
                        enabled = !busy,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh saved readings",
                            tint = Cobalt,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onImport,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Palette.accentBlue),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cobalt),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    "Import from Storage",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (readings.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "No saved readings yet.",
                        color = Muted,
                        fontSize = 11.sp
                    )
                    Text(
                        "If you reinstalled MeterLink, tap refresh to restore older files.",
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
            } else {
                Text(
                    "${readings.size} reading(s) stored locally — newest first",
                    color = Muted,
                    fontSize = 11.sp
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    readings.forEach { reading ->
                        Surface(
                            color = TileFill,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Hairline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { onSelect(reading) }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    "Meter #${reading.serialNumber} — ${reading.scannedAtFormatted}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                SavedReadingMetrics(
                                    activeEnergy = energyAmount(reading.kwhReading, "kWh"),
                                    apparentEnergy = energyAmount(reading.kvahReading, "kVAh"),
                                    nameplate = reading.nameplateCount,
                                    instantaneous = reading.instantaneousCount,
                                    billing = reading.billingCount,
                                    daily = reading.dailyCount,
                                    block = reading.blockCount,
                                    tamper = reading.tamperCount,
                                    onSectionClick = { section -> onSectionClick(reading, section) }
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Hairline)
            Text(
                "Data is saved locally inside Documents/MeterLink folder.",
                fontSize = 10.sp,
                color = Muted
            )
        }
    }
}
