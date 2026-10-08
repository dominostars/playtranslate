# PlayTranslate

<!-- l10n-header -->
<div align="center">

[English](../README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-HK.md) | [Español](./README.es.md) | [العربية](./README.ar.md) | [Français](./README.fr.md) | [Português (Brasil)](./README.pt-BR.md) | [Русский](./README.ru.md) | Deutsch | [日本語](./README.ja.md) | [Türkçe](./README.tr.md) | [Tiếng Việt](./README.vi.md) | [한국어](./README.ko.md) | [ไทย](./README.th.md)

[![Downloads](https://img.shields.io/github/downloads/dominostars/playtranslate/total)](https://github.com/dominostars/playtranslate/releases)
[![Stars](https://img.shields.io/github/stars/dominostars/playtranslate?style=flat)](https://github.com/dominostars/playtranslate/stargazers)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![License](https://img.shields.io/github/license/dominostars/playtranslate)](https://github.com/dominostars/playtranslate/blob/main/LICENSE)

</div>
<!-- /l10n-header -->

PlayTranslate ist eine Android-App, die Spiele, Visual Novels, Manga und jeden anderen Text auf dem Bildschirm in Echtzeit übersetzt und dir beim Sprachenlernen hilft. Unterstützt werden 26 Spielsprachen und 59 Zielsprachen!

[Klicke hier, um die neueste Version herunterzuladen](https://github.com/dominostars/playtranslate/releases/latest)

Um Probleme zu melden, Hilfe zu bekommen oder Wünsche zu äußern, tritt unserem [Discord-Server](https://discord.gg/DVCj6p7MUC) bei.

[PlayTranslate in Persona 3 Reload](https://github.com/user-attachments/assets/e89c2c6e-92f3-41d2-8e51-5483beaca612)

## Funktionen

- **Übersetzung mit einem Tippen**: Ein Tippen genügt, um den Spielbildschirm aufzunehmen und den Text darauf zu übersetzen.
- **Auto-Übersetzung**: Übersetzt automatisch, sobald sich der Dialog ändert – ganz ohne Tippen.
- **Wortsuche**: Fahre mit der schwebenden Lupe über ein beliebiges Wort, und seine Definition aus dem Wörterbuch erscheint sofort.
- **Offline-Nutzung**: Texterkennung (OCR) und Wörterbuchsuche funktionieren ohne Internetverbindung. Für die Übersetzung kannst du zusätzlich Offline-Modelle herunterladen.
- **Furigana- und Pinyin-Modus**: Zeigt in Echtzeit über den Schriftzeichen an, wie sie gelesen werden.
- **Tastenkürzel**: Lege eine physische Taste fest, die Übersetzungen oder Furigana einblendet, solange du sie gedrückt hältst – ideal für Handhelds mit zusätzlichen Tasten.
- **Zwei Bildschirme und Splitscreen**: Funktioniert auf Geräten mit zwei Displays wie dem Ayn Thor über beide Bildschirme hinweg oder im Android-Splitscreen neben Spielen im Fenstermodus.
- **Aufnahmebereiche**: Beschränke die Aufnahme auf die Textbox, die Untertitel oder einen beliebigen benutzerdefinierten Bereich.
- **Sprachausgabe**: Lass dir Texte vorlesen. Die Standardstimme kannst du in den Einstellungen ändern.
- **Anki-Export**: Speichere Sätze in AnkiDroid – mit Originaltext, Übersetzung, Wörterliste, Zielwörtern, Sprachausgabe und Screenshot. Du kannst sogar Spiel-Audio aufnehmen und hinzufügen! Den Kartentyp kannst du frei wählen; für beliebte Stapel gibt es fertige Presets.
- **Yomitan-Integration**: Yomitan-Wörterbücher fügen sich nahtlos ein: Tonhöhenakzent, Häufigkeitsangaben, zusätzliche Kanji-Infos und zusammengeführte Definitionen stehen dir überall zur Verfügung (auch in Anki). Eine noch tiefere Integration ist für die Zukunft geplant.
- **Kameraübersetzung**: Richte die Kamera auf Text in deiner Umgebung und lies die Übersetzung live mit, oder nimm ein Standbild auf, um auf Wörter zu tippen und sie nachzuschlagen.
- **Textverlauf**: Speichert eine Liste der aufgenommenen Sätze. Standardmäßig ausgeschaltet.

## Installation

1. [Klicke hier, um die neueste Version herunterzuladen](https://github.com/dominostars/playtranslate/releases/latest)
2. Erlaube deinem Browser oder Dateimanager auf deinem Android-Gerät, unbekannte Apps zu installieren: Öffne **Einstellungen → Apps → Spezieller App-Zugriff → Unbekannte Apps installieren**, wähle die App aus und aktiviere **Dieser Quelle vertrauen**. Wenn du die APK zum ersten Mal öffnest, bietet Android dir auch an, direkt zu dieser Einstellung zu wechseln
3. Öffne die APK und tippe auf „Installieren“
4. Folge beim ersten Start den Schritten der Einrichtung und erteile die nötigen Berechtigungen

### Die App lässt sich nicht installieren?

Auf manchen Android-Geräten blockiert **Google Play Protect** APKs, die nicht aus dem Play Store stammen, und zeigt eine vage Warnung wie „App nicht installiert“ oder einen Hinweis auf eine schädliche App. Schalte die Prüfung in diesem Fall vorübergehend aus:

1. Öffne den **Play Store**
2. Tippe auf dein **Profilsymbol** (oben rechts)
3. Tippe auf **Play Protect**
4. Tippe auf das **Zahnradsymbol** (oben rechts)
5. Deaktiviere **Apps mit Play Protect scannen**

Installiere die APK und aktiviere Play Protect danach wieder, damit deine anderen Apps weiterhin geprüft werden.

### Bedienungshilfen lassen sich nicht aktivieren?

Bei einigen erweiterten Funktionen (etwa der Vorschau durch Gedrückthalten eines Tastenkürzels) wirst du aufgefordert, die Berechtigung für Bedienungshilfen zu erteilen. Ab Android 13 graut Android den Bedienungshilfen-Schalter jeder App aus, die über eine heruntergeladene APK-Datei installiert wurde, und beim Antippen erscheint der Hinweis „Eingeschränkte Einstellung“. So hebst du die Sperre auf:

1. Wähle unter **Einstellungen → Bedienungshilfen** PlayTranslate aus und tippe einmal auf den ausgegrauten Schalter. Schließe den Hinweis „Eingeschränkte Einstellung“.
2. Öffne die App-Info: **Einstellungen → Apps → PlayTranslate**.
3. Tippe auf das Menü **⋮** und wähle **Eingeschränkte Einstellungen zulassen**. Dieser Eintrag erscheint erst nach Schritt 1.
4. Authentifiziere dich, falls du dazu aufgefordert wirst, kehre dann zu den Bedienungshilfen zurück und aktiviere den Schalter.

## Support

Um Probleme zu melden, Hilfe zu bekommen oder Wünsche zu äußern, tritt unserem [Discord-Server](https://discord.gg/DVCj6p7MUC) bei.

Du kannst PlayTranslate auf Ko-fi unterstützen: https://ko-fi.com/playtranslate

## Unterstützte Sprachen

PlayTranslate übersetzt aus **26 Spielsprachen** (in diesen Sprachen kann die App Text vom Bildschirm lesen) in **59 Zielsprachen** (in diesen Sprachen bekommst du die Übersetzung angezeigt). Beide Tabellen sind nach der weltweiten Gesamtzahl der Sprecher sortiert.

### Spielsprachen (vom Bildschirm gelesen)

| Sprache                   | Eigenbezeichnung | Kürzel  |
|---------------------------|------------------|---------|
| Englisch                  | English          | en      |
| Chinesisch (Vereinfacht)  | 简体中文             | zh      |
| Chinesisch (Traditionell) | 繁體中文             | zh-Hant |
| Hindi                     | हिन्दी           | hi      |
| Spanisch                  | Español          | es      |
| Arabisch                  | العربية          | ar      |
| Französisch               | Français         | fr      |
| Portugiesisch             | Português        | pt      |
| Russisch                  | Русский          | ru      |
| Indonesisch               | Bahasa Indonesia | id      |
| Deutsch                   | Deutsch          | de      |
| Japanisch                 | 日本語              | ja      |
| Türkisch                  | Türkçe           | tr      |
| Vietnamesisch             | Tiếng Việt       | vi      |
| Koreanisch                | 한국어              | ko      |
| Italienisch               | Italiano         | it      |
| Thailändisch              | ไทย              | th      |
| Niederländisch            | Nederlands       | nl      |
| Rumänisch                 | Română           | ro      |
| Ungarisch                 | Magyar           | hu      |
| Schwedisch                | Svenska          | sv      |
| Katalanisch               | Català           | ca      |
| Dänisch                   | Dansk            | da      |
| Finnisch                  | Suomi            | fi      |
| Norwegisch                | Norsk            | no      |
| Polnisch                  | Polski           | pl      |

### Zielsprachen (für dich übersetzt)

| Sprache         | Eigenbezeichnung | Kürzel |
|-----------------|------------------|------|
| Englisch        | English          | en   |
| Chinesisch      | 中文               | zh   |
| Hindi           | हिन्दी           | hi   |
| Spanisch        | Español          | es   |
| Arabisch        | العربية          | ar   |
| Französisch     | Français         | fr   |
| Bengalisch      | বাংলা            | bn   |
| Portugiesisch   | Português        | pt   |
| Russisch        | Русский          | ru   |
| Urdu            | اردو             | ur   |
| Indonesisch     | Bahasa Indonesia | id   |
| Suaheli         | Kiswahili        | sw   |
| Deutsch         | Deutsch          | de   |
| Japanisch       | 日本語              | ja   |
| Marathi         | मराठी            | mr   |
| Telugu          | తెలుగు           | te   |
| Türkisch        | Türkçe           | tr   |
| Vietnamesisch   | Tiếng Việt       | vi   |
| Koreanisch      | 한국어              | ko   |
| Tamil           | தமிழ்            | ta   |
| Persisch        | فارسی            | fa   |
| Italienisch     | Italiano         | it   |
| Thailändisch    | ไทย              | th   |
| Gujarati        | ગુજરાતી          | gu   |
| Polnisch        | Polski           | pl   |
| Ukrainisch      | Українська       | uk   |
| Tagalog         | Tagalog          | tl   |
| Malaiisch       | Bahasa Melayu    | ms   |
| Kannada         | ಕನ್ನಡ            | kn   |
| Niederländisch  | Nederlands       | nl   |
| Rumänisch       | Română           | ro   |
| Ungarisch       | Magyar           | hu   |
| Griechisch      | Ελληνικά         | el   |
| Tschechisch     | Čeština          | cs   |
| Schwedisch      | Svenska          | sv   |
| Belarussisch    | Беларуская       | be   |
| Hebräisch       | עברית            | he   |
| Bulgarisch      | Български        | bg   |
| Katalanisch     | Català           | ca   |
| Slowakisch      | Slovenčina       | sk   |
| Haiti-Kreolisch | Kreyòl Ayisyen   | ht   |
| Kroatisch       | Hrvatski         | hr   |
| Dänisch         | Dansk            | da   |
| Finnisch        | Suomi            | fi   |
| Norwegisch      | Norsk            | no   |
| Albanisch       | Shqip            | sq   |
| Galicisch       | Galego           | gl   |
| Slowenisch      | Slovenščina      | sl   |
| Litauisch       | Lietuvių         | lt   |
| Lettisch        | Latviešu         | lv   |
| Afrikaans       | Afrikaans        | af   |
| Mazedonisch     | Македонски       | mk   |
| Estnisch        | Eesti            | et   |
| Georgisch       | ქართული          | ka   |
| Walisisch       | Cymraeg          | cy   |
| Maltesisch      | Malti            | mt   |
| Isländisch      | Íslenska         | is   |
| Irisch          | Gaeilge          | ga   |
| Esperanto       | Esperanto        | eo   |

## Online-Übersetzungsdienste (optional)

Standardmäßig übersetzt die App mit [Lingva](https://github.com/thedaviddelta/lingva-translate). Ist die Online-Übersetzung nicht verfügbar, springt ML Kit als Offline-Ausweichlösung ein. Für hochwertigere Übersetzungen kannst du unter **Einstellungen → Übersetzungsdienste** einen API-Schlüssel für einen der folgenden Dienste hinterlegen. Du kannst beliebig viele hinzufügen. Jeder Dienst ist ein eigener Eintrag in der Liste, sodass du mehrere einrichten und über ihre Reihenfolge festlegen kannst, welcher zuerst übersetzt:

- **DeepL**: kostenloser Tarif unter [deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer)
- **OpenAI**: [platform.openai.com](https://platform.openai.com/api-keys) – das Modell wählst du direkt in der App
- **Gemini**: [aistudio.google.com](https://aistudio.google.com/app/apikey) – das Modell wählst du direkt in der App
- **DeepSeek**: [platform.deepseek.com](https://platform.deepseek.com/api_keys) – das Modell wählst du direkt in der App
- **Mistral**: [console.mistral.ai](https://console.mistral.ai/api-keys) – das Modell wählst du direkt in der App
- **Groq**: [console.groq.com](https://console.groq.com/keys) – das Modell wählst du direkt in der App
- **OpenRouter**: [openrouter.ai](https://openrouter.ai/keys) – das Modell wählst du direkt in der App
- **Claude**: [platform.claude.com](https://platform.claude.com/settings/keys) – das Modell wählst du direkt in der App
- **Benutzerdefiniert**: jeder andere OpenAI-kompatible Endpunkt – trage unter „Benutzerdefinierte URL“ einfach die URL deines Backends ein

## Anki-Karteikarten (optional)

Installiere [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) und erteile PlayTranslate in den Einstellungen die Berechtigung, auf AnkiDroid zuzugreifen. Dann kannst du Karten direkt in deine Stapel exportieren.

## Danksagungen und Lizenz

Die Danksagungen für die verwendeten Bibliotheken, Modelle und Sprachdaten findest du in der [englischen README](https://github.com/dominostars/playtranslate#credits). PlayTranslate steht unter der [GPL 3.0](https://github.com/dominostars/playtranslate/blob/main/LICENSE).
