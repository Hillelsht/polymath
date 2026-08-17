package com.hillelsht.smart.domain.play.vaults

/**
 * A whole run, small enough to fit in a link.
 *
 * The engine is deterministic: the same room, the same tuning and the same buttons produce the
 * same run, frame for frame. So a run does not need to be recorded — **the inputs *are* the run**,
 * and everything else can be recomputed by replaying them. That is what makes a ghost race
 * possible with no server behind it: you send someone a few hundred characters and their browser
 * reconstructs your attempt exactly, down to the frame you mistimed.
 *
 * Four buttons is four bits a frame, which for a minute of play would be 1,800 bytes before any
 * encoding — too much for a URL people paste into a chat. But a player does not change what they
 * are holding sixty times a second; they hold *right* for two seconds, then add *jump*. So the
 * stream is stored as runs of identical frames, which is where nearly all of the size goes.
 *
 * The text is deliberately not base64. It uses one alphabet for the button mask and a different
 * one for the length, so a token is self-delimiting, the whole string is URL-safe without
 * escaping, and a corrupted link fails to parse instead of silently replaying something else.
 */
object Ghost {

    /** Button masks, one character each. Uppercase, so a token boundary is unmistakable. */
    private const val MASKS = "ABCDEFGHIJKLMNOP"

    /** Frame counts, base 36. Lowercase and digits, so they can never be read as a mask. */
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

    /**
     * A run longer than this is not a Vaults attempt, it is a corrupt link or someone probing.
     * Ten minutes at sixty frames a second, against a par of ninety seconds.
     */
    const val MAX_FRAMES = 36_000

    fun bits(buttons: Buttons): Int =
        (if (buttons.left) 1 else 0) or
            (if (buttons.right) 2 else 0) or
            (if (buttons.jump) 4 else 0) or
            (if (buttons.down) 8 else 0)

    fun buttons(bits: Int): Buttons = Buttons(
        left = bits and 1 != 0,
        right = bits and 2 != 0,
        jump = bits and 4 != 0,
        down = bits and 8 != 0,
    )

    /** Packs a frame-by-frame input stream into its shareable form. */
    fun encode(frames: List<Buttons>): String {
        val out = StringBuilder()
        var index = 0
        while (index < frames.size) {
            val mask = bits(frames[index])
            var length = 1
            while (index + length < frames.size && bits(frames[index + length]) == mask) length++
            out.append(MASKS[mask]).append(base36(length))
            index += length
        }
        return out.toString()
    }

    /**
     * Unpacks a shared run, or returns null if the text is not one.
     *
     * Null rather than an exception or a best effort: this arrives from a URL, so it is the one
     * input here that a stranger controls. A ghost that half-decodes would race against something
     * its owner never played, which is worse than declining to race at all.
     */
    fun decode(text: String): List<Buttons>? {
        if (text.isEmpty()) return null
        val frames = mutableListOf<Buttons>()
        var index = 0
        while (index < text.length) {
            val mask = MASKS.indexOf(text[index])
            if (mask < 0) return null
            index++

            var length = 0
            var digits = 0
            while (index < text.length && text[index] !in MASKS) {
                val digit = DIGITS.indexOf(text[index])
                if (digit < 0) return null
                length = length * 36 + digit
                digits++
                index++
                // Checked inside the loop, not after: a long enough run of digits would overflow
                // to a plausible-looking small number before anything downstream could object.
                if (length > MAX_FRAMES) return null
            }
            if (digits == 0 || length == 0) return null

            val buttons = buttons(mask)
            repeat(length) { frames += buttons }
            if (frames.size > MAX_FRAMES) return null
        }
        return frames
    }

    private fun base36(value: Int): String {
        if (value == 0) return "0"
        val out = StringBuilder()
        var left = value
        while (left > 0) {
            out.append(DIGITS[left % 36])
            left /= 36
        }
        return out.reverse().toString()
    }

    /**
     * Replays a recorded run against a room and reports where it got to.
     *
     * The respawn after a death is applied here rather than left to whoever is driving, because it
     * is part of what makes a run reproducible: the recorded stream contains the frames spent
     * lying on the floor, and a replay that skipped them would drift out of step with the original
     * within one death. [DescentRules.DEATH_PAUSE_FRAMES] is the whole policy.
     */
    fun replay(rooms: List<Room>, frames: List<Buttons>, tuning: Tuning = Tuning()): Descent {
        var descent = DescentRules.start(rooms, tuning)
        var sinceDeath = 0
        for (frame in frames) {
            if (descent.finished) break
            if (descent.runner.phase == Phase.DEAD) {
                sinceDeath++
                descent = DescentRules.tick(descent, Buttons(), tuning)
                if (DescentRules.readyToRespawn(descent, sinceDeath)) {
                    descent = DescentRules.respawn(descent, tuning)
                    sinceDeath = 0
                }
                continue
            }
            descent = DescentRules.tick(descent, frame, tuning)
        }
        return descent
    }
}
