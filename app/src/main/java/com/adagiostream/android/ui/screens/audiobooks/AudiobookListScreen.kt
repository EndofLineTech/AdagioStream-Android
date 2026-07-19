package com.adagiostream.android.ui.screens.audiobooks

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adagiostream.android.service.audiobookshelf.AbsLibraryItem
import com.adagiostream.android.service.audiobookshelf.AudiobookshelfApi

/**
 * Book list for one Audiobookshelf library (beads_adagio-59p.1.4): a
 * Continue Listening shelf on top (started-and-unfinished books, most recent
 * first), then all books sorted by title (server-side sort).
 *
 * Tapping anything — shelf card or book row — opens the detail screen;
 * direct resume-from-shelf arrives with the playback branch (59p.1.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookListScreen(
    viewModel: AudiobookListViewModel = hiltViewModel(),
    onBookClick: (itemId: String) -> Unit,
    onBack: () -> Unit,
) {
    val api by viewModel.api.collectAsStateWithLifecycle()
    val booksState by viewModel.booksState.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val continueListening by viewModel.continueListening.collectAsStateWithLifecycle()

    LaunchedEffect(api) {
        if (api != null && booksState == AbsLoadState.Idle) {
            viewModel.load()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Audiobooks") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )

        when (val s = booksState) {
            AbsLoadState.Idle,
            AbsLoadState.Loading,
            -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading audiobooks…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            AbsLoadState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No audiobooks in this library.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }

            is AbsLoadState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("Couldn't load audiobooks", style = MaterialTheme.typography.titleMedium)
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.retry() }) { Text("Retry") }
                    }
                }
            }

            AbsLoadState.Loaded -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (continueListening.isNotEmpty()) {
                        item {
                            Text(
                                text = "Continue Listening",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            ContinueListeningShelf(
                                items = continueListening,
                                api = api,
                                onBookClick = onBookClick,
                            )
                            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        }
                    }

                    items(books, key = { it.id }) { book ->
                        AudiobookRow(
                            book = book,
                            api = api,
                            onClick = { onBookClick(book.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }
        }
    }
}

/** Horizontal shelf of started-and-unfinished books, mirroring iOS. */
@Composable
private fun ContinueListeningShelf(
    items: List<AbsLibraryItem>,
    api: AudiobookshelfApi?,
    onBookClick: (itemId: String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        items(items, key = { it.id }) { book ->
            Column(
                modifier = Modifier
                    .width(120.dp)
                    .clickable(onClick = { onBookClick(book.id) }),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AudiobookCover(api = api, itemId = book.id, size = 120.dp, coverWidth = 240)
                Text(
                    text = book.media?.metadata?.title ?: "Untitled",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { book.bookProgress.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AudiobookRow(
    book: AbsLibraryItem,
    api: AudiobookshelfApi?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AudiobookCover(api = api, itemId = book.id, size = 44.dp, coverWidth = 88)

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = book.media?.metadata?.title ?: "Untitled",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            book.media?.metadata?.displayAuthor?.takeIf { it.isNotBlank() }?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            progressBadge(book)?.let { badge ->
                Text(
                    text = badge,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
