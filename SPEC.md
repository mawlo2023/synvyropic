# Synvyropic – Konzept & Spezifikation

> Android-App, die aus Einzelbildern und Musik automatisch beat-synchrone Reels und Slideshows
> mit professionellen GPU-Übergängen erzeugt.
>
> Status: **v0.3 – M0 fertig, M1a in Arbeit** – wird im Frage-Antwort-Verfahren gemeinsam gepflegt.

---

## 1. Ziel & Rahmen

| Punkt | Festlegung |
|---|---|
| Zielgerät | OnePlus 8 Pro, Android 13 (API 33), Snapdragon 865 |
| Mindest-Android | Android 13 (API 33) |
| Sprache der Oberfläche | Deutsch |
| Verteilung | APK per Sideload, gebaut über GitHub Actions, signiert mit festem Keystore (GitHub-Secret) |
| Bedienkonzept | **Auto + Feinschliff**: ein Tipp erzeugt ein fertiges Video, danach ist jeder Clip einzeln anpassbar |

## 2. Kernablauf (User Flow)

1. **Startbildschirm** – Projektliste (Vorschaubild, Name, Datum) + „Neues Projekt“.
2. **Bilder wählen** – Mehrfachauswahl über den System-Photo-Picker, Reihenfolge per Drag & Drop.
3. **Musik wählen** – Audiodatei (MP3/AAC/M4A/WAV/OGG/FLAC) vom Gerät; Ausschnitt (Start/Ende) per Wellenform festlegen.
4. **Format & Stil** – 9:16 (Reel) oder 16:9 (Slideshow); Stil-Preset (z. B. „Glitch Energy“, „Smooth Cinematic“, „Flash Party“).
5. **Auto-Schnitt** – Beat-Analyse → Bilder werden auf Beats verteilt, Übergänge/Effekte gemäß Stil gesetzt, starke Effekte auf Betonungen/Drops.
6. **Vorschau** – Echtzeit-Wiedergabe mit Musik.
7. **Feinschliff** – pro Clip: Dauer (in Beats), Übergang, Effekt, Ken-Burns-Richtung, Farbfilter, Text.
8. **Export** – MP4 (H.264, optional HEVC), 1080p oder 4K, 30/60 fps → Galerie (`Movies/Synvyropic`).

## 3. Funktionsumfang

### 3.1 Beat-Erkennung („Beats + Betonung“)
- Audio dekodieren → Mono-PCM → STFT.
- **Onset-Erkennung** über Spectral Flux.
- **Tempo (BPM)** über Autokorrelation der Onset-Hüllkurve.
- **Beat-Tracking** per dynamischer Programmierung (Ellis-Verfahren) → tatsächliche Beat-Zeitpunkte, robust gegen leichte Temposchwankungen.
- **Betonung**: Bassband-Energie (Kick) + Lautheitssprünge → Gewichtung je Beat; Erkennung von Takt-Einsen und Drops.
- Schnittraster wählbar: Bildwechsel alle **1 / 2 / 4 Beats** oder „nur auf Betonungen“.
- Komplett in Kotlin, keine GPL-Bibliotheken.

### 3.2 Übergänge (GPU-Shader, Priorität in dieser Reihenfolge)
1. **RGB/Glitch** – RGB-Split, Chromatic Aberration, Digital Glitch (Block-Displacement), VHS (Scanlines, Tracking-Noise), Pixel-Sort, Datamosh-Look.
2. **Bewegung/Zoom** – Zoom-Punch (in/out), Whip-Pan (4 Richtungen, mit Motion Blur), Spin, Shake, Stretch.
3. **Licht/Flash** – White/Color Flash, Light Leaks, Lens Flare, Overexposure-Burn.
4. **Formen/Wipes** – Kreis, Linie (Winkel frei), Zickzack, Kachel/Grid, Dissolve, Luma-Morph.

Jeder Übergang hat: Dauer (in Beats oder ms), Intensität, Easing-Kurve; Glitch/RGB optional **beat-reaktiv** (Intensität folgt der Beat-Hüllkurve).

### 3.3 Clip-Effekte
- **Ken Burns / Zoom & Pan** – automatische Richtung oder manuell (Start-/End-Ausschnitt).
- **Beat-Pulse** – leichter Zoom/Helligkeitspuls auf jedem Beat.
- Unschärfe-Hintergrund, wenn das Bildformat nicht zum Videoformat passt (statt schwarzer Balken).

