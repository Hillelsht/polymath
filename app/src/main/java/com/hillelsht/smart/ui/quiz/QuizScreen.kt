package com.hillelsht.smart.ui.quiz

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.hillelsht.smart.domain.QuizGenerator
import com.hillelsht.smart.domain.model.Category
import com.hillelsht.smart.domain.model.QuizAnswer
import com.hillelsht.smart.domain.model.QuizQuestion
import com.hillelsht.smart.ui.components.CategoryChip
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.components.FactImage
import com.hillelsht.smart.ui.components.MasteryRing
import com.hillelsht.smart.ui.components.SessionProgress
import com.hillelsht.smart.ui.theme.SmartPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val QUIZ_LENGTH = 10

data class QuizUiState(
    val questions: List<QuizQuestion> = emptyList(),
    val index: Int = 0,
    val selected: Int? = null,
    val answers: List<QuizAnswer> = emptyList(),
    val ready: Boolean = false,
) {
    val current: QuizQuestion? get() = questions.getOrNull(index)
    val finished: Boolean get() = ready && questions.isNotEmpty() && index >= questions.size
    val correctCount: Int get() = answers.count { it.correct }
}

class QuizViewModel(
    private val repository: SmartRepository,
    private val category: Category?,
) : ViewModel() {

    private val index = MutableStateFlow(0)
    private val selected = MutableStateFlow<Int?>(null)
    private val answers = MutableStateFlow<List<QuizAnswer>>(emptyList())
    private var questions: List<QuizQuestion>? = null
    private var recorded = false

    val state = combine(
        repository.facts,
        index,
        selected,
        answers,
    ) { facts, position, choice, given ->
        val built = questions ?: run {
            if (facts.isEmpty()) return@run emptyList()
            QuizGenerator.generateFromPool(facts, QUIZ_LENGTH, category).also { questions = it }
        }
        QuizUiState(
            questions = built,
            index = position,
            selected = choice,
            answers = given,
            ready = facts.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuizUiState())

    fun choose(optionIndex: Int) {
        if (selected.value != null) return
        val question = questions?.getOrNull(index.value) ?: return

        selected.value = optionIndex
        answers.value += QuizAnswer(
            factId = question.factId,
            selectedIndex = optionIndex,
            correct = optionIndex == question.correctIndex,
        )
    }

    fun next() {
        selected.value = null
        index.value += 1
        if (index.value >= (questions?.size ?: 0)) finish()
    }

    /** Wrong answers are pushed back into the review queue, so a miss is never just a miss. */
    private fun finish() {
        if (recorded) return
        recorded = true
        val given = answers.value
        viewModelScope.launch {
            repository.recordQuiz(
                category = category,
                total = given.size,
                correct = given.count { it.correct },
                missedFactIds = given.filterNot { it.correct }.map { it.factId },
            )
        }
    }

    companion object {
        fun factory(repository: SmartRepository, category: Category?) = viewModelFactory {
            initializer { QuizViewModel(repository, category) }
        }
    }
}

@Composable
fun QuizScreen(repository: SmartRepository, category: Category?, onDone: () -> Unit) {
    val viewModel: QuizViewModel = viewModel(
        factory = QuizViewModel.factory(repository, category),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onDone) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            SessionProgress(
                current = state.index,
                total = state.questions.size,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${(state.index + 1).coerceAtMost(state.questions.size.coerceAtLeast(1))}" +
                    "/${state.questions.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))

        val question = state.current
        when {
            question != null -> QuestionCard(
                question = question,
                selected = state.selected,
                modifier = Modifier.weight(1f),
                onChoose = viewModel::choose,
                onNext = viewModel::next,
            )

            state.finished -> ScoreCard(
                correct = state.correctCount,
                total = state.answers.size,
                modifier = Modifier.weight(1f),
                onDone = onDone,
            )

            else -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Building your quiz",
                    body = "Pulling questions from the curriculum…",
                )
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuizQuestion,
    selected: Int?,
    modifier: Modifier = Modifier,
    onChoose: (Int) -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            CategoryChip(question.category)
            Spacer(Modifier.height(16.dp))
            Text(
                text = question.prompt,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(20.dp))

            question.options.forEachIndexed { optionIndex, option ->
                OptionRow(
                    text = option,
                    state = optionState(optionIndex, selected, question.correctIndex),
                    onClick = { onChoose(optionIndex) },
                )
                Spacer(Modifier.height(10.dp))
            }

            if (selected != null) {
                Spacer(Modifier.height(10.dp))
                FactImage(
                    wikiTitle = question.wikiTitle,
                    category = question.category,
                    ratio = 16f / 9f,
                )
                question.hook?.let { hook ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = hook,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        if (selected != null) {
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Next", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private enum class OptionState { IDLE, CORRECT, WRONG, MISSED }

private fun optionState(index: Int, selected: Int?, correctIndex: Int): OptionState = when {
    selected == null -> OptionState.IDLE
    index == correctIndex -> OptionState.CORRECT
    index == selected -> OptionState.WRONG
    else -> OptionState.MISSED
}

@Composable
private fun OptionRow(text: String, state: OptionState, onClick: () -> Unit) {
    val target = when (state) {
        OptionState.IDLE -> MaterialTheme.colorScheme.surface
        OptionState.CORRECT -> SmartPalette.Success.copy(alpha = 0.2f)
        OptionState.WRONG -> SmartPalette.Danger.copy(alpha = 0.2f)
        OptionState.MISSED -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    }
    val background by animateColorAsState(target, tween(220), label = "optionBackground")

    val accent = when (state) {
        OptionState.CORRECT -> SmartPalette.Success
        OptionState.WRONG -> SmartPalette.Danger
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clickable(enabled = state == OptionState.IDLE, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (state == OptionState.CORRECT || state == OptionState.WRONG) {
            Icon(
                imageVector = if (state == OptionState.CORRECT) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ScoreCard(correct: Int, total: Int, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val ratio = if (total == 0) 0f else correct.toFloat() / total
    val verdict = when {
        ratio >= 0.9f -> "Outstanding"
        ratio >= 0.7f -> "Solid work"
        ratio >= 0.5f -> "Getting there"
        else -> "Worth another look"
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MasteryRing(
            progress = ratio,
            ringSize = 160.dp,
            strokeWidth = 14.dp,
            colors = listOf(SmartPalette.Mint, SmartPalette.IrisBright),
            label = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$correct/$total",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "correct",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = verdict,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (correct == total) {
                "A clean sweep."
            } else {
                "The ${total - correct} you missed have been added back to your review queue."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Finish", style = MaterialTheme.typography.labelLarge)
        }
    }
}
