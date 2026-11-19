package de.synvyropic.app.render

/**
 * GLSL-ES-3.00-Shader. Render-Pipeline pro Frame:
 *  1. CLIP: Bild A (und ggf. B) mit Ken Burns in je ein FBO
 *  2. TRANSITION: A + B → FBO C (Übergangstyp = Ordnungszahl von TransitionType)
 *  3. POST: Beat-Effekte (RGB-Puls, VHS, Helligkeit) → Bildschirm / Encoder
 */
object Shaders {

    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** Bild „cover“-skaliert + Ken Burns. uCenter/uSpan in Bildkoordinaten (y nach unten). */
    const val CLIP = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uCenter;
uniform vec2 uSpan;
uniform int uMode;       // 0 = füllend, 1 = ganzes Bild mit Blur-Rand
uniform vec2 uBgSpan;    // Ausschnitt des Hintergrunds (füllend, zentriert)
uniform vec4 uG1;        // Helligkeit, Kontrast, Sättigung, Wärme (-1..1)
uniform vec3 uG2;        // Tönung, Fade (0..0.3), aktiv (0/1)
uniform vec3 uShadow;    // Split-Toning Schatten
uniform vec3 uHigh;      // Split-Toning Lichter

vec3 grade(vec3 c) {
    if (uG2.z < 0.5) return c;
    c += uG1.x * 0.25;
    c = (c - 0.5) * (1.0 + uG1.y) + 0.5;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, 1.0 + uG1.z);
    c.r += uG1.w * 0.08;
    c.b -= uG1.w * 0.08;
    c.g += uG2.x * 0.06;
    float lt = clamp(l, 0.0, 1.0);
    c += uShadow * (1.0 - smoothstep(0.0, 0.6, lt)) + uHigh * smoothstep(0.4, 1.0, lt);
    c = uG2.y + c * (1.0 - uG2.y);
    return clamp(c, 0.0, 1.0);
}

void main() {
    vec2 st = vec2(vUv.x, 1.0 - vUv.y);
    vec2 uv = uCenter + (st - 0.5) * uSpan;
    if (uMode == 1 && (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)) {
        // Unschärfe über hohe Mipmap-Stufen + leichtes Jittern, abgedunkelt
        vec2 bg = 0.5 + (st - 0.5) * uBgSpan;
        vec2 d = uBgSpan * 0.02;
        vec3 c = textureLod(uTex, bg, 5.5).rgb * 0.4
               + textureLod(uTex, bg + vec2(d.x, d.y), 5.5).rgb * 0.15
               + textureLod(uTex, bg + vec2(-d.x, d.y), 5.5).rgb * 0.15
               + textureLod(uTex, bg + vec2(d.x, -d.y), 5.5).rgb * 0.15
               + textureLod(uTex, bg + vec2(-d.x, -d.y), 5.5).rgb * 0.15;
        fragColor = vec4(grade(c) * 0.6, 1.0);
        return;
    }
    fragColor = vec4(grade(texture(uTex, uv).rgb), 1.0);
}
"""

    /** Einfache Überblendung – Fallback, falls der große Übergangs-Shader nicht kompiliert. */
    const val TRANSITION_FALLBACK = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uP;
void main() {
    fragColor = mix(texture(uA, vUv), texture(uB, vUv), uP);
}
"""

    const val TRANSITION = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uA;
uniform sampler2D uB;
uniform int uType;
uniform float uP;          // 0..1, Beat bei 0.5
uniform float uIntensity;  // 0..1
uniform float uTime;       // Sekunden
uniform vec2 uRes;         // Auflösung in Pixeln
uniform vec3 uColor;
uniform float uAngle;      // Radiant
uniform float uEnv;        // Beat-Hüllkurve 0..1

const float PI = 3.14159265;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// Dreiecksfunktion: 0 an den Rändern, 1 in der Mitte (auf dem Beat)
float bell(float p) { return 1.0 - abs(2.0 * p - 1.0); }

