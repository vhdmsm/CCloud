package com.pira.ccloud.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.pira.ccloud.utils.DeviceUtils
import kotlinx.coroutines.delay

private const val FOCUS_ANIMATION_MS = 150

/**
 * How the remote's focus shows on a card, as on Netflix: the focused card grows a little, gets a
 * white outline and a shadow, and is drawn over its neighbours. Put it before the card's
 * clickable (it watches the focus of what follows).
 */
fun Modifier.focusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.08f,
    outlineWidth: Dp = 3.dp
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(FOCUS_ANIMATION_MS), label = "focusScale")
    val outline by animateColorAsState(if (focused) Color.White else Color.Transparent, tween(FOCUS_ANIMATION_MS), label = "focusOutline")
    val shadow by animateDpAsState(if (focused) 16.dp else 0.dp, tween(FOCUS_ANIMATION_MS), label = "focusShadow")
    this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .shadow(shadow, shape, clip = false)
        .border(outlineWidth, outline, shape)
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
}

/**
 * On a TV, when a list screen opens, moves the remote's focus onto the item at [index] (the first,
 * or the one opened before going back) once the list has items, scrolling to it first. Runs
 * again only when the screen opens again, not when the list is replaced (another sort, or the
 * new ranking of a saved list).
 */
@Composable
fun FocusListOnOpen(gridState: LazyGridState, requester: FocusRequester, index: Int, hasItems: Boolean) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(hasItems) {
        if (!hasItems || done || !DeviceUtils.isTv(context)) return@LaunchedEffect
        done = true
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
        // The item may not be placed yet in this frame
        repeat(20) {
            try {
                requester.requestFocus()
                return@LaunchedEffect
            } catch (e: IllegalStateException) {
                delay(50)
            }
        }
    }
}

/**
 * On a TV, moves the remote's focus to [requester] once [ready], when a screen opens (e.g. a
 * movie's first quality, as Netflix starts on Play), instead of leaving it on the sidebar.
 * [bringIntoReach] scrolls the target into a lazy list first, where it may not be composed yet.
 */
@Composable
fun FocusWhenReady(requester: FocusRequester, ready: Boolean, bringIntoReach: suspend () -> Unit = {}) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (!ready || done || !DeviceUtils.isTv(context)) return@LaunchedEffect
        done = true
        bringIntoReach()
        repeat(20) {
            try {
                requester.requestFocus()
                return@LaunchedEffect
            } catch (e: IllegalStateException) {
                delay(50)
            }
        }
    }
}
