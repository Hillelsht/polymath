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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.R
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
    val gambitGames: Int = 0,
    /** Whether at least one game has ended in a win — [SmartRepository.saveRun] scores a win 1
     * and everything else 0, so the running best across games is exactly this, with no need for
     * a dedicated wins counter. */
    val gambitEverWon: Boolean = false,
)

class PlayViewModel(private val repository: SmartRepository) : ViewModel() {

    private val chainsToday = MutableStateFlow(false to false)

    val state = combine(
        repository.bestScore(GameId.CLIMB),
        repository.runCount(GameId.CLIMB),
        repository.recentDailies(GameId.CHAINS),
        chainsToday,
        combine(
            repository.runCount(GameId.GAMBIT),
            repository.bestScore(GameId.GAMBIT),
        ) { gambitGames, gambitBest ->
            gambitGames to (gambitBest > 0)
        },
    ) { best, runs, dailies, (available, played), gambit ->
        PlayUiState(
            climbBest = best,
            climbRuns = runs,
            chainsPlayedToday = played,
            chainsAvailable = available,
            chainsStreak = dailyStreak(dailies.map { it.date }),
            gambitGames = gambit.first,
            gambitEverWon = gambit.second,
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
    onGambit: () -> Unit,
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
                    stringResource(R.string.tab_play),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(R.string.play_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            GameCard(
                title = GameId.CLIMB.localizedTitle(),
                blurb = GameId.CLIMB.localizedBlurb(),
                footnote = when {
                    state.climbRuns == 0 -> stringResource(R.string.play_climb_never)
                    else -> stringResource(R.string.play_climb_summary, state.climbBest, state.climbRuns)
                },
                callToAction = stringResource(
                    if (state.climbRuns == 0) R.string.play_climb_cta_start else R.string.play_climb_cta_again,
                ),
                gradient = listOf(SmartPalette.Iris, SmartPalette.IrisBright),
                onClick = onClimb,
            ) { ClimbGlyph() }
        }

        item {
            GameCard(
                title = GameId.CHAINS.localizedTitle(),
                blurb = GameId.CHAINS.localizedBlurb(),
                footnote = when {
                    !state.chainsAvailable -> stringResource(R.string.play_chains_none)
                    state.chainsPlayedToday -> if (state.chainsStreak > 1) {
                        stringResource(R.string.play_chains_done_with_streak, state.chainsStreak)
                    } else {
                        stringResource(R.string.play_chains_done)
                    }

                    state.chainsStreak > 0 ->
                        stringResource(R.string.play_chains_waiting_with_streak, state.chainsStreak)

                    else -> stringResource(R.string.play_chains_waiting)
                },
                callToAction = stringResource(
                    if (state.chainsPlayedToday) R.string.play_chains_cta_see else R.string.play_chains_cta_play,
                ),
                gradient = listOf(SmartPalette.Mint, SmartPalette.Success),
                enabled = state.chainsAvailable,
                onClick = onChains,
            ) { ChainsGlyph() }
        }

        item {
            GameCard(
                title = GameId.GAMBIT.localizedTitle(),
                blurb = GameId.GAMBIT.localizedBlurb(),
                footnote = when {
                    state.gambitGames == 0 -> stringResource(R.string.play_gambit_never)
                    state.gambitEverWon ->
                        stringResource(R.string.play_gambit_summary_won, state.gambitGames)

                    else -> stringResource(R.string.play_gambit_summary, state.gambitGames)
                },
                callToAction = stringResource(
                    if (state.gambitGames == 0) R.string.play_gambit_cta_start else R.string.play_gambit_cta_resume,
                ),
                gradient = listOf(Color(0xFF56A0FF), SmartPalette.Iris),
                onClick = onGambit,
            ) { GambitGlyph() }
        }

        item {
            GameCard(
                title = GameId.QUIZ.localizedTitle(),
                blurb = GameId.QUIZ.localizedBlurb(),
                footnote = stringResource(R.string.play_quiz_footnote),
                callToAction = stringResource(R.string.play_quiz_cta),
                gradient = listOf(SmartPalette.Warning, Color(0xFFFF9F5A)),
                onClick = onQuiz,
            ) { QuizGlyph() }
        }
    }
}

/**
 * [GameId.title] and [GameId.blurb] live in the domain layer as plain English, since that layer
 * is shared verbatim with `enginetests` and cannot reference generated Android resources. These
 * resolve the same game to the current locale's text. Kept local to this file because GameId is
 * only ever displayed here.
 */
@Composable
private fun GameId.localizedTitle(): String = stringResource(
    when (this) {
        GameId.CLIMB -> R.string.game_climb_title
        GameId.CHAINS -> R.string.game_chains_title
        GameId.GAMBIT -> R.string.game_gambit_title
        GameId.QUIZ -> R.string.game_quiz_title
    },
)

@Composable
private fun GameId.localizedBlurb(): String = stringResource(
    when (this) {
        GameId.CLIMB -> R.string.game_climb_blurb
        GameId.CHAINS -> R.string.game_chains_blurb
        GameId.GAMBIT -> R.string.game_gambit_blurb
        GameId.QUIZ -> R.string.game_quiz_blurb
    },
)

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
                    // Weighted (and capped to its natural size with fill = false) so a long
                    // footnote — Quiz's is the longest in the set — wraps within its own share
                    // of the row instead of leaving the unweighted call-to-action text almost no
                    // width to measure against, which is what was folding it one letter per line.
                    Text(
                        footnote,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (enabled) callToAction else stringResource(R.string.play_cta_disabled),
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
private fun GambitGlyph() {
    // The same Unicode chess set the board itself draws with — vector, in every system font,
    // nothing to ship.
    Text("♞", fontSize = 46.sp, color = Color.White)
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
