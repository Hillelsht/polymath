package com.hillelsht.smart.ui.play.climb

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.QuizGenerator
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.QuizQuestion
import com.hillelsht.smart.domain.play.GameId
import com.hillelsht.smart.domain.play.climb.ClimbMap
import com.hillelsht.smart.domain.play.climb.ClimbNode
import com.hillelsht.smart.domain.play.climb.ClimbPhase
import com.hillelsht.smart.domain.play.climb.ClimbRules
import com.hillelsht.smart.domain.play.climb.ClimbRun
import com.hillelsht.smart.domain.play.climb.NodeKind
import com.hillelsht.smart.domain.play.climb.Relic
import com.hillelsht.smart.domain.play.climb.Relics
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.components.FactImage
import com.hillelsht.smart.ui.components.SmartCard
import com.hillelsht.smart.ui.theme.SmartPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ClimbUiState(
    val ready: Boolean = false,
    val run: ClimbRun = ClimbRun(seed = 0L),
    val roster: List<Relic> = emptyList(),
    val question: QuizQuestion? = null,
    val doors: List<ClimbNode> = emptyList(),
    /** Set for a moment after an answer so the option can flash right or wrong. */
    val lastAnswer: Pair<Int, Boolean>? = null,
    val insight: Int = 0,
)

class ClimbViewModel(private val repository: SmartRepository) : ViewModel() {

    private val ui = MutableStateFlow(ClimbUiState())
    val state = ui.asStateFlow()

    private var pool: List<Fact> = emptyList()
    private var subjects: ArrayDeque<Fact> = ArrayDeque()
    private var askedAt = 0L
    private var startedAt = 0L
    // Typed explicitly: inferring from `Random.Default` gives the *object's* type, and the
    // reassignment to a seeded Random in begin() then does not compile.
    private var random: Random = Random.Default

    init {
        viewModelScope.launch {
            pool = repository.facts.first()
            val roster = runCatching { repository.climbRelics() }.getOrNull() ?: Relics.FALLBACK
            val banked = runCatching { repository.insight() }.getOrNull() ?: 0
            ui.value = ClimbUiState(ready = true, roster = roster, insight = banked)
            begin()
        }
    }

    fun begin() {
        val seed = System.currentTimeMillis()
        random = Random(seed)
        startedAt = System.currentTimeMillis()
        subjects = ArrayDeque(pool.shuffled(random))
        val run = ClimbRules.start(seed)
        ui.value = ui.value.copy(
            run = run,
            doors = ClimbMap.floor(seed, run.floor),
            question = null,
            lastAnswer = null,
        )
    }

    fun choose(node: ClimbNode) {
        val current = ui.value
        val next = ClimbRules.choose(current.run, node, current.roster)
        ui.value = current.copy(run = next, doors = ClimbMap.floor(next.seed, next.floor))
        if (next.phase == ClimbPhase.FIGHTING) ask() else ui.value = ui.value.copy(question = null)
    }

    fun take(relicId: String) {
        val current = ui.value
        val next = ClimbRules.take(current.run, relicId, current.roster)
        ui.value = current.copy(run = next, doors = ClimbMap.floor(next.seed, next.floor))
    }

    fun answer(optionIndex: Int) {
        val current = ui.value
        val question = current.question ?: return
        if (current.lastAnswer != null) return

        val correct = optionIndex == question.correctIndex
        val elapsed = System.currentTimeMillis() - askedAt
        val next = ClimbRules.answer(
            run = current.run,
            correct = correct,
            elapsedMs = elapsed,
            categoryId = question.category.id,
            roster = current.roster,
        )
        ui.value = current.copy(run = next, lastAnswer = optionIndex to correct)

        viewModelScope.launch {
            // A question missed here comes back in tomorrow's reviews. This is the line that
            // makes the game part of the app rather than an arcade attached to it.
            if (!correct) runCatching { repository.recordGameMiss(question.factId) }
            if (next.over) finish(next)
        }
    }

    /** Called by the screen once the right/wrong flash has been seen. */
    fun continueAfterAnswer() {
        val current = ui.value.copy(lastAnswer = null)
        ui.value = current
        when (current.run.phase) {
            ClimbPhase.FIGHTING -> ask()
            else -> ui.value = current.copy(question = null)
        }
    }

    private fun ask() {
        val current = ui.value
        val options = ClimbRules.optionCount(current.run, current.roster)
        var question: QuizQuestion? = null
        var tries = 0
        while (question == null && tries < 40) {
            if (subjects.isEmpty()) subjects = ArrayDeque(pool.shuffled(random))
            val subject = subjects.removeFirstOrNull() ?: break
            question = QuizGenerator
                .generate(listOf(subject), pool, count = 1, optionCount = options, random = random)
                .firstOrNull()
            tries++
        }
        askedAt = System.currentTimeMillis()
        ui.value = ui.value.copy(question = question)
    }

    private suspend fun finish(run: ClimbRun) {
        runCatching {
            repository.saveRun(
                game = GameId.CLIMB,
                score = run.score,
                seed = run.seed,
                durationMs = System.currentTimeMillis() - startedAt,
                detail = "Floor ${run.floor} · ${run.answered} answered",
            )
            repository.addInsight(run.insight)
        }
        ui.value = ui.value.copy(insight = runCatching { repository.insight() }.getOrNull() ?: 0)
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { ClimbViewModel(repository) }
        }
    }
}