// Weicher Wechsel A→B um die Mitte herum
float midMix(float p, float w) { return smoothstep(0.5 - w, 0.5 + w, p); }

vec3 A(vec2 uv) { return texture(uA, uv).rgb; }
vec3 B(vec2 uv) { return texture(uB, uv).rgb; }
vec3 AB(vec2 uv, float m) { return mix(A(uv), B(uv), m); }

// Seitenverhältnis-korrigierte Koordinaten (Kreise bleiben rund)
vec2 aspectUv(vec2 uv) { return vec2((uv.x - 0.5) * uRes.x / uRes.y, uv.y - 0.5); }

vec3 rgbSplit(vec2 uv, vec2 off, float m) {
    return vec3(AB(uv + off, m).r, AB(uv, m).g, AB(uv - off, m).b);
}

vec3 radialBlur(vec2 uv, float amount, float m) {
    vec3 acc = vec3(0.0);
    vec2 dir = uv - 0.5;
    for (int i = 0; i < 10; i++) {
        float s = 1.0 - amount * float(i) / 9.0;
        acc += AB(0.5 + dir * s, m);
    }
    return acc / 10.0;
}

vec3 slide(vec2 uv, vec2 dir, float p, float blur) {
    vec3 acc = vec3(0.0);
    for (int i = 0; i < 10; i++) {
        float o = (float(i) / 9.0 - 0.5) * blur;
        vec2 s = uv + dir * (p + o);
        float along = dot(s, dir);
        acc += (along <= 1.0) ? A(s) : B(s - dir);
    }
    return acc / 10.0;
}