### 3.4 Farbe
- Presets: Cinematic, Teal & Orange, Vintage, S/W, Warm, Cool, High Contrast, Faded.
- **LUT-Import**: `.cube`-Dateien (3D-LUT bis 64³) → 3D-Textur; Intensitätsregler.
- Basisregler: Helligkeit, Kontrast, Sättigung, Temperatur.

### 3.5 Text-Overlays
- Titel/Untertitel mit Schriftwahl, Farbe, Kontur/Schatten, Position.
- Animationen: Fade, Slide, Pop/Scale, Typewriter, Glitch-Text; optional auf Beat getaktet.

### 3.6 Musik
- Ausschnitt wählen, Lautstärke, Fade-In/Fade-Out.
- Videolänge = Musikausschnitt **oder** automatisch passend zur Bildanzahl.

### 3.7 Projekte
- Speicherung als JSON (kotlinx.serialization) im App-Speicher; Bilder/Audio werden per URI referenziert, bei Bedarf in den App-Speicher kopiert, damit Projekte nicht „brechen“.
- Projekt duplizieren, umbenennen, löschen, erneut exportieren.

### 3.8 Branding
- **Eigenes Wasserzeichen**: Logo (Bild mit Transparenz) und/oder Text, Ecke wählbar, Deckkraft und Größe einstellbar, für jedes Projekt ein-/ausschaltbar.
- **Intro-/Outro-Karte**: Titel + Untertitel, Hintergrundfarbe oder -bild, animiert (Pop, Glitch, Fade); Intro wird auf den ersten Beat bzw. Drop getaktet.

### 3.9 Export
| Format | Auflösung | Hinweis |
|---|---|---|
| 9:16 | 1080×1920 | Standard Reels/TikTok/Shorts |
| 16:9 | 1920×1080 | Slideshow/YouTube |
| 9:16 / 16:9 | 2160×3840 / 3840×2160 | **4K-Option**, längere Exportzeit |

H.264 High Profile (Standard), HEVC optional; AAC 48 kHz Stereo 192 kbit/s; Fortschrittsanzeige, Export als Vordergrund-Dienst (läuft weiter, wenn die App im Hintergrund ist).

## 4. Architektur

```
┌──────────────── UI (Jetpack Compose, Material 3, Dark Theme) ────────────────┐
│ Projektliste · Medienauswahl · Stil-Auswahl · Editor/Feinschliff · Export    │
└───────────────┬───────────────────────────────────────────────┬──────────────┘
                │ ViewModels (StateFlow)                        │
┌───────────────▼──────────────┐                ┌───────────────▼──────────────┐
│ Projektmodell (Timeline)     │                │ Auto-Schnitt-Engine          │
│ Clips, Übergänge, Texte, LUT │◄───────────────│ Beats + Stil → Timeline      │
└───────────────┬──────────────┘                └───────────────▲──────────────┘
                │                                               │
┌───────────────▼──────────────────────────────┐ ┌──────────────┴──────────────┐
│ Render-Engine (OpenGL ES 3.0)                │ │ Audio-Analyse               │
│ Timeline(t) → Shader-Graph → Frame           │ │ Decoder → STFT → Onsets →   │
│  ├─ Vorschau: TextureView + Audio-Clock      │ │ BPM → Beat-Tracking         │
│  └─ Export:  EGL-Surface → MediaCodec        │ └─────────────────────────────┘
└───────────────┬──────────────────────────────┘
                │
┌───────────────▼──────────────────────────────┐
│ Encoder: MediaCodec (H.264/HEVC + AAC)       │
│          → MediaMuxer → MP4 → MediaStore     │
└──────────────────────────────────────────────┘
```

**Kernidee:** *Eine* Render-Engine für Vorschau **und** Export. Was du in der Vorschau siehst, ist exakt das, was exportiert wird. Die Timeline ist eine reine Funktion `frame(t)`, dadurch sind Vorschau-Scrubbing und framegenauer Export trivial.

