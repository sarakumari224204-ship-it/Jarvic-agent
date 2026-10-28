# SARA AI (Jarvic-agent)

Native Android Voice AI Assistant (Android 14/15).

## Features
- Continuous voice listening (SpeechRecognizer)
- Gemini-powered autonomous screen agent
- **Optional offline LLM** (download ONE model, ~450–850 MB)
- Accessibility-based taps, swipes, typing, app launch
- ElevenLabs TTS with native Android TTS fallback
- Futuristic Jetpack Compose HUD UI

## AI modes
- **Auto** — online Gemini when network/key available; falls back to offline model
- **Online Only** — Gemini only
- **Offline Only** — local GGUF model only (no API key required)

## Offline models (user downloads ONE)
| Choice | Model | Approx size |
|--------|--------|-------------|
| Lite | Qwen2.5 0.5B Instruct (GGUF Q4_K_M) | ~450 MB |
| Balanced (recommended) | Llama 3.2 1B Instruct | ~700 MB |
| Quality | Gemma 3 1B Instruct | ~850 MB |

Models are **not** bundled in the APK. Download after install via Offline Model Manager.
Runtime: [llama-android](https://github.com/ffmpegkit-maintained/llama-android) (llama.cpp).

## Build APK from GitHub
1. **Actions** → **Build Debug APK** → **Run workflow**
2. Wait 3–6 minutes → download **jarvis-os-debug-apk**

## Setup
1. Open app → enter **Gemini API key** (for online / screen agent)
2. (Optional) ElevenLabs key + Voice ID
3. (Optional) Download **one** offline model for no-internet chat
4. Enable **Accessibility** → **JARVIS OS Screen Agent**
5. Grant Mic + Notifications → **Start Listening**

## Limitations (honest)
- Small on-device models (≤1B) are weaker than Gemini for complex multi-step UI automation
- Offline path is best for short conversation / Q&A; local regex still opens apps without an LLM
- Android SpeechRecognizer may still need network on some devices
- Offline model load needs free RAM (Lite recommended on low-RAM phones)
- Hugging Face download URLs can change; verify in OfflineModelCatalog if a link breaks

## Tech
Kotlin · Coroutines · Compose · Ktor · DataStore + Keystore · AccessibilityService · llama.cpp (optional offline)
