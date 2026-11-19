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
    /** Ausgewählte Quellbilder (Reihenfolge = Wunschreihenfolge). */
    val images: List<String> = emptyList(),
    /** Analyse-Ergebnisse (Größe, Gesichter, Darstellungsmodus) je Bildpfad. */
    val imageInfo: Map<String, ImageInfo> = emptyMap(),
    /** null = Raster des Stils verwenden. */
    val beatsPerCut: Int? = null,
    val lengthMode: LengthMode = LengthMode.FIT_IMAGES,
    /** Beste Bilder auf Anfang und Drops legen. */
    val bestOnHighlights: Boolean = false,
    val seed: Int = 1,
    /** Stil aller Bild-Untertitel (Text steht in ImageInfo.caption). */
    val captionStyle: TextStyle = TextStyle.CAPTION,
    /** Von Hand gewählte Übergänge je Clip-Index; überstehen jeden neuen Auto-Schnitt. */
    val transitionOverrides: Map<Int, Transition> = emptyMap(),
    val music: MusicTrack? = null,
    val clips: List<Clip> = emptyList(),
    val texts: List<TextOverlay> = emptyList(),
    val color: ColorGrade = ColorGrade(),
    val cinema: CinemaLook = CinemaLook(),
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
    /** Wellenform zur Anzeige (0..1, ca. 1200 Werte über die ganze Datei). */
    val waveform: List<Float> = emptyList(),
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
    /** Aufgelöster Modus (nie AUTO): FILL/PAN = füllend, BLUR = ganz mit Blur-Rand. */
    val fit: FitMode = FitMode.FILL,
    val transitionIn: Transition = Transition(),
    val effect: ClipEffect? = null,
    val color: ColorGrade? = null,
)

/** Gesichtsrechteck in normierten Bildkoordinaten (0..1, y nach unten). */
@Serializable
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** Wie ein Bild ins Videoformat eingepasst wird. */
@Serializable
enum class FitMode {
    /** Automatisch anhand der Gesichter wählen. */
    AUTO,
    /** Bildschirmfüllend, Ausschnitt auf die Gesichter ausgerichtet. */
    FILL,
    /** Ganzes Bild, Ränder mit unscharfer Kopie gefüllt. */
    BLUR,
    /** Bildschirmfüllend, Kamerafahrt von Gesicht zu Gesicht. */
    PAN,
}

@Serializable
data class ImageInfo(
    val width: Int,
    val height: Int,
    val faces: List<FaceBox> = emptyList(),
    /** Vom Nutzer gewählter Modus; AUTO = App entscheidet. */
    val fit: FitMode = FitMode.AUTO,
    /** Farbkorrektur nur für dieses Bild (wirkt zusätzlich zum Projekt-Look). */
    val color: ColorGrade? = null,
    /** Untertitel, solange dieses Bild zu sehen ist. */
    val caption: String? = null,
    /** Aufnahmezeit (EXIF), falls bekannt. */
    val takenAt: Long? = null,
    /** Schärfe (Laplace-Varianz, relativ vergleichbar innerhalb eines Projekts). */
    val sharpness: Float? = null,
    /** Wahrnehmungs-Hash (dHash) zum Erkennen fast gleicher Fotos. */
    val dhash: Long? = null,
    /** Farbigkeit 0..~1. */
    val colorfulness: Float? = null,
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

/**
 * Fortschreitende Übergänge (Wipes, Schwenks, Blenden) bekommen Easing.
 * Alle anderen haben ihren Höhepunkt genau auf dem Beat (p = 0.5) und laufen linear.
 */
val TransitionType.isProgressive: Boolean
    get() = this in setOf(
        TransitionType.CROSSFADE, TransitionType.WHIP_PAN_LEFT, TransitionType.WHIP_PAN_RIGHT,
        TransitionType.WHIP_PAN_UP, TransitionType.WHIP_PAN_DOWN, TransitionType.LIGHT_LEAK,
        TransitionType.LENS_FLARE, TransitionType.CIRCLE_WIPE, TransitionType.LINE_WIPE,
        TransitionType.ZIGZAG_WIPE, TransitionType.GRID_WIPE, TransitionType.DISSOLVE, TransitionType.LUMA_MORPH,
    )

@Serializable
enum class Easing {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, EXPO_OUT, BACK_OUT;

    fun apply(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            EASE_IN -> x * x * x
            EASE_OUT -> 1f - (1f - x) * (1f - x) * (1f - x)
            EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
            EXPO_OUT -> if (x >= 1f) 1f else 1f - Math.pow(2.0, -10.0 * x).toFloat()
            BACK_OUT -> { val c1 = 1.70158f; val c3 = c1 + 1f; 1f + c3 * (x - 1f).let { it * it * it } + c1 * (x - 1f).let { it * it } }
        }
    }
}

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

