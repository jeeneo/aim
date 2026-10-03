// SPDX-License-Identifier: GPL-3.0-or-later

package org.codeberg.aimapp.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val GroupedListSpacing: Dp = 2.dp
val ScreenHorizontalPadding: Dp = 16.dp

@Composable
fun segmentedListShapes(index: Int, count: Int) = ListItemDefaults.segmentedShapes(index - 1, count)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedTooltipItem(
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    tooltip: String = "",
    enabled: Boolean = true,
    selected: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.segmentedColors(),
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val item: @Composable () -> Unit = {
        when {
            selected != null && onClick != null -> SegmentedListItem(
                selected = selected,
                onClick = onClick,
                shapes = shapes,
                modifier = modifier,
                enabled = enabled,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                supportingContent = supportingContent,
                onLongClick = onLongClick,
                colors = colors,
                content = content,
            )

            onClick != null -> SegmentedListItem(
                onClick = onClick,
                shapes = shapes,
                modifier = modifier,
                enabled = enabled,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                supportingContent = supportingContent,
                onLongClick = onLongClick,
                colors = colors,
                content = content,
            )

            else -> SegmentedListItem(
                shapes = shapes,
                modifier = modifier,
                enabled = enabled,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                supportingContent = supportingContent,
                colors = colors,
                content = content,
            )
        }
    }
    if (tooltip.isBlank()) {
        item()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Above
            ),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            item()
        }
    }
}

fun Modifier.screenContentPadding(innerPadding: PaddingValues): Modifier =
    this
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(horizontal = ScreenHorizontalPadding)
