package com.utility.dlmsreader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.utility.dlmsreader.dlms.VendorPasswordCatalog
import com.utility.dlmsreader.dlms.VendorProfile
import com.utility.dlmsreader.ui.theme.Cobalt
import com.utility.dlmsreader.ui.theme.Hairline
import com.utility.dlmsreader.ui.theme.Ink

/**
 * Vendor picker plus authentication controls. Owns only the dropdown's
 * transient expanded state; the selected vendor/password stay in the screen
 * state so a reading keeps using exactly what the operator sees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VendorConfigurationCard(
    selectedVendor: VendorProfile,
    onSelectVendor: (VendorProfile) -> Unit,
    llsPassword: String,
    onPasswordChange: (String) -> Unit,
    autoTryAlternatePasswords: Boolean,
    onAutoTryAlternateChange: (Boolean) -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    var vendorMenuExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Hairline),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = vendorMenuExpanded,
                onExpandedChange = { vendorMenuExpanded = !vendorMenuExpanded }
            ) {
                OutlinedTextField(
                    value = selectedVendor.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Meter Manufacturer / Vendor") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = vendorMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = vendorMenuExpanded,
                    onDismissRequest = { vendorMenuExpanded = false },
                    containerColor = Color.White,
                    tonalElevation = 0.dp,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Hairline)
                ) {
                    VendorPasswordCatalog.VENDORS.forEach { vendor ->
                        DropdownMenuItem(
                            text = { Text(vendor.displayName) },
                            onClick = {
                                onSelectVendor(vendor)
                                onPasswordChange(vendor.defaultPrimaryPassword)
                                vendorMenuExpanded = false
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = Ink,
                                leadingIconColor = Cobalt
                            )
                        )
                    }
                }
            }

            if (selectedVendor.defaultPasswords.isEmpty()) {
                OutlinedTextField(
                    value = llsPassword,
                    onValueChange = onPasswordChange,
                    label = { Text("Custom LLS Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Switch(
                    checked = autoTryAlternatePasswords,
                    onCheckedChange = onAutoTryAlternateChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Cobalt,
                        checkedBorderColor = Cobalt,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFE2E8F0),
                        uncheckedBorderColor = Color(0xFFCBD5E1)
                    )
                )
                Text(
                    "Auto-try alternate vendor passwords",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Ink
                )
            }

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}