package com.hillelsht.smart.ui.play.gambit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
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
import com.hillelsht.smart.domain.play.chess.Chess
import com.hillelsht.smart.domain.play.chess.Fen
import com.hillelsht.smart.domain.play.chess.Gambit
import com.hillelsht.smart.domain.play.chess.GambitMatch
import com.hillelsht.smart.domain.play.chess.GambitOutcome
import com.hillelsht.smart.domain.play.chess.GambitResult
import com.hillelsht.smart.domain.play.chess.Move
import com.hillelsht.smart.domain.play.chess.TempoAction
import com.hillelsht.smart.ui.theme.SmartPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class GambitPhase { PLAYER_TURN, ANSWERING, GAME_OVER }

/** A pawn reaching the back rank with more than one promotion choice waiting on an answer. */
data class PromotionPrompt(val from: Int, val to: Int, val options: List<Int>)

data class GambitUiState(
    val ready: Boolean = false,
    val match: GambitMatch = GambitMatch(),
    val phase: GambitPhase = GambitPhase.PLAYER_TURN,
    val selected: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val promotion: PromotionPrompt? = null,
    val question: QuizQuestion? = null,
    /** Which option was tapped, and whether it was right — shown as a flash before continuing. */
    val answered: Pair<Int, Boolean>? = null,
    /** The move [Gambit.suggest] named, kept on screen until the player's next action. */
    val hint: String? = null,
    val engineThinking: Boolean = false,
)

class GambitViewModel(private val repository: SmartRepository) : ViewModel() {

    private val ui = MutableStateFlow(GambitUiState())
    val state = ui.asStateFlow()

    private var pool: List<Fact> = emptyList()
    private val random = Random.Default

    init {
        viewModelScope.launch {
            pool = repository.facts.first()
            val resumed = runCatching { repository.gambitMatch() }.getOrNull()
            val match = resumed?.takeIf { it.outcome == GambitOutcome.IN_PROGRESS } ?: GambitMatch()
            val board = Fen.parse(match.fen)
            val phase = if (match.outcome != GambitOutcome.IN_PROGRESS) GambitPhase.GAME_OVER else GambitPhase.PLAYER_TURN
            ui.value = GambitUiState(ready = true, match = match, phase = phase)

            // The app can close between the player's move landing and the engine's reply —
            // resuming has to notice the board is not actually waiting on the player and pick
            // up where the game left off, or the match sits stuck forever.
            if (match.outcome == GambitOutcome.IN_PROGRESS && board.sideToMove != match.playerSide) {
                runEngineMove()
            }
        }
    }

    fun tapSquare(square: Int) {
        val current = ui.value
        if (current.phase != GambitPhase.PLAYER_TURN || current.engineThinking) return
        val board = Fen.parse(current.match.fen)
        if (board.sideToMove != current.match.playerSide) return

        val selected = current.selected
        if (selected != null && square in current.legalTargets) {
            val matches = board.legalMoves().filter { Move.from(it) == selected && Move.to(it) == square }
            if (matches.size > 1) {
                ui.value = current.copy(
                    selected = null,
                    legalTargets = emptySet(),
                    promotion = PromotionPrompt(selected, square, matches.toList()),
                )
            } else {
                applyPlayerMove(Chess.moveName(matches.first()))
            }
            return
        }

        val piece = board.squares[square]
        ui.value = if (piece != Chess.EMPTY && Chess.colourOf(piece) == board.sideToMove) {
            val targets = board.legalMoves().filter { Move.from(it) == square }.map { Move.to(it) }.toSet()
            current.copy(selected = square, legalTargets = targets, promotion = null)
        } else {
            current.copy(selected = null, legalTargets = emptySet())
        }
    }

    fun choosePromotion(pieceType: Int) {
        val prompt = ui.value.promotion ?: return
        val move = prompt.options.firstOrNull { Move.promotion(it) == pieceType } ?: return
        ui.value = ui.value.copy(promotion = null)
        applyPlayerMove(Chess.moveName(move))
    }

    fun cancelPromotion() {
        ui.value = ui.value.copy(promotion = null, selected = null, legalTargets = emptySet())
    }

    private fun applyPlayerMove(moveName: String) {
        val current = ui.value
        val result = Gambit.playerMove(current.match, moveName)
        if (result !is GambitResult.Applied) return
        val match = result.match
        persist(match)

        if (match.outcome != GambitOutcome.IN_PROGRESS) {
            recordFinishedGame(match)
            ui.value = current.copy(match = match, phase = GambitPhase.GAME_OVER, selected = null, legalTargets = emptySet())
            return
        }

        val question = nextQuestion()
        if (question == null) {
            // No facts to draw a question from — go straight to the engine's reply rather than
            // showing a quiz card with nothing in it.
            ui.value = current.copy(match = match, phase = GambitPhase.PLAYER_TURN, selected = null, legalTargets = emptySet())
            runEngineMove()
        } else {
            ui.value = current.copy(
                match = match,
                phase = GambitPhase.ANSWERING,
                selected = null,
                legalTargets = emptySet(),
                question = question,
                answered = null,
                hint = null,
            )
        }
    }

