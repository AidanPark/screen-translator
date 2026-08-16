<p align="center">
  <img src="app/src/main/res/mipmap-xxhdpi/ic_launcher.png" width="120" alt="Screen Translate Icon"/>
</p>

<h1 align="center">Screen Translate</h1>

<p align="center">
  <strong>Point. Tap. Understand.</strong><br>
  <em>Instantly translate any text on your Android screen — right where it appears.</em>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.galaxy.airviewdictionary">
    <img src="https://img.shields.io/badge/Google%20Play-1M%2B%20Downloads-34A853?style=for-the-badge&logo=google-play&logoColor=white" alt="Google Play"/>
  </a>&nbsp;
  <img src="https://img.shields.io/badge/Rating-3.9%20%E2%AD%90-FBBC04?style=for-the-badge" alt="Rating"/>&nbsp;
  <img src="https://img.shields.io/badge/146%2B%20Languages-4285F4?style=for-the-badge&logo=google-translate&logoColor=white" alt="Languages"/>
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.galaxy.airviewdictionary">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" width="220" alt="Get it on Google Play"/>
  </a>
</p>

---

## The Problem

You're reading a webpage in Japanese. Or watching a Korean drama without subtitles. Or trying to use a Chinese app. Every time, you have to:

1. Long-press the text (if you even can)
2. Copy it
3. Switch to a translator app
4. Paste it
5. Read the translation
6. Switch back

**What if you could just... point at it?**

---

## How It Works

Screen Translate floats on top of whatever you're doing. Just drag the pointer to any text, and the translation appears instantly — like magic.

<table>
  <tr>
    <td align="center" width="50%">
      <strong>Word Mode</strong><br>
      <em>Tap any word for instant translation</em><br><br>
      <img src=".images/word_original_rounded.gif" width="280" alt="Word Mode"/>
    </td>
    <td align="center" width="50%">
      <strong>Sentence Mode</strong><br>
      <em>Auto-detects and translates full sentences</em><br><br>
      <img src=".images/sentence_original_rounded.gif" width="280" alt="Sentence Mode"/>
    </td>
  </tr>
  <tr>
    <td align="center">
      <strong>Paragraph Mode</strong><br>
      <em>Translates entire blocks of text at once</em><br><br>
      <img src=".images/paragraph_original_rounded.gif" width="280" alt="Paragraph Mode"/>
    </td>
    <td align="center">
      <strong>Selection Mode</strong><br>
      <em>Draw a box around exactly what you need</em><br><br>
      <img src=".images/select_original_rounded.gif" width="280" alt="Selection Mode"/>
    </td>
  </tr>
</table>

---

## Works Everywhere

<p align="center">
  <img src=".images/screenshots/02.png" width="220" alt="Works with every app"/>
  &nbsp;&nbsp;
  <img src=".images/screenshots/05.png" width="220" alt="Select what you need"/>
  &nbsp;&nbsp;
  <img src=".images/screenshots/06.png" width="220" alt="Even comics"/>
</p>

<p align="center">
  News apps, social media, games, manga, webtoons — if there's text on screen, it can be translated.
</p>

---

## Pick Your Translation Engine

<p align="center">
  <img src=".images/screenshots/04.png" width="250" alt="Multiple translation engines"/>
</p>

Not all translation engines are created equal. Screen Translate lets you switch between **five engines** to find the best one for your language pair:

<table>
  <tr>
    <td align="center" width="20%">
      <img src="https://img.shields.io/badge/Google-4285F4?style=for-the-badge&logo=googletranslate&logoColor=white" alt="Google Translate"/><br><br>
      <strong>Google Translate</strong><br>
      <sub>The all-rounder. Fast, free,<br>and built in — no key needed.</sub>
    </td>
    <td align="center" width="20%">
      <img src="https://img.shields.io/badge/DeepL-0F2B46?style=for-the-badge&logo=deepl&logoColor=white" alt="DeepL"/><br><br>
      <strong>DeepL</strong><br>
      <sub>Natural, human-sounding<br>translations. Best for European languages.</sub>
    </td>
    <td align="center" width="20%">
      <img src="https://img.shields.io/badge/OpenAI-412991?style=for-the-badge&logo=openai&logoColor=white" alt="OpenAI"/><br><br>
      <strong>OpenAI</strong><br>
      <sub>LLM translation that keeps<br>context, tone, and idiom.</sub>
    </td>
    <td align="center" width="20%">
      <img src="https://img.shields.io/badge/Gemini-8E75B2?style=for-the-badge&logo=googlegemini&logoColor=white" alt="Gemini"/><br><br>
      <strong>Gemini</strong><br>
      <sub>Google's LLM. Fast, with a<br>generous free tier.</sub>
    </td>
    <td align="center" width="20%">
      <img src="https://img.shields.io/badge/Claude-D97757?style=for-the-badge&logo=claude&logoColor=white" alt="Claude"/><br><br>
      <strong>Claude</strong><br>
      <sub>Anthropic's LLM. Strong at<br>nuance and longer passages.</sub>
    </td>
  </tr>
</table>

> Google works out of the box. **DeepL, OpenAI, Gemini and Claude run on your own API key** — enter it under *Settings → API Key*. For the three LLM engines you can also choose which model to use.

