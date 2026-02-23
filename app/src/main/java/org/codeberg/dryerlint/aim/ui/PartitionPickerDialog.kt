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

package org.codeberg.dryerlint.aim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.codeberg.dryerlint.aim.R
import org.codeberg.dryerlint.aim.utils.PartitionEntry
import org.codeberg.dryerlint.aim.utils.PartitionScheme

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

@Composable
fun PartitionPickerDialog(
    title: String,
    partitions: List<PartitionEntry>,
    totalSizeBytes: Long,
    scheme: PartitionScheme = PartitionScheme.MBR,
    initialSelectedIndex: Int? = null,
    onDismiss: () -> Unit,
    onSelect: (PartitionEntry) -> Unit,
) {
    val infoOnly = partitions.size == 1
    val savedIndex =
        initialSelectedIndex?.let { idx -> partitions.indexOfFirst { it.index == idx } }
            ?.takeIf { it >= 0 }
    val firstMountable = partitions.indexOfFirst { it.detectedFs != null }
    var selected by remember {
        mutableIntStateOf(
            savedIndex ?: (if (firstMountable >= 0) firstMountable else 0)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "$title (${scheme.name})", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.dialog_partition_description, partitions.size, partitions.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val barTotal =
                    if (totalSizeBytes > 0) totalSizeBytes else partitions.maxOfOrNull { it.offsetBytes + it.sizeBytes }
                        ?: 1L
                DiskBar(partitions = partitions, totalBytes = barTotal)
                Spacer(modifier = Modifier.height(8.dp))
                partitions.forEachIndexed { idx, part ->
                    val mountable = part.detectedFs != null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (!infoOnly && mountable) Modifier.clickable { selected = idx }
                            else Modifier.alpha(0.45f))
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                            RadioButton(
                                selected = selected == idx,
                                onClick = if (!infoOnly && mountable) ({
                                    selected = idx
                                }) else null,
                                enabled = !infoOnly && mountable,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = part.label ?: stringResource(
                                        R.string.partition_label_part, part.index
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = part.typeName,
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
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Row {
                                Text(
                                    text = formatSize(part.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                val fsLabel = part.detectedFs?.mountType
                                    ?: stringResource(R.string.dialog_partition_unsupported)
                                Text(
                                    text = fsLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (mountable) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val fraction =
                                if (barTotal > 0) (part.sizeBytes.toFloat() / barTotal).coerceIn(
                                    0.01f, 1f
                                ) else 0.01f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (mountable) partitionColor(idx)
                                        else MaterialTheme.colorScheme.outlineVariant
                                    ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (infoOnly) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.dialog_ok))
                }
            } else {
                TextButton(
                    onClick = { onSelect(partitions[selected]) },
                    enabled = partitions.getOrNull(selected)?.detectedFs != null,
                ) {
                    Text(text = stringResource(R.string.dialog_partition_mount))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.dialog_cancel))
            }
        },
    )
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
        partitions.forEachIndexed { idx, part ->
            val weight = (part.sizeBytes.toFloat() / totalBytes).coerceAtLeast(0.02f)
            Box(
                modifier = Modifier
                    .weight(weight)
                    .height(20.dp)
                    .padding(horizontal = 0.5.dp)
                    .background(partitionColor(idx)),
                contentAlignment = Alignment.Center,
            ) {
                if (weight > 0.08f) {
                    Text(
                        text = part.label ?: stringResource(
                            R.string.partition_bar_label, part.index
                        ),
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
private fun partitionColor(index: Int) = when (index % 5) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    2 -> MaterialTheme.colorScheme.secondary
    3 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.inversePrimary
}