    private fun nextQuestion(): QuizQuestion? {
        if (pool.isEmpty()) return null
        val subject = pool[random.nextInt(pool.size)]
        return QuizGenerator.generate(listOf(subject), pool, count = 1, random = random).firstOrNull()
    }

    fun answer(optionIndex: Int) {
        val current = ui.value
        val question = current.question ?: return
        if (current.answered != null) return
        val correct = optionIndex == question.correctIndex

        if (!correct) {
            viewModelScope.launch { runCatching { repository.recordGameMiss(question.factId) } }
        }
        val banked = Gambit.bankTempo(current.match, correct)
        persist(banked)
        ui.value = current.copy(match = banked, answered = optionIndex to correct)
    }

    fun skipQuestion() = finishTurn()

    fun continueAfterAnswer() = finishTurn()

    private fun finishTurn() {
        ui.value = ui.value.copy(phase = GambitPhase.PLAYER_TURN, question = null, answered = null)
        runEngineMove()
    }

    private fun runEngineMove() {
        viewModelScope.launch {
            val before = ui.value
            ui.value = before.copy(engineThinking = true)
            val result = withContext(Dispatchers.Default) { Gambit.engineMove(before.match) }
            val applied = (result as? GambitResult.Applied)?.match ?: before.match
            persist(applied)
            if (applied.outcome != GambitOutcome.IN_PROGRESS) recordFinishedGame(applied)
            ui.value = ui.value.copy(
                match = applied,
                engineThinking = false,
                phase = if (applied.outcome != GambitOutcome.IN_PROGRESS) GambitPhase.GAME_OVER else GambitPhase.PLAYER_TURN,
                hint = null,
            )
        }
    }

    /**
     * One row in the shared `game_runs` table, so the Play picker's "N games played" reuses the
     * same [SmartRepository.runCount] plumbing every other game already reports through rather
     * than a parallel counter of its own.
     */
    private fun recordFinishedGame(match: GambitMatch) {
        val detail = when (match.outcome) {
            GambitOutcome.PLAYER_WON -> "Won in ${match.moveHistory.size} moves"
            GambitOutcome.ENGINE_WON -> "Lost in ${match.moveHistory.size} moves"
            GambitOutcome.DRAW -> "Drew in ${match.moveHistory.size} moves"
            GambitOutcome.IN_PROGRESS -> return
        }
        viewModelScope.launch {
            runCatching {
                repository.saveRun(
                    game = GameId.GAMBIT,
                    score = if (match.outcome == GambitOutcome.PLAYER_WON) 1 else 0,
                    seed = 0L,
                    durationMs = 0L,
                    detail = detail,
                )
            }
        }
    }

    fun spend(action: TempoAction) {
        val current = ui.value
        if (!Gambit.canAfford(current.match, action)) return
        when (action) {
            TempoAction.HINT -> viewModelScope.launch {
                val suggestion = withContext(Dispatchers.Default) { Gambit.suggest(current.match) }
                val spent = Gambit.spend(current.match, action)
                persist(spent)
                ui.value = ui.value.copy(match = spent, hint = suggestion)
            }

            TempoAction.TAKEBACK -> {
                val spent = Gambit.spend(current.match, action)
                persist(spent)
                ui.value = current.copy(
                    match = spent,
                    phase = GambitPhase.PLAYER_TURN,
                    question = null,
                    answered = null,
                    hint = null,
                    selected = null,
                    legalTargets = emptySet(),
                )
            }

            TempoAction.WEAKEN -> {
                val spent = Gambit.spend(current.match, action)
                persist(spent)
                ui.value = current.copy(match = spent)
            }
        }
    }

    fun newGame() {
        viewModelScope.launch { runCatching { repository.clearGambitMatch() } }
        ui.value = ui.value.copy(
            match = GambitMatch(),
            phase = GambitPhase.PLAYER_TURN,
            selected = null,
            legalTargets = emptySet(),
            question = null,
            answered = null,
            hint = null,
            promotion = null,
        )
    }

    private fun persist(match: GambitMatch) {
        viewModelScope.launch { runCatching { repository.saveGambitMatch(match) } }
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { GambitViewModel(repository) }
        }
    }
}

@Composable
fun ChessScreen(repository: SmartRepository, onBack: () -> Unit) {
    val viewModel: GambitViewModel = viewModel(factory = GambitViewModel.factory(repository))
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

        Header(state, onBack)
        BoardGrid(state, onTap = viewModel::tapSquare)

        when (state.phase) {
            GambitPhase.PLAYER_TURN -> TempoBar(state, viewModel::spend)
            GambitPhase.ANSWERING -> Question(state, viewModel::answer, viewModel::continueAfterAnswer, viewModel::skipQuestion)
            GambitPhase.GAME_OVER -> Summary(state, viewModel::newGame, onBack)
        }
    }

    state.promotion?.let { prompt ->
        PromotionPicker(
            white = Fen.parse(state.match.fen).sideToMove == Chess.WHITE,
            onPick = viewModel::choosePromotion,
            onDismiss = viewModel::cancelPromotion,
        )
    }
}

