# 👻 Phantom

**Phantom** is a lightweight, real-time on-screen AI assistant and trivia HUD for Android. 

It captures screen frames via the Android MediaProjection API, processes questions and options using on-device ML Kit OCR, and queries Groq's high-speed inference engine (`qwen/qwen3.8-27b`) via Server-Sent Events (SSE) streaming—displaying answers directly on top of other apps in a subtle, non-intrusive floating HUD.

---

## ⚡ Features

- **Real-Time Screen Capture**: Utilizes Android's `MediaProjection` and `VirtualDisplay` APIs for background screen acquisition.
- **On-Device OCR & Spatial Filtering**: Powered by Google ML Kit Text Recognition with spatial line sorting and noise suppression (status bar artifacts, timestamps, icons).
- **Ultra-Fast LLM Inference**: Queries Groq's LPUs running `qwen/qwen3.8-27b` with token-by-token streaming via OkHttp and Kotlin Coroutines `Flow`.
- **Ghost Overlay UI**: Draggable, translucent floating window built with Jetpack Compose (`SYSTEM_ALERT_WINDOW`) that stays out of your way and dims when idle.
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

Phantom was developed as an educational project and a technical demonstration of real-time on-device computer vision and low-latency cloud LLM streaming on Android. It is intended for learning, experimentation, and assistive research. Please do not use it in competitive, academic, or prohibited environments.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
