package com.hillelsht.smart.ui.play.palace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import com.hillelsht.smart.domain.model.Fact
import com.hillelsht.smart.domain.model.QuizQuestion
import com.hillelsht.smart.domain.play.GameId
import com.hillelsht.smart.domain.play.palace.Facing
import com.hillelsht.smart.domain.play.palace.PalaceInput
import com.hillelsht.smart.domain.play.palace.PalaceLevels
import com.hillelsht.smart.domain.play.palace.PalacePhase
import com.hillelsht.smart.domain.play.palace.PalacePhysics
import com.hillelsht.smart.domain.play.palace.PalaceRules
import com.hillelsht.smart.domain.play.palace.PalaceRun
import com.hillelsht.smart.ui.components.EmptyState
import com.hillelsht.smart.ui.mascot.AryehJoints
import com.hillelsht.smart.ui.mascot.AryehPose
import com.hillelsht.smart.ui.mascot.drawAryeh
import com.hillelsht.smart.ui.theme.SmartPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

data class PalaceUiState(
    val ready: Boolean = false,
    val poolEmpty: Boolean = false,
    val run: PalaceRun = PalaceRun.start(PalaceLevels.theHall),
    val gateQuestion: QuizQuestion? = null,
    val gateId: String? = null,
    /** Set for a moment after a gate is answered so the option can flash right or wrong. */
    val lastGateAnswer: Pair<Int, Boolean>? = null,
)

class PalaceViewModel(private val repository: SmartRepository) : ViewModel() {

    private val ui = MutableStateFlow(PalaceUiState())
    val state = ui.asStateFlow()

    private var pool: List<Fact> = emptyList()
    private var random: Random = Random.Default
    private var startedAt = 0L

    // Physics reads these once per frame rather than from Compose state — they change on every
    // press and release, and neither side of that needs to trigger recomposition on its own.
    private var moveLeft = false
    private var moveRight = false
    private var pendingJump = false
    private var pendingClimb = false
    private var pendingDrop = false

    init {
        viewModelScope.launch {
            pool = repository.facts.first()
            random = Random(System.currentTimeMillis())
            startedAt = System.currentTimeMillis()
            ui.value = PalaceUiState(ready = true, poolEmpty = pool.isEmpty())
        }
    }

    fun setMoveLeft(held: Boolean) {
        moveLeft = held
    }

    fun setMoveRight(held: Boolean) {
        moveRight = held
    }

    fun jump() {
        pendingJump = true
    }

    fun climb() {
        pendingClimb = true
    }

    fun drop() {
        pendingDrop = true
    }

    /** Advances the physics by [dtMs]. Called once per displayed frame from the screen. */
    fun tick(dtMs: Long) {
        val current = ui.value
        if (!current.ready || current.poolEmpty || current.gateQuestion != null || current.run.over) {
            return
        }

        val input = PalaceInput(
            moveLeft = moveLeft,
            moveRight = moveRight,
            jump = pendingJump,
            climb = pendingClimb,
            drop = pendingDrop,
        )
        pendingJump = false
        pendingClimb = false
        pendingDrop = false

        val next = PalacePhysics.tick(current.run, input, dtMs)
        val gate = PalaceRules.gateAt(next)
        if (gate == null) {
            ui.value = current.copy(run = next)
            if (next.over) finish(next)
            return
        }

        val question = buildQuestion(gate.categoryId)
        if (question == null) {
            // Nothing to ask — a corpus too thin for this gate's category, or at all. Opening it
            // rather than leaving the player stuck at a gate that can never be answered.
            ui.value = current.copy(run = PalaceRules.answerGate(next, gate.id, correct = true))
            return
        }
        ui.value = current.copy(run = next, gateQuestion = question, gateId = gate.id)
    }

    fun answerGate(index: Int) {
        val current = ui.value
        val question = current.gateQuestion ?: return
        val gateId = current.gateId ?: return
        if (current.lastGateAnswer != null) return

        val correct = index == question.correctIndex
        val opened = PalaceRules.answerGate(current.run, gateId, correct)
        ui.value = current.copy(run = opened, lastGateAnswer = index to correct)

        viewModelScope.launch {
            // A question missed here comes back in tomorrow's reviews, the same as every other
            // game's misses do.
            if (!correct) runCatching { repository.recordGameMiss(question.factId) }
            delay(650)
            val afterFlash = ui.value
            ui.value = afterFlash.copy(gateQuestion = null, gateId = null, lastGateAnswer = null)
        }
    }