void main() {
    vec2 uv = vUv;
    float p = clamp(uP, 0.0, 1.0);
    float k = clamp(uIntensity, 0.0, 1.0);
    float b = bell(p) * k;
    float tq = floor(uTime * 24.0);  // quantisierte Zeit für Glitch-Flackern
    vec3 col;

    if (uType == 0) {                       // CUT
        col = p < 0.5 ? A(uv) : B(uv);
    } else if (uType == 1) {                // CROSSFADE
        col = AB(uv, p);
    } else if (uType == 2) {                // RGB_SPLIT
        vec2 dir = vec2(cos(uAngle), sin(uAngle));
        col = rgbSplit(uv, dir * 0.06 * b, midMix(p, 0.08));
    } else if (uType == 3) {                // CHROMATIC_ABERRATION
        vec2 c = 0.5 + (uv - 0.5) * (1.0 - 0.06 * b);
        col = rgbSplit(c, (c - 0.5) * 0.12 * b, midMix(p, 0.15));
    } else if (uType == 4) {                // DIGITAL_GLITCH
        vec2 block = floor(uv * vec2(10.0, 22.0));
        float r = hash(block + tq);
        vec2 g = uv;
        if (r < 0.6 * b) g.x += (hash(block + tq + 1.7) - 0.5) * 0.35 * b;
        float row = floor(uv.y * 60.0);
        if (hash(vec2(row, tq)) < 0.3 * b) g.x += (hash(vec2(row, tq + 3.1)) - 0.5) * 0.1;
        float m = step(hash(block + 9.3), p * 1.4 - 0.2);
        col = rgbSplit(g, vec2(0.02 * b, 0.0), m);
    } else if (uType == 5) {                // VHS
        vec2 g = uv;
        g.x += (noise(vec2(uv.y * 90.0, uTime * 25.0)) - 0.5) * 0.02 * b;
        float band = fract(uTime * 0.6);
        if (abs(uv.y - band) < 0.06 * b) g.x += 0.06 * b * hash(vec2(tq, 1.0));
        col = rgbSplit(g, vec2(0.008 * b, 0.0), midMix(p, 0.12));
        col *= 1.0 - 0.18 * b * step(0.5, fract(uv.y * uRes.y * 0.25));
        col += (hash(uv * uRes + tq) - 0.5) * 0.15 * b;
    } else if (uType == 6) {                // PIXEL_SORT
        float m = midMix(p, 0.1);
        float colX = floor(uv.x * uRes.x / 3.0);
        float len = hash(vec2(colX, tq)) * 0.35 * b;
        vec3 best = AB(uv, m);
        for (int i = 1; i < 12; i++) {
            vec3 s = AB(uv + vec2(0.0, len * float(i) / 11.0), m);
            if (luma(s) > luma(best) && luma(s) > 0.45) best = s;
        }
        col = best;
    } else if (uType == 7) {                // DATAMOSH
        vec2 block = floor(uv * vec2(14.0, 26.0));
        vec2 mv = (vec2(hash(block), hash(block + 7.1)) - 0.5) * 0.18 * b;
        float switchAt = 0.25 + hash(block + 1.3) * 0.5;
        if (p < switchAt) col = A(uv + mv * 0.5);
        else col = mix(B(uv + mv), A(uv + mv), 0.35 * b);
        col = mix(col, rgbSplit(uv + mv, vec2(0.015 * b, 0.0), step(switchAt, p)), 0.5 * b);
    } else if (uType == 8 || uType == 9) {  // ZOOM_PUNCH_IN / OUT
        float z = (p < 0.5 ? p : 1.0 - p) * 2.0;
        float s = uType == 8 ? 1.0 + z * 1.2 * k : 1.0 / (1.0 + z * 0.6 * k);
        vec2 g = 0.5 + (uv - 0.5) / s;
        col = radialBlur(g, 0.25 * b, step(0.5, p));
    } else if (uType >= 10 && uType <= 13) {// WHIP_PAN
        vec2 dir = uType == 10 ? vec2(1.0, 0.0) : uType == 11 ? vec2(-1.0, 0.0)
                 : uType == 12 ? vec2(0.0, -1.0) : vec2(0.0, 1.0);
        // Positionen „vor“ der Linie zeigen A, dahinter B (mit Spiegelung über FBO-Wrap)
        float e = p;  // Easing kommt bereits vom FrameResolver
        if (dir.x + dir.y < 0.0) {
            // negative Richtung: Achse umdrehen, damit along in 0..1 bleibt
            vec2 f = vec2(1.0) - uv;
            vec2 d2 = -dir;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 10; i++) {
                float o = (float(i) / 9.0 - 0.5) * 0.25 * b;
                vec2 s = f + d2 * (e + o);
                acc += dot(s, d2) <= 1.0 ? A(vec2(1.0) - s) : B(vec2(1.0) - (s - d2));
            }
            col = acc / 10.0;
        } else {
            col = slide(uv, dir, e, 0.25 * b);
        }
    } else if (uType == 14) {               // SPIN
        float a = (p < 0.5 ? p : p - 1.0) * PI * 1.2 * k;
        vec2 c = aspectUv(uv);
        float s = 1.0 + 0.4 * b;
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float aa = a - float(i) * 0.03 * b;
            mat2 rot = mat2(cos(aa), -sin(aa), sin(aa), cos(aa));
            vec2 r = rot * (c * s);
            vec2 g = vec2(r.x * uRes.y / uRes.x + 0.5, r.y + 0.5);
            acc += AB(g, step(0.5, p));
        }
        col = acc / 8.0;
    } else if (uType == 15) {               // SHAKE
        vec2 o = (vec2(noise(vec2(uTime * 40.0, 0.0)), noise(vec2(0.0, uTime * 40.0 + 5.0))) - 0.5) * 0.08 * b;
        vec2 g = 0.5 + (uv - 0.5) / (1.0 + 0.08 * b) + o;
        col = AB(g, midMix(p, 0.05));
    } else if (uType == 16) {               // STRETCH
        float z = (p < 0.5 ? p : 1.0 - p) * 2.0;
        vec2 g = vec2(0.5 + (uv.x - 0.5) / (1.0 + z * z * 3.0 * k), uv.y);
        col = AB(g, step(0.5, p)) * (1.0 + 0.4 * b);
    } else if (uType == 17 || uType == 18) {// WHITE_FLASH / COLOR_FLASH
        vec3 fc = uType == 17 ? vec3(1.0) : uColor;
        col = AB(uv, midMix(p, 0.04));
        col = mix(col, fc, pow(bell(p), 1.6) * k);
    } else if (uType == 19) {               // LIGHT_LEAK
        col = AB(uv, smoothstep(0.2, 0.8, p));
        vec2 c = aspectUv(uv);
        vec2 pos = vec2(mix(-0.8, 0.8, p), 0.2 * sin(p * PI));
        float leak = exp(-dot(c - pos, c - pos) * 3.0);
        float leak2 = exp(-dot(c + pos * 0.7, c + pos * 0.7) * 5.0);
        col += (vec3(1.0, 0.55, 0.2) * leak + vec3(1.0, 0.25, 0.4) * leak2) * 1.2 * b;
    } else if (uType == 20) {               // LENS_FLARE
        col = AB(uv, smoothstep(0.25, 0.75, p));
        vec2 c = aspectUv(uv);
        vec2 pos = vec2(mix(-0.6, 0.6, p), mix(0.3, -0.1, p));
        float d = length(c - pos);
        float core = exp(-d * d * 60.0);
        float halo = smoothstep(0.02, 0.0, abs(d - 0.18)) * 0.4;
        float streak = exp(-abs(c.y - pos.y) * 80.0) * exp(-abs(c.x - pos.x) * 1.5);
        vec2 ghostPos = -pos * 0.6;
        float ghost = exp(-dot(c - ghostPos, c - ghostPos) * 120.0) * 0.5;
        col += (vec3(1.0, 0.95, 0.85) * (core + streak) + vec3(0.4, 0.7, 1.0) * (halo + ghost)) * 1.5 * b;
    } else if (uType == 21) {               // BURN
        col = AB(uv, midMix(p, 0.1));
        float burn = pow(b, 1.5);
        col = col * (1.0 + 4.0 * burn) + vec3(1.0, 0.6, 0.3) * burn * 0.5;
        col = mix(col, vec3(luma(col)), 0.5 * burn);
    } else if (uType == 22) {               // CIRCLE_WIPE
        float d = length(aspectUv(uv));
        float r = p * 1.25;
        float m = smoothstep(r, r - 0.02, d);
        col = mix(A(uv), B(uv), m);
        col += vec3(1.0) * smoothstep(0.015, 0.0, abs(d - r)) * 0.6 * k * step(0.001, p) * step(p, 0.999);
    } else if (uType == 23 || uType == 24) {// LINE_WIPE / ZIGZAG_WIPE
        vec2 dir = vec2(cos(uAngle), sin(uAngle));
        vec2 perp = vec2(-dir.y, dir.x);
        float proj = dot(uv - 0.5, dir) + 0.5;
        if (uType == 24) proj += abs(fract(dot(uv - 0.5, perp) * 8.0) - 0.5) * 0.12;
        float edge = mix(-0.3, 1.3, p);
        float m = smoothstep(edge + 0.01, edge - 0.01, proj);
        col = mix(A(uv), B(uv), m);
        col += vec3(1.0) * smoothstep(0.01, 0.0, abs(proj - edge)) * 0.5 * k;
    } else if (uType == 25) {               // GRID_WIPE
        vec2 cells = vec2(6.0, 6.0 * uRes.y / uRes.x);
        vec2 cell = floor(uv * cells);
        vec2 local = fract(uv * cells) - 0.5;
        float delay = hash(cell) * 0.5;
        float lp = clamp((p - delay) * 2.0, 0.0, 1.0);
        float m = step(max(abs(local.x), abs(local.y)), lp * 0.5);
        col = mix(A(uv), B(uv), m);
    } else if (uType == 26) {               // DISSOLVE
        float n = noise(uv * vec2(uRes.x / uRes.y, 1.0) * 12.0) * 0.7 + hash(uv * uRes) * 0.3;
        float t = p * 1.2 - 0.1;
        float m = smoothstep(n - 0.04, n + 0.04, t);
        col = mix(A(uv), B(uv), m);
        col += vec3(1.0, 0.7, 0.4) * smoothstep(0.05, 0.0, abs(n - t)) * k * step(0.01, p) * step(p, 0.99);
    } else if (uType == 27) {               // LUMA_MORPH
        vec2 e = vec2(1.0) / uRes;
        float l = luma(A(uv));
        vec2 grad = vec2(luma(A(uv + vec2(e.x * 2.0, 0.0))) - l, luma(A(uv + vec2(0.0, e.y * 2.0))) - l);
        vec2 g = uv + grad * 0.5 * b;
        float t = p * 1.3 - 0.15;
        float m = smoothstep(l - 0.15, l + 0.15, t);
        col = mix(A(g), B(g), m);
    } else {
        col = AB(uv, p);
    }

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

    /** Beat-Effekte über das fertige Bild. */
    const val POST = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform vec2 uRes;
