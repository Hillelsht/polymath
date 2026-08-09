package com.hillelsht.smart.ui.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hillelsht.smart.domain.MascotDirector
import com.hillelsht.smart.domain.MascotDirector.Activity
import com.hillelsht.smart.domain.MascotDirector.Surface
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** He is drawn 64dp tall; the rig stands about 106 units, so this maps one onto the other. */
private const val RIG_HEIGHT = 106f
private const val DRAWN_HEIGHT_DP = 64f

/** How far across the strip he patrols, as a fraction of the space he has. */
private const val WALK_FROM = 0.10f
private const val WALK_TO = 0.84f

/**
 * Aryeh, living along the bottom of a tab.
 *
 * Three layers of motion on unrelated clocks — ambient (breathing at 1.85s, blinking at 6.3s,
 * mane sway at 2.15s, ear twitch at 8.1s, tail at 1.65s), whatever activity [MascotDirector]
 * chose, and your taps. None of those periods is a multiple of another, so the combination
 * almost never repeats; that is the difference between a character that is alive and one that
 * is animated.
 *
 * He is [clearAndSetSemantics] with nothing, so a screen reader never meets him. He is
 * decoration, and putting a wandering lion into the accessibility tree would be hostile.
 */
@Composable
fun MascotHost(surface: Surface, modifier: Modifier = Modifier) {
    var beat by remember { mutableStateOf(MascotDirector.next(surface)) }
    var dismissed by remember { mutableStateOf(false) }
    var lastTouchMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var greeting by remember { mutableStateOf(false) }

    // Changing tab retires anything the new tab does not do — except sleeping, which carries
    // over, because waking him because you tapped Progress is worse than letting him lie.
    LaunchedEffect(surface) {
        if (!MascotDirector.carriesOver(beat.activity, surface)) {
            beat = MascotDirector.next(surface, previous = beat.activity)
        }
    }

    LaunchedEffect(beat, surface) {
        delay(beat.durationMs)
        beat = MascotDirector.next(
            surface = surface,
            previous = beat.activity,
            idleMs = System.currentTimeMillis() - lastTouchMs,
        )
    }

    if (dismissed) return

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(DRAWN_HEIGHT_DP.dp)
            .clearAndSetSemantics { },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val scale = with(density) { DRAWN_HEIGHT_DP.dp.toPx() } / RIG_HEIGHT
        val figureWidth = 110f * scale

        val pose = when (beat.activity) {
            Activity.READ, Activity.WATCH -> AryehPose.SIT
            Activity.NAP -> AryehPose.CURL
            else -> AryehPose.STAND
        }
        val walking = beat.activity == Activity.WANDER

        val ambient = rememberInfiniteTransition(label = "aryeh-ambient")
        val breath by ambient.animateFloat(
            1f, 1.03f, infiniteRepeatable(tween(1850), RepeatMode.Reverse), label = "breath",
        )
        val sway by ambient.animateFloat(
            -1.8f, 1.8f, infiniteRepeatable(tween(2150), RepeatMode.Reverse), label = "sway",
        )
        val tailSwing by ambient.animateFloat(
            -9f, 11f, infiniteRepeatable(tween(1650), RepeatMode.Reverse), label = "tail",
        )
        val blink by ambient.animateFloat(
            0f, 1f, infiniteRepeatable(tween(6300)), label = "blink",
        )
        val twitch by ambient.animateFloat(
            0f, 1f, infiniteRepeatable(tween(8100)), label = "twitch",
        )
        val stride by ambient.animateFloat(
            0f, 1f, infiniteRepeatable(tween(1020)), label = "stride",
        )

        // A double blink near the end of a long cycle. One evenly-spaced blink reads as a
        // metronome; two close together and then a long pause reads as an animal.
        val eyeOpen = when {
            beat.activity == Activity.NAP -> 0.04f
            blink in 0.880f..0.900f || blink in 0.920f..0.940f -> 0.08f
            else -> 1f
        }

        val travel = remember { Animatable(WALK_FROM) }
        var facingRight by remember { mutableStateOf(true) }
        LaunchedEffect(walking, widthPx) {
            if (!walking) return@LaunchedEffect
            while (true) {
                facingRight = true
                travel.animateTo(WALK_TO, tween(7000))
                facingRight = false
                travel.animateTo(WALK_FROM, tween(7000))
            }
        }

        val hop = remember { Animatable(0f) }
        LaunchedEffect(greeting) {
            if (!greeting) return@LaunchedEffect
            hop.animateTo(1f, tween(200))
            hop.animateTo(0f, tween(260))
            greeting = false
        }

        val swing = if (walking) (sin(stride * 2 * PI) * 20.0).toFloat() else 0f
        val joints = AryehJoints(
            breath = breath,
            eyeOpen = eyeOpen,
            maneSway = sway,
            earTwitch = if (twitch in 0.86f..0.92f) -13f else 0f,
            tailSwing = tailSwing,
            headTilt = if (beat.activity == Activity.READ) 8f else 0f,
            headLift = if (greeting) -4f else 0f,
            foreSwing = swing,
            hindSwing = -swing,
        )

        val figureX = (widthPx - figureWidth) * travel.value + figureWidth * 0.45f

        // The Canvas carries no pointer input, so it is transparent to touch. Anything else
        // would have the strip swallow every tap in the bottom 64dp of all four tabs.
        Canvas(Modifier.fillMaxSize()) {
            drawAryeh(
                origin = Offset(x = figureX, y = size.height - hop.value * 10f),
                scale = scale,
                pose = pose,
                joints = joints,
                facingRight = facingRight,
            )
        }

        // Only his own footprint is tappable, and it follows him as he walks.
        Box(
            Modifier
                .offset { IntOffset((figureX - figureWidth / 2f).roundToInt(), 0) }
                .size(with(density) { figureWidth.toDp() }, DRAWN_HEIGHT_DP.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            lastTouchMs = System.currentTimeMillis()
                            greeting = true
                        },
                        // Long-press sends him away until the app is next launched. An animated
                        // character with no off switch is one people uninstall the app over.
                        onLongPress = { dismissed = true },
                    )
                },
        )
    }
}