@Composable
fun ClimbScreen(repository: SmartRepository, onBack: () -> Unit) {
    val viewModel: ClimbViewModel = viewModel(factory = ClimbViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!state.ready) {
            Box(Modifier.fillMaxSize())
            return@Column
        }
        if (state.run.phase != ClimbPhase.DEAD && state.question == null &&
            state.run.phase == ClimbPhase.FIGHTING
        ) {
            // No question could be built at all — a corpus too thin to quiz on.
            EmptyState(
                title = "Not enough to ask about yet",
                body = "Learn a few more facts and the tower will have something to throw at you.",
                modifier = Modifier.fillMaxSize(),
                action = { OutlinedButton(onClick = onBack) { Text("Back") } },
            )
            return@Column
        }

        Header(state, onBack)

        when (state.run.phase) {
            ClimbPhase.CHOOSING -> Doors(state, viewModel::choose)
            ClimbPhase.FIGHTING -> Fight(state, viewModel::answer, viewModel::continueAfterAnswer)
            ClimbPhase.LOOTING -> Cache(state, viewModel::take)
            ClimbPhase.DEAD -> Summary(state, viewModel::begin, onBack)
        }
    }
}

@Composable
private fun Header(state: ClimbUiState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(
                "Floor ${state.run.floor}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "${state.run.score} points" +
                    if (state.run.combo > 1) "  ·  ${state.run.combo}x streak" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp)) { Text("Leave") }
    }

    Bar(
        fraction = state.run.hp.toFloat() / state.run.maxHp,
        colour = SmartPalette.Success,
        label = "${state.run.hp} / ${state.run.maxHp}" +
            if (state.run.shield > 0) "   ${state.run.shield} shielded" else "",
    )
}

@Composable
private fun Bar(fraction: Float, colour: Color, label: String) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(320), label = "bar")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colour),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Doors(state: ClimbUiState, onChoose: (ClimbNode) -> Unit) {
    Text(
        if (state.doors.size == 1) "Straight ahead" else "Choose your way up",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.doors.forEach { node ->
            SmartCard(
                modifier = Modifier.fillMaxWidth().clickable { onChoose(node) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        node.kind.title(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = node.kind.colour(),
                    )
                    Text(
                        node.kind.blurb(state.run.floor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ColumnScope because these lay themselves out inside the screen's column and use weight to
// take the space left over between the header and the buttons.
@Composable
private fun ColumnScope.Fight(
    state: ClimbUiState,
    onAnswer: (Int) -> Unit,
    onContinue: () -> Unit,
) {
    val question = state.question ?: return
    Bar(
        fraction = if (state.run.enemyMaxHp == 0) 0f else {
            state.run.enemyHp.toFloat() / state.run.enemyMaxHp
        },
        colour = SmartPalette.Danger,
        label = state.run.node?.kind?.title().orEmpty(),
    )

    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ClimbRules.revealsImage(state.run, state.roster)) {
            FactImage(
                imageUrl = question.imageUrl,
                wikiTitle = question.wikiTitle,
                category = question.category,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                cornerRadius = 16.dp,
            )
        }
        Text(
            question.prompt,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        question.options.forEachIndexed { index, option ->
            val chosen = state.lastAnswer?.first == index
            val revealed = state.lastAnswer != null
            val background = when {
                revealed && index == question.correctIndex -> SmartPalette.Success.copy(alpha = 0.85f)
                chosen -> SmartPalette.Danger.copy(alpha = 0.85f)
                else -> MaterialTheme.colorScheme.surface
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(background)
                    .clickable(enabled = !revealed) { onAnswer(index) }
                    .padding(18.dp),
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (revealed && (chosen || index == question.correctIndex)) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }

    if (state.lastAnswer != null && !state.run.over) {
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Continue") }
    }
}

@Composable
private fun Cache(state: ClimbUiState, onTake: (String) -> Unit) {
    Text(
        "A cache. Take one.",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.run.offered.mapNotNull { id -> state.roster.firstOrNull { it.id == id } }
            .forEach { relic ->
                SmartCard(modifier = Modifier.fillMaxWidth().clickable { onTake(relic.id) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            relic.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            relic.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
    }
}

@Composable
private fun ColumnScope.Summary(state: ClimbUiState, onAgain: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Floor ${state.run.floor}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "${state.run.score} points",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "${state.run.answered} answered · ${state.run.missed} missed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.run.missed > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "The ones you missed are in tomorrow's reviews.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${state.insight} insight banked",
            style = MaterialTheme.typography.labelLarge,
            color = SmartPalette.Warning,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Done") }
        Button(
            onClick = onAgain,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Climb again") }
    }
}

private fun NodeKind.title(): String = when (this) {
    NodeKind.DUEL -> "A challenger"
    NodeKind.ELITE -> "Something bigger"
    NodeKind.CACHE -> "A cache"
    NodeKind.REST -> "A quiet room"
    NodeKind.BOSS -> "The gatekeeper"
}

private fun NodeKind.blurb(floor: Int): String = when (this) {
    NodeKind.DUEL -> "An ordinary fight. ${ClimbMap.questionsToClear(floor, this)} or so questions."
    NodeKind.ELITE -> "Harder, and worth more insight."
    NodeKind.CACHE -> "Choose one relic of three."
    NodeKind.REST -> "Mend ${ClimbRules.REST_HEAL_PERCENT}% of your health and move on."
    NodeKind.BOSS -> "No way past but through."
}

@Composable
private fun NodeKind.colour(): Color = when (this) {
    NodeKind.ELITE, NodeKind.BOSS -> SmartPalette.Danger
    NodeKind.CACHE -> SmartPalette.Warning
    NodeKind.REST -> SmartPalette.Success
    NodeKind.DUEL -> MaterialTheme.colorScheme.primary
}
