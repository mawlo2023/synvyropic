package de.synvyropic.model

import de.synvyropic.model.TransitionType.*

/**
 * Ein Stil ist eine Regelmenge für den Auto-Schnitt.
 * @param beatsPerCut Standard-Schnittraster (1, 2 oder 4 Beats)
 * @param transitionBeatFraction Übergangsdauer relativ zur Beatlänge
 */
data class Style(
    val id: String,
    val name: String,
    val description: String,
    val beatsPerCut: Int,
    val transitions: List<Pair<TransitionType, Int>>,
    val downbeatTransitions: List<Pair<TransitionType, Int>>,
    val dropTransition: TransitionType,
    val transitionBeatFraction: Float,
    val kenBurnsStrength: Float,
    val beatReactive: Boolean,
    val clipEffect: ClipEffectType?,
    val colorPresetId: String?,
)

object Styles {
    val GLITCH_ENERGY = Style(
        id = "glitch_energy",
        name = "Glitch Energy",
        description = "Schnelle Schnitte, RGB-Split und Glitches",
        beatsPerCut = 2,
        transitions = listOf(RGB_SPLIT to 4, DIGITAL_GLITCH to 3, CHROMATIC_ABERRATION to 3, ZOOM_PUNCH_IN to 2, CUT to 1),
        downbeatTransitions = listOf(ZOOM_PUNCH_IN to 3, RGB_SPLIT to 2, PIXEL_SORT to 1),
        dropTransition = DATAMOSH,
        transitionBeatFraction = 0.5f,
        kenBurnsStrength = 0.12f,
        beatReactive = true,
        clipEffect = ClipEffectType.RGB_SHIFT_PULSE,
        colorPresetId = "high_contrast",
    )

    val SMOOTH_CINEMATIC = Style(
        id = "smooth_cinematic",
        name = "Smooth Cinematic",
        description = "Ruhige Schnitte, weiche Blenden, Light Leaks",
        beatsPerCut = 4,
        transitions = listOf(CROSSFADE to 3, DISSOLVE to 2, LIGHT_LEAK to 2, WHIP_PAN_LEFT to 1, WHIP_PAN_RIGHT to 1),
        downbeatTransitions = listOf(LIGHT_LEAK to 2, LUMA_MORPH to 1, CROSSFADE to 2),
        dropTransition = LENS_FLARE,
        transitionBeatFraction = 1.0f,
        kenBurnsStrength = 0.18f,
        beatReactive = false,
        clipEffect = null,
        colorPresetId = "cinematic",
    )

    val FLASH_PARTY = Style(
        id = "flash_party",
        name = "Flash Party",
        description = "Schnitt auf jeden Beat, Flashes und Zoom-Punches",
        beatsPerCut = 1,
        transitions = listOf(WHITE_FLASH to 3, ZOOM_PUNCH_IN to 3, ZOOM_PUNCH_OUT to 2, SHAKE to 2, CUT to 2),
        downbeatTransitions = listOf(COLOR_FLASH to 3, ZOOM_PUNCH_IN to 2),
        dropTransition = COLOR_FLASH,
        transitionBeatFraction = 0.35f,
        kenBurnsStrength = 0.08f,
        beatReactive = true,
        clipEffect = ClipEffectType.BEAT_PULSE,
        colorPresetId = null,
    )

    val RETRO_VHS = Style(
        id = "retro_vhs",
        name = "Retro VHS",
        description = "VHS-Look, Linien-Wipes, chromatische Ränder",
        beatsPerCut = 2,
        transitions = listOf(VHS to 3, LINE_WIPE to 2, CHROMATIC_ABERRATION to 2, CROSSFADE to 1),
        downbeatTransitions = listOf(VHS to 2, ZIGZAG_WIPE to 1),
        dropTransition = DIGITAL_GLITCH,
        transitionBeatFraction = 0.6f,
        kenBurnsStrength = 0.1f,
        beatReactive = true,
        clipEffect = ClipEffectType.VHS_OVERLAY,
        colorPresetId = "vintage",
    )

    val all = listOf(GLITCH_ENERGY, SMOOTH_CINEMATIC, FLASH_PARTY, RETRO_VHS)

    fun byId(id: String): Style = all.firstOrNull { it.id == id } ?: GLITCH_ENERGY
}
