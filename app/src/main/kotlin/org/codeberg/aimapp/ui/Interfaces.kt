package org.codeberg.aimapp.ui

// SPDX-License-Identifier: GPL-3.0-or-later

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class CardPosition { Leading, Center, Trailing, Solo }

val GroupedListSpacing: Dp = 2.dp
val ScreenHorizontalPadding: Dp = 16.dp

fun cardShape(
    position: CardPosition, outer: Dp = 16.dp, inner: Dp = 6.dp
): RoundedCornerShape = when (position) {
    CardPosition.Leading -> RoundedCornerShape(
        topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner
    )

    CardPosition.Center -> RoundedCornerShape(inner)
    CardPosition.Trailing -> RoundedCornerShape(
        topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer
    )

    CardPosition.Solo -> RoundedCornerShape(outer)
}

fun positionFor(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.Solo
    index == 1 -> CardPosition.Leading // start is 1 for simplicity
    index == count -> CardPosition.Trailing // end so trail
    else -> CardPosition.Center
}

inline fun Modifier.thenIf(condition: Boolean, factory: Modifier.() -> Modifier): Modifier =
    if (condition) factory() else this

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GroupedRow(
    position: CardPosition,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    verticalPadding: Dp = 14.dp,
    horizontalPadding: Dp = 14.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    tooltip: String = "",
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedOuter by animateDpAsState(
        targetValue = if (isPressed) 16.dp else 6.dp,
        label = "groupedRowOuterCorner",
    )
    val shape = cardShape(position, inner = animatedOuter)
    val background = if (selected) {
        MaterialTheme.colorScheme.inverseOnSurface
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val row = @Composable {
        Box(modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .alpha(if (enabled) 1f else 0.6f)
                    .background(background, shape)
                    .thenIf(enabled && (onClick != null || onLongClick != null)) {
                        combinedClickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = { HapticPatterns.tap(); onClick?.invoke() },
                            onLongClick = onLongClick,
                        )
                    }
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = verticalPadding,
                    ),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
    if (tooltip.isNotEmpty()) {
        val tooltipState = rememberTooltipState()
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(tooltip)
                }
            },
            state = tooltipState,
        ) {
            row()
        }
    } else {
        row()
    }
}

fun Modifier.screenContentPadding(innerPadding: PaddingValues): Modifier =
    this
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(horizontal = ScreenHorizontalPadding)
