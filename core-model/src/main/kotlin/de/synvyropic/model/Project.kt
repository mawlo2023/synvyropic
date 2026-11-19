package de.synvyropic.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Ein Synvyropic-Projekt: Bilder + Musik + Timeline + Exporteinstellungen. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val format: AspectFormat = AspectFormat.PORTRAIT_9_16,
    val styleId: String = "glitch_energy",
    val music: MusicTrack? = null,
    val clips: List<Clip> = emptyList(),
    val texts: List<TextOverlay> = emptyList(),
    val color: ColorGrade = ColorGrade(),
    val watermark: Watermark? = null,
    val intro: TitleCard? = null,
    val outro: TitleCard? = null,
    val export: ExportSettings = ExportSettings(),
) {
    fun toJson(): String = ProjectJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): Project = ProjectJson.decodeFromString(serializer(), json)
    }
}

internal val ProjectJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

@Serializable
enum class AspectFormat(val width: Int, val height: Int) {
    PORTRAIT_9_16(1080, 1920),
    LANDSCAPE_16_9(1920, 1080);

    fun size(resolution: Resolution): Pair<Int, Int> =
        if (resolution == Resolution.UHD_4K) width * 2 to height * 2 else width to height
}

@Serializable
enum class Resolution { FHD_1080, UHD_4K }

@Serializable
enum class VideoCodec { H264, HEVC }

@Serializable
data class ExportSettings(
    val resolution: Resolution = Resolution.FHD_1080,
    val fps: Int = 30,
    val codec: VideoCodec = VideoCodec.H264,
)

/** Musik mit gewähltem Ausschnitt und Analyse-Ergebnis. */
@Serializable
data class MusicTrack(
    val uri: String,
    val startMs: Long = 0,
    val endMs: Long? = null,
    val volume: Float = 1f,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 1500,
    val beats: BeatGrid? = null,
)

/** Ergebnis der Beat-Analyse. strengths[i] in 0..1 gehört zu beatsMs[i]. */
@Serializable
data class BeatGrid(
    val bpm: Float,
    val beatsMs: List<Long>,
    val strengths: List<Float>,
    val downbeatIndices: List<Int> = emptyList(),
    val dropIndices: List<Int> = emptyList(),
)

/** Ein Bild auf der Timeline. Dauer wird in Beats angegeben, wenn Musik vorhanden ist. */
@Serializable
data class Clip(
    val id: String,
    val imageUri: String,
    val durationBeats: Int = 2,
    val durationMsFallback: Long = 2000,
    val motion: KenBurns = KenBurns(),
    val transitionIn: Transition = Transition(),
    val effect: ClipEffect? = null,
    val color: ColorGrade? = null,
)

@Serializable
data class KenBurns(
    val enabled: Boolean = true,
    val startScale: Float = 1.0f,
    val endScale: Float = 1.15f,
    val startCenterX: Float = 0.5f,
    val startCenterY: Float = 0.5f,
    val endCenterX: Float = 0.5f,
    val endCenterY: Float = 0.5f,
)

@Serializable
enum class TransitionFamily { RGB_GLITCH, MOTION_ZOOM, LIGHT_FLASH, SHAPE_WIPE, BASIC }

@Serializable
enum class TransitionType(val family: TransitionFamily) {
    CUT(TransitionFamily.BASIC),
    CROSSFADE(TransitionFamily.BASIC),

    RGB_SPLIT(TransitionFamily.RGB_GLITCH),
    CHROMATIC_ABERRATION(TransitionFamily.RGB_GLITCH),
    DIGITAL_GLITCH(TransitionFamily.RGB_GLITCH),
    VHS(TransitionFamily.RGB_GLITCH),
    PIXEL_SORT(TransitionFamily.RGB_GLITCH),
    DATAMOSH(TransitionFamily.RGB_GLITCH),

    ZOOM_PUNCH_IN(TransitionFamily.MOTION_ZOOM),
    ZOOM_PUNCH_OUT(TransitionFamily.MOTION_ZOOM),
    WHIP_PAN_LEFT(TransitionFamily.MOTION_ZOOM),
    WHIP_PAN_RIGHT(TransitionFamily.MOTION_ZOOM),
    WHIP_PAN_UP(TransitionFamily.MOTION_ZOOM),
    WHIP_PAN_DOWN(TransitionFamily.MOTION_ZOOM),
    SPIN(TransitionFamily.MOTION_ZOOM),
    SHAKE(TransitionFamily.MOTION_ZOOM),
    STRETCH(TransitionFamily.MOTION_ZOOM),

    WHITE_FLASH(TransitionFamily.LIGHT_FLASH),
    COLOR_FLASH(TransitionFamily.LIGHT_FLASH),
    LIGHT_LEAK(TransitionFamily.LIGHT_FLASH),
    LENS_FLARE(TransitionFamily.LIGHT_FLASH),
    BURN(TransitionFamily.LIGHT_FLASH),

    CIRCLE_WIPE(TransitionFamily.SHAPE_WIPE),
    LINE_WIPE(TransitionFamily.SHAPE_WIPE),
    ZIGZAG_WIPE(TransitionFamily.SHAPE_WIPE),
    GRID_WIPE(TransitionFamily.SHAPE_WIPE),
    DISSOLVE(TransitionFamily.SHAPE_WIPE),
    LUMA_MORPH(TransitionFamily.SHAPE_WIPE),
}

@Serializable
enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, EXPO_OUT, BACK_OUT }

@Serializable
data class Transition(
    val type: TransitionType = TransitionType.CROSSFADE,
    val durationMs: Long = 400,
    val intensity: Float = 1f,
    val easing: Easing = Easing.EASE_IN_OUT,
    /** Intensität folgt der Beat-Hüllkurve. */
    val beatReactive: Boolean = false,
    val angleDeg: Float = 0f,
    val colorArgb: Long = 0xFFFFFFFF,
)

@Serializable
enum class ClipEffectType { BEAT_PULSE, RGB_SHIFT_PULSE, SHAKE, GLITCH_BURST, VHS_OVERLAY }

@Serializable
data class ClipEffect(
    val type: ClipEffectType,
    val intensity: Float = 0.5f,
    val beatReactive: Boolean = true,
)

@Serializable
data class ColorGrade(
    val presetId: String? = null,
    /** Pfad zu importierter .cube-LUT im App-Speicher. */
    val lutPath: String? = null,
    val lutIntensity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
)

@Serializable
enum class TextAnimation { NONE, FADE, SLIDE_UP, POP, TYPEWRITER, GLITCH }

@Serializable
data class TextOverlay(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val fontId: String = "default",
    val sizeSp: Float = 48f,
    val colorArgb: Long = 0xFFFFFFFF,
    val outlineArgb: Long? = 0xFF000000,
    val shadow: Boolean = true,
    val x: Float = 0.5f,
    val y: Float = 0.8f,
    val animation: TextAnimation = TextAnimation.FADE,
    val syncToBeat: Boolean = false,
)

@Serializable
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Eigenes Wasserzeichen: Logo (Bild) und/oder Text. */
@Serializable
data class Watermark(
    val imageUri: String? = null,
    val text: String? = null,
    val corner: Corner = Corner.BOTTOM_RIGHT,
    val opacity: Float = 0.7f,
    val scale: Float = 0.15f,
)

/** Intro- bzw. Outro-Karte. */
@Serializable
data class TitleCard(
    val title: String,
    val subtitle: String? = null,
    val durationMs: Long = 2500,
    val backgroundArgb: Long = 0xFF000000,
    val backgroundImageUri: String? = null,
    val animation: TextAnimation = TextAnimation.POP,
)
