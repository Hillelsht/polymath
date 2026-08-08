package com.hillelsht.smart.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.LengthClass
import com.hillelsht.smart.domain.model.Video
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.components.accent
import com.hillelsht.smart.ui.components.secondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WatchUiState(
    val videos: List<Video> = emptyList(),
    val watched: Set<String> = emptySet(),
    val category: Category? = null,
    val length: LengthClass? = null,
    val loaded: Boolean = false,
) {
    /** The catalog after the two filter chips are applied. */
    val visible: List<Video>
        get() = videos.filter { video ->
            (category == null || video.category == category) &&
                (length == null || video.lengthClass == length)
        }
}

class WatchViewModel(private val repository: SmartRepository) : ViewModel() {

    private val category = MutableStateFlow<Category?>(null)
    private val length = MutableStateFlow<LengthClass?>(null)
    private val loaded = MutableStateFlow(false)

    init {
        // Same pattern as content packs: check for a fresher catalog when the tab is opened,
        // which is the only moment the result is visible.
        viewModelScope.launch {
            repository.refreshVideos()
            loaded.value = true
        }
    }

    val state = combine(
        repository.videos,
        repository.watchedVideoIds,
        category,
        length,
        loaded,
    ) { videos, watched, selectedCategory, selectedLength, isLoaded ->
        WatchUiState(
            videos = videos,
            watched = watched,
            category = selectedCategory,
            length = selectedLength,
            loaded = isLoaded || videos.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchUiState())

    fun selectCategory(value: Category?) {
        category.value = if (category.value == value) null else value
    }

    fun selectLength(value: LengthClass?) {
        length.value = if (length.value == value) null else value
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { WatchViewModel(repository) }
        }
    }
}

@Composable
fun WatchScreen(repository: SmartRepository, onPlay: (Video) -> Unit) {
    val viewModel: WatchViewModel = viewModel(factory = WatchViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    text = "Watch",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Only videos that teach you something",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Category.entries.toList(), key = { "watch-cat:${it.id}" }) { category ->
                    FilterChip(
                        label = category.displayName,
                        selected = state.category == category,
                        accent = category.accent(),
                        onClick = { viewModel.selectCategory(category) },
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LengthClass.entries.forEach { length ->
                    FilterChip(
                        label = length.label,
                        selected = state.length == length,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { viewModel.selectLength(length) },
                    )
                }
            }
        }

        val visible = state.visible
        if (visible.isEmpty()) {
            item {
                EmptyState(
                    title = if (state.loaded) "Nothing matches" else "Loading the catalog…",
                    body = if (state.loaded) {
                        "No videos in this combination yet. Try clearing a filter."
                    } else {
                        "Fetching the curated list of videos."
                    },
                )
            }
        } else {
            items(visible, key = { "video:${it.id}" }) { video ->
                VideoCard(
                    video = video,
                    watched = video.id in state.watched,
                    onClick = { onPlay(video) },
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else accent,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) accent else accent.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun VideoCard(video: Video, watched: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = video.bestThumbnailUrl,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(
                                video.category.accent().copy(alpha = 0.35f),
                                video.category.secondary().copy(alpha = 0.55f),
                            ),
                        ),
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${video.minutes} min",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }

        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (watched) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Watched",
                        tint = video.category.accent(),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${video.channel} · ${video.category.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
