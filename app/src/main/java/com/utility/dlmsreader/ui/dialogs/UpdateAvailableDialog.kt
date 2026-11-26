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
import com.utility.dlmsreader.R
import com.utility.dlmsreader.ui.components.ResultRow
import com.utility.dlmsreader.ui.theme.*
import com.utility.dlmsreader.update.MeterLinkRelease

/**
 * Notice for a newer GitHub build. The APK is downloaded on tap and handed to
 * the system package installer, which asks for confirmation.
 */
@Composable
internal fun UpdateAvailableDialog(
    release: MeterLinkRelease,
    installedVersion: String,
    downloading: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    MeterLinkDialog(
        title = "Update available",
        icon = ImageVector.vectorResource(R.drawable.ic_system_update),
        onDismiss = onDismiss,
        dismissEnabled = !downloading,
        actionContent = {
            Button(
                onClick = onInstall,
                enabled = !downloading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cobalt)
            ) {
                if (downloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Palette.onCobalt
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Downloading…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        "Download & Install",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) {
        Text(
            "A newer MeterLink build is available on GitHub.",
            fontSize = 12.sp,
            color = Ink
        )
        ResultRow("Latest release", release.tagName)
        ResultRow("Installed build", installedVersion)
        error?.let {
            Text(it, fontSize = 12.sp, color = Palette.errorText)
        }
        Text(
            "The APK downloads from GitHub into the app cache. Android asks for confirmation before installing.",
            fontSize = 10.sp,
            color = Muted
        )
    }
}
