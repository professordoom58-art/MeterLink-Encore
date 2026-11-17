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
import com.utility.dlmsreader.transport.TraceDirection
import com.utility.dlmsreader.transport.TraceLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collapsible diagnostics console. Trace collection and [exportHexLog] remain
 * always active; only this rendering is gated by [SHOW_LIVE_PROTOCOL_TRACE].
 */
@Composable
internal fun LiveProtocolTraceSection(
    traceLogs: List<TraceLog>,
    onExportHex: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Live Protocol Trace (latest ${minOf(traceLogs.size, 150)} of ${traceLogs.size})",
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(
            onClick = onExportHex,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text("Export Hex Log", fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F172A))
            .padding(10.dp)
    ) {
        val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
        val visibleTraceLogs = traceLogs.take(150)
        if (traceLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Awaiting optical connection...", color = Color.Gray, fontSize = 11.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(visibleTraceLogs) { item ->
                    val color = when (item.direction) {
                        TraceDirection.TX -> Color(0xFF60A5FA)
                        TraceDirection.RX -> Color(0xFF4ADE80)
                        TraceDirection.INFO -> Color(0xFFFACC15)
                        TraceDirection.ERROR -> Color(0xFFF87171)
                    }
                    Text(
                        text = "${timeFormat.format(Date(item.timestampMs))} [${item.direction}] ${item.hexOrMessage}",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
internal fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
