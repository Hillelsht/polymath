package com.hillelsht.smart.ui.watch

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.hillelsht.smart.domain.PlaybackWatchdog
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
import kotlinx.coroutines.delay
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
    val context = LocalContext.current
    var playback by remember(videoId) { mutableStateOf<Playback>(Playback.Embedded) }

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
            when (val state = playback) {
                is Playback.HandedOff -> HandedOffStrip(state.reason)
                Playback.Embedded -> VideoSurface(
                    youtubeId = current.youtubeId,
                    onStarted = { repository.markWatched(current.id) },
                    onDuration = { seconds -> repository.recordDuration(current.youtubeId, seconds) },
                    onFailed = { failure ->
                        // Only a refusal aimed at this one video may delete it. An app-level
                        // rejection or a timeout would otherwise drain the whole shelf.
                        val verdict = PlaybackWatchdog.assess(0, sawSignal = false, failure = failure)
                        if (verdict is PlaybackWatchdog.Verdict.Failed && verdict.blocklist) {
                            repository.blockVideo(current.youtubeId, failure.name)
                        }
                        // It is about to be watched, just not here.
                        repository.markWatched(current.id)
                        playback = Playback.HandedOff(failure)
                        openOnYouTube(context, current.youtubeId)
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

/** Whether this video is playing here, or was handed to the YouTube app. */
private sealed interface Playback {
    data object Embedded : Playback
    data class HandedOff(val reason: PlaybackWatchdog.Failure) : Playback
}

/** Android routes this to the YouTube app when it is installed, and to the browser otherwise. */
private fun openOnYouTube(context: Context, youtubeId: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeId")),
        )
    }
}

/**
 * Replaces the player once the video has been sent to YouTube.
 *
 * Deliberately a slim strip rather than a full error screen: everything below it — the title,
 * the facts, the quiz button — stays exactly where it was, so coming back from YouTube lands
 * on the quiz with nothing to navigate and no dead player to look at.
 */
@Composable
private fun HandedOffStrip(reason: PlaybackWatchdog.Failure) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.OpenInNew,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Playing on YouTube",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            Text(
                text = when (reason) {
                    // Only this case has actually removed anything, so only it says so.
                    PlaybackWatchdog.Failure.VIDEO_REFUSED ->
                        "This one will not play inside apps. Removed from your shelf."
                    PlaybackWatchdog.Failure.APP_REJECTED ->
                        "YouTube would not play it here. It stays on your shelf."
                    PlaybackWatchdog.Failure.SILENT ->
                        "It would not start here. It stays on your shelf."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    onFailed: suspend (PlaybackWatchdog.Failure) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Survives recomposition, and the listener reads the same instance the factory captured.
    val started = remember { AtomicBoolean(false) }
    // Anything from YouTube proving it served the video. Error 152 never reports itself, so
    // this flag not flipping is the only evidence the app gets that playback failed.
    val sawSignal = remember(youtubeId) { AtomicBoolean(false) }
    // Whichever path fires first wins; the other becomes a no-op.
    val settled = remember(youtubeId) { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()

    val report: (PlaybackWatchdog.Failure) -> Unit = { failure ->
        if (settled.compareAndSet(false, true)) scope.launch { onFailed(failure) }
    }

    LaunchedEffect(youtubeId) {
        delay(PlaybackWatchdog.DEADLINE_MS)
        val verdict = PlaybackWatchdog.assess(
            elapsedMs = PlaybackWatchdog.DEADLINE_MS,
            sawSignal = sawSignal.get(),
        )
        if (verdict is PlaybackWatchdog.Verdict.Failed) report(verdict.reason)
    }

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
                            // Any of these proves YouTube answered, which calls the watchdog off.
                            if (state == PlayerConstants.PlayerState.VIDEO_CUED ||
                                state == PlayerConstants.PlayerState.BUFFERING ||
                                state == PlayerConstants.PlayerState.PLAYING
                            ) {
                                sawSignal.set(true)
                            }
                            if (state == PlayerConstants.PlayerState.PLAYING &&
                                started.compareAndSet(false, true)
                            ) {
                                scope.launch { onStarted() }
                            }
                        }

                        /**
                         * The errors YouTube *does* report. Error 152 is not among them — it is
                         * drawn by the player itself in silence, which is what the watchdog
                         * above exists to catch.
                         */
                        override fun onError(
                            youTubePlayer: YouTubePlayer,
                            error: PlayerConstants.PlayerError,
                        ) {
                            when (error) {
                                PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER,
                                PlayerConstants.PlayerError.VIDEO_NOT_FOUND,
                                -> report(PlaybackWatchdog.Failure.VIDEO_REFUSED)

                                // YouTube rejected this app's right to embed anything, not this
                                // video. Blocklisting on it would empty the shelf card by card.
                                PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER,
                                -> report(PlaybackWatchdog.Failure.APP_REJECTED)

                                else -> Unit
                            }
                        }

                        /**
                         * Feeds carry no duration; the player does, once the video loads — which
                         * also makes this the earliest hard proof that playback is alive.
                         */
                        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                            sawSignal.set(true)
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