### Module
| Modul | Inhalt |
|---|---|
| `app` | Compose-UI, Navigation, ViewModels |
| `core-model` | Projekt-/Timeline-Datenmodell, Serialisierung |
| `audio` | Beat-Analyse, Resampling (reines Kotlin, mit Unit-Tests) |
| `render` | GL-Kontext, Shader-Bibliothek, Textur-Cache, Compositor |
| `export` | Encoder-Pipeline, Muxer, Vordergrund-Dienst |

### Technologien
Kotlin · Jetpack Compose · Material 3 · Coroutines/Flow · OpenGL ES 3.0 (GLSL ES 3.00) · MediaCodec/MediaExtractor/MediaMuxer · kotlinx.serialization · Coil (Thumbnails) · Gradle (Kotlin DSL) · GitHub Actions.

## 5. Stil-Presets (Auto-Schnitt)
Ein Stil ist eine Regelmenge: Schnittraster, Übergangspool (gewichtet), Effekt bei Betonung/Drop, Farb-Preset, Ken-Burns-Stärke, Text-Animation.

| Stil | Raster | Übergänge | Auf Drops |
|---|---|---|---|
| Glitch Energy | 1–2 Beats | RGB-Split, Glitch, Zoom-Punch | Datamosh + Flash |
| Smooth Cinematic | 4 Beats | Dissolve, Light Leak, Whip-Pan | Lens Flare |
| Flash Party | 1 Beat | Flash, Zoom-Punch, Shake | Color Flash + RGB |
| Retro VHS | 2 Beats | VHS, Line-Wipe, Chromatic | Tracking-Noise-Burst |

## 6. Ausbaustufen (Roadmap)

| Stufe | Inhalt | Ergebnis |
|---|---|---|
| **M0 – Fundament** | Gradle-Projekt, Module, Compose-Gerüst, GitHub-Actions-Build | Installierbare APK mit leerer Oberfläche |
| **M1a – Medien & Beats** ✅ | Bild-/Musikauswahl (inkl. Google Fotos), Beat-Analyse mit Visualisierung, Auto-Schnitt mit 4 Stilen, Projekte speichern | Timeline steht |
| **M1b – Vorschau & Export** | GL-Vorschau, Export 1080p, Crossfade + Ken Burns, Musik-Fades | **Erstes echtes beat-synchrones Video** |
| **M2 – Effekt-Engine** | Shader-Framework, RGB/Glitch-Familie, Bewegung/Zoom-Familie, beat-reaktive Parameter | Profi-Übergänge |
| **M3 – Licht & Farbe** | Licht/Flash, Formen/Wipes, Farb-Presets, LUT-Import | Komplette Effektpalette |
| **M4 – Editor & Text** | Feinschliff-Editor pro Clip, Text-Overlays, Wasserzeichen, Intro/Outro, Projektverwaltung, Stil-Presets | Vollständiger MVP |
| **M5 – Feinschliff** | 4K, HEVC, 60 fps, Performance, Musik-Wellenform-Editor | Version 1.0 |

## 7. Qualitätsziele
- Vorschau flüssig (≥ 30 fps) bei 1080p auf dem OnePlus 8 Pro.
- Export 1080p/30 fps eines 30-s-Reels in < 30 s.
- Beat-Treffer: Übergangsmitte liegt ±20 ms am Beat.
- Keine Abstürze bei großen Bildern (Downsampling auf Zielauflösung, Textur-Cache mit Limit).

## 8. Später denkbar (nicht MVP)
Videoclips als Quelle · Sticker/Overlays · Velocity/Speed-Ramps · Templates teilen · Auto-Reihenfolge nach Bildqualität · KI-Hintergrund-Entfernung · Tonspur aus Video übernehmen.

## 9. Entscheidungen

| Frage | Entscheidung |
|---|---|
| Signierung | Fester Keystore, als GitHub-Secrets hinterlegt → Updates ohne Neuinstallation |
| Mindest-Android | API 33 (Android 13) |
| Branding | Eigenes Wasserzeichen + Intro/Outro-Karte (beides optional pro Projekt) |
| Bildquellen | System-Fotoauswahl (zeigt Google-Fotos-Cloudbilder mit an) + Button „Google Fotos“, der die Fotos-App direkt öffnet. Bilder werden ins Projekt kopiert (max. 3840 px). Kein Login/keine Google-API nötig. |
| Ausrichtung | Hoch- und Querformat; im Querformat zweispaltiges Layout (Einstellungen links, Timeline rechts) |

## 10. Offene Fragen
_keine_
