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
import com.utility.dlmsreader.dlms.LoadSurveySelection
import com.utility.dlmsreader.dlms.MeterDataModule
import com.utility.dlmsreader.ui.theme.*

@Composable
internal fun LoadSurveyRangeChips(
    selected: LoadSurveySelection,
    enabled: Boolean,
    onSelect: (LoadSurveySelection) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Load Survey Range",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.subtleText
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LoadSurveySelection.entries.forEach { option ->
                val isSelected = selected == option
                val labelColor = when {
                    !enabled -> Muted.copy(alpha = 0.5f)
                    isSelected -> Cobalt
                    else -> Palette.subtleText
                }
                Surface(
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    shape = RoundedCornerShape(percent = 50),
                    color = if (isSelected) SoftBlue else CardSurface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) Palette.selectedBorder else Hairline
                    ),
                    contentColor = labelColor
                ) {
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            option.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = labelColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExtractionButtonRow(
    first: Pair<String, MeterDataModule>,
    second: Pair<String, MeterDataModule>,
    enabled: Boolean,
    onRead: (MeterDataModule) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExtractionModuleButton(
            label = first.first,
            enabled = enabled,
            onClick = { onRead(first.second) },
            modifier = Modifier.weight(1f)
        )
        ExtractionModuleButton(
            label = second.first,
            enabled = enabled,
            onClick = { onRead(second.second) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ExtractionModuleButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = TileFill,
            contentColor = Ink,
            disabledContainerColor = TileFill,
            disabledContentColor = Muted
        ),
        border = BorderStroke(1.dp, Hairline),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 6.dp)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
