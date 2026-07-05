package com.adagiostream.android.ui.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.ui.components.RetryableAsyncImage
import com.adagiostream.android.ui.screens.epg.EPGListingItem

/**
 * Full multi-channel program guide (Android port of iOS's channel-list-with-programs guide).
 * Every visible channel is listed with its current program emphasized (via the same
 * [EPGListingItem] the per-channel bottom sheet uses) plus a short look-ahead. Tapping a channel
 * row or its current program tunes to that channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    viewModel: GuideViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onChannelTuned: () -> Unit = {},
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Program Guide") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val hasAnyChannel = groups.any { it.channels.isNotEmpty() }
            when {
                isLoading && !hasAnyChannel -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                !hasAnyChannel -> {
                    Text(
                        text = "No channels available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        groups.forEach { group ->
                            if (group.channels.isNotEmpty()) {
                                item(key = "guide_header_${group.name}") {
                                    Text(
                                        text = group.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                items(
                                    items = group.channels,
                                    key = { "guide_channel_${it.channel.id}" },
                                ) { info ->
                                    GuideChannelRow(
                                        info = info,
                                        onTune = {
                                            viewModel.playChannel(info.channel)
                                            onChannelTuned()
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideChannelRow(info: GuideChannelInfo, onTune: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTune)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.channel.logoURL != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF303030)),
                    contentAlignment = Alignment.Center,
                ) {
                    RetryableAsyncImage(
                        model = info.channel.logoURL,
                        contentDescription = info.channel.name,
                        modifier = Modifier.size(40.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Radio,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = info.channel.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (info.current != null) {
            EPGListingItem(entry = info.current)
        } else {
            Text(
                text = "No program data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (info.upcoming.isNotEmpty()) {
            Text(
                text = "Up next: " + info.upcoming.joinToString(", ") { it.title },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
