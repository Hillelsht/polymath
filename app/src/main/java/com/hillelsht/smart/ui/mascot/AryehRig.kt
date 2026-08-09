package com.hillelsht.smart.ui.mascot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/** Aryeh's palette. Fixed, not theme-derived — he is the same animal on either background. */
internal object AryehColors {
    val Mane = Color(0xFFB9741C)
    val ManeFront = Color(0xFFD98F2C)
    val Fur = Color(0xFFF2C978)
    val Muscle = Color(0xFFE9B45F)
    val Pale = Color(0xFFFCEBC8)
    val Dark = Color(0xFF2A1A06)
    val Blush = Color(0xFFE8836B)
    val Crown = Color(0xFFFFD98A)
    val CrownRim = Color(0xFFB9741C)
    val White = Color(0xFFFFFFFF)
}

/**
 * The pose Aryeh holds, and the joint angles laid over it.
 *
 * Pose is data, not artwork: the same head, mane, crown, torso, four tapered limbs and tail are
 * drawn in every one of them. That is what lets him sit, walk, read and sleep without shipping
 * a frame of animation for each, and it is why the whole character costs a few KB of path data.
 */
internal enum class AryehPose { STAND, SIT, CURL }

internal data class AryehJoints(
    val breath: Float = 1f,
    val eyeOpen: Float = 1f,
    val maneSway: Float = 0f,
    val earTwitch: Float = 0f,
    val tailSwing: Float = 0f,
    val headTilt: Float = 0f,
    val headLift: Float = 0f,
    /** Fore and hind limb swing, in degrees. Opposite signs give a gait. */
    val foreSwing: Float = 0f,
    val hindSwing: Float = 0f,
)

/** Parsed once and reused — re-parsing 30 paths every frame would be visible. */
private val cache = HashMap<String, Path>()

private fun path(data: String): Path =
    cache.getOrPut(data) { PathParser().parsePathString(data).toPath() }

/**
 * Draws Aryeh at [origin] (his feet) scaled by [scale].
 *
 * Layer order is the whole illusion: far limbs sit behind the torso in a darker tone, the neck
 * bridges chest to skull, and the mane's front tufts land on top of the crown band so the crown
 * reads as worn rather than balanced on the fur.
 */
internal fun DrawScope.drawAryeh(
    origin: Offset,
    scale: Float,
    pose: AryehPose,
    joints: AryehJoints,
    facingRight: Boolean,
) {
    translate(origin.x, origin.y) {
        scale(if (facingRight) scale else -scale, scale, pivot = Offset.Zero) {
            when (pose) {
                AryehPose.CURL -> drawCurled(joints)
                AryehPose.SIT -> drawSeated(joints)
                AryehPose.STAND -> drawStanding(joints)
            }
        }
    }
}

private fun DrawScope.fill(data: String, color: Color, alpha: Float = 1f) =
    drawPath(path(data), color, alpha = alpha)

private fun DrawScope.stroke(data: String, color: Color, width: Float, alpha: Float = 1f) =
    drawPath(path(data), color, alpha = alpha, style = Stroke(width = width))

private fun DrawScope.tail(joints: AryehJoints) {
    rotate(joints.tailSwing, pivot = Offset(-34f, -40f)) {
        stroke(AryehArt.TAIL, AryehColors.Fur, 7.5f)
        fill(AryehArt.TAIL_TUFT, AryehColors.Mane)
    }
}

private fun DrawScope.torso(data: String, breath: Float) {
    scale(1f, breath, pivot = Offset(0f, -40f)) { fill(data, AryehColors.Fur) }
}

