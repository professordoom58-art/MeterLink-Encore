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
import com.utility.dlmsreader.dlms.MeterDataReport
import com.utility.dlmsreader.dlms.NetMetering
import com.utility.dlmsreader.storage.identityValue
import com.utility.dlmsreader.storage.instantaneousParameterCount
import com.utility.dlmsreader.storage.nameplateFieldCount
import com.utility.dlmsreader.ui.PreviewSection
import com.utility.dlmsreader.ui.energyReading
import com.utility.dlmsreader.ui.manufacturerTag
import com.utility.dlmsreader.ui.netActiveEnergyReading
import com.utility.dlmsreader.ui.netApparentEnergyReading
import com.utility.dlmsreader.ui.theme.*

@Composable
internal fun MeterReadingSummaryCard(
    report: MeterDataReport,
    busy: Boolean,
    lastHexLogName: String?,
    onExportPdf: () -> Unit,
    onExportExcel: () -> Unit,
    onExportHex: () -> Unit,
    onViewPdf: (() -> Unit)? = null,
    onSectionClick: (PreviewSection) -> Unit
) {
    val serial = report.identityValue("0.0.96.1.0.255").ifBlank { "UNKNOWN" }
    val ldn = report.identityValue("0.0.42.0.0.255")
    val manufacturerTag = manufacturerTag(report.identityValue("0.0.96.1.1.255"))
    val activeEnergy = report.energyReading("1.0.1.8.0.255", "kWh")
    val apparentEnergy = report.energyReading("1.0.9.8.0.255", "kVAh")
    val isNetMeter = report.isNetMeter
    val exportActiveEnergy = report.energyReading(NetMetering.OBIS_ACTIVE_ENERGY_EXPORT, "kWh")
    val exportApparentEnergy = report.energyReading(NetMetering.OBIS_APPARENT_ENERGY_EXPORT, "kVAh")
    val netActiveEnergy = report.netActiveEnergyReading()
    val netApparentEnergy = report.netApparentEnergyReading()

    val sections = listOf(
        PreviewSection.NAMEPLATE to report.nameplateFieldCount(),
        PreviewSection.INSTANTANEOUS to report.instantaneousParameterCount(),
        PreviewSection.BILLING to (report.billing?.rows?.size ?: 0),
        PreviewSection.DAILY to (report.dailyLoad?.rows?.size ?: 0),
        PreviewSection.BLOCK to (report.blockLoad?.rows?.size ?: 0),
        PreviewSection.TAMPER to report.tamperEvents.size
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "#$serial",
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.weight(1.2f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = SoftBlue,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            manufacturerTag,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cobalt,
                            maxLines = 1
                        )
                    }
                    if (isNetMeter) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = Color(0xFFDCFCE7),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "NET METER",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF166534),
                                maxLines = 1
                            )
                        }
                    }
                    if (ldn.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            ldn,
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Muted,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (isNetMeter) {
                // Bi-directional meter: import, export and net on each channel.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EnergyReadout(
                            label = "ACTIVE IMPORT",
                            value = activeEnergy,
                            unit = "kWh",
                            modifier = Modifier.weight(1f)
                        )
                        EnergyReadout(
                            label = "ACTIVE EXPORT",
                            value = exportActiveEnergy.ifBlank { "—" },
                            unit = "kWh",
                            modifier = Modifier.weight(1f)
                        )
                        EnergyReadout(
                            label = "ACTIVE NET",
                            value = netActiveEnergy,
                            unit = "kWh",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EnergyReadout(
                            label = "APPARENT IMPORT",
                            value = apparentEnergy,
                            unit = "kVAh",
                            modifier = Modifier.weight(1f)
                        )
                        EnergyReadout(
                            label = "APPARENT EXPORT",
                            value = exportApparentEnergy.ifBlank { "—" },
                            unit = "kVAh",
                            modifier = Modifier.weight(1f)
                        )
                        EnergyReadout(
                            label = "APPARENT NET",
                            value = netApparentEnergy,
                            unit = "kVAh",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
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
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

            report.errors.take(3).forEach {
                Text(it, color = Color(0xFFB45309), fontSize = 10.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onExportPdf,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cobalt,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        "↓ Export PDF",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = onExportExcel,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Cobalt,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        "↓ Export Excel (.xlsx)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            OutlinedButton(
                onClick = onExportHex,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    "Export Hex (.txt)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            onViewPdf?.let { viewPdf ->
                OutlinedButton(
                    onClick = viewPdf,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Cobalt),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cobalt),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(
                        "View PDF",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            lastHexLogName?.let {
                Text("Hex log ready: $it", fontSize = 10.sp, color = Muted)
            }
        }
    }
}

@Composable
internal fun EnergyReadout(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFFF0F7FF),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFFDBEAFE))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Muted,
                maxLines = 1
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    " $unit",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cobalt,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun SectionCountBadge(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = if (onClick != null) Color(0xFFF8FAFC) else TileFill,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (onClick != null) Color(0xFFBFDBFE) else Hairline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                fontSize = 10.5.sp,
                color = if (onClick != null) Cobalt else Muted,
                fontWeight = if (onClick != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Text(
                count.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Ink
            )
        }
    }
}
