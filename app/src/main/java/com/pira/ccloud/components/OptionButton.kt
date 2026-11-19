package com.pira.ccloud.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** How strongly an [OptionButton] stands out among the others of a dialog. */
enum class OptionStyle { MAIN, NORMAL, QUIET }

/**
 * A full-width button of an options dialog. The one the remote is on turns solid in the theme's
 * color with a contrasting outline and grows a little, so it stands out against the dialog
 * whatever the theme (the stock buttons only tint slightly when focused). [OptionStyle.MAIN] marks
 * the action the dialog is mostly for, such as Play.
 */
@Composable
fun OptionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: OptionStyle = OptionStyle.NORMAL
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val container by animateColorAsState(
        when {
            focused -> colors.primary
            style == OptionStyle.MAIN -> colors.primaryContainer
            style == OptionStyle.NORMAL -> colors.surfaceVariant
            else -> Color.Transparent
        },
        tween(150),
        label = "optionContainer"
    )
    val content = when {
        focused -> colors.onPrimary
        style == OptionStyle.MAIN -> colors.onPrimaryContainer
        style == OptionStyle.NORMAL -> colors.onSurfaceVariant
        else -> colors.primary
    }
    val outline by animateColorAsState(if (focused) colors.onSurface else Color.Transparent, tween(150), label = "optionBorder")
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(150), label = "optionButtonScale")

    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(3.dp, outline, shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (style == OptionStyle.MAIN || focused) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
