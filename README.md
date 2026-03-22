# PlayTranslate

A real-time game translation app, built primarily for the **Ayn Thor** dual-screen Android device. Currently only supports Japanese → English.

PlayTranslate runs on the bottom screen while your game runs on the top screen. It will capture the game screen, OCR the Japanese text, and get an instant translation — with word-by-word definitions, romaji, and optional Anki flashcard export.

> Note: This app was built using Claude Code, and I'm new to running a GitHub repository, but I'd love any feedback, feature requests, or pull requests. I made this app because I wanted it, and I'm hoping it will be useful for you too.

## Features

- **OCR + Translation** — Captures the game screen and translates Japanese text on demand
- **Live mode** — Automatically captures and translates on a set interval, so the screen updates as dialogue changes without you having to tap
- **Word lookup** — Tap any word in the original text for a full dictionary entry (readings, meanings, JLPT level, frequency)
- **Romaji** — Automatic transliteration shown below the original text
- **Capture regions** — Crop to just the dialogue box, subtitles, or any custom area
- **Anki export** — Save sentences to AnkiDroid with the original text, translation, word list, and a screenshot
- **Offline** — OCR and translation work without an internet connection (ML Kit on-device models)
- **Themes** — Black, White, Stone, and Purple

## Requirements

- **Device:** Ayn Thor (dual-screen Android handheld)
- **OS:** Android 8.0 (API 26) or higher
- **Screen capture permission** — required for most features
- **Accessibility permission** *(optional)* — required to create and preview custom capture regions on the game screen

## Installation

1. Download `PlayTranslate-v0.1.0.apk` from the [Releases](../../releases) page
2. On your Ayn Thor, enable **Settings → Security → Install unknown apps** for your file manager or browser
3. Open the APK and tap Install
4. On first launch, follow the onboarding steps to grant the necessary permissions

## Optional: DeepL API Key

By default, translation uses [Lingva](https://github.com/thedaviddelta/lingva-translate) with ML Kit as an offline fallback. For higher quality translations, get a free DeepL API key at [deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer) and enter it in **Settings → DeepL API Key**.

## Optional: Anki Flashcards

Install [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) and grant PlayTranslate access in Settings to export cards directly to your decks.

## Credits

- [ML Kit](https://developers.google.com/ml-kit) — on-device OCR and translation
- [JMdict](https://www.edrdg.org/jmdict/j_jmdict.html) — Japanese dictionary data (EDRDG licence)
- [Kuromoji](https://github.com/atilika/kuromoji) — Japanese morphological analysis
- [Lingva](https://github.com/thedaviddelta/lingva-translate) — online translation
- [AnkiDroid](https://github.com/ankidroid/Anki-Android) — flashcard integration

## License

[GPL 3.0](LICENSE)
