package de.synvyropic.model

/** Einfacher RGB-Wert (Offsets für Split-Toning, ca. -0.1..0.1). */
data class Rgb3(val r: Float, val g: Float, val b: Float) {
    operator fun times(k: Float) = Rgb3(r * k, g * k, b * k)
    companion object { val ZERO = Rgb3(0f, 0f, 0f) }
}

/** Fertig aufgelöste Shader-Parameter. */
data class ColorParams(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    /** Schwarzwerte anheben („verblasst“) 0..0.3 */
    val fade: Float = 0f,
    val vignette: Float = 0f,
    val shadowTint: Rgb3 = Rgb3.ZERO,
    val highlightTint: Rgb3 = Rgb3.ZERO,
) {
    val isIdentity get() = this == ColorParams()

    operator fun plus(o: ColorParams) = ColorParams(
        brightness + o.brightness, contrast + o.contrast, saturation + o.saturation,
        temperature + o.temperature, tint + o.tint, fade + o.fade, vignette + o.vignette,
        Rgb3(shadowTint.r + o.shadowTint.r, shadowTint.g + o.shadowTint.g, shadowTint.b + o.shadowTint.b),
        Rgb3(highlightTint.r + o.highlightTint.r, highlightTint.g + o.highlightTint.g, highlightTint.b + o.highlightTint.b),
    )

    fun scaled(k: Float) = ColorParams(
        brightness * k, contrast * k, saturation * k, temperature * k, tint * k, fade * k, vignette * k,
        shadowTint * k, highlightTint * k,
    )

    fun clamped() = copy(
        brightness = brightness.coerceIn(-1f, 1f), contrast = contrast.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f), temperature = temperature.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f), fade = fade.coerceIn(0f, 0.3f), vignette = vignette.coerceIn(0f, 1f),
    )
}

data class ColorLook(val id: String, val name: String, val params: ColorParams)

object ColorLooks {
    private val teal = Rgb3(-0.04f, 0.01f, 0.06f)
    private val orange = Rgb3(0.06f, 0.02f, -0.05f)

    val all = listOf(
        ColorLook("cinematic", "Cinematic", ColorParams(contrast = 0.15f, saturation = -0.1f, temperature = 0.05f, fade = 0.03f, vignette = 0.35f, shadowTint = teal * 0.6f, highlightTint = orange * 0.6f)),
        ColorLook("teal_orange", "Teal & Orange", ColorParams(contrast = 0.2f, saturation = 0.1f, shadowTint = teal, highlightTint = orange, vignette = 0.2f)),
        ColorLook("vintage", "Vintage", ColorParams(contrast = -0.1f, saturation = -0.3f, temperature = 0.25f, tint = 0.05f, fade = 0.12f, vignette = 0.4f, highlightTint = Rgb3(0.04f, 0.03f, -0.02f))),
        ColorLook("bw", "Schwarzweiß", ColorParams(saturation = -1f, contrast = 0.25f, vignette = 0.3f)),
        ColorLook("warm", "Warm", ColorParams(temperature = 0.4f, saturation = 0.05f)),
        ColorLook("cool", "Kühl", ColorParams(temperature = -0.4f, tint = -0.02f)),
        ColorLook("high_contrast", "Kontrast", ColorParams(contrast = 0.4f, saturation = 0.2f, vignette = 0.2f)),
        ColorLook("faded", "Verblasst", ColorParams(contrast = -0.2f, saturation = -0.2f, fade = 0.15f)),
        ColorLook("neon", "Neon", ColorParams(contrast = 0.2f, saturation = 0.4f, shadowTint = Rgb3(0.06f, -0.02f, 0.08f), highlightTint = Rgb3(-0.03f, 0.05f, 0.06f), vignette = 0.3f)),
    )

    fun byId(id: String?): ColorLook? = all.firstOrNull { it.id == id }

    /** Regler eines ColorGrade als Parameter (ohne Look). */
    private fun sliders(g: ColorGrade) = ColorParams(
        brightness = g.brightness, contrast = g.contrast, saturation = g.saturation,
        temperature = g.temperature, tint = g.tint, vignette = g.vignette,
    )

    /** Projekt-Look × Stärke + Projekt-Regler + Bildkorrektur. */
    fun resolve(project: ColorGrade, image: ColorGrade? = null): ColorParams {
        var p = byId(project.presetId)?.params?.scaled(project.presetAmount.coerceIn(0f, 1f)) ?: ColorParams()
        p += sliders(project)
        if (image != null) p += sliders(image).copy(vignette = 0f)
        return p.clamped()
    }
}

/** Geparste 3D-LUT (.cube). data: RGB-Tripel, Rot läuft am schnellsten. */
class CubeLut(val size: Int, val data: FloatArray, val title: String?) {
    companion object {
        fun parse(text: String): CubeLut {
            var size = 0
            var title: String? = null
            var minV = floatArrayOf(0f, 0f, 0f)
            var maxV = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"')
                    "LUT_3D_SIZE" -> size = parts[1].toInt()
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("1D-LUTs werden nicht unterstützt")
                    "DOMAIN_MIN" -> minV = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "DOMAIN_MAX" -> maxV = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    else -> {
                        val f = parts[0].toFloatOrNull() ?: continue // unbekannte Schlüsselwörter ignorieren
                        require(parts.size >= 3) { "Ungültige Datenzeile: $line" }
                        values += f; values += parts[1].toFloat(); values += parts[2].toFloat()
                    }
                }
            }
            require(size in 2..128) { "LUT_3D_SIZE fehlt oder ungültig" }
            require(values.size == size * size * size * 3) {
                "Erwartet ${size * size * size} Farbwerte, gefunden ${values.size / 3}"
            }
            val data = FloatArray(values.size) { i ->
                val c = i % 3
                ((values[i] - minV[c]) / (maxV[c] - minV[c])).coerceIn(0f, 1f)
            }
            return CubeLut(size, data, title)
        }

        /** Identitäts-LUT (z. B. als Platzhalter-Textur). */
        fun identity(size: Int): CubeLut {
            val d = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                d[i++] = r / (size - 1f); d[i++] = g / (size - 1f); d[i++] = b / (size - 1f)
            }
            return CubeLut(size, d, "Identität")
        }
    }

    /** Nächster Gitterpunkt (für Tests/Vorschaubilder). */
    fun lookupNearest(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        fun idx(v: Float) = Math.round(v.coerceIn(0f, 1f) * (size - 1))
        val i = ((idx(b) * size + idx(g)) * size + idx(r)) * 3
        return Triple(data[i], data[i + 1], data[i + 2])
    }
}
