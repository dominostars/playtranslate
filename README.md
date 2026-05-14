# PlayTranslate

A real-time game translation Android app, built for both language learners and people who just want to play. Supports 21 game languages and 59 user languages!

To report issues, receive support, or make requests, please join the [Discord server](https://discord.gg/DVCj6p7MUC)

[PlayTranslate with Persona 3 Reload and Breath of Fire 3](https://github.com/user-attachments/assets/135cf573-e438-4aa8-a9fc-8d2a2cd25c43)

## Features

- **One-tap Translation**: Capture the game screen and translate Japanese text with one tap
- **Auto Translation Mode**: Automatically translates as dialogue changes, no tapping required
- **Word lookup**: Hover the floating lens over any word for immediate dictionary definitionss
- **Furigana/Pinyin Mode**: Show reading hints above characters in real time
- **Hotkey hold-to-preview**: Configure a physical key to hold-to-preview translations or furigana, great for handhelds with dedicated buttons
- **Dual Screen & Split Screen**: Works across both screens on dual-display devices like the Ayn Thor, or in Android split-screen alongside windowed games
- **Capture regions**: Crop to just the dialogue box, subtitles, or any custom area
- **Anki export**: Save sentences to AnkiDroid with the original text, translation, word list, target words, and a screenshot. Card type selection with presets for popular decks.
- **Offline**: OCR and translation work without an internet connection, with optional on-device translation models

## How to Use

1. [Download the latest release by clicking here](../../releases/download/v2.1.0/PlayTranslate-2.1.0.apk)
2. On your Android, enable **Settings → Security → Install unknown apps** for your file manager or browser
3. Open the APK and tap Install
4. On first launch, follow the onboarding steps to grant the necessary permissions

### Won't install?

On some Android devices, **Google Play Protect** blocks sideloaded APKs and shows a vague "App not installed" or "harmful app" warning. If that happens, temporarily disable the scanner:

1. Open the **Play Store**
2. Tap your **profile icon** (top right)
3. Tap **Play Protect**
4. Tap the **gear icon** (top right)
5. Turn off **Scan apps with Play Protect**

Install the APK, then re-enable Play Protect afterward to keep scanning your other apps.

### Can't enable accessibility?

Some Android OEMs block sideloaded apps from receiving accessibility permissions by default. The toggle in Settings is grayed out or shows a "Restricted setting" message. To unblock it:

1. Open **Settings → Apps → PlayTranslate**
2. Tap the **⋮** menu (top right)
3. Tap **Allow restricted settings**
4. Authenticate when prompted

You can now turn on accessibility for PlayTranslate.

## Support

To report issues, receive support, or make requests, please join the [Discord server](https://discord.gg/DVCj6p7MUC)

You can support PlayTranslate on Ko-fi at https://ko-fi.com/playtranslate

## Supported Languages

PlayTranslate translates from **21 game languages** (the text it can read off the screen) into **59 translation languages** (the language shown to you). Both tables are sorted by total worldwide speakers.

### Game languages (read from the screen)

| Language              | Native name      | Code     |
|-----------------------|------------------|----------|
| English               | English          | en       |
| Chinese (Simplified)  | 简体中文          | zh       |
| Spanish               | Español          | es       |
| French                | Français         | fr       |
| Portuguese            | Português        | pt       |
| Indonesian            | Bahasa Indonesia | id       |
| German                | Deutsch          | de       |
| Japanese              | 日本語           | ja       |
| Vietnamese            | Tiếng Việt       | vi       |
| Turkish               | Türkçe           | tr       |
| Korean                | 한국어           | ko       |
| Italian               | Italiano         | it       |
| Chinese (Traditional) | 繁體中文          | zh-Hant  |
| Romanian              | Română           | ro       |
| Dutch                 | Nederlands       | nl       |
| Hungarian             | Magyar           | hu       |
| Swedish               | Svenska          | sv       |
| Catalan               | Català           | ca       |
| Danish                | Dansk            | da       |
| Finnish               | Suomi            | fi       |
| Norwegian             | Norsk            | no       |

### Translation languages (translated for you)

| Language       | Native name        | Code |
|----------------|--------------------|------|
| English        | English            | en   |
| Chinese        | 中文               | zh   |
| Hindi          | हिन्दी              | hi   |
| Spanish        | Español            | es   |
| Arabic         | العربية             | ar   |
| French         | Français           | fr   |
| Bengali        | বাংলা              | bn   |
| Portuguese     | Português          | pt   |
| Russian        | Русский            | ru   |
| Urdu           | اردو               | ur   |
| Indonesian     | Bahasa Indonesia   | id   |
| Swahili        | Kiswahili          | sw   |
| German         | Deutsch            | de   |
| Japanese       | 日本語             | ja   |
| Marathi        | मराठी              | mr   |
| Telugu         | తెలుగు              | te   |
| Turkish        | Türkçe             | tr   |
| Vietnamese     | Tiếng Việt         | vi   |
| Korean         | 한국어             | ko   |
| Tamil          | தமிழ்              | ta   |
| Persian        | فارسی              | fa   |
| Italian        | Italiano           | it   |
| Thai           | ไทย                | th   |
| Gujarati       | ગુજરાતી             | gu   |
| Polish         | Polski             | pl   |
| Ukrainian      | Українська         | uk   |
| Tagalog        | Tagalog            | tl   |
| Malay          | Bahasa Melayu      | ms   |
| Kannada        | ಕನ್ನಡ              | kn   |
| Dutch          | Nederlands         | nl   |
| Romanian       | Română             | ro   |
| Hungarian      | Magyar             | hu   |
| Greek          | Ελληνικά           | el   |
| Czech          | Čeština            | cs   |
| Swedish        | Svenska            | sv   |
| Belarusian     | Беларуская         | be   |
| Hebrew         | עברית              | he   |
| Bulgarian      | Български          | bg   |
| Catalan        | Català             | ca   |
| Slovak         | Slovenčina         | sk   |
| Haitian Creole | Kreyòl Ayisyen     | ht   |
| Croatian       | Hrvatski           | hr   |
| Danish         | Dansk              | da   |
| Finnish        | Suomi              | fi   |
| Norwegian      | Norsk              | no   |
| Albanian       | Shqip              | sq   |
| Galician       | Galego             | gl   |
| Slovenian      | Slovenščina        | sl   |
| Lithuanian     | Lietuvių           | lt   |
| Latvian       | Latviešu           | lv   |
| Afrikaans      | Afrikaans          | af   |
| Macedonian     | Македонски         | mk   |
| Estonian       | Eesti              | et   |
| Georgian       | ქართული            | ka   |
| Welsh          | Cymraeg            | cy   |
| Maltese        | Malti              | mt   |
| Icelandic      | Íslenska           | is   |
| Irish          | Gaeilge            | ga   |
| Esperanto      | Esperanto          | eo   |

## Optional: DeepL API Key

By default, translation uses [Lingva](https://github.com/thedaviddelta/lingva-translate) with ML Kit as an offline fallback. For higher quality translations, get a free DeepL API key at [deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer) and enter it in **Settings → DeepL API Key**.

## Optional: Anki Flashcards

Install [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) and grant PlayTranslate access in Settings to export cards directly to your decks.

## Credits

### Libraries and services

- [ML Kit](https://developers.google.com/ml-kit): on-device OCR and translation
- [Kuromoji](https://github.com/atilika/kuromoji): Japanese morphological analysis
- [HanLP](https://github.com/hankcs/HanLP): Chinese word segmentation
- [KOMORAN](https://github.com/shineware/KOMORAN): Korean morphological analysis
- [Snowball stemmers](https://snowballstem.org/) via [Apache Lucene](https://lucene.apache.org/): Latin/European stemming
- [Lingva](https://github.com/thedaviddelta/lingva-translate): online translation
- [AnkiDroid](https://github.com/ankidroid/Anki-Android): flashcard integration
- [llama.cpp](https://github.com/ggerganov/llama.cpp): on-device LLM inference engine (MIT)
- Arm AI Chat: Android wrapper around llama.cpp
- [OpenCL Headers](https://github.com/KhronosGroup/OpenCL-Headers) and [ICD Loader](https://github.com/KhronosGroup/OpenCL-ICD-Loader): GPU backend (Apache 2.0)

### (Optional) Downloadable Offline Models

- TranslateGemma 4B (Google): translation-tuned Gemma 3, downloadable as an optional offline pack (Gemma terms of use)
- Qwen 2.5 1.5B Instruct (Alibaba): downloadable as an optional offline pack (Apache 2.0)

### Linguistic data

- [JMdict](https://www.edrdg.org/jmdict/j_jmdict.html) and [KANJIDIC2](https://www.edrdg.org/kanjidic/kanjidic2.html): Japanese dictionary and kanji data (EDRDG licence)
- [CC-CEDICT](https://cc-cedict.org/wiki/): Chinese-English dictionary (CC BY-SA 4.0)
- [Wiktionary](https://en.wiktionary.org/) via [kaikki.org](https://kaikki.org/): multilingual dictionary entries (CC BY-SA)
- [Tatoeba](https://tatoeba.org/): example sentences (CC BY 2.0)
- [PanLex](https://panlex.org/): multilingual translation pairs (CC0)
- [wordfreq](https://github.com/rspeer/wordfreq): word frequency data

## License

[GPL 3.0](LICENSE)
