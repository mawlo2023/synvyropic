package de.synvyropic.app.ui.editor

import de.synvyropic.model.TransitionType
import de.synvyropic.model.TransitionType.*
import java.util.Locale

fun TransitionType.label(): String = when (this) {
    CUT -> "Harter Schnitt"
    CROSSFADE -> "Überblendung"
    RGB_SPLIT -> "RGB-Split"
    CHROMATIC_ABERRATION -> "Chromatische Aberration"
    DIGITAL_GLITCH -> "Digital Glitch"
    VHS -> "VHS"
    PIXEL_SORT -> "Pixel-Sort"
    DATAMOSH -> "Datamosh"
    ZOOM_PUNCH_IN -> "Zoom-Punch rein"
    ZOOM_PUNCH_OUT -> "Zoom-Punch raus"
    WHIP_PAN_LEFT -> "Whip-Pan links"
    WHIP_PAN_RIGHT -> "Whip-Pan rechts"
    WHIP_PAN_UP -> "Whip-Pan hoch"
    WHIP_PAN_DOWN -> "Whip-Pan runter"
    SPIN -> "Spin"
    SHAKE -> "Shake"
    STRETCH -> "Stretch"
    WHITE_FLASH -> "Weißer Flash"
    COLOR_FLASH -> "Farb-Flash"
    LIGHT_LEAK -> "Light Leak"
    LENS_FLARE -> "Lens Flare"
    BURN -> "Burn"
    CIRCLE_WIPE -> "Kreis-Wipe"
    LINE_WIPE -> "Linien-Wipe"
    ZIGZAG_WIPE -> "Zickzack-Wipe"
    GRID_WIPE -> "Kachel-Wipe"
    DISSOLVE -> "Dissolve"
    LUMA_MORPH -> "Luma-Morph"
}

/** 83500 → "1:23,5" */
fun formatMs(ms: Long): String {
    val totalTenths = ms / 100
    val tenths = totalTenths % 10
    val s = (totalTenths / 10) % 60
    val m = totalTenths / 600
    return String.format(Locale.GERMANY, "%d:%02d,%d", m, s, tenths)
}
