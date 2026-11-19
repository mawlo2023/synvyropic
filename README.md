# Synvyropic

Android-App (Kotlin, Jetpack Compose, OpenGL ES): aus Bildern und Musik automatisch beat-synchrone Reels und Slideshows mit RGB-/Glitch-Übergängen erzeugen.

- Konzept & Roadmap: [SPEC.md](SPEC.md)
- APK: wird bei jedem Push von GitHub Actions gebaut → **Releases** → `latest-<branch>` → `Synvyropic-*.apk`

## Lokal bauen
Benötigt JDK 17 und Android SDK (API 35).

```bash
./gradlew :app:assembleRelease
```

## Signierung (GitHub-Secrets)
| Secret | Inhalt |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Keystore-Datei (`.jks`), Base64-kodiert |
| `SIGNING_STORE_PASSWORD` | Passwort des Keystores |
| `SIGNING_KEY_ALIAS` | Alias des Schlüssels |
| `SIGNING_KEY_PASSWORD` | Passwort des Schlüssels |

Ohne Secrets wird mit dem Debug-Schlüssel signiert.