/** Kino-Look: Balken, Glühen, Lichtsaum, Filmkorn, Bewegungsunschärfe (alle 0..1). */
@Serializable
data class CinemaLook(
    /** Cinemascope-Balken oben/unten. */
    val letterbox: Float = 0f,
    /** Glühen um helle Stellen. */
    val bloom: Float = 0f,
    /** Rötlicher Lichtsaum um Spitzlichter (wie bei Kinofilm). */
    val halation: Float = 0f,
    /** Filmkorn. */
    val grain: Float = 0f,
    /** Bewegungsunschärfe beim Export (mehrere Teilbilder pro Frame). */
    val motionBlur: Boolean = false,
) {
    val isOff get() = letterbox == 0f && bloom == 0f && halation == 0f && grain == 0f && !motionBlur

    companion object {
        val PRESET = CinemaLook(letterbox = 0.6f, bloom = 0.35f, halation = 0.3f, grain = 0.25f, motionBlur = true)
    }
}

/**
 * Farbbearbeitung. Regler-Werte -1..1 (0 = neutral), Vignette 0..1.
 * Auf Projektebene: Look + Regler + LUT. Pro Bild (ImageInfo.color): nur Korrektur-Regler.
 */
@Serializable
data class ColorGrade(
    val presetId: String? = null,
    /** Stärke des Looks 0..1. */
    val presetAmount: Float = 1f,
    /** Pfad zu importierter .cube-LUT im App-Speicher. */
    val lutPath: String? = null,
    val lutName: String? = null,
    val lutIntensity: Float = 1f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vignette: Float = 0f,
) {
    val isNeutral: Boolean
        get() = presetId == null && lutPath == null && brightness == 0f && contrast == 0f &&
            saturation == 0f && temperature == 0f && tint == 0f && vignette == 0f
}

@Serializable
enum class TextAnimation { NONE, FADE, SLIDE_UP, POP, TYPEWRITER, GLITCH }

@Serializable
enum class TextFont { SANS_BOLD, SANS, BLACK, CONDENSED, SERIF, MONO }

/**
 * Aussehen eines Textes. Größe in Pixeln bezogen auf 1080 px Videobreite;
 * Position = Textmitte, normiert (0..1, y nach unten).
 */
@Serializable
data class TextStyle(
    val font: TextFont = TextFont.SANS_BOLD,
    val size: Float = 64f,
    val colorArgb: Long = 0xFFFFFFFF,
    val outlineArgb: Long? = null,
    val shadow: Boolean = true,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val animation: TextAnimation = TextAnimation.FADE,
    val syncToBeat: Boolean = false,
) {
    companion object {
        val TITLE = TextStyle(font = TextFont.BLACK, size = 110f, y = 0.42f, animation = TextAnimation.POP)
        val CAPTION = TextStyle(font = TextFont.SANS_BOLD, size = 52f, y = 0.86f, animation = TextAnimation.SLIDE_UP)
        val FREE = TextStyle(size = 72f, y = 0.5f, animation = TextAnimation.POP)
    }
}

/** Frei platzierter Text mit eigener Zeit. */
@Serializable
data class TextOverlay(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val style: TextStyle = TextStyle.FREE,
)

@Serializable
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Eigenes Wasserzeichen: Logo (Bild) und/oder Text. */
@Serializable
data class Watermark(
    val imageUri: String? = null,
    val text: String? = null,
    val corner: Corner = Corner.BOTTOM_RIGHT,
    val opacity: Float = 0.75f,
    /** Logobreite als Anteil der Videobreite. */
    val scale: Float = 0.16f,
    /** Textgröße (px bei 1080 Breite). */
    val textSize: Float = 38f,
)

/** Intro-/Outro-Titel über dem ersten bzw. letzten Bild (das abgedunkelt wird). */
@Serializable
data class TitleCard(
    val title: String,
    val subtitle: String? = null,
    val durationMs: Long = 2500,
    val style: TextStyle = TextStyle.TITLE,
    /** Abdunkelung des Bildes darunter 0..1. */
    val dim: Float = 0.35f,
)
