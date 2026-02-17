/**
* Copyright (C) 2026 dryerlint <codeberg.org/dryerlint>
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:Suppress("AssignedValueIsNeverRead")
package org.codeberg.dryerlint.aim.ui

import android.content.res.ColorStateList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.materialswitch.MaterialSwitch
import org.codeberg.dryerlint.aim.R

@Composable
fun PreferenceCategory(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp),
        )
        content()
    }
}

@Composable
fun PreferenceItem(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    summaryColor: androidx.compose.ui.graphics.Color? = null,
    titleFontWeight: FontWeight? = null,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .then(
            if ((onClick != null || onLongClick != null) && enabled) Modifier.combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = { onLongClick?.invoke() },
            )
            else Modifier
        )
        .alpha(if (enabled) 1f else 0.38f)
        .padding(horizontal = 16.dp, vertical = 12.dp)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = titleFontWeight ?: FontWeight.SemiBold
            ),
        )
        if (summary != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = summaryColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SwitchPreferenceItem(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null && enabled) Modifier.clickable(onClick = onClick)
                    else Modifier
                )
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
        val onPrimaryColor = MaterialTheme.colorScheme.onPrimary.toArgb()
        val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant.toArgb()
        val outlineColor = MaterialTheme.colorScheme.outline.toArgb()
        AndroidView(
            modifier = Modifier.padding(horizontal = 16.dp),
            factory = { context ->
                MaterialSwitch(context).apply {
                    trackTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(-android.R.attr.state_checked)
                        ),
                        intArrayOf(primaryColor, surfaceVariantColor)
                    )
                    thumbTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(-android.R.attr.state_checked)
                        ),
                        intArrayOf(onPrimaryColor, outlineColor)
                    )
                    trackDecorationTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(-android.R.attr.state_checked)
                        ),
                        intArrayOf(primaryColor, outlineColor)
                    )
                }
            },
            update = { switchView ->
                switchView.isChecked = checked
                switchView.isEnabled = enabled
                switchView.setOnCheckedChangeListener { _, isChecked ->
                    if (enabled) onCheckedChange(isChecked)
                }
            },
        )
    }
}



@Composable
fun ImageOptionsDialog(
    title: String,
    onDismiss: () -> Unit,
    safExposed: Boolean,
    onSafChange: (Boolean) -> Unit,
    storageExposed: Boolean = false,
    onStorageChange: (Boolean) -> Unit = {},
    onRemove: () -> Unit,
    onFormat: (String) -> Unit = {},
    showFormat: Boolean = true,
    onChangePartition: () -> Unit = {},
    isMultipart: Boolean = false,
) {
    var confirmFormat by remember { mutableStateOf(false) }
    var selectedFsType by remember { mutableStateOf("ext4") }
    if (confirmFormat) {
        AlertDialog(
            onDismissRequest = { confirmFormat = false },
            title = { Text(text = stringResource(R.string.dialog_format_title)) },
            text = {
                Column {
                    Text(text = stringResource(R.string.dialog_format_message, title))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.dialog_format_fs_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    listOf("ext4", "exfat").forEach { fsType ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedFsType = fsType }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = selectedFsType == fsType,
                                onClick = { selectedFsType = fsType },
                            )
                            Text(
                                text = fsType,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFormat = false
                    onFormat(selectedFsType)
                }) {
                    Text(text = stringResource(R.string.dialog_format_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFormat = false }) {
                    Text(text = stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.dialog_expose_heading),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSafChange(!safExposed) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = safExposed,
                            onCheckedChange = onSafChange,
                        )
                        Text(
                            text = stringResource(R.string.pref_expose_saf_name),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onStorageChange(!storageExposed) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = storageExposed,
                            onCheckedChange = onStorageChange,
                        )
                        Text(
                            text = stringResource(R.string.pref_expose_storage_name),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dialog_actions_heading),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                // Spacer(modifier = Modifier.height(4.dp))
                if (showFormat) {
                    Text(
                        text = stringResource(R.string.pref_format_image_name),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { confirmFormat = true }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
                Text(
                    text = if (isMultipart) stringResource(R.string.pref_change_partition_name)
                    else stringResource(R.string.pref_partition_info_name),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onChangePartition() }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
                Text(
                    text = stringResource(R.string.dialog_remove_image),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onRemove() }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_close))
            }
        },
    )
}

@Composable
fun BindDirDialog(
    currentDir: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentDir) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.dialog_bind_dir_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dialog_bind_dir_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )

}
