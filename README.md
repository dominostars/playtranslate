# PlayTranslate

A real-time game translation Android app, built for both language learners and people who just want to play. Currently only supports Japanese → English, but more languages coming soon!

To report issues, receive support, or make requests, please join the [Discord server](https://discord.gg/DVCj6p7MUC)

[PlayTranslate with Persona 3 Reload and Breath of Fire 3](https://github.com/user-attachments/assets/135cf573-e438-4aa8-a9fc-8d2a2cd25c43)

## Features

- **Instant Translation** — Capture the game screen and translate Japanese text with one tap
- **Live mode** — Automatically translates as dialogue changes, no tapping required
- **Word lookup** — Hover the floating lens over any word for immediate dictionary definitions.
- **Dual Screen Support** — Works across both screens on dual-display devices like the Ayn Thor
- **Capture regions** — Crop to just the dialogue box, subtitles, or any custom area
- **Anki export** — Save sentences to AnkiDroid with the original text, translation, word list, target words, and a screenshot
- **Offline** — OCR and translation work without an internet connection

## How to Use

1. [Download the latest release by clicking here](../../releases/download/v1.1.0/PlayTranslate-1.1.0.apk)
2. On your Android, enable **Settings → Security → Install unknown apps** for your file manager or browser
3. Open the APK and tap Install
4. On first launch, follow the onboarding steps to grant the necessary permissions

## Support

To report issues, receive support, or make requests, please join the [Discord server](https://discord.gg/DVCj6p7MUC)

You can support PlayTranslate on Ko-fi at https://ko-fi.com/playtranslate

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