private fun DrawScope.drawStanding(j: AryehJoints) {
    tail(j)
    rotate(j.hindSwing, pivot = Offset(-24f, -40f)) {
        fill(AryehArt.LEG_HIND_FAR, AryehColors.Muscle)
    }
    rotate(-j.foreSwing, pivot = Offset(19f, -36f)) {
        fill(AryehArt.LEG_FORE_FAR, AryehColors.Muscle)
    }
    torso(AryehArt.TORSO_STAND, j.breath)
    fill(AryehArt.THIGH, AryehColors.Muscle)
    fill(AryehArt.SHOULDER, AryehColors.Muscle)
    rotate(-j.hindSwing, pivot = Offset(-18f, -40f)) {
        fill(AryehArt.LEG_HIND_NEAR, AryehColors.Fur)
    }
    rotate(j.foreSwing, pivot = Offset(26f, -36f)) {
        fill(AryehArt.LEG_FORE_NEAR, AryehColors.Fur)
    }
    fill(AryehArt.NECK_STAND, AryehColors.Fur)
    head(Offset(46f, -68f), j)
}

private fun DrawScope.drawSeated(j: AryehJoints) {
    tail(j)
    fill(AryehArt.LEG_SIT_FAR, AryehColors.Muscle)
    torso(AryehArt.TORSO_SIT, j.breath)
    fill(AryehArt.THIGH, AryehColors.Muscle)
    fill(AryehArt.SHOULDER, AryehColors.Muscle)
    rotate(j.foreSwing, pivot = Offset(25f, -32f)) {
        fill(AryehArt.LEG_SIT_NEAR, AryehColors.Fur)
    }
    fill(AryehArt.NECK_SIT, AryehColors.Fur)
    head(Offset(48f, -74f), j)
}

private fun DrawScope.drawCurled(j: AryehJoints) {
    tail(j)
    torso(AryehArt.TORSO_CURL, j.breath)
    fill(AryehArt.NECK_CURL, AryehColors.Fur)
    head(Offset(24f, -31f), j, scale = 0.86f)
}

private fun DrawScope.head(at: Offset, j: AryehJoints, scale: Float = 1f) {
    translate(at.x, at.y + j.headLift) {
        rotate(j.headTilt, pivot = Offset.Zero) {
            scale(scale, scale, pivot = Offset.Zero) {
                rotate(j.maneSway, pivot = Offset.Zero) {
                    fill(AryehArt.MANE, AryehColors.Mane)
                }
                fill(AryehArt.SKULL, AryehColors.Fur)

                // Crown first, then the front tufts over its band — that ordering is what makes
                // it look worn. Reverse the two and it goes back to sitting on top like a hat.
                fill(AryehArt.CROWN_ARCH, AryehColors.Crown)
                fill(AryehArt.CROWN_BAND, AryehColors.Crown)
                stroke(AryehArt.CROWN_RIM, AryehColors.CrownRim, 0.9f, alpha = 0.55f)
                fill(AryehArt.CROWN_JEWELS, AryehColors.CrownRim, alpha = 0.75f)
                rotate(j.maneSway, pivot = Offset.Zero) {
                    fill(AryehArt.MANE_FRONT, AryehColors.ManeFront)
                }

                face(j)
            }
        }
    }
}

private fun DrawScope.face(j: AryehJoints) {
    rotate(j.earTwitch, pivot = Offset(-12.6f, -12.6f)) {
        fill(AryehArt.EARS, AryehColors.Mane)
        fill(AryehArt.EARS_INNER, AryehColors.Pale)
    }
    fill(AryehArt.BLUSH, AryehColors.Blush, alpha = 0.34f)
    fill(AryehArt.MUZZLE, AryehColors.Pale)
    fill(AryehArt.NOSE, AryehColors.Dark)
    stroke(AryehArt.MOUTH, AryehColors.Dark, 1.7f)

    // Blinking squashes the eyes rather than hiding them, so the lids read as closing.
    scale(1f, j.eyeOpen.coerceAtLeast(0.02f), pivot = Offset(0f, -2f)) {
        fill(AryehArt.EYES, AryehColors.Dark)
        if (j.eyeOpen > 0.5f) {
            fill(AryehArt.GLINT, AryehColors.White)
            fill(AryehArt.GLINT_SMALL, AryehColors.White, alpha = 0.8f)
        }
    }
}
