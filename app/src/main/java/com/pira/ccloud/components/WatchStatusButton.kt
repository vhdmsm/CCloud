package com.pira.ccloud.components

import com.pira.ccloud.utils.tr

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.pira.ccloud.data.model.FavoriteItem
import com.pira.ccloud.data.model.WatchStatus
import com.pira.ccloud.utils.WatchHistory

/**
 * On a movie's or series' page: "Mark as seen" (for something watched elsewhere), or, once it is
 * Seen, a button that takes it off the Seen list. Read again when the page is shown (e.g. after the player).
 */
@Composable
fun WatchStatusButton(item: FavoriteItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember(item.id, item.type) { mutableStateOf(WatchHistory.get(context, item.id, item.type)?.status) }
    LifecycleResumeEffect(item.id, item.type) {
        status = WatchHistory.get(context, item.id, item.type)?.status
        onPauseOrDispose { }
    }
    val shape = RoundedCornerShape(12.dp)
    if (status == WatchStatus.SEEN) {
        FilledTonalButton(
            onClick = {
                WatchHistory.remove(context, item.id, item.type)
                status = null
                android.widget.Toast.makeText(context, tr("Removed from Seen"), android.widget.Toast.LENGTH_SHORT).show()
            },
            shape = shape,
            modifier = modifier.focusHighlight(shape = shape, focusedScale = 1.03f)
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Seen"))
        }
    } else {
        OutlinedButton(
            onClick = {
                WatchHistory.markSeen(context, item)
                status = WatchStatus.SEEN
                android.widget.Toast.makeText(context, tr("Added to Seen"), android.widget.Toast.LENGTH_SHORT).show()
            },
            shape = shape,
            modifier = modifier.focusHighlight(shape = shape, focusedScale = 1.03f)
        ) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Mark as seen"))
        }
    }
}
