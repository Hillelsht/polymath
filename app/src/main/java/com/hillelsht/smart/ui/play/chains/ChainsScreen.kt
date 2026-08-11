package com.hillelsht.smart.ui.play.chains

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import com.hillelsht.smart.domain.play.GameId
import com.hillelsht.smart.domain.play.chains.ChainsGroup
import com.hillelsht.smart.domain.play.chains.ChainsPuzzle
import com.hillelsht.smart.domain.play.chains.ChainsRules
import com.hillelsht.smart.domain.play.chains.ChainsState
import com.hillelsht.smart.domain.play.chains.Verdict
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.theme.SmartPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Loaded, missing, or in progress. */
sealed interface ChainsUi {
    data object Loading : ChainsUi
    data object Unavailable : ChainsUi
    data class Playing(val state: ChainsState, val alreadyPlayed: Boolean) : ChainsUi
}

class ChainsViewModel(private val repository: SmartRepository) : ViewModel() {

    private val ui = MutableStateFlow<ChainsUi>(ChainsUi.Loading)
    val state = ui.asStateFlow()

    private val today: LocalDate = LocalDate.now()

    init {
        viewModelScope.launch {
            val puzzle = runCatching { repository.chainsPuzzle(today) }.getOrNull()
            if (puzzle == null) {
                ui.value = ChainsUi.Unavailable
                return@launch
            }
            val finished = runCatching { repository.dailyResult(GameId.CHAINS, today) }.getOrNull()
            ui.value = if (finished == null) {
                ChainsUi.Playing(ChainsRules.start(puzzle), alreadyPlayed = false)
            } else {
                // A finished grid reopens showing your work rather than offering a second go —
                // the point of a daily is one attempt at the same grid everybody else got.
                val solvedIds = finished.solved.split(",").filter { it.isNotBlank() }.toSet()
                ChainsUi.Playing(
                    ChainsRules.start(puzzle).copy(
                        solved = puzzle.groups.filter { it.id in solvedIds },
                        mistakes = finished.mistakes,
                    ),
                    alreadyPlayed = true,
                )
            }
        }
    }

    fun select(tile: String) = update { ChainsRules.select(it, tile) }

    fun clear() = update { ChainsRules.clear(it) }

    fun submit() = update { ChainsRules.submit(it) }

    private fun update(transform: (ChainsState) -> ChainsState) {
        val current = ui.value as? ChainsUi.Playing ?: return
        if (current.alreadyPlayed) return
        val next = transform(current.state)
        ui.value = current.copy(state = next)
        if (next.over && !current.state.over) {
            viewModelScope.launch {
                repository.saveDailyResult(
                    game = GameId.CHAINS,
                    date = today,
                    score = ChainsRules.score(next),
                    mistakes = next.mistakes,
                    won = next.won,
                    solvedGroupIds = next.solved.map { it.id },
                )
            }
        }
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { ChainsViewModel(repository) }
        }
    }
}

private val bandColours = listOf(
    Color(0xFFF2C94C),
    Color(0xFF3DDC97),
    Color(0xFF56A0FF),
    Color(0xFFB07BFF),
)

@Composable
fun ChainsScreen(repository: SmartRepository, onBack: () -> Unit) {
    val viewModel: ChainsViewModel = viewModel(factory = ChainsViewModel.factory(repository))
    val ui by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (val current = ui) {
            ChainsUi.Loading -> Box(Modifier.fillMaxSize())

            ChainsUi.Unavailable -> EmptyState(
                title = "No grid today",
                body = "Today's puzzle has not been published, or it could not be fetched. " +
                    "Try again once you are back online.",
                modifier = Modifier.fillMaxSize(),
                action = { OutlinedButton(onClick = onBack) { Text("Back") } },
            )

            is ChainsUi.Playing -> Grid(
                state = current.state,
                readOnly = current.alreadyPlayed,
                onSelect = viewModel::select,
                onClear = viewModel::clear,
                onSubmit = viewModel::submit,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun Grid(
    state: ChainsState,
    readOnly: Boolean,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text(
                "Chains",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Find four groups of four",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(ChainsRules.MAX_MISTAKES) { index ->
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < ChainsRules.MAX_MISTAKES - state.mistakes) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                SmartPalette.Danger
                            },
                        ),
                )
            }
        }
    }

    // Solved bands stack above the remaining tiles, so the board visibly shrinks as it is solved.
    val bands = if (state.over) ChainsRules.reveal(state) else state.solved
    bands.forEachIndexed { index, group ->
        Band(group, bandColours[group.difficulty.coerceIn(1, 4) - 1], found = group in state.solved)
        if (index == bands.lastIndex) Spacer(Modifier.height(2.dp))
    }

    val remaining = state.remaining
    remaining.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { tile ->
                Tile(
                    label = tile,
                    selected = tile in state.selected,
                    enabled = !readOnly && !state.over,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(tile) },
                )
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }

    Spacer(Modifier.height(2.dp))

    val message = when {
        state.won -> "Solved in ${state.mistakes} mistakes."
        state.lost -> "Out of guesses — here is the whole grid."
        state.verdict == Verdict.ONE_AWAY -> "One away."
        state.verdict == Verdict.WRONG -> "Not a group."
        state.verdict == Verdict.ALREADY_TRIED -> "You have tried that one."
        readOnly -> "You have played today's grid."
        else -> " "
    }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            state.won -> SmartPalette.Success
            state.lost -> SmartPalette.Danger
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )

    if (state.over || readOnly) {
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Done") }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onClear,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Clear") }
            Button(
                onClick = onSubmit,
                enabled = state.selected.size == ChainsPuzzle.GROUP_SIZE,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Submit") }
        }
    }
}

@Composable
private fun Band(group: ChainsGroup, colour: Color, found: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (found) colour.copy(alpha = 0.9f) else colour.copy(alpha = 0.35f))
            .padding(vertical = 10.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            group.label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF0D1426),
        )
        Text(
            group.members.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF0D1426),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Tile(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        label = "tile",
    )
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            // Long labels are the norm here — "Constantinople" has to fit a quarter of a phone.
            fontSize = if (label.length > 12) 10.sp else 12.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(4.dp),
        )
    }
}
