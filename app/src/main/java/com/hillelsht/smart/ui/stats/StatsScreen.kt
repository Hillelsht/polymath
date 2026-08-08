package com.hillelsht.smart.ui.stats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.data.local.QuizResultEntity
import com.hillelsht.smart.domain.CategoryMastery
import com.hillelsht.smart.domain.MasteryCalculator
import com.hillelsht.smart.domain.Streak
import com.hillelsht.smart.ui.components.SmartCard
import com.hillelsht.smart.ui.components.StatTile
import com.hillelsht.smart.ui.components.accent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val mastery: List<CategoryMastery> = emptyList(),
    val streak: Streak = Streak(0, 0, false),
    val quizzes: List<QuizResultEntity> = emptyList(),
) {
    val overall: Float get() = MasteryCalculator.overall(mastery)
    val mastered: Int get() = mastery.sumOf { it.masteredFacts }
    val seen: Int get() = mastery.sumOf { it.seenFacts }
    val total: Int get() = mastery.sumOf { it.totalFacts }

    /** Lifetime quiz accuracy, which is the closest thing to a "how good at trivia am I" number. */
    val quizAccuracy: Float?
        get() {
            val answered = quizzes.sumOf { it.total }
            if (answered == 0) return null
            return quizzes.sumOf { it.correct }.toFloat() / answered
        }
}

class StatsViewModel(repository: SmartRepository) : ViewModel() {
    val state = combine(
        repository.mastery,
        repository.streak,
        repository.recentQuizzes,
    ) { mastery, streak, quizzes ->
        StatsUiState(mastery = mastery, streak = streak, quizzes = quizzes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { StatsViewModel(repository) }
        }
    }
}

@Composable
fun StatsScreen(repository: SmartRepository) {
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(repository))
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
            Text(
                text = "Progress",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        item {
            SmartCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatTile(
                        value = "${(state.overall * 100).toInt()}%",
                        caption = "Mastery",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.mastered.toString(),
                        caption = "Facts mastered",
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = state.streak.current.toString(),
                        caption = "Day streak",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SmartCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = "Quiz accuracy",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.quizAccuracy
                            ?.let { "${(it * 100).toInt()}% across ${state.quizzes.size} quizzes" }
                            ?: "Take your first quiz to start tracking this.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Longest streak: ${state.streak.longest} days · " +
                            "${state.seen} of ${state.total} facts encountered",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text(
                text = "Mastery by subject",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        items(state.mastery, key = { it.category.id }) { entry ->
            MasteryBar(entry)
        }
    }
}

/**
 * A horizontal bar per subject.
 *
 * Bars beat rings here: side by side at a common baseline, they make it obvious at a glance
 * which subject is lagging — which is exactly the decision this screen exists to support.
 */
@Composable
private fun MasteryBar(entry: CategoryMastery) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = entry.category.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${entry.masteredFacts}/${entry.totalFacts}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(entry.mastery.coerceIn(0f, 1f))
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(entry.category.accent()),
            )
        }
    }
}
