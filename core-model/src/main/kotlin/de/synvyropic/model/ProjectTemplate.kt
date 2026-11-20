package de.synvyropic.model

import kotlinx.serialization.Serializable

/**
 * Vorlage: alle gestalterischen Einstellungen eines Projekts – ohne Bilder und Musik.
 * Dateien (LUT, Logo) werden beim Speichern/Anwenden über [remap] umkopiert.
 */
@Serializable
data class ProjectTemplate(
    val id: String,
    val name: String,
    val createdAt: Long,
    val styleId: String,
    val beatsPerCut: Int? = null,
    val lengthMode: LengthMode = LengthMode.FIT_IMAGES,
    val bestOnHighlights: Boolean = false,
    val dynamicCut: Boolean = true,
    val format: AspectFormat = AspectFormat.PORTRAIT_9_16,
    val color: ColorGrade = ColorGrade(),
    val cinema: CinemaLook = CinemaLook(),
    val parallax: Float = 0f,
    val captionStyle: TextStyle = TextStyle.CAPTION,
    val intro: TitleCard? = null,
    val outro: TitleCard? = null,
    val watermark: Watermark? = null,
    val export: ExportSettings = ExportSettings(),
) {
    /** Einstellungen auf ein Projekt übertragen; Bilder, Musik, Texte und Handauswahl bleiben. */
    fun applyTo(project: Project, remap: (String) -> String = { it }): Project = AutoCut.apply(
        project.copy(
            styleId = styleId,
            beatsPerCut = beatsPerCut,
            lengthMode = lengthMode,
            bestOnHighlights = bestOnHighlights,
            dynamicCut = dynamicCut,
            format = format,
            color = color.copy(lutPath = color.lutPath?.let(remap)),
            cinema = cinema,
            parallax = parallax,
            captionStyle = captionStyle,
            intro = intro,
            outro = outro,
            watermark = watermark?.copy(imageUri = watermark.imageUri?.let(remap)),
            export = export,
            updatedAt = System.currentTimeMillis(),
        ),
    )

    companion object {
        fun from(project: Project, id: String, name: String, now: Long, remap: (String) -> String = { it }) = ProjectTemplate(
            id = id, name = name, createdAt = now,
            styleId = project.styleId, beatsPerCut = project.beatsPerCut, lengthMode = project.lengthMode,
            bestOnHighlights = project.bestOnHighlights, dynamicCut = project.dynamicCut, format = project.format,
            color = project.color.copy(lutPath = project.color.lutPath?.let(remap)),
            cinema = project.cinema, parallax = project.parallax, captionStyle = project.captionStyle,
            intro = project.intro, outro = project.outro,
            watermark = project.watermark?.copy(imageUri = project.watermark.imageUri?.let(remap)),
            export = project.export,
        )
    }
}
