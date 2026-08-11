package com.hillelsht.smart.ui.play

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.play.GameId
import com.hillelsht.smart.ui.components.SmartCard
import com.hillelsht.smart.ui.theme.SmartPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PlayUiState(
    val climbBest: Int = 0,
    val climbRuns: Int = 0,
    val chainsPlayedToday: Boolean = false,
    val chainsAvailable: Boolean = false,
    val chainsStreak: Int = 0,
)

class PlayViewModel(private val repository: SmartRepository) : ViewModel() {

    private val chainsToday = MutableStateFlow(false to false)

    val state = combine(
        repository.bestScore(GameId.CLIMB),
        repository.runCount(GameId.CLIMB),
        repository.recentDailies(GameId.CHAINS),
        chainsToday,
    ) { best, runs, dailies, (available, played) ->
        PlayUiState(
            climbBest = best,
            climbRuns = runs,
            chainsPlayedToday = played,
            chainsAvailable = available,
            chainsStreak = dailyStreak(dailies.map { it.date }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayUiState())

    init {
        viewModelScope.launch {
            // One small fetch when the tab opens, so a grid published this morning is playable
            // this morning. Failure leaves the card saying there is nothing today, which is the
            // truth from the phone's point of view.
            val today = LocalDate.now()
            val puzzle = runCatching { repository.chainsPuzzle(today) }.getOrNull()
            val done = runCatching { repository.dailyResult(GameId.CHAINS, today) }.getOrNull()
            chainsToday.value = (puzzle != null) to (done != null)
        }
    }

    /** Consecutive days ending today or yesterday, so a streak survives until the day is out. */
    private fun dailyStreak(days: List<LocalDate>): Int {
        if (days.isEmpty()) return 0
        val played = days.toSortedSet().reversed()
        val today = LocalDate.now()
        var cursor = when (played.first()) {
            today, today.minusDays(1) -> played.first()
            else -> return 0
        }
        var streak = 0
        played.forEach { day ->
            if (day == cursor) {
                streak++
                cursor = cursor.minusDays(1)
            }
        }
        return streak
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { PlayViewModel(repository) }
        }
    }
}

@Composable
fun PlayScreen(
    repository: SmartRepository,
    onClimb: () -> Unit,
    onChains: () -> Unit,
    onQuiz: () -> Unit,
) {
    val viewModel: PlayViewModel = viewModel(factory = PlayViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    "Play",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Everything you know, put to work",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            GameCard(
                title = GameId.CLIMB.title,
                blurb = GameId.CLIMB.blurb,
                footnote = when {
                    state.climbRuns == 0 -> "Never climbed"
                    else -> "Best ${state.climbBest} · ${state.climbRuns} runs"
                },
                callToAction = if (state.climbRuns == 0) "Start climbing" else "Climb again",
                gradient = listOf(SmartPalette.Iris, SmartPalette.IrisBright),
                onClick = onClimb,
            ) { ClimbGlyph() }
        }

        item {
            GameCard(
                title = GameId.CHAINS.title,
                blurb = GameId.CHAINS.blurb,
                footnote = when {
                    !state.chainsAvailable -> "No grid today"
                    state.chainsPlayedToday -> "Done for today" +
                        if (state.chainsStreak > 1) " · ${state.chainsStreak} day streak" else ""

                    state.chainsStreak > 0 -> "Today's grid is waiting · ${state.chainsStreak} day streak"
                    else -> "Today's grid is waiting"
                },
                callToAction = if (state.chainsPlayedToday) "See today's grid" else "Play today's grid",
                gradient = listOf(SmartPalette.Mint, SmartPalette.Success),
                enabled = state.chainsAvailable,
                onClick = onChains,
            ) { ChainsGlyph() }
        }

        item {
            GameCard(
                title = GameId.QUIZ.title,
                blurb = GameId.QUIZ.blurb,
                footnote = "Ten questions from what you have been learning",
                callToAction = "Take a quiz",
                gradient = listOf(SmartPalette.Warning, Color(0xFFFF9F5A)),
                onClick = onQuiz,
            ) { QuizGlyph() }
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    blurb: String,
    footnote: String,
    callToAction: String,
    gradient: List<Color>,
    enabled: Boolean = true,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    SmartCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentPadding = 0.dp,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .background(
                        Brush.linearGradient(
                            colors = if (enabled) gradient else gradient.map { it.copy(alpha = 0.35f) },
                            start = Offset.Zero,
                            end = Offset(900f, 400f),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) { glyph() }

            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        footnote,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (enabled) callToAction else "Not yet",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

// Art rather than assets: a few shapes each, so the tab costs the APK nothing.

@Composable
private fun ClimbGlyph() {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(22.dp, 38.dp, 54.dp, 70.dp).forEach { height ->
            Box(
                Modifier
                    .width(18.dp)
                    .height(height)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.28f)),
            )
        }
    }
}

@Composable
private fun ChainsGlyph() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(4) { column ->
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                Color.White.copy(alpha = if ((row + column) % 3 == 0) 0.45f else 0.2f),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizGlyph() {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("?", style = MaterialTheme.typography.displaySmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