    fun restart() {
        startedAt = System.currentTimeMillis()
        ui.value = ui.value.copy(
            run = PalaceRun.start(PalaceLevels.theHall),
            gateQuestion = null,
            gateId = null,
            lastGateAnswer = null,
        )
    }

    private fun buildQuestion(categoryId: String?): QuizQuestion? {
        if (pool.isEmpty()) return null
        val category = categoryId?.let { Category.fromId(it) }
        val themed = QuizGenerator.generateFromPool(pool, count = 1, category = category, random = random)
            .firstOrNull()
        if (themed != null) return themed
        return QuizGenerator.generateFromPool(pool, count = 1, category = null, random = random)
            .firstOrNull()
    }

    private fun finish(run: PalaceRun) {
        viewModelScope.launch {
            runCatching {
                repository.saveRun(
                    game = GameId.PALACE,
                    score = if (run.phase == PalacePhase.WON) 1 else 0,
                    seed = 0L,
                    durationMs = System.currentTimeMillis() - startedAt,
                    detail = if (run.phase == PalacePhase.WON) "Reached the end" else "Ran out of lives",
                )
            }
        }
    }

    companion object {
        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { PalaceViewModel(repository) }
        }
    }
}

@Composable
fun PalaceScreen(repository: SmartRepository, onBack: () -> Unit) {
    val viewModel: PalaceViewModel = viewModel(factory = PalaceViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.ready, state.poolEmpty) {
        if (!state.ready || state.poolEmpty) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Clamped so a stall (a slow frame, or the process resuming from the background)
            // advances physics by a bounded step instead of a leap large enough to tunnel
            // through a platform — see PalacePhysics.tick's own doc comment on this.
            val dtMs = ((now - last) / 1_000_000L).coerceIn(1L, 48L)
            last = now
            viewModel.tick(dtMs)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        if (!state.ready) {
            Box(Modifier.fillMaxSize())
            return@Column
        }
        if (state.poolEmpty) {
            EmptyState(
                title = "Nothing to ask about yet",
                body = "Learn a few facts and the palace's gates will have questions to ask.",
                modifier = Modifier.fillMaxSize(),
                action = { OutlinedButton(onClick = onBack) { Text("Back") } },
            )
            return@Column
        }

        Header(state, onBack)
        Body(state, viewModel, onBack)
    }
}

// ColumnScope because this lays itself out inside the screen's column and uses weight to take
// the space left over between the header and the controls — the same pattern ClimbScreen's
// Fight uses.
@Composable
private fun ColumnScope.Body(state: PalaceUiState, viewModel: PalaceViewModel, onBack: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Level(state.run)
        state.gateQuestion?.let { question ->
            GateOverlay(question, state.lastGateAnswer, viewModel::answerGate)
        }
        if (state.run.over) {
            Summary(state.run, onAgain = viewModel::restart, onBack = onBack)
        }
    }

    if (state.gateQuestion == null && !state.run.over) {
        Controls(state.run, viewModel)
    }
}

@Composable
private fun Header(state: PalaceUiState, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(20.dp, 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Aryeh's Palace",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(state.run.lives) {
                    Text("♥", color = SmartPalette.Danger, style = MaterialTheme.typography.titleMedium)
                }
            }
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp)) { Text("Leave") }
        }
    }
}

/** How many display pixels one world unit draws as. Platforms, gaps and jump arcs are all
 * authored in the same units [PalacePhysics]' constants use, so 1:1 keeps the level's numbers
 * meaningful without a second scale to keep in sync. */
private const val WORLD_TO_DP = 1f

@Composable
private fun Level(run: PalaceRun) {
    val density = LocalDensity.current
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SmartPalette.Surface),
    ) {
        val scale = with(density) { WORLD_TO_DP.dp.toPx() }
        val originY = size.height * 0.62f
        val levelMaxX = run.level.platforms.maxOf { it.x1 }
        val cameraX = (run.x * scale - size.width / 2f)
            .coerceIn(0f, (levelMaxX * scale - size.width).coerceAtLeast(0f))

        fun sx(worldX: Float) = worldX * scale - cameraX
        fun sy(worldY: Float) = originY + worldY * scale
        fun floorYAt(worldX: Float): Float =
            run.level.platforms.firstOrNull { worldX >= it.x0 && worldX <= it.x1 }?.y ?: 0f

        val floorHeight = 18f * scale
        run.level.platforms.forEach { platform ->
            if (platform.id in run.brokenPlatformIds) return@forEach
            val color = if (platform.collapsing) {
                val elapsed = run.collapseTimers[platform.id] ?: 0f
                lerp(
                    SmartPalette.Warning,
                    SmartPalette.Danger,
                    (elapsed / PalacePhysics.COLLAPSE_DELAY_MS).coerceIn(0f, 1f),
                )
            } else {
                SmartPalette.Outline
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(sx(platform.x0), sy(platform.y)),
                size = Size(sx(platform.x1) - sx(platform.x0), floorHeight),
                cornerRadius = CornerRadius(6f * scale),
            )
        }

        val gateHeight = 130f * scale
        run.level.gates.forEach { gate ->
            val open = gate.id in run.openGateIds
            val floorY = floorYAt(gate.x)
            drawRoundRect(
                color = if (open) SmartPalette.Success.copy(alpha = 0.45f) else SmartPalette.Danger,
                topLeft = Offset(sx(gate.x) - 4f * scale, sy(floorY) - gateHeight),
                size = Size(8f * scale, gateHeight),
                cornerRadius = CornerRadius(4f * scale),
            )
        }

        val goalFloorY = floorYAt(run.level.goalX)
        val goalHeight = 90f * scale
        drawRoundRect(
            color = SmartPalette.Mint,
            topLeft = Offset(sx(run.level.goalX) - 3f * scale, sy(goalFloorY) - goalHeight),
            size = Size(6f * scale, goalHeight),
        )

        val aryehScale = with(density) { 56.dp.toPx() } / 106f
        val swing = if (run.grounded && run.vx != 0f) (sin(run.elapsedMs / 90.0) * 22.0).toFloat() else 0f
        drawAryeh(
            origin = Offset(sx(run.x), sy(run.y)),
            scale = aryehScale,
            pose = AryehPose.STAND,
            joints = AryehJoints(foreSwing = swing, hindSwing = -swing),
            facingRight = run.facing == Facing.RIGHT,
        )
    }
}

@Composable
private fun BoxScope.GateOverlay(
    question: QuizQuestion,
    lastAnswer: Pair<Int, Boolean>?,
    onAnswer: (Int) -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "A gate blocks the way",
            style = MaterialTheme.typography.labelLarge,
            color = SmartPalette.Warning,
        )
        Text(
            question.prompt,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        question.options.forEachIndexed { index, option ->
            val chosen = lastAnswer?.first == index
            val revealed = lastAnswer != null
            val background = when {
                revealed && index == question.correctIndex -> SmartPalette.Success.copy(alpha = 0.85f)
                chosen -> SmartPalette.Danger.copy(alpha = 0.85f)
                else -> MaterialTheme.colorScheme.background
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(background)
                    .clickable(enabled = !revealed) { onAnswer(index) }
                    .padding(16.dp),
            ) {
                Text(
                    option,
                    color = if (revealed && (chosen || index == question.correctIndex)) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.Summary(run: PalaceRun, onAgain: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .align(Alignment.Center)
            .fillMaxWidth(0.85f)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val won = run.phase == PalacePhase.WON
        Text(
            if (won) "You made it through" else "Out of lives",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (won) SmartPalette.Success else SmartPalette.Danger,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(16.dp)) { Text("Done") }
            Button(onClick = onAgain, shape = RoundedCornerShape(16.dp)) { Text("Try again") }
        }
    }
}

@Composable
private fun Controls(run: PalaceRun, viewModel: PalaceViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton("◀", onHold = viewModel::setMoveLeft)
            HoldButton("▶", onHold = viewModel::setMoveRight)
        }
        if (run.phase == PalacePhase.HANGING) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TapButton("Up", onTap = viewModel::climb)
                TapButton("Drop", onTap = viewModel::drop)
            }
        } else {
            TapButton("Jump", onTap = viewModel::jump)
        }
    }
}

@Composable
private fun HoldButton(label: String, onHold: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onHold(true)
                        try {
                            awaitRelease()
                        } finally {
                            onHold(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TapButton(label: String, onTap: () -> Unit) {
    Button(
        onClick = onTap,
        modifier = Modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(label) }
}
