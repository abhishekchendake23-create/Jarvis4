# ABHYNEX JARVIS

Personal AI voice assistant for Android (Kotlin + Jetpack Compose).
**Install first, configure keys inside the app afterwards.** The app opens and works (local commands, settings) with zero keys.

> Status: complete source project. It has NOT been compiled or run by the author's tooling (no Android SDK in the generation sandbox). Expect to fix a small compile error or two on first sync, and test every feature on a real phone.

## 1. Build the APK
Requirements: Android Studio (Koala or newer), JDK 17 (bundled), Android SDK 34.

1. Open Android Studio → **Open** → select the `AbhynexJarvis` folder.
2. If asked, let Studio create/update the Gradle wrapper (gradle-wrapper.jar is not included). **Sync Gradle**.
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
   For a release APK use **Build → Generate Signed App Bundle / APK** and create your own keystore (never commit it).
4. Copy the APK to your phone (USB/Drive), allow "install unknown apps", install.
5. Open **ABHYNEX JARVIS**. No keys are needed to install or open.

## 2. After installation (inside the app)
1. Open the app → Settings (⚙️) → **OPEN API CENTER**.
2. **AI API**: paste Anthropic API key, check the model name, **SAVE KEY**, **TEST CONNECTION**.
3. **Inworld Voice**: paste the Base64 credential from Inworld Portal → Settings → API Keys, confirm Voice ID / Model ID, **SAVE KEY**, **TEST VOICE**.
4. **Web Search**: paste a Brave Search API key, **SAVE KEY**, **TEST SEARCH**.
5. Tap 🎙️ and allow the microphone. Pick a language (AUTO/EN/HI/MR) in Settings.
6. Settings → Security → turn on **Owner Protection** (needs a screen lock or biometric on the phone).
7. Mobile (📱) → try the safe commands.

Status indicators are real: 🟢 only after a successful test or call; 🟡 saved but untested; 🔴 not configured/failed.

## 3. How it works
- **AI**: Anthropic Messages API (`https://api.anthropic.com/v1/messages`). Provider code is isolated in `AiClient` (Services.kt).
- **Voice**: Inworld TTS `POST https://api.inworld.ai/tts/v1/voice`, header `Authorization: Basic <key>`, body `text/voiceId/modelId`. Reply is split into sentences; the next sentence is synthesized while the current one plays. Falls back to Android TextToSpeech. Speaking stops when you send a new message or tap the mic. (Verified against docs.inworld.ai; check them for current voices/models.)
- **Search**: Brave Search API (`X-Subscription-Token`). Used when you arm 🌐 Search, or automatically (if enabled and keyed) for current-events wording such as "today/latest/news/weather/price". Sources are shown as links; without a search key the app says so and offers CONFIGURE SEARCH.
- **Memory**: last 100 messages, AES-GCM encrypted with an Android Keystore key (`MemoryStore`, swappable for SQLite/Supabase). Toggle + CLEAR MEMORY.
- **Key storage**: AES-256-GCM, non-exportable Keystore key. Keys are masked, never logged, deletable (with confirmation). `allowBackup=false` so keys are not copied to cloud backups.

## 4. Owner protection (honest limits)
- Biometric/screen-lock via `BiometricPrompt` guards: unlocking the app, enabling/disabling protection, clearing memory, changing security settings.
- **Voice verification** = your spoken passphrase must accompany voice-issued device commands. It is a convenience layer, NOT speaker recognition and NOT strong security.
- **Unauthorized access alert** fires on repeated failed biometric attempts / lockout in this app, or failed voice verification: full-screen alert, optional notification, optional alarm, spoken alert. Android does not let an app detect arbitrary device intrusion, so nothing more is claimed. No audio/video/location is recorded or uploaded.

## 5. Mobile control (allowlist)
`OPEN_CAMERA, OPEN_PHONE, OPEN_SETTINGS, OPEN_WIFI_SETTINGS, OPEN_BLUETOOTH_SETTINGS, OPEN_BATTERY_SETTINGS, OPEN_NOTIFICATION_SETTINGS, VOLUME_UP, VOLUME_DOWN, MEDIA_PLAY, MEDIA_PAUSE, BATTERY_STATUS, VIBRATION_ON`.
Matching is keyword-based in English/Hindi/Marathi (e.g. "Abhynex camera open kar", "Abhynex कॅमेरा उघड"), only for short phrases. The AI never produces commands; unknown requests go to chat. Sending messages, calling and deleting files are intentionally **not** implemented.
Notes: Vibration mode needs Do Not Disturb access on many phones (the app opens that screen). Media play/pause is sent as a media key to the active player.

## 6. Wake word
"Hey Abhynex" is a **visible foreground** mode (Settings → Voice). It uses the normal recognizer in a loop, keeps the screen on, shows "Microphone active", and stops when the app leaves the foreground. No background listening.

## 7. Permissions
`INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO` (asked when you tap the mic), `POST_NOTIFICATIONS` (asked when you enable notifications).

## 8. Troubleshooting
- *Gradle sync fails*: use JDK 17, SDK 34 installed; accept Studio's wrapper/plugin upgrade prompts.
- *AI authentication failed*: wrong/expired key. *Model error*: edit the Model field to one your account can use.
- *Inworld unavailable*: re-copy the Base64 credential; check Voice ID/Model ID exist in your workspace. The app falls back to Android voice.
- *Speech recognition not supported*: install/enable Google's speech services; Hindi/Marathi need the language pack.
- *Hindi/Marathi voice sounds wrong in fallback*: install that language in Android TTS settings.
- *Biometric prompt says set up lock*: add a screen lock in Android settings.

## 9. Optional backend
See `backend/README.md`. Not needed to install or run the app.
