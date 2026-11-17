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
import com.utility.dlmsreader.ui.theme.*

@Composable
internal fun AboutAppDialog(version: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "About App",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "DLMS / COSEM (IEC 62056) Optical Meter Reader",
                    fontSize = 12.sp,
                    color = Muted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = SoftBlue,
                    shape = RoundedCornerShape(percent = 50),
                    border = BorderStroke(1.dp, SoftBlueBorder)
                ) {
                    Text(
                        "BETA • $version",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = Cobalt
                    )
                }
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(TileFill)
                        .border(1.dp, Hairline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AboutFeature(
                        title = "Supported Vendors",
                        body = "L&T / Schneider, Genus, Secure, HPL & Avon",
                        caption = "Custom LLS password support for unknown vendors"
                    )
                    AboutFeature(
                        title = "Supported Readings",
                        body = "Identity, Cumulative Energy & MD, Instantaneous, " +
                            "Billing History, Block Load, Daily Load & Tamper Logs"
                    )
                    AboutFeature(
                        title = "Export Formats",
                        body = "Excel (.xlsx) & PDF Report Format"
                    )
                    AboutFeature(
                        title = "Persistent Local Storage",
                        body = "Readings are safely stored in Documents/MeterLink and persist " +
                            "across app updates or uninstalls."
                    )
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    color = Hairline
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "CREATOR",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = Muted
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildAnnotatedString {
                        append("Made by ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("MIHIR")
                        }
                    },
                    fontSize = 12.5.sp,
                    color = Ink,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Powered by Gurux DLMS Open Source Library",
                    fontSize = 10.5.sp,
                    color = Muted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = Cobalt)
                ) {
                    Text("Close", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Left-aligned feature block: a quiet uppercase heading, the value line, and
 * an optional muted caption. Used inside the rounded features container.
 */
@Composable
private fun AboutFeature(
    title: String,
    body: String,
    caption: String? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            title,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            color = Muted
        )
        Text(
            body,
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            color = Ink,
            textAlign = TextAlign.Start
        )
        caption?.let {
            Text(
                it,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = Muted,
                textAlign = TextAlign.Start
            )
        }
    }
}
