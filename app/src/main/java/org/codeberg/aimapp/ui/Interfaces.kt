package org.codeberg.aimapp.ui

// SPDX-License-Identifier: GPL-3.0-or-later

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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

@OptIn(ExperimentalFoundationApi::class)
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .alpha(if (enabled) 1f else 0.6f)
            .background(background, shape)
            .then(
                when {
                    (onClick != null || onLongClick != null) && enabled -> Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = { HapticPatterns.tap(); onClick?.invoke() },
                        onLongClick = onLongClick,
                    )

                    else -> Modifier
                }
            )
            .padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding,
            ),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

fun Modifier.screenContentPadding(innerPadding: PaddingValues): Modifier =
    this
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(horizontal = ScreenHorizontalPadding)