> **A note on the screenshots:** some still show the older engine line-up, which included **Azure Translator** and **Papago**. Those two ran on API keys the app handed out to paid installs — when the paid features were removed, the keys went away and those engines went with them. DeepL and OpenAI stayed on by switching to keys you supply yourself.

---

## 130+ Languages

<p align="center">
  <img src=".images/screenshots/03.png" width="250" alt="130+ languages"/>
</p>

From Arabic to Zulu, from English to Japanese — translate between **130+ languages** with automatic source language detection. No need to manually set what language you're reading.

---

## Why I Built This

I live between languages every day, and existing solutions all required too many steps. I wanted something that just **works** — point at text, get the meaning. No context switching, no clipboard juggling.

Over time, it grew into a full-featured translation tool with multiple engines and smart text recognition. And now **over 1 million people** use it.

---

## Under the Hood

> *For the curious developers who want to peek inside.*

### How the magic happens

1. **Screen Capture** — Android's `MediaProjection` API continuously captures what's on your screen
2. **Text Recognition** — Google ML Kit runs **on-device OCR** to find and extract text from the captured frames
3. **Smart Grouping** — A custom algorithm clusters detected text into words, lines, sentences, or paragraphs based on position, spacing, and font size
4. **Translation** — The recognized text is sent to your chosen translation engine via REST APIs
5. **Overlay Rendering** — Results are displayed in a floating Compose UI overlay on top of your current app

All of this happens in **under a second**.

### Tech highlights

| | |
|---|---|
| **Kotlin + Jetpack Compose** | Modern Android UI with reactive state management |
| **Adaptive, edge-to-edge UI** | Scales cleanly from phones to tablets and foldables |
| **Clean Architecture + MVVM** | Separation of concerns with ViewModels and Repositories |
| **Hilt** | Dependency injection for clean, testable code |
| **ML Kit** | On-device OCR for English, Chinese, Japanese, Korean, and Devanagari |
| **Multi-engine translation** | Google, DeepL, OpenAI, Gemini, and Claude behind one interface |
| **Coroutines + StateFlow** | Smooth async operations without callback hell |
| **Firebase** | Analytics, Crashlytics, and Remote Config |
| **Firebase App Check** | Play Integrity attestation for backend requests |
| **AdMob** | A rewarded-ad gate — how the app stays free |
| **Encrypted key storage** | AES-GCM + Android Keystore for user-supplied API keys |
| **MediaProjection** | Real-time screen capture with optimized frame streaming |

### Project structure

```
app/src/main/java/com/galaxy/airviewdictionary/
├── core/                  # OverlayService — the heart of the app
├── data/
│   ├── local/
│   │   ├── vision/        # OCR & text grouping algorithms
│   │   ├── capture/       # Screen capture via MediaProjection
│   │   ├── tts/           # Text-to-Speech
│   │   ├── secure/        # Encrypted storage for user API keys
│   │   ├── ads/           # Rewarded-ad state
│   │   └── preference/    # User settings
│   └── remote/
│       ├── translation/   # Google, DeepL, OpenAI, Gemini, Claude
│       ├── firebase/      # Analytics & Remote Config
│       └── geolocale/     # Region lookup
├── ui/
│   ├── screen/
│   │   ├── overlay/       # Floating translation UI
│   │   ├── main/          # Settings screens
│   │   ├── ads/           # Rewarded-ad gate
│   │   ├── reply/         # Reply translation window
│   │   └── onboarding/    # First-time setup
│   └── common/            # Shared components
├── di/                    # Hilt dependency injection
└── extensions/            # Kotlin extension functions
```

---

## Building from Source

### What you need

- **Android Studio** (2025.3 or later — the project builds with AGP 9)
- **JDK 17**
- **Android SDK 36**

### Steps

1. **Clone the repo**
   ```bash
   git clone https://github.com/AidanPark/android-screen-translator.git
   cd android-screen-translator
   ```

2. **Set up signing** — Edit `gradle.properties`:
   ```properties
   KEYSTORE_FILE=your-keystore.jks
   KEY_ALIAS=your-key-alias
   KEY_PASSWORD=your-key-password
   ```

3. **Add Firebase config** — Place your own `google-services.json` in the `app/` directory

4. **Build & Run**
   ```bash
   ./gradlew assembleDebug
   ```

> **Note:** You'll need your own Firebase project to run the full app. Google translation and the on-device OCR need no API key at all. DeepL, OpenAI, Gemini and Claude are optional — they use keys you enter inside the app at runtime (*Settings → API Key*), not build-time config.

---

## Permissions Explained

| Permission | What it does |
|-----------|-------------|
| **Display over other apps** | Shows the floating translation overlay |
| **Screen capture** | Reads text from your screen using OCR |
| **Internet** | Connects to translation APIs |
| **Vibration** | Gives haptic feedback when text is detected |
| **Notifications** | Required for the foreground service indicator |

---

## License

This project is provided for **educational and reference purposes**. See [LICENSE](LICENSE) for details.

---

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.galaxy.airviewdictionary">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" width="220" alt="Get it on Google Play"/>
  </a>
  <br><br>
  <sub>Made with caffeine and curiosity.</sub>
</p>
