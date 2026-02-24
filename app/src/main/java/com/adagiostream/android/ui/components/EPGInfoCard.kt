package com.adagiostream.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adagiostream.android.model.EPGEntry
import com.adagiostream.android.util.DateUtils

@Composable
fun EPGInfoCard(
    entries: List<EPGEntry>,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    val currentProgram = entries.firstOrNull { it.isCurrentlyAiring }
    val upcoming = entries.filter { it.isUpcoming }.take(3)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (currentProgram != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ) {
                        Text("LIVE")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentProgram.title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                val timeRange = "${DateUtils.formatTime(currentProgram.start)} - ${DateUtils.formatTime(currentProgram.end)}"
                Text(
                    text = timeRange,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                currentProgram.description?.let { desc ->
                    if (desc.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                }
            }

            if (upcoming.isNotEmpty()) {
                if (currentProgram != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                upcoming.forEach { entry ->
                    Text(
                        text = "${DateUtils.formatTime(entry.start)} ${entry.title}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
