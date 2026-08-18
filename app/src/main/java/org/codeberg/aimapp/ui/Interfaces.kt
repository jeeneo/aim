package org.codeberg.aimapp.ui

// SPDX-License-Identifier: GPL-3.0-or-later

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class CardPosition { Leading, Center, Trailing, Solo }

val GroupedListSpacing: Dp = 4.dp
val ScreenHorizontalPadding: Dp = 16.dp

fun cardShape(
    position: CardPosition, outer: Dp = 16.dp, inner: Dp = 6.dp
): RoundedCornerShape = when (position) {
    CardPosition.Leading -> RoundedCornerShape(
        topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner
    )

    CardPosition.Center -> RoundedCornerShape(6.dp)
    CardPosition.Trailing -> RoundedCornerShape(
        topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer
    )

    CardPosition.Solo -> RoundedCornerShape(outer)
}

fun buttonShape(
    position: CardPosition,
    outer: Dp = 16.dp,
    inner: Dp = 6.dp,
    isShown: Boolean,
): RoundedCornerShape = when (position) {
    CardPosition.Leading -> RoundedCornerShape(
        topStart = inner,
        topEnd = inner,
        bottomStart = if (isShown) inner else outer,
        bottomEnd = inner,
    )

    CardPosition.Center -> RoundedCornerShape(inner)
    CardPosition.Trailing -> RoundedCornerShape(
        topStart = inner,
        topEnd = inner,
        bottomStart = inner,
        bottomEnd = if (isShown) inner else outer,
    )

    CardPosition.Solo -> RoundedCornerShape(outer)
}

fun positionFor(index: Int, count: Int): CardPosition = when {
    count <= 1 -> CardPosition.Solo
    index == 1 -> CardPosition.Leading // start is 1 for simplicity
    index == count -> CardPosition.Trailing // end so trail
    else -> CardPosition.Center
}

@SuppressLint("MissingHapticFeedback")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupedRow(
    position: CardPosition,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    verticalPadding: Dp = 14.dp,
    horizontalPadding: Dp = 14.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = cardShape(position)
    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .then(
                when {
                    onClick != null || onLongClick != null -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )

                    else -> Modifier
                }
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
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
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets(0.dp),
        content = content,
    )
}

fun Modifier.screenContentPadding(innerPadding: PaddingValues): Modifier =
    this
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(horizontal = ScreenHorizontalPadding)
