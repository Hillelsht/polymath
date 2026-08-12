package com.hillelsht.smart.ui.mascot

/**
 * Aryeh's geometry, in SVG path syntax.
 *
 * Compose ships a path parser that reads this syntax directly, so these are the exact strings
 * the design was reviewed against rather than a hand-translation of them into `Path` calls.
 * That removes transcription error entirely: the lion in the app is the lion that was approved,
 * by construction.
 *
 * Coordinates are in the rig's own space — the figure stands on y = 0 and faces +x, and the
 * head is drawn about its own origin so a pose only has to say where to put it. [AryehRig]
 * handles placing and scaling into whatever box the caller gives it.
 */
internal object AryehArt {

    // Six spikes, 60° apart, so the gap the crown used to fill sits exactly between the two
    // that flank the top — the same shape at every point, just rotated, which is what keeps a
    // silhouette actually symmetric instead of merely close.
    const val MANE =
        "M16.1,-5.2 Q25.0,-4.4 27.6,0.0 Q25.0,4.4 16.1,5.2 A17.0,17.0 0 0 0 16.1,-5.2 Z M12.6,11.3 Q16.3,19.5 13.8,23.9 Q8.7,23.9 3.5,16.5 A17.0,17.0 0 0 0 12.6,11.3 Z M-3.5,16.5 Q-8.7,23.9 -13.8,23.9 Q-16.3,19.5 -12.6,11.3 A17.0,17.0 0 0 0 -3.5,16.5 Z M-16.1,5.2 Q-25.0,4.4 -27.6,0.0 Q-25.0,-4.4 -16.1,-5.2 A17.0,17.0 0 0 0 -16.1,5.2 Z M-12.6,-11.3 Q-16.3,-19.5 -13.8,-23.9 Q-8.7,-23.9 -3.5,-16.5 A17.0,17.0 0 0 0 -12.6,-11.3 Z M3.5,-16.5 Q8.7,-23.9 13.8,-23.9 Q16.3,-19.5 12.6,-11.3 A17.0,17.0 0 0 0 3.5,-16.5 Z"
    const val MANE_FRONT =
        "M-12.0,-12.0 Q-15.4,-20.2 -12.9,-24.3 Q-8.1,-24.0 -3.2,-16.6 A17.0,17.0 0 0 0 -12.0,-12.0 Z M-4.4,-16.4 Q-2.9,-20.1 0.0,-22.0 Q2.9,-20.1 4.4,-16.4 A17.0,17.0 0 0 0 -4.4,-16.4 Z M3.2,-16.6 Q8.1,-24.0 12.9,-24.3 Q15.4,-20.2 12.0,-12.0 A17.0,17.0 0 0 0 3.2,-16.6 Z"
    const val SKULL =
        "M-18.02,0.00 a18.02,18.02 0 1,0 36.04,0 a18.02,18.02 0 1,0 -36.04,0 Z"
    const val EARS =
        "M-18.00,-11.50 a5.40,5.40 0 1,0 10.80,0 a5.40,5.40 0 1,0 -10.80,0 Z M7.20,-11.50 a5.40,5.40 0 1,0 10.80,0 a5.40,5.40 0 1,0 -10.80,0 Z"
    const val EARS_INNER =
        "M-15.30,-11.10 a2.70,2.70 0 1,0 5.40,0 a2.70,2.70 0 1,0 -5.40,0 Z M9.90,-11.10 a2.70,2.70 0 1,0 5.40,0 a2.70,2.70 0 1,0 -5.40,0 Z"
    const val BLUSH =
        "M-14.50,5.10 a3.50,2.30 0 1,0 7.00,0 a3.50,2.30 0 1,0 -7.00,0 Z M7.50,5.10 a3.50,2.30 0 1,0 7.00,0 a3.50,2.30 0 1,0 -7.00,0 Z"
    const val MUZZLE =
        "M-10.80,8.50 a6.10,5.00 0 1,0 12.20,0 a6.10,5.00 0 1,0 -12.20,0 Z M-1.40,8.50 a6.10,5.00 0 1,0 12.20,0 a6.10,5.00 0 1,0 -12.20,0 Z"
    const val NOSE =
        "M-3.1,3.1 Q0.0,1.2 3.1,3.1 Q0.0,7.3 -3.1,3.1 Z"
    const val MOUTH =
        "M0.0,6.6 L0.0,9.1 M0.0,9.1 Q-2.8,12.7 -6.6,8.0 M0.0,9.1 Q2.8,12.7 6.6,8.0"
    const val EYES =
        "M-10.70,-1.90 a3.90,4.30 0 1,0 7.80,0 a3.90,4.30 0 1,0 -7.80,0 Z M2.90,-1.90 a3.90,4.30 0 1,0 7.80,0 a3.90,4.30 0 1,0 -7.80,0 Z"
    const val GLINT =
        "M-9.60,-3.50 a1.50,1.60 0 1,0 3.00,0 a1.50,1.60 0 1,0 -3.00,0 Z M4.00,-3.50 a1.50,1.60 0 1,0 3.00,0 a1.50,1.60 0 1,0 -3.00,0 Z"
    const val GLINT_SMALL =
        "M-6.20,0.30 a0.80,0.80 0 1,0 1.60,0 a0.80,0.80 0 1,0 -1.60,0 Z M7.40,0.30 a0.80,0.80 0 1,0 1.60,0 a0.80,0.80 0 1,0 -1.60,0 Z"
    const val TORSO_STAND =
        "M32,-24 C41,-36 40,-50 29,-58 C16,-62 2,-58 -8,-56 C-22,-58 -34,-52 -38,-42 C-41,-33 -37,-25 -27,-24 C-18,-30 -7,-35 2,-34 C13,-33 25,-28 32,-24 Z"
    const val TORSO_SIT =
        "M30,-30 C37,-42 36,-55 27,-62 C16,-67 4,-63 -6,-58 C-20,-52 -32,-40 -34,-24 C-35,-13 -28,-7 -16,-7 C-4,-7 6,-11 12,-19 C18,-25 25,-29 30,-30 Z"
    const val TORSO_CURL =
        "M-34,-16 C-40,-30 -30,-42 -12,-44 C8,-46 30,-40 36,-26 C41,-14 32,-4 14,-3 C-6,-2 -28,-4 -34,-16 Z"
    const val NECK_STAND =
        "M24,-60 C38,-64 50,-60 52,-50 C53,-40 42,-33 30,-35 C22,-42 20,-53 24,-60 Z"
    const val NECK_SIT =
        "M26,-66 C40,-70 52,-66 54,-56 C55,-46 44,-38 32,-40 C24,-46 22,-58 26,-66 Z"
    const val NECK_CURL =
        "M6,-34 C16,-42 28,-42 32,-34 C34,-26 24,-20 14,-22 C6,-26 4,-30 6,-34 Z"
    const val SHOULDER =
        "M9.50,-47.00 a13.50,15.50 0 1,0 27.00,0 a13.50,15.50 0 1,0 -27.00,0 Z"
    const val THIGH =
        "M-42.50,-42.00 a16.50,17.50 0 1,0 33.00,0 a16.50,17.50 0 1,0 -33.00,0 Z"
    const val LEG_HIND_FAR =
        "M-30.0,-40.0 C-30.0,-22.9 -28.4,-13.4 -27.8,-5.8 A3.8,3.8 0 0 0 -20.2,-5.8 C-19.6,-13.4 -18.0,-22.9 -18.0,-40.0 Z"
    const val LEG_HIND_NEAR =
        "M-24.2,-40.0 C-24.2,-22.9 -22.4,-13.4 -21.8,-5.8 A3.8,3.8 0 0 0 -14.2,-5.8 C-13.7,-13.4 -11.8,-22.9 -11.8,-40.0 Z"
    const val LEG_FORE_FAR =
        "M13.0,-36.0 C13.0,-20.7 14.8,-12.2 15.4,-5.6 A3.6,3.6 0 0 0 22.6,-5.6 C23.2,-12.2 25.0,-20.7 25.0,-36.0 Z"
    const val LEG_FORE_NEAR =
        "M19.5,-36.0 C19.5,-20.7 21.6,-12.2 22.2,-5.8 A3.8,3.8 0 0 0 29.8,-5.8 C30.4,-12.2 32.5,-20.7 32.5,-36.0 Z"
    const val LEG_SIT_FAR =
        "M10.2,-30.0 C10.2,-18.3 11.8,-11.8 12.4,-7.6 A3.6,3.6 0 0 0 19.6,-7.6 C20.2,-11.8 21.8,-18.3 21.8,-30.0 Z"
    const val LEG_SIT_NEAR =
        "M18.8,-32.0 C18.8,-19.4 20.6,-12.4 21.2,-7.8 A3.8,3.8 0 0 0 28.8,-7.8 C29.4,-12.4 31.2,-19.4 31.2,-32.0 Z"
    const val TAIL =
        "M-34,-40 C-58,-34 -70,-50 -68,-66"
    const val TAIL_TUFT =
        "M-74.50,-66.00 a6.50,8.50 0 1,0 13.00,0 a6.50,8.50 0 1,0 -13.00,0 Z"
}
