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
import androidx.compose.ui.draw.scale
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
import com.utility.dlmsreader.ui.theme.*
import java.util.Locale

private val ScanNegativeMarkers = listOf(
    "no ", "not ", "none", "error", "fail", "denied", "disconnect"
)
/**
 * Green markers: a probe/cable is physically present, or the meter link is up.
 * "USB Cable Detected" turns the dot green without waiting for a meter reply.
 */
private val ScanHealthyMarkers = listOf(
    "ready", "link", "established", "connected", "detected", "cable"
)

/**
 * Dot colour for the SCAN USB pill: red with no USB probe, green once one is
 * detected.
 *
 * Negative/error wording is matched first: "No USB Optical Probe Detected"
 * contains both "no " and "detected", and "Cable Connected, but No Meter
 * Response" contains both "cable" and "connected", so a positive-first match
 * would paint a disconnected probe green.
 */
internal fun scanUsbDotColor(probeStatus: String): Color {
    val status = probeStatus.lowercase(Locale.US)
    return when {
        ScanNegativeMarkers.any(status::contains) -> Color(0xFFEF4444)
        ScanHealthyMarkers.any(status::contains) -> Color(0xFF10B981)
        else -> Color(0xFFEF4444)
    }
}

/** Unified landing header: wordmark, beta pill, USB scan status, and overflow. */
@Composable
internal fun LandingHeader(
    probeStatus: String,
    historyCount: Int,
    checkingForUpdates: Boolean,
    darkMode: Boolean,
    onScanUsb: () -> Unit,
    onHistory: () -> Unit,
    onCheckUpdates: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onAbout: () -> Unit
) {
    val dotColor = scanUsbDotColor(probeStatus)

    Surface(color = Palette.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MeterLink",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Ink
                )
                Surface(
                    color = SoftBlue,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "BETA",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Cobalt
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onScanUsb,
                    modifier = Modifier.height(34.dp),
                    shape = RoundedCornerShape(percent = 50),
                    color = CardSurface,
                    border = BorderStroke(1.dp, Hairline)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(dotColor, CircleShape)
                        )
                        Text(
                            "SCAN USB",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink
                        )
                    }
                }
                HeaderOverflowMenu(
                    historyCount = historyCount,
                    checkingForUpdates = checkingForUpdates,
                    darkMode = darkMode,
                    onHistory = onHistory,
                    onCheckUpdates = onCheckUpdates,
                    onToggleDarkMode = onToggleDarkMode,
                    onAbout = onAbout
                )
            }
        }
    }
}

/** Borderless 3-dot overflow button pinned to the right of the landing header. */
@Composable
private fun HeaderOverflowMenu(
    historyCount: Int,
    checkingForUpdates: Boolean,
    darkMode: Boolean,
    onHistory: () -> Unit,
    onCheckUpdates: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onAbout: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = (-16).dp, y = 4.dp),
            containerColor = CardSurface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Hairline)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (historyCount > 0) "History ($historyCount)" else "History",
                        fontSize = 14.sp
                    )
                },
                leadingIcon = { MenuItemIcon(R.drawable.ic_menu_history) },
                onClick = {
                    expanded = false
                    onHistory()
                },
                colors = MenuDefaults.itemColors(
                    textColor = Ink,
                    leadingIconColor = Cobalt
                )
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (checkingForUpdates) "Checking for Updates…" else "Check for Updates",
                        fontSize = 14.sp
                    )
                },
                leadingIcon = { MenuItemIcon(R.drawable.ic_system_update) },
                onClick = {
                    expanded = false
                    onCheckUpdates()
                },
                colors = MenuDefaults.itemColors(
                    textColor = Ink,
                    leadingIconColor = Cobalt
                )
            )
            DropdownMenuItem(
                text = { Text("Dark mode", fontSize = 14.sp) },
                leadingIcon = { MenuItemIcon(R.drawable.ic_menu_dark_mode) },
                trailingIcon = {
                    // The whole row toggles; the switch only shows the state.
                    Switch(
                        checked = darkMode,
                        onCheckedChange = null,
                        modifier = Modifier.scale(0.8f)
                    )
                },
                // The menu stays open so the theme change shows immediately.
                onClick = onToggleDarkMode,
                colors = MenuDefaults.itemColors(
                    textColor = Ink,
                    leadingIconColor = Cobalt
                )
            )
            DropdownMenuItem(
                text = { Text("About App", fontSize = 14.sp) },
                leadingIcon = { MenuItemIcon(R.drawable.ic_menu_about) },
                onClick = {
                    expanded = false
                    onAbout()
                },
                colors = MenuDefaults.itemColors(
                    textColor = Ink,
                    leadingIconColor = Cobalt
                )
            )
        }
    }
}

/** Leading slot icon for the overflow menu, tinted with the cobalt accent. */
@Composable
private fun MenuItemIcon(drawableRes: Int) {
    Icon(
        painter = painterResource(drawableRes),
        contentDescription = null,
        tint = Cobalt,
        modifier = Modifier.size(18.dp)
    )
}
