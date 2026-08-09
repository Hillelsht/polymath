package com.hillelsht.smart.ui.watch

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Video
import com.hillelsht.smart.ui.components.CategoryChip
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.components.SmartCard
import com.hillelsht.smart.ui.components.accent
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays one curated video and turns it into study material.
 *
 * Playback uses YouTube's official IFrame player, which is what keeps this within their terms
 * — but deliberately *only* the player: no search, no recommendations, no comments, no
 * end-screen suggestions to wander off into. The only way into this screen is a video someone
 * put on the allowlist.
 */
@Composable
fun VideoPlayerScreen(
    repository: SmartRepository,
    videoId: String,
    onBack: () -> Unit,
    onQuiz: (List<String>, Category) -> Unit,
) {
    val video by produceState<Video?>(initialValue = null, videoId) {
        value = repository.video(videoId)
    }
    val current = video
    var blocked by remember(videoId) { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(title = "Video unavailable", body = "This video is no longer in the catalog.")
            }
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (blocked) {
                UnplayableNotice(current.youtubeId)
            } else {
                VideoSurface(
                    youtubeId = current.youtubeId,
                    onStarted = { repository.markWatched(current.id) },
                    onDuration = { seconds -> repository.recordDuration(current.youtubeId, seconds) },
                    onUnplayable = {
                        repository.blockVideo(current.youtubeId, it)
                        blocked = true
                    },
                )
            }

            Column(Modifier.padding(20.dp)) {
                CategoryChip(current.category)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = listOfNotNull(
                        current.channel,
                        current.minutes?.let { "$it min" },
                        current.lengthClass?.label,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (current.relatedFactIds.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    RelatedFacts(repository, current.relatedFactIds)
                }

                // The button is always offered. Where the pipeline matched specific facts the
                // quiz asks about exactly those; otherwise it falls back to the video's
                // subject, so watching always ends in recall rather than sometimes trailing off.
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { onQuiz(current.relatedFactIds, current.category) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = if (current.relatedFactIds.isNotEmpty()) {
                            "Quiz me on this"
                        } else {
                            "Quiz me on ${current.category.displayName}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Anything you miss joins your review queue.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Shown when YouTube refuses to embed a video (its error 152).
 *
 * The video has already been removed from the shelf by this point, so this is the last time
 * it will ever be seen; the message says so rather than inviting a pointless retry.
 */
@Composable
private fun UnplayableNotice(youtubeId: String) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "This channel does not allow playback outside YouTube",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Removed from your shelf, so it will not come up again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=$youtubeId"),
                        ),
                    )
                }
            },
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("Open in YouTube")
        }
    }
}

/**
 * The IFrame player, bound to the composition's lifecycle.
 *
 * Handing the view to the lifecycle is what releases the underlying WebView when the screen
 * goes away — without it the player keeps playing audio after you navigate back.
 */
@Composable
private fun VideoSurface(
    youtubeId: String,
    onStarted: suspend () -> Unit,
    onDuration: suspend (Int) -> Unit,
    onUnplayable: suspend (String) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Survives recomposition, and the listener reads the same instance the factory captured.
    val started = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        factory = { context ->
            YouTubePlayerView(context).apply {
                lifecycleOwner.lifecycle.addObserver(this)
                addYouTubePlayerListener(
                    object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            youTubePlayer.cueVideo(youtubeId, 0f)
                        }

                        override fun onStateChange(
                            youTubePlayer: YouTubePlayer,
                            state: PlayerConstants.PlayerState,
                        ) {
                            if (state == PlayerConstants.PlayerState.PLAYING &&
                                started.compareAndSet(false, true)
                            ) {
                                scope.launch { onStarted() }
                            }
                        }

                        // The feed cannot say whether a video allows embedding, so the app
                        // finds out here — once — and never offers it again.
                        override fun onError(
                            youTubePlayer: YouTubePlayer,
                            error: PlayerConstants.PlayerError,
                        ) {
                            if (error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER ||
                                error == PlayerConstants.PlayerError.VIDEO_NOT_FOUND
                            ) {
                                scope.launch { onUnplayable(error.name) }
                            }
                        }

                        /** Feeds carry no duration; the player does, once the video loads. */
                        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                            scope.launch { onDuration(duration.toInt()) }
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun RelatedFacts(repository: SmartRepository, factIds: List<String>) {
    val facts by produceState<List<Fact>>(initialValue = emptyList(), factIds) {
        value = repository.factsByIds(factIds)
    }
    if (facts.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "WHAT THIS TEACHES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        facts.forEach { fact ->
            SmartCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                Column {
                    Text(
                        text = fact.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = fact.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = fact.category.accent(),
                    )
                }
            }
        }
    }
}