uniform float uTime;
uniform float uRgbPulse;   // RGB-Verschiebung (0..1)
uniform float uBright;     // Helligkeitspuls (0..1)
uniform float uVhs;        // VHS-Overlay (0..1)
uniform highp sampler3D uLut;
uniform float uLutAmount;  // 0 = keine LUT
uniform float uLutSize;
uniform float uVignette;   // 0..1

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

void main() {
    vec2 uv = vUv;
    vec3 col;
    if (uRgbPulse > 0.001 || uVhs > 0.001) {
        float off = 0.012 * uRgbPulse + 0.0025 * uVhs;
        col = vec3(texture(uTex, uv + vec2(off, 0.0)).r,
                   texture(uTex, uv).g,
                   texture(uTex, uv - vec2(off, 0.0)).b);
    } else {
        col = texture(uTex, uv).rgb;
    }
    if (uLutAmount > 0.001) {
        vec3 lc = clamp(col, 0.0, 1.0) * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
        col = mix(col, texture(uLut, lc).rgb, uLutAmount);
    }
    if (uVignette > 0.001) {
        float d = length((uv - 0.5) * vec2(uRes.x / uRes.y, 1.0)) / length(vec2(0.5 * uRes.x / uRes.y, 0.5));
        col *= 1.0 - uVignette * 0.85 * smoothstep(0.35, 1.0, d);
    }
    if (uVhs > 0.001) {
        col *= 1.0 - 0.08 * uVhs * step(0.5, fract(uv.y * uRes.y * 0.25));
        col += (hash(uv * uRes + floor(uTime * 24.0)) - 0.5) * 0.06 * uVhs;
        col = mix(col, col * vec3(1.05, 0.98, 0.9), uVhs);
    }
    col *= 1.0 + 0.18 * uBright;
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""

    /** Rechteck an beliebiger Stelle (für Texte/Logos). */
    const val OVERLAY_VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uCenter;
uniform vec2 uHalf;
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(uCenter + aPos * uHalf, 0.0, 1.0);
}
"""

    /** Text/Logo mit Deckkraft, Schreibmaschinen-Maske und Glitch (vormultipliertes Alpha). */
    const val OVERLAY = """#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uReveal;
uniform float uGlitch;
uniform float uTime;
void main() {
    vec2 uv = vUv;
    if (uGlitch > 0.001) {
        float row = floor(uv.y * 12.0);
        float h = fract(sin(row * 91.7 + floor(uTime * 20.0)) * 43758.5453);
        if (h < uGlitch) uv.x += (h - 0.5) * 0.15 * uGlitch;
    }
    if (uv.x > uReveal) { fragColor = vec4(0.0); return; }
    vec4 c = texture(uTex, uv);
    if (uGlitch > 0.001) {
        float o = 0.015 * uGlitch;
        c.r = texture(uTex, uv + vec2(o, 0.0)).r;
        c.b = texture(uTex, uv - vec2(o, 0.0)).b;
    }
    fragColor = c * uAlpha;
}
"""
}
