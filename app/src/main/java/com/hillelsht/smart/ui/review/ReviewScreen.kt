package com.hillelsht.smart.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.R
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.Rating
import com.hillelsht.smart.ui.components.CategoryChip
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.components.FactImage
import com.hillelsht.smart.ui.components.GoDeeper
import com.hillelsht.smart.ui.components.SessionProgress
import com.hillelsht.smart.ui.components.accent
import com.hillelsht.smart.ui.theme.SmartPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReviewUiState(
    val queue: List<Fact> = emptyList(),
    val completed: Int = 0,
    val revealed: Boolean = false,
    val ready: Boolean = false,
) {
    val current: Fact? get() = queue.firstOrNull()
    val remaining: Int get() = queue.size
    val total: Int get() = completed + remaining
}

/**
 * Active recall: show the question, let the learner try, then reveal and grade.
 *
 * A failed card is pushed to the back of the session queue rather than dropped, so it is seen
 * again before the session ends — that second look inside the same sitting is what converts a
 * lapse into a memory instead of a repeated failure tomorrow.
 */
class ReviewViewModel(private val repository: SmartRepository) : ViewModel() {

    private val queue = MutableStateFlow<List<Fact>?>(null)
    private val completed = MutableStateFlow(0)
    private val revealed = MutableStateFlow(false)

    val state = combine(
        repository.plan,
        repository.facts,
        queue,
        completed,
        revealed,
    ) { plan, facts, sessionQueue, done, isRevealed ->
        val resolved = sessionQueue ?: run {
            val byId = facts.associateBy { it.id }
            plan.dueFactIds.mapNotNull(byId::get).also {
                if (facts.isNotEmpty()) queue.value = it
            }
        }
        ReviewUiState(
            queue = resolved,
            completed = done,
            revealed = isRevealed,
            ready = facts.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun reveal() {
        revealed.value = true
    }

    fun grade(fact: Fact, rating: Rating) {
        viewModelScope.launch {
            repository.grade(fact.id, rating)

            val rest = queue.value.orEmpty().drop(1)
            queue.value = if (rating == Rating.AGAIN) rest + fact else rest
            if (rating != Rating.AGAIN) completed.value += 1
            revealed.value = false
        }
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { ReviewViewModel(repository) }
        }
    }
}

@Composable
fun ReviewScreen(repository: SmartRepository, onDone: () -> Unit) {
    val viewModel: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            SessionProgress(
                current = state.completed,
                total = state.total,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.review_remaining, state.remaining),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        val fact = state.current
        if (fact == null) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(
                        if (state.completed > 0) {
                            R.string.review_complete_title
                        } else {
                            R.string.review_nothing_due_title
                        },
                    ),
                    body = if (state.completed > 0) {
                        stringResource(
                            if (state.completed == 1) {
                                R.string.review_complete_body_singular
                            } else {
                                R.string.review_complete_body_plural
                            },
                            state.completed,
                        )
                    } else {
                        stringResource(R.string.review_nothing_due_body)
                    },
                    action = {
                        Button(onClick = onDone, shape = RoundedCornerShape(14.dp)) {
                            Text(stringResource(R.string.action_done))
                        }
                    },
                )
            }
        } else {
            Flashcard(
                fact = fact,
                revealed = state.revealed,
                modifier = Modifier.weight(1f),
                onReveal = viewModel::reveal,
                onGrade = { viewModel.grade(fact, it) },
            )
        }
    }
}

@Composable
private fun Flashcard(
    fact: Fact,
    revealed: Boolean,
    modifier: Modifier = Modifier,
    onReveal: () -> Unit,
    onGrade: (Rating) -> Unit,
) {
    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            CategoryChip(fact.category)
            Spacer(Modifier.height(20.dp))
            Text(
                text = fact.question,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            AnimatedVisibility(
                visible = revealed,
                enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            ) {
                Column {
                    Spacer(Modifier.height(24.dp))
                    FactImage(imageUrl = fact.imageUrl, wikiTitle = fact.wikiTitle, category = fact.category)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = fact.answer,
                        style = MaterialTheme.typography.displayMedium,
                        color = fact.category.accent(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = fact.statement,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    fact.hook?.let { hook ->
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(fact.category.accent().copy(alpha = 0.12f))
                                .padding(14.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Lightbulb,
                                contentDescription = null,
                                tint = fact.category.accent(),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = hook,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    GoDeeper(
                        details = fact.details,
                        pageUrl = fact.pageUrl,
                        accent = fact.category.accent(),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (revealed) {
            RatingBar(onGrade)
        } else {
            Button(
                onClick = onReveal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.review_show_answer_button), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * The four SM-2 grades.
 *
 * Labelled with what they mean to the learner ("Forgot", "Easy") rather than with the
 * algorithm's vocabulary, and coloured so the choice is made by feel rather than by reading.
 */
@Composable
private fun RatingBar(onGrade: (Rating) -> Unit) {
    val options = listOf(
        Triple(Rating.AGAIN, stringResource(R.string.rating_forgot), SmartPalette.Danger),
        Triple(Rating.HARD, stringResource(R.string.rating_hard), SmartPalette.Warning),
        Triple(Rating.GOOD, stringResource(R.string.rating_good), SmartPalette.Mint),
        Triple(Rating.EASY, stringResource(R.string.rating_easy), SmartPalette.Success),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (rating, label, color) ->
            Button(
                onClick = { onGrade(rating) },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = color.copy(alpha = 0.18f),
                    contentColor = color,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    color = color,
                )
            }
        }
    }
}
