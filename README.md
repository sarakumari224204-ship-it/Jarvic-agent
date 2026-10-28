# JARVIS OS

Fully functional native Android Voice AI Assistant (Android 14/15).

## Features
- Continuous voice listening (SpeechRecognizer)
- Gemini-powered autonomous screen agent
- Accessibility-based taps, swipes, typing, app launch
- ElevenLabs TTS with native Android TTS fallback
- Futuristic Jetpack Compose HUD UI

## Build APK from GitHub (no computer needed)

1. Go to the **Actions** tab of this repository
2. Select **Build Debug APK**
3. Click **Run workflow** → **Run workflow**
4. Wait 3–6 minutes
5. Open the completed run → download **jarvis-os-debug-apk**
6. Extract the `.apk` and install on your phone

> You may need to enable **Install from unknown sources** for your browser/file manager.

## Setup after install
1. Open the app → enter your **Gemini API key**
2. (Optional) Enter ElevenLabs key + Voice ID
3. Tap **Enable Accessibility** and turn on **JARVIS OS Screen Agent**
4. Grant Microphone + Notifications permission
5. Tap **Start Listening** and speak a command

## Tech Stack
- Kotlin + Coroutines + StateFlow
- Jetpack Compose (Material 3)
- Ktor (networking)
- DataStore + Android Keystore (secure keys)
- AccessibilityService for UI automation