@Composable
private fun Header(state: GambitUiState, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                "Gambit",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "${state.match.tempo} tempo banked",
                style = MaterialTheme.typography.bodyMedium,
                color = SmartPalette.Warning,
            )
        }
        OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp)) { Text("Leave") }
    }
}

@Composable
private fun BoardGrid(state: GambitUiState, onTap: (Int) -> Unit) {
    val board = Fen.parse(state.match.fen)
    val inCheckSquare = if (board.inCheck()) board.kingSquare(board.sideToMove) else Chess.NO_SQUARE

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        for (rank in 7 downTo 0) {
            Row(Modifier.fillMaxWidth()) {
                for (file in 0..7) {
                    val square = Chess.square(file, rank)
                    Square(
                        piece = board.squares[square],
                        light = (file + rank) % 2 != 0,
                        selected = square == state.selected,
                        target = square in state.legalTargets,
                        inCheck = square == inCheckSquare,
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                        onClick = { onTap(square) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Square(
    piece: Int,
    light: Boolean,
    selected: Boolean,
    target: Boolean,
    inCheck: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        inCheck -> SmartPalette.Danger.copy(alpha = 0.65f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        target -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        light -> Color(0xFF2B3A5C)
        else -> Color(0xFF162034)
    }
    Box(
        modifier
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (piece != Chess.EMPTY) {
            Text(
                glyph(piece),
                fontSize = 30.sp,
                color = if (piece > 0) Color(0xFFF2F5FF) else Color(0xFF0D1426),
            )
        }
    }
}

/** Unicode's own chess set: vector, in every system font, and zero bytes in the APK. */
private fun glyph(piece: Int): String {
    val whiteGlyphs = "♙♘♗♖♕♔"
    val blackGlyphs = "♟♞♝♜♛♚"
    val index = Chess.typeOf(piece) - 1
    return if (piece > 0) whiteGlyphs[index].toString() else blackGlyphs[index].toString()
}

@Composable
private fun PromotionPicker(white: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC0A0F1C))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(Chess.QUEEN, Chess.ROOK, Chess.BISHOP, Chess.KNIGHT).forEach { type ->
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .clickable { onPick(type) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        glyph(if (white) type else -type),
                        fontSize = 32.sp,
                        color = if (white) Color(0xFFF2F5FF) else Color(0xFF0D1426),
                    )
                }
            }
        }
    }
}

@Composable
private fun TempoBar(state: GambitUiState, onSpend: (TempoAction) -> Unit) {
    state.hint?.let {
        Text(
            "Hint: $it",
            style = MaterialTheme.typography.bodyMedium,
            color = SmartPalette.Mint,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
    if (state.engineThinking) {
        Text(
            "The engine is thinking…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TempoButton("Weaken", Gambit.COST_WEAKEN, Gambit.canAfford(state.match, TempoAction.WEAKEN), Modifier.weight(1f)) {
            onSpend(TempoAction.WEAKEN)
        }
        TempoButton("Hint", Gambit.COST_HINT, Gambit.canAfford(state.match, TempoAction.HINT), Modifier.weight(1f)) {
            onSpend(TempoAction.HINT)
        }
        TempoButton("Takeback", Gambit.COST_TAKEBACK, Gambit.canAfford(state.match, TempoAction.TAKEBACK), Modifier.weight(1f)) {
            onSpend(TempoAction.TAKEBACK)
        }
    }
}

@Composable
private fun TempoButton(label: String, cost: Int, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(14.dp)) {
        Text("$label · $cost", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColumnScope.Question(
    state: GambitUiState,
    onAnswer: (Int) -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val question = state.question ?: return
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Answer for tempo",
            style = MaterialTheme.typography.labelLarge,
            color = SmartPalette.Warning,
        )
        Text(
            question.prompt,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        question.options.forEachIndexed { index, option ->
            val chosen = state.answered?.first == index
            val revealed = state.answered != null
            val background = when {
                revealed && index == question.correctIndex -> SmartPalette.Success.copy(alpha = 0.85f)
                chosen -> SmartPalette.Danger.copy(alpha = 0.85f)
                else -> MaterialTheme.colorScheme.surface
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(background)
                    .clickable(enabled = !revealed) { onAnswer(index) }
                    .padding(14.dp),
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (revealed && (chosen || index == question.correctIndex)) Color.White else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (state.answered != null) {
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Continue")
        }
    } else {
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Skip — no tempo")
        }
    }
}

@Composable
private fun ColumnScope.Summary(state: GambitUiState, onNewGame: () -> Unit, onBack: () -> Unit) {
    val (title, colour) = when (state.match.outcome) {
        GambitOutcome.PLAYER_WON -> "You won" to SmartPalette.Success
        GambitOutcome.ENGINE_WON -> "The engine won" to SmartPalette.Danger
        GambitOutcome.DRAW -> "Draw" to MaterialTheme.colorScheme.onSurfaceVariant
        GambitOutcome.IN_PROGRESS -> "" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colour)
        Spacer(Modifier.height(6.dp))
        Text(
            "${state.match.moveHistory.size} moves played",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("Done")
        }
        Button(onClick = onNewGame, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
            Text("New game")
        }
    }
}
