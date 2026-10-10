# 👻 Phantom

> *Because why lose at trivia night when you have 27 billion parameters whispering answers in your ear?*  
> *— Built purely for science, obviously.*

**Phantom** is a lightweight, real-time on-screen AI assistant and trivia HUD (Heads-Up Display) for Android. 

It captures screen frames via the Android `MediaProjection` API, extracts questions and options using on-device ML Kit OCR, and queries Groq's high-speed inference engine (`qwen/qwen3.8-27b`) via Server-Sent Events (SSE) streaming—displaying answers directly on top of other apps in a subtle, non-intrusive floating HUD.

---

## 🤔 Why does this exist?

1. **Curiosity**: To see if Android's `MediaProjection` + ML Kit + Groq could answer faster than human reaction time *(spoiler: it can)*.
2. **Engineering**: To test how close to invisible an Android Compose overlay can actually get.
3. **The real reason**: Definitely not because I kept losing trivia games.

---

## ⚡ Features

- **Real-Time Screen Capture**: Utilizes Android's `MediaProjection` and `VirtualDisplay` APIs for background screen acquisition.
- **On-Device OCR & Noise Eraser**: Powered by Google ML Kit Text Recognition with spatial line sorting and noise suppression (strips timestamps, battery %, and status bar icons so the AI doesn't think `"87%"` is the capital of France).
- **Reflex-Grade LLM Speed**: Queries Groq's LPUs running `qwen/qwen3.8-27b` with token-by-token streaming via OkHttp and Kotlin Coroutines `Flow`—faster than the quiz countdown timer can stress you out.
- **Ghost Overlay UI**: Draggable, translucent floating HUD built with Jetpack Compose (`SYSTEM_ALERT_WINDOW`) that dims to near-invisibility when idle so anyone looking over your shoulder just sees you looking like a genius.
- **Modern Android Architecture**: 100% Kotlin, Jetpack Compose, Coroutines & Flow, Hilt Dependency Injection, and Gradle Version Catalogs.

---

## 🏗️ Architecture

```
┌─────────────────────┐
│  Screen Capture     │  MediaProjection + VirtualDisplay
└──────────┬──────────┘
           │ (Bitmap)
┌──────────▼──────────┐
│  ML Kit Vision OCR  │  Spatial Text Sorting & OCR Cleaning
└──────────┬──────────┘
           │ (Structured Question & Options)
┌──────────▼──────────┐
│  Groq Repository    │  OkHttp SSE Stream (qwen/qwen3.8-27b)
└──────────┬──────────┘
           │ (Tokens via Flow)
┌──────────▼──────────┐
│  Answer Overlay HUD │  Jetpack Compose Floating Window
└─────────────────────┘
```

---

## 🚀 Getting Started

### Prerequisites

- Android Studio Meerkat or newer
- JDK 17+
- Android device running Android 8.0 (API 26) or higher
- A free [Groq Cloud API Key](https://console.groq.com/keys)

### Setup & Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/korryrdev/phantom.git
   cd phantom
   ```

2. **Configure your API Key:**
   Create a `local.properties` file in the root directory (or copy from `local.properties.example`):
   ```properties
   GROQ_API_KEY=gsk_your_groq_api_key_here
   ```

3. **Build and Run:**
   - Open the project in Android Studio.
   - Connect your Android device via USB / Wi-Fi debugging.
   - Run the app (`Run` -> `app`).
   - Grant the required permissions:
     - **Display over other apps** (for the floating HUD)
     - **Screen Recording / Capture** (for MediaProjection)

---

## 🛠️ Tech Stack

- **Language**: Kotlin 2.0+
- **UI**: Jetpack Compose, Material 3
- **Vision / OCR**: Google ML Kit Text Recognition
- **Networking**: OkHttp 4 (Server-Sent Events streaming)
- **Dependency Injection**: Hilt
- **Concurrency**: Kotlin Coroutines & Flow
- **Logging**: Timber

---

## ⚖️ Disclaimer

Phantom was developed as an educational project and a technical demonstration of real-time on-device computer vision and low-latency cloud LLMs on Android. It is intended for learning, experimentation, and assistive research.

If you use this to completely destroy your friends on trivia night, that is strictly between you, your conscience, and your group chat.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
