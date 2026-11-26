package com.utility.dlmsreader.ui.components

import com.utility.dlmsreader.ui.theme.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.utility.dlmsreader.dlms.ExtractionProgress
import com.utility.dlmsreader.dlms.LoadSurveySelection
import com.utility.dlmsreader.dlms.MeterDataModule

/**
 * Primary extraction surface: progress banner, full dump, per-module reads,
 * load-survey range and tamper log. Every control stays disabled while a
 * reading is in flight.
 */
@Composable
internal fun ExtractionCenterCard(
    isReading: Boolean,
    progressText: String?,
    reportStatusText: String?,
    loadSurveySelection: LoadSurveySelection,
    onSelectLoadSurveyRange: (LoadSurveySelection) -> Unit,
    onStartReading: (MeterDataModule) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val bannerLines = listOfNotNull(progressText, reportStatusText)
            if (isReading || bannerLines.isNotEmpty()) {
                Surface(
                    color = SoftBlue,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SoftBlueBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            bannerLines.joinToString("\n").ifBlank { "Reading meter..." },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Cobalt,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        val barShape = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(50))
                        if (isReading) {
                            // Smooth determinate fill from the section
                            // percentage in the banner (0%..100%).
                            val progressPercent =
                                ExtractionProgress.parsePercent(progressText) ?: 0
                            LinearProgressIndicator(
                                progress = { progressPercent / 100f },
                                modifier = barShape,
                                color = Cobalt,
                                trackColor = Palette.trackBlue
                            )
                        } else {
                            // Completed run: full tonal bar under the status line.
                            LinearProgressIndicator(
                                progress = { 1f },
                                modifier = barShape,
                                color = Cobalt,
                                trackColor = Palette.trackBlue
                            )
                        }
                    }
                }
            }
            Button(
                onClick = { onStartReading(MeterDataModule.ALL) },
                enabled = !isReading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cobalt,
                    contentColor = Palette.onCobalt
                ),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    "Read All (Full Meter Dump)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            ExtractionButtonRow(
                first = "Read Identity" to MeterDataModule.IDENTITY,
                second = "Read Energy & MD" to MeterDataModule.ENERGY,
                enabled = !isReading,
                onRead = onStartReading
            )
            ExtractionButtonRow(
                first = "Instantaneous" to MeterDataModule.INSTANTANEOUS,
                second = "Billing History" to MeterDataModule.BILLING,
                enabled = !isReading,
                onRead = onStartReading
            )
            ExtractionButtonRow(
                first = "Read Block Load" to MeterDataModule.BLOCK_LOAD,
                second = "Read Daily Load" to MeterDataModule.DAILY_LOAD,
                enabled = !isReading,
                onRead = onStartReading
            )
            LoadSurveyRangeChips(
                selected = loadSurveySelection,
                enabled = !isReading,
                onSelect = onSelectLoadSurveyRange
            )
            OutlinedButton(
                onClick = { onStartReading(MeterDataModule.TAMPER) },
                enabled = !isReading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Palette.accentBlue),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cobalt),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    "Read Tamper Logs",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}