package com.hillelsht.smart.ui.play.vaults

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hillelsht.smart.R
import com.hillelsht.smart.data.SmartRepository
import com.hillelsht.smart.domain.play.GameId
import com.hillelsht.smart.domain.play.vaults.Buttons
import com.hillelsht.smart.domain.play.vaults.Descent
import com.hillelsht.smart.domain.play.vaults.DescentRules
import com.hillelsht.smart.domain.play.vaults.Phase
import com.hillelsht.smart.domain.play.vaults.Rooms
import com.hillelsht.smart.domain.play.vaults.Tuning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VaultsUiState(
    val descent: Descent,
    val saved: Boolean = false,
)

/**
 * Drives a descent at a fixed 60 steps a second, whatever the display is doing.
 *
 * Palace advanced its physics by the real frame delta, which quietly makes the game a different
 * game on a 90Hz or 120Hz phone. Here the screen accumulates elapsed time and spends it in whole
 * [Tuning.DT] steps, so a run is the same run everywhere — and identical to the one the tests and
 * the browser harness play.
 */
class VaultsViewModel(private val repository: SmartRepository) : ViewModel() {

    private val ui = MutableStateFlow(VaultsUiState(DescentRules.start(Rooms.all)))
    val state = ui.asStateFlow()

    // Held button state. Plain fields rather than Compose state: they change on touch events that
    // must not each trigger a recomposition, and the frame loop reads them directly.
    private var left = false
    private var right = false
    private var down = false
    private var jumpHeld = false

    /**
     * Frames for which jump is reported held regardless of the button's real state.
     *
     * A tap can begin and end between two frames. Without this the press would never be visible to
     * a single step and would simply not happen — a different route to the same silence that made
     * Palace unplayable. Latching guarantees at least one frame of contact; [com.hillelsht.smart
     * .domain.play.vaults.Motion] does the rest, holding the press until it can be spent.
     */
    private var jumpLatch = 0

    private var framesSinceDeath = 0
    private var startedAt = System.currentTimeMillis()

    fun setLeft(held: Boolean) { left = held }
    fun setRight(held: Boolean) { right = held }
    fun setDown(held: Boolean) { down = held }

    fun setJump(held: Boolean) {
        jumpHeld = held
        if (held) jumpLatch = LATCH_FRAMES
    }

    /** One fixed step. Called from the screen's frame loop, never from a timer of its own. */
    fun step() {
        val current = ui.value
        if (current.descent.finished) {
            if (!current.saved) save(current.descent)
            return
        }

        if (current.descent.runner.phase == Phase.DEAD) {
            framesSinceDeath++
            val ticked = DescentRules.tick(current.descent, Buttons())
            ui.value = current.copy(
                descent = if (DescentRules.readyToRespawn(ticked, framesSinceDeath)) {
                    framesSinceDeath = 0
                    DescentRules.respawn(ticked)
                } else {
                    ticked
                },
            )
            return
        }

        val jump = jumpHeld || jumpLatch > 0
        if (jumpLatch > 0) jumpLatch--

        val next = DescentRules.tick(current.descent, Buttons(left, right, jump, down))
        ui.value = current.copy(descent = next)
        if (next.finished) save(next)
    }

    fun restart() {
        framesSinceDeath = 0
        jumpLatch = 0
        startedAt = System.currentTimeMillis()
        ui.value = VaultsUiState(DescentRules.start(Rooms.all))
    }

    /**
     * Records the run. Also called when the player leaves partway down, because a descent that got
     * five rooms deep and was abandoned is still a result worth keeping — and [DescentRules.score]
     * ranks depth above speed precisely so that it reads as one.
     */
    fun save(descent: Descent = ui.value.descent) {
        if (ui.value.saved || descent.roomsCleared == 0) return
        ui.value = ui.value.copy(saved = true)
        viewModelScope.launch {
            runCatching {
                repository.saveRun(
                    game = GameId.VAULTS,
                    score = DescentRules.score(descent),
                    seed = 0L,
                    durationMs = System.currentTimeMillis() - startedAt,
                    detail = "rooms=${descent.roomsCleared} deaths=${descent.deaths}",
                )
            }
        }
    }

    companion object {
        const val LATCH_FRAMES = 2

        fun factory(repository: SmartRepository) = viewModelFactory {
            initializer { VaultsViewModel(repository) }
        }
    }
}

// The vaults' palette. Fixed rather than themed: this is a lit place underground, and it should
// look the same whether the phone is in light mode or dark.
private val Stone = Color(0xFF0F1014)
private val StoneDeep = Color(0xFF0A0B0E)
private val StoneBody = Color(0xFF22242E)
private val StoneLip = Color(0xFF3A3E4E)
private val LooseBody = Color(0xFF2A2530)
private val LooseLip = Color(0xFF4A4050)
private val Torch = Color(0xFFE8A33D)
private val Sky = Color(0xFF6E93E0)
private val Verdigris = Color(0xFF5FB89A)
private val Rust = Color(0xFFC4563F)
private val Chalk = Color(0xFFE6E1D6)
private val ChalkDim = Color(0xFF8A8778)

@Composable
fun VaultsScreen(repository: SmartRepository, onBack: () -> Unit) {
    val viewModel: VaultsViewModel = viewModel(factory = VaultsViewModel.factory(repository))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val descent = state.descent

    // The frame loop. Elapsed time is accumulated and spent in whole fixed steps, so a 120Hz
    // display runs the same physics as a 60Hz one — it simply gets a smoother look at it.
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var acc = 0L
        while (true) {
            val now = withFrameNanos { it }
            // A long gap means the app was backgrounded. Spending it would teleport the runner
            // through a wall, so it is discarded rather than simulated.
            acc += (now - last).coerceAtMost(MAX_CATCHUP_NANOS)
            last = now
            while (acc >= STEP_NANOS) {
                viewModel.step()
                acc -= STEP_NANOS
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Stone)
            .systemBarsPadding(),
    ) {
        RunBar(descent, onBack = { viewModel.save(); onBack() })

        Box(Modifier.weight(1f).fillMaxWidth()) {
            RoomCanvas(descent)
            if (descent.finished) {
                FinishedOverlay(descent, onAgain = viewModel::restart, onBack = {
                    viewModel.save(); onBack()
                })
            }
        }

        Controls(viewModel)
    }
}

@Composable
private fun RunBar(descent: Descent, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.vaults_room_of, descent.roomsCleared + 1, descent.rooms.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChalkDim,
                )
                Text(
                    stringResource(R.string.vaults_clock, descent.elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Chalk,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(Tuning().startingHealth) { i ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i < descent.runner.health) Torch else StoneLip),
                    )
                }
            }
        }
        if (descent.room.epigraph.isNotEmpty()) {
            Text(
                descent.room.epigraph,
                style = MaterialTheme.typography.bodySmall,
                color = ChalkDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Draws the room.
 *
 * The camera follows the runner and is clamped to the room, so a phone in portrait shows a window
 * onto a wide room rather than the whole thing shrunk to illegibility. Everything is a rectangle
 * or a line — vectors, as with every other game here, so none of this grows the APK.
 */
@Composable
private fun RoomCanvas(descent: Descent) {
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(StoneDeep),
    ) {
        val room = descent.room
        val runner = descent.runner

        // Fit roughly this much of the room across the view, so the runner is a readable size.
        val visibleUnits = 420f
        val scale = size.width / visibleUnits
        val roomRight = room.ledges.maxOf { it.x1 }.toFloat()
        val camX = (runner.x.toFloat() * scale - size.width / 2f)
            .coerceIn(0f, (roomRight * scale - size.width).coerceAtLeast(0f))
        val originY = size.height * 0.42f

        fun sx(x: Double) = x.toFloat() * scale - camX
        fun sy(y: Double) = originY + y.toFloat() * scale

        // the drop
        drawRect(
            color = Color(0xFF07080A),
            topLeft = Offset(0f, sy(96.0)),
            size = Size(size.width, (size.height - sy(96.0)).coerceAtLeast(0f)),
        )

        room.ledges.forEach { ledge ->
            if (ledge.id in runner.broken) return@forEach
            val x0 = sx(ledge.x0)
            val w = ((ledge.x1 - ledge.x0).toFloat() * scale)
            val y = sy(ledge.y)
            val thickness = 18f * scale
            drawRect(
                color = if (ledge.collapsing) LooseBody else StoneBody,
                topLeft = Offset(x0, y),
                size = Size(w, thickness),
            )
            // A loose tile being stood on warms toward rust as it runs out of patience.
            val strain = if (ledge.collapsing && runner.standingOn == ledge.id) {
                (runner.standingFrames.toFloat() / Tuning().collapseFrames).coerceIn(0f, 1f)
            } else {
                0f
            }
            val lip = when {
                strain > 0f -> blend(LooseLip, Rust, strain)
                ledge.collapsing -> LooseLip
                else -> StoneLip
            }
            drawRect(
                color = lip,
                topLeft = Offset(x0, y),
                size = Size(w, 2.5f * scale + 2f * scale * strain),
            )
        }

        room.chompers.forEach { chomper ->
            val bx = sx(chomper.x)
            val by = sy(chomper.y)
            val reach = chomper.reach.toFloat() * scale
            val hw = chomper.halfWidth.toFloat() * scale
            val open = chomper.isOpen(runner.frame)
            // The housing stays drawn while the blade is open, so it still reads as a threat.
            drawRect(
                color = StoneLip,
                topLeft = Offset(bx - hw - 1.5f, by - reach),
                size = Size(hw * 2f + 3f, 2f),
            )
            val h = if (open) reach * 0.16f else reach
            drawRect(
                color = if (open) StoneLip else Rust,
                topLeft = Offset(bx - hw, by - h),
                size = Size(hw * 2f, h),
            )
        }

        // the way out
        drawRect(
            color = Verdigris,
            topLeft = Offset(sx(room.exitX) - 1.5f, sy(0.0) - 54f * scale),
            size = Size(3f, 54f * scale + 18f * scale),
        )

        // the runner
        val px = sx(runner.x)
        val py = sy(runner.y)
        val bodyW = 12f * scale
        val bodyH = 28f * scale
        when {
            runner.phase == Phase.DEAD -> drawRect(
                color = Color(0xFF5A3038),
                topLeft = Offset(px - bodyW, py - 7f * scale),
                size = Size(bodyW * 2f, 7f * scale),
            )
            runner.hanging -> {
                drawRect(Verdigris, Offset(px - bodyW / 2f, py), Size(bodyW, bodyH))
                drawRect(Verdigris, Offset(px - bodyW * 0.75f, py - 3f * scale), Size(bodyW * 1.5f, 3f * scale))
            }
            else -> drawRect(
                color = if (runner.grounded) Torch else Sky,
                topLeft = Offset(px - bodyW / 2f, py - bodyH),
                size = Size(bodyW, bodyH),
            )
        }

        // An unspent jump press, waiting for a frame it can be used on. Palace threw this away;
        // showing it is the cheapest way to make the fix legible while playing.
        if (runner.jumpPressedAgo != null && runner.phase != Phase.DEAD) {
            drawCircle(
                color = Verdigris,
                radius = 3.5f * scale,
                center = Offset(px, py - (if (runner.hanging) -34f else 36f) * scale),
            )
        }
    }
}

@Composable
private fun FinishedOverlay(descent: Descent, onAgain: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xD10F1014)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.vaults_finished_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Chalk,
            )
            Text(
                stringResource(
                    R.string.vaults_finished_summary,
                    descent.elapsedSeconds,
                    descent.deaths,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = ChalkDim,
            )
            Text(
                stringResource(R.string.vaults_score, DescentRules.score(descent)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Torch,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, shape = RoundedCornerShape(14.dp)) {
                    Text(stringResource(R.string.action_back))
                }
                Button(onClick = onAgain, shape = RoundedCornerShape(14.dp)) {
                    Text(stringResource(R.string.vaults_again))
                }
            }
        }
    }
}

@Composable
private fun Controls(viewModel: VaultsViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton("◀") { viewModel.setLeft(it) }
            HoldButton("▶") { viewModel.setRight(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton("▼") { viewModel.setDown(it) }
            HoldButton("▲", wide = true) { viewModel.setJump(it) }
        }
    }
}

/**
 * A button that reports being held rather than being clicked.
 *
 * `onPress`/`awaitRelease` rather than a click listener, because a platformer needs to know that a
 * direction is *still* held, and because the release must be reported even if the finger slides
 * off — hence the `finally`.
 */
@Composable
private fun HoldButton(label: String, wide: Boolean = false, onHold: (Boolean) -> Unit) {
    Box(
        Modifier
            .then(if (wide) Modifier.width(104.dp).height(64.dp) else Modifier.size(64.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2029))
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
        Text(label, style = MaterialTheme.typography.titleLarge, color = Chalk)
    }
}

/** Named to avoid any ambiguity with Compose's own Color lerp. */
private fun blend(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private const val STEP_NANOS = 1_000_000_000L / Tuning.FPS
private const val MAX_CATCHUP_NANOS = 200_000_000L
