// SPDX-License-Identifier: GPL-3.0-or-later

@file:Suppress("AssignedValueIsNeverRead")

package org.codeberg.aimapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.codeberg.aimapp.R
import org.codeberg.aimapp.utils.mounts.PartitionEntry
import org.codeberg.aimapp.utils.mounts.PartitionScheme

@OptIn(ExperimentalMaterial3Api::class)
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
    bindDir: String? = null,
    onBindDirChange: () -> Unit = {},
    onBindDirReset: () -> Unit = {},
) {
    var confirmFormat by remember { mutableStateOf(false) }
    val sheetState = rememberExpandedSheetState()
    if (confirmFormat) {
        ModalBottomSheet(
            onDismissRequest = { confirmFormat = false }, sheetState = rememberExpandedSheetState()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_format_message, title)
                )
                SectionHeader(stringResource(R.string.dialog_format_fs_label))
                listOf("ext4", "exFAT").forEachIndexed { index, fsType ->
                    Spacer(modifier = Modifier.height(GroupedListSpacing))
                    GroupedRow(
                        position = positionFor(index + 1, 2),
                        onClick = { confirmFormat = false; onFormat(fsType) },
                    ) {
                        Text(
                            text = fsType,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(GroupedListSpacing),
        ) {
            Text(
                text = title, style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SectionHeader(stringResource(R.string.dialog_expose_heading))
            GroupedRow(
                position = positionFor(1, 2),
                onClick = { onSafChange(!safExposed) },
            ) {
                Text(
                    text = stringResource(R.string.pref_expose_saf_name),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = safExposed,
                    thumbContent = {
                        Icon(
                            imageVector = if (safExposed) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    },
                    onCheckedChange = { HapticPatterns.tap(); onSafChange(!safExposed) },
                    modifier = Modifier
                        .height(21.dp)
                        .aspectRatio(2f)
                        .wrapContentSize(Alignment.Center)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            GroupedRow(
                position = positionFor(2, 2),
                onClick = { onStorageChange(!storageExposed) },
            ) {
                Text(
                    text = stringResource(R.string.pref_expose_storage_name),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = storageExposed,
                    thumbContent = {
                        Icon(
                            imageVector = if (storageExposed) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    },
                    onCheckedChange = { HapticPatterns.tap(); onStorageChange(!storageExposed) },
                    modifier = Modifier
                        .height(21.dp)
                        .aspectRatio(2f)
                        .wrapContentSize(Alignment.Center)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            SectionHeader(stringResource(R.string.dialog_bind_dir_custom_name))
            GroupedRow(
                position = CardPosition.Solo,
                onClick = { onBindDirChange() },
                onLongClick = {
                    if (bindDir != null) onBindDirReset()
                },
            ) {
                Text(
                    text = bindDir ?: stringResource(R.string.dialog_use_custom_bind),
                )
            }
            if (bindDir != null) {
                SectionHeader(stringResource(R.string.dialog_bind_dir_reset_hint))
            }
            SectionHeader(stringResource(R.string.dialog_actions_heading))
            val actionCount = (if (showFormat) 1 else 0) + 2
            var actionIndex = 0
            if (showFormat) {
                actionIndex += 1
                GroupedRow(
                    position = positionFor(actionIndex, actionCount),
                    onClick = { confirmFormat = true },
                ) {
                    Text(
                        text = stringResource(R.string.pref_format_image_name),
                    )
                }
            }
            actionIndex += 1
            GroupedRow(
                position = positionFor(actionIndex, actionCount),
                onClick = { onChangePartition() },
            ) {
                Text(
                    text = if (isMultipart) stringResource(R.string.pref_change_partition_name)
                    else stringResource(R.string.pref_partition_info_name),
                )
            }
            actionIndex += 1
            GroupedRow(
                position = positionFor(actionIndex, actionCount),
                onClick = { onRemove() },
            ) {
                Text(
                    text = stringResource(R.string.dialog_remove_image),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindDirDialog(
    currentDir: String,
    isGlobal: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val sheetState = rememberExpandedSheetState()
    var text by remember { mutableStateOf(currentDir) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.dialog_bind_dir_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(
                    if (isGlobal) R.string.dialog_bind_dir_description_global
                    else R.string.dialog_bind_dir_description_single
                ),
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
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { HapticPatterns.tap(); onConfirm(text) },
                    enabled = text.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.dialog_save))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp),
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberExpandedSheetState() = rememberBottomSheetState(
    initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartitionPickerDialog(
    title: String,
    partitions: List<PartitionEntry>,
    totalSizeBytes: Long,
    scheme: PartitionScheme = PartitionScheme.MBR,
    initialSelectedIndex: Int? = null,
    isMountFlow: Boolean,
    onDismiss: () -> Unit,
    onSelect: (PartitionEntry) -> Unit,
) {
    val multipart = partitions.size >= 2
    val savedIndex =
        initialSelectedIndex?.let { idx -> partitions.indexOfFirst { it.index == idx } }
            ?.takeIf { it >= 0 }
    val sheetState = rememberExpandedSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = true,
            shouldDismissOnClickOutside = !isMountFlow,
        ),
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column {
                Text(
                    text = "$title (${scheme.name})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.dialog_partition_description, partitions.size, partitions.size
                    )
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val barTotal =
                    if (totalSizeBytes > 0) totalSizeBytes else partitions.maxOfOrNull { it.offsetBytes + it.sizeBytes }
                        ?: 1L
                DiskBar(partitions = partitions, totalBytes = barTotal)
                Spacer(modifier = Modifier.height(8.dp))
                partitions.forEachIndexed { index, part ->
                    val totalPartitions = partitions.size
                    val supported = part.detectedFs != null
                    val selected = if (multipart) supported && savedIndex == index else supported
                    val enabled = supported && multipart
                    GroupedRow(
                        position = positionFor(index + 1, totalPartitions),
                        onClick = { onSelect(part) },
                        enabled = enabled,
                        selected = selected
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            enabled = enabled,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = part.label ?: stringResource(
                                        R.string.partition_label_part, part.index
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "(${part.typeName})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (part.bootable) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.dialog_partition_boot),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (!supported) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.dialog_partition_unsupported),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Row {
                                Text(
                                    text = formatSize(part.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val fraction =
                                if (barTotal > 0) (part.sizeBytes.toFloat() / barTotal).coerceIn(
                                    0.01f, 1f
                                ) else 0.01f
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.secondary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DiskBar(partitions: List<PartitionEntry>, totalBytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        partitions.forEachIndexed { _, part ->
            val weight = (part.sizeBytes.toFloat() / totalBytes).coerceAtLeast(0.02f)
            Box(
                modifier = Modifier
                    .weight(weight)
                    .height(20.dp)
                    .padding(horizontal = 0.5.dp)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (weight > 0.1f) {
                    Text(
                        text = part.label ?: part.typeName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun SnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState = hostState) { data ->
        val dismissState = rememberSwipeToDismissBoxState()
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                data.dismiss()
            }
        }
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = { },
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
        ) {
            Snackbar(snackbarData = data)
        }
    }
}
