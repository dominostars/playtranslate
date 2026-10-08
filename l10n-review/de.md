# German (values-de) localization review

Mechanical layer first: all `<xliff:g>` inner content, placeholders, `\n` / `\{\}` / `&lt;…&gt;` escapes, markup, and plural categories check out; the file uses „ " typographic quotes throughout, so there are no unescaped apostrophes. **No 🛑 findings.**

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| pack_upgrade_mandatory_message | ❌ | „Aktualisiere jetzt oder lösche **es**, um eine andere Sprache zu wählen." | „…oder lösche **das Paket**, um…" | "es" has no valid antecedent — the nearest noun is „die installierte Version" (feminine, would need „sie"); the actual referent is the language pack. As written, the destructive instruction is grammatically broken and ambiguous. |
| label_region_drag_hint | ❌ | „Ziehe die obere oder untere Kante oder die Mitte, um das gesamte Feld zu verschieben." | „Ziehe die obere oder untere Kante, oder ziehe die Mitte, um das gesamte Feld zu verschieben." | The DE merges all three drag targets under "moves the whole box". In the EN, the repeated "drag" scopes "to move the whole box" to the middle only; edges resize. The current DE actively misinstructs. |
| onboarding_a11y_title, mp_overlay_permission_title | ⚠ | „Über anderen Apps **anzeigen**" | „Über anderen Apps **einblenden**" | Android's German system wording for this permission screen is „Über anderen Apps einblenden". These dialogs send the user to that exact screen — the title should match verbatim. (Body prose in onboarding_a11y_body / mp_overlay_permission_message can keep „angezeigt werden".) |
| settings_capture_interval_hint | ⚠ | „Mindestens <xliff>%1$s</xliff> Sekunden." | „Mindestens <xliff>%1$s</xliff> Sek." | When the minimum is exactly "1" (the documented example), „Mindestens 1 Sekunden" is wrong number agreement. The abbreviation doesn't inflect, so it's safe for 0,5 / 1 / 2. |
| lang_setup_requires_64bit_msg | ⚠ | „…erfordert ein 64-Bit-Gerät, **was dieses Gerät nicht ist**." | „…erfordert ein 64-Bit-Gerät – dieses Gerät ist keines." | Direct calque of "which this device isn't"; reads as machine translation. |
| tts_no_engine_dialog_message | ⚠ | „Installiere eine – oder aktiviere sie, falls dein Gerät bereits eine Engine hat, in den Android-Einstellungen." | „Installiere eine – oder aktiviere sie in den Android-Einstellungen, falls dein Gerät bereits eine hat." | „in den Android-Einstellungen" dangles after the inserted falls-clause; the verb bracket is stretched past readability. |
| tr_service_offline_footer | ⚠ | „…können sehr langsam und **belastend** sein" | „…können sehr langsam sein und das Gerät stark belasten" | "taxing" means resource-heavy here; bare „belastend" reads as emotionally burdensome. |
| anki_content_flag_vocabulary_desc, anki_content_flag_sentence_desc, anki_content_flag_targeted_sentence_desc | ⚠ | „bei **Sendungen** im Wortmodus" / „bei **Satzsendungen**" | „beim Senden im Wortmodus" / „beim Senden von Satzkarten" | „Sendung" means broadcast/parcel; „Satzsendungen" is genuinely confusing. The nominalized infinitive is the natural rendering of "sends". |
| settings_show_overlay_icon | ⚠ | „**Zum** schwebenden Symbol" | „**Auf dem** schwebenden Symbol" | This is an informational header over the gesture list (gestures performed *on* the icon). „Zum…" reads like a navigation cell ("go to the floating icon"). |
| hymt_legal_message | ⚠ | „Indem du auf **Zustimmen** tippst" | „Indem du auf „Ich stimme zu" tippst" | The button (hymt_legal_agree) is labeled „Ich stimme zu – Hunyuan aktivieren". In an attestation, the referenced control name should match the actual button. Everything else is faithful: §5(b) kept, EU/UK/Südkorea enumeration intact, „versicherst und garantierst" carries the affirm-and-warrant force. |
| settings_header_ocr | ⚠ | „Bild zu Text (OCR)" | „Texterkennung (OCR)" | „Bild zu Text" is a calque; „Texterkennung" is the standard German term and already used in lang_setup_requires_64bit_msg („Die Texterkennung für…"). |
| target_pack_migration_title, target_pack_migration_message, anki_section_description | ⚠ | „Definitionen **in** Spanisch" / „Karteikarten **in** Englisch" | „Definitionen **auf** Spanisch" / „Karteikarten **auf** Englisch" | For content in a language, idiomatic German is „auf <Sprache>"; „in <Sprache>" reads like a school subject. Works with the bare language-name placeholder. |
| translate_button_prefix_translate, translate_button_prefix_reload | ⚠ | composed: „Übersetzen Vollbild" / „Neu laden Vollbild" | „Übersetzen:" / „Neu laden:" | The code joins prefix + space + region label; verb-first „Übersetzen Vollbild" is not grammatical German. Since the order is code-fixed, a trailing colon is the practical fix. |
| nav_settings | ⚠ | „Einstellungen" (8sp bottom-bar label) | verify on device; no good shorter standard term | 13 chars at 8sp next to Auto/Pause/Bereiche is the highest truncation risk in the file. German has no accepted short form for Settings — if it clips, prefer auto-shrink/marquee over a nonstandard word. |
| floating_menu_btn_capture_region | 💬 | „Aufnahme-\nbereich" | keep; verify at 9sp/54dp | Top line „Aufnahme-" is 9 glyphs vs EN „Capture" 7 — likely fits, but it's the two-line button the checklist calls out; worth one on-device look. |
| onboarding_notif_body | 💬 | „dass Apps, die im Hintergrund laufen, während sie aktiv sind, eine Benachrichtigung … anzeigen" | „dass Apps, die im Hintergrund laufen, eine Benachrichtigung in der Statusleiste anzeigen, solange sie aktiv sind" | Grammatical, but the stacked insertions force a re-read. |
| anki_content_section_flag | 💬 | „Kartentyp-**Kennzeichen**" | „Kartentyp-**Markierungen**" | The four items under this header all call themselves „…-Markierung (x)"; the header should use the same word. |
| btn_clear | 💬 | „Löschen" | „Leeren" | "Clear" (wipe a field on the Anki sheet) currently shares its label with destructive "Delete" („Löschen" in pack_upgrade_button_delete, settings_ocr_delete_confirm); „Leeren" disambiguates. |
| crash_dialog_title | 💬 | „PlayTranslate ist **zuvor** abgestürzt" | „PlayTranslate ist kürzlich abgestürzt" | „zuvor" without a reference point is stilted. |
| tts_no_engine_row_subtitle | 💬 | „Keine **Sprach-Engine** verfügbar" | „Keine Sprachausgabe-Engine verfügbar" | Everywhere else TTS engine = „Sprachausgabe-Engine" (tts_no_engine_dialog_message, tts_language_unsupported_unknown_engine_message). |
| tts_voices_section_header | 💬 | „<xliff>%1$s</xliff>-STIMMEN" → „JAPANISCH-STIMMEN" | „STIMMEN FÜR <xliff>%1$s</xliff>" | The hyphen compound with an uppercased language name is readable but clunky as a section header. |
| anki_long_press_footer | 💬 | „zur <xliff>anki</xliff>-Kartenerstellungsseite bringt" | „zur Kartenerstellungsseite von <xliff>anki</xliff> bringt" | Bolting a German compound onto the (source-lowercase) brand inside the xliff block looks off; repositioning the whole block is allowed and cleaner. |
| hint_region_name | 💬 | „z. B. Dialogfeld" | „z. B. Textbox" | „Dialogfeld" is the established term for an OS dialog window; gamers call the in-game element Textbox/Dialogbox. |
| accessibility_dialog_message, overlay_icon_a11y_required_message | 💬 | „Einstellungen → Bedienungshilfen → **Installierte Apps**" | „Heruntergeladene Apps" | Stock Android's German accessibility list names the section „Heruntergeladene Apps". The EN source has the same drift ("Installed apps"), so this is an upstream note, not a translation error. |
| anki_settings_get_ankidroid_title | 💬 | „AnkiDroid kostenlos bei Google Play **erhalten**" | „…kostenlos bei Google Play **laden**" | „erhalten" for "Get" is stiff; the sibling string settings_anki_get_app_summary already says „Lade … herunter". |
| settings_overlay_mode_subtitle | 💬 | „bei der **Halten-Vorschau**" | „bei der Vorschau durch Gedrückthalten" | Ad-hoc compound for "hold-to-preview" doesn't parse on first read. |
| cd_toggle_inline_furigana | 💬 | „**Integriertes** Furigana ein-/ausschalten" | „Inline-Furigana ein-/ausschalten" | „Integriert" is the established translation of "built-in" (and is used that way in settings_ocr_note_builtin); "inline" is the standard loanword. |
| a11y_required_enhanced_message | 💬 | „um das **Erlebnis der automatischen Übersetzung** zu verbessern" | „um die automatische Übersetzung zu verbessern" | "experience" calque; German drops it naturally. |
| anki_sort_field_empty | 💬 | „führen … zu **Duplikat-Ablehnungsfehlern**" | „führen dazu, dass die Karte beim Senden als Duplikat abgelehnt wird" | Triple compound is technically parseable but heavy for an error message. |
| word_detail_stroke_abbr | 💬 | „Str." | verify pill width; consider „STR" | Source says the pill fits three glyphs; „Str." is four and lowercase reads as the „Straße" abbreviation out of context. |

## Verdicts

- **Register consistency:** clean — informal lowercase `du/dein/dir/dich` throughout, no `Sie` anywhere, imperatives consistently du-form.
- **Terminology consistency:** good — Stapel/Kartentyp, Bereich, Bildschirmaufnahme, Sprachpaket, Tastenkürzel, Sprachausgabe, Bedienungshilfen, getaktetes Netzwerk, herunterladen/löschen all map 1:1; only minor drift ("Get"→erhalten/laden, Kennzeichen/Markierung, Sprach-Engine).
- **Android-settings wording:** mostly matches (Bedienungshilfen ✓, Schnelleinstellungen ✓, „getaktet" ✓, „Eingeschränkte Einstellungen zulassen" ✓); the overlay permission should be „Über anderen Apps einblenden", and the accessibility nav path inherits the source's "Installed apps" drift.
- **Plurals:** clean — all three `<plurals>` (Bedeutung/Bedeutungen, Zeichen, Treffer) are correct for German one/other.
- **Grammar around placeholders:** strong overall („Das 1,2 GB große Modell", „1,2 GB sind frei" all agree); two real defects: pack_upgrade_mandatory_message pronoun and „Mindestens 1 Sekunden".
- **Truncation risk:** Auto/Pause/Bereiche fine; „Einstellungen" at 8sp and „Aufnahme-\nbereich" at 9sp need one on-device check.
- **Legal text:** faithful and conservative — §5(b), the EU/UK/Südkorea enumeration, and the affirm-and-warrant force all intact; only the „Zustimmen"-vs-„Ich stimme zu" button-name mismatch to align.
- **Overall:** **fix-then-ship** — fix the two ❌ items and the system-wording/legal-button ⚠ items; the rest is polish on an otherwise genuinely natural, consistent translation.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR sets; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| anki_content_frequency_values_desc | 💬 | „…jeder **Häufigkeitswörterbuch-Wert** für das hervorgehobene Wort…“ | „…jeder **Wert aus den Häufigkeitswörterbüchern**…“ | Triple noun-stack for "frequency-dictionary value" is parseable but the heaviest compound in the set. The sibling anki_content_frequency_harmonic_desc renders the same concept more lightly as „Wörterbuch-Häufigkeiten“. Optional. |
| yomitan_import_summary_more | 💬 | one: „+<xliff>%1$d</xliff> **weiterer**“ | „+<xliff>%1$d</xliff> weiterer **Eintrag**“ (or keep) | Appended after an elided name list (e.g. „JMdict, +1 weiterer“). The masculine „weiterer“ silently agrees with an elided masc. noun (Eintrag/Name) and reads fine in context; standalone it's slightly bare. „other“ form „+%1$d weitere“ is clean. Acceptable as-is; flagged only for the elision assumption. |

## Clean areas (delta)
- **du-register:** clean across all 29 — every imperative is du-form (`Verwende https://`); no `Sie`/`Ihr`/`Ihre` anywhere (grep-verified on the exact target lines). Matches the file's established informal register.
- **Quotes:** all field-name and option-name citations use „ “ typographic quotes (`„PitchPosition“`, `„Frequency“`, `„FreqSort“`, `„FrequencySort“`, `„FrequenciesStylized“`, `„PAOverride“`); zero raw apostrophes, so no escaping needed.
- **Terminology — 1:1 with the rest of the file:** Tonhöhenakzent (= yomitan_category_pitch_accent), Häufigkeit/Häufigkeitsliste (= yomitan_category_frequency, anki_content_frequency*), Wörterbuch/Wörterbücher (file-wide), Import/importieren (= yomitan_importing_*, yomitan_io_error_*), `hervorgehobene(s) Wort` (matches all sibling anki_content_*_desc), Sprachausgabe for the TTS audio source (= settings_cell_tts / tts_no_engine_* / anki_content_*_audio_desc), Erweitert for "Advanced", Benutzerdefiniert(e) for "Custom" (= llm_backend_model_custom_entry), „Keine Ergebnisse“ for "No results" (= lang_search_no_results, dictionary_status_no_results), Stapel preserved for deck in neighbors. No drift introduced.
- **"Couldn't"/failure patterns:** consistent with the file's house style — `audio_error_loading` „Konnte nicht geladen werden“ mirrors word_detail_more_examples_error and the `konnte nicht … werden` family (anki_translation_error, dictionary_status_error); `yomitan_import_summary_title_none` „Import nicht möglich“ and `yomitan_import_summary_failed` „Fehlgeschlagen“ align with yomitan_io_error_title „Import fehlgeschlagen“.
- **"Loading…":** `audio_loading` „Wird geladen…“ and `yomitan_importing_progress` „… wird importiert…“ follow the dominant passive-`wird`-…-`-t` progress idiom used by llm_model_picker_loading, anki_deck_picker_loading, install_downloading_*, etc. Ellipsis character preserved.
- **Plurals / dative:** `yomitan_import_summary_count` correct — other: „<xliff>%1$d</xliff> von <xliff>%2$d</xliff> **Wörterbüchern** importiert.“ (dative plural after „von“ ✓); one (fires when total = 1): „… von <xliff>%2$d</xliff> **Wörterbuch** importiert.“ (neuter dative singular, no inflection ✓). `yomitan_import_summary_more` carries both German one/other with correct weiterer/weitere split. Both `<xliff:g>` spans byte-identical to EN.
- **Placeholder grammar:** `llm_backend_base_url_invalid` keeps the literal `https://`/`http://` tokens verbatim and reads naturally („Verwende https:// – http:// ist nur für eine lokale oder LAN-Adresse zulässig“); `yomitan_importing_progress` „%1$d von %2$d wird importiert…“ reads correctly with real counts (noun deliberately omitted, matching the EN comment, so no agreement trap); the summary-line `: %1$s` strings are clean colon-prefix constructions.
- **Short-label truncation:** the audio-picker section headers (`audio_source_tts_name` „Sprachausgabe“ 13 ch, `audio_source_commons_name` brand, `audio_no_results`/`audio_loading`/`audio_error_loading` cell text) and `audio_source_picker_title` „Audio“ are section headers / full-width cells, not the tiny bottom-bar labels — no clipping risk. `llm_backend_advanced_header` „Erweitert“ (9 ch) and `yomitan_auto_update_label` „Automatische Updates“ are card/group headers with room. „Benutzerdefinierte URL“ (Custom URL row label) is long but a standard full-width row label.
- **`Example:` / quoted-field-name rule:** honored — `anki_content_pitch_position_desc` keeps `Beispiel: 0,2` verbatim (sample untranslated, only the lead-in „Beispiel:“ localized), and all brand-template field names („PitchPosition“, „FreqSort“, „FrequenciesStylized“, …) are left in their original English inside the typographic quotes. Brands Lapis/JPMN/Wikimedia Commons/OpenAI untranslated.
- **Overall (delta):** **ship** — no 🛑/❌/⚠️; two 💬 nits only, both optional. The 29 new keys are natural, du-consistent, and reuse the file's established terminology verbatim.

---

# Delta review — 2026-07-14 sync (174 keys)

Scope: the 170 newly translated + 4 changed-English keys (History/translation log, LLM prompt editor, in-app updater, game-audio trim, single-app capture, OCR picker, the 38 `misc_*` dictionary tags).

**Mechanical layer — verified programmatically, no 🛑.** All 174 keys present, none extra; every `%1$s`/`%2$s`/`%d` present and matching EN; all `<xliff:g>` spans byte-identical to EN including `id`/`example`; `\n` preserved in `floating_menu_capture_screen`; no unescaped `'`/`"`; `<plurals>` = one/other (German CLDR); XML parses. The bare in-prose keyword tokens (`{N}`, `{source}`, `{source_code}`, `{target}`, `{target_code}`, `{text}`, `{strings}`, `{context}`) are byte-identical Latin in every string that carries them. The only EN↔DE `&amp;` delta is `update_dialog_download` ("Download & install" → „Herunterladen und installieren“) — correct German, not a lost escape.

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| history_clear_menu, history_clear_confirm_title | ❌ | „Verlauf **löschen**“ / „Gesamten Verlauf **löschen**?“ | „Verlauf **leeren**“ / „Gesamten Verlauf **leeren**?“ | **"Clear" collapsed into "Delete", against a committed precedent.** This file already distinguishes them: `btn_clear` = „Leeren“ and `dictionary_clear_query` = „Leeren“ (both applied from the previous review round), while Delete = „löschen“. The glossary mandates Clear stay distinct from deleting one entry — yet on the History screen the danger row „Verlauf löschen“ (wipes everything) and the per-entry action `history_action_delete` = „Löschen“ (one row) now share a verb, on two destructive actions with very different blast radius. „Verlauf leeren“ is standard German (cf. Firefox „Chronik leeren“) and the word is already in the file. **Leave `history_clear_confirm_message` („…werden … gelöscht.“) alone** — that describes the *effect*, and EN says "deleted" there too. |
| floating_menu_capture_screen | ⚠️ | „Vollbild-\naufnahme“ | „Bildschirm\naufnehmen“ | Three problems, none of them width (it fits — see Truncation below). (1) **„Vollbild“ already means *fullscreen*** in this very delta: `error_single_app_not_fullscreen` = „…sobald die App wieder **im Vollbild** läuft“. Reusing it for "the whole screen" muddles two different concepts. (2) The file's established capture noun is **„Bildschirmaufnahme“** (`error_capture_blocked_secure` „blockiert die **Bildschirmaufnahme**“; committed `region_overlay_drag_instruction` „**Bildschirmaufnahmen**“). (3) **It breaks the button's own two-state parallel:** this is the *same* button as committed `floating_menu_btn_capture_region` = „Aufnahme-\nbereich“, and the head noun flips as the label swaps in place — region state ends in „…bereich“ (a region), screen state ends in „…aufnahme“ (a capture). EN keeps them parallel (Capture Region / Capture Screen). The suggested form uses the established noun + verb, matches the verb-phrase register of `floating_menu_edit_region` („Bereich bearbeiten“), and needs no mid-compound hyphen break. **Companion fix (outside the delta, but this is the drift):** `floating_menu_btn_capture_region` → „Bereich\naufnehmen“, making the pair parallel again. |
| settings_debug_log_trace | ⚠️ | „Trace des Übersetzungs**protokolls** aufzeichnen“ | „Trace des Übersetzungs**verlaufs** aufzeichnen“ | **Terminology collision.** In the *same* Settings → Debug list, „Protokoll“ already means *debug log*: `settings_debug_show_detection_log` = „Erkennungs**protokoll** anzeigen“, `settings_debug_log_pinhole` / `_log_grouping` = „… **protokollieren**“, `settings_debug_export_logs_title` = „**Protokolle** exportieren“. So „Übersetzungsprotokoll“ reads as *the debug log of translations*. But EN "translation-log" is the **History** subsystem, which this file calls **„Verlauf“** (`history_screen_title`, `settings_cell_history`). Debug-only string, so low blast radius — but it is a straight collision between two established terms. |
| misc_informal | ⚠️ | „Informell“ | „Salopp“ | **A gloss of English, not a German usage label.** German lexicography's register ladder is *umgangssprachlich → salopp → derb → vulgär*; there is no „informell“ label, and in ordinary German „informell“ means *unofficial* („informelle Gespräche“). „Salopp“ is the real Duden label for exactly this slot and is instantly read as a register tag. Cluster stays fully distinct: **Umgangssprachlich / Salopp / Familiär / Slang**. Trade-off to note: it drops the surface pairing with `misc_formal` = „Formell“ — cosmetic only, since the two never contrast on one word and are not a glossary cluster. |
| misc_dated | ⚠️ | „Veraltend“ | „Altmodisch“ | **The one-letter-neighbour question, answered: semantically right, but a genuine readability bug as rendered.** Duden does use *veraltet* (obsolete) / *veraltend* (dated) as distinct labels, so this is not a mistranslation. But the tags render as a single 12.5sp **italic** `" · "`-joined run (not as separate chips — see below), so a reader is distinguishing „Veraltend“ from `misc_obsolete` = „Veraltet“ by one terminal *d* in small italic type — and the audience is JA/ZH/KO *learners*, not lexicographers. „Altmodisch“ is exactly what the EN source comment glosses it as (`<!-- Misc tag: dated — old-fashioned. -->`), is unmistakable at a glance, and keeps the obsolescence cluster four-way distinct: **Archaisch / Veraltet / Altmodisch / Historisch**. (Keeping „Veraltend“ is defensible if strict Duden fidelity is preferred — flagging it as the judgment call it is.) |
| settings_yomitan_empty_summary | ⚠️ | „**Importiere** Wörterbücher, um die Wortsuche zu verbessern“ | „**Wörterbücher importieren**, um die Wortsuche zu verbessern“ | Imperative in a *cell subtitle*. Every sibling subtitle in the delta is an infinitive/declarative — `history_toggle_subtitle` „Aufgenommene Sätze … **speichern**“, `anki_game_audio_row_subtitle` „Die letzten Minuten … **behalten**, damit…“. The imperative also mis-signals that the row *is* the import action; it only opens the Yomitan hub. |
| update_unknown_sources_message | ⚠️ | „…erlaube PlayTranslate **auf dem Einstellungsbildschirm, der sich öffnet,** App-Updates zu installieren.“ | „…erlaube PlayTranslate **auf dem folgenden Einstellungsbildschirm die Installation von App-Updates**.“ | Garden-path sentence: the relative clause is wedged between the dative object and the infinitive complement, stretching „erlaube PlayTranslate … zu installieren“ across eight intervening words. Grammatical, but it forces a re-read and reads like MT. Rest of the string is fine. |
| llm_prompt_warning_title | 💬 | „**Prüfe** diesen Prompt“ | „**Diesen Prompt prüfen**“ | Imperative as a dialog *title*; German titles are conventionally noun/infinitive phrases. Its sibling `llm_prompt_invalid_title` is a statement („Dieser Prompt kann nicht gespeichert werden“), so the two dialogs currently disagree on form. |
| settings_ocr_use_manga_subtitle | 💬 | „…und kann nicht allein **arbeiten**“ | „…und **funktioniert nicht allein**“ | „arbeiten“ (to work, as a *person* works) is odd for software; German says *funktionieren*. |
| llm_prompt_advisory_foreign_token | 💬 | „…wird von diesem Prompt **nicht gefüllt und als wörtlicher Text gesendet**.“ | „…wird von diesem Prompt nicht gefüllt und **daher** als wörtlicher Text gesendet.“ | One „wird“ governs two participles with „nicht“ before the first, so the negation can momentarily be read as scoping over both ("is not filled *and not sent*"). „daher“ fixes the scope and supplies the causal link EN implies. |
| stream_kind_prompt_title | 💬 | „Welche **Freigabe**option hast du gewählt?“ | „Welche Option hast du gewählt?“ | Two nouns for one concept inside one dialog: the title says „Freigabe“, while its own body says „…was **geteilt** wurde“ and both buttons say „…**teilen**“. The buttons are deliberately AOSP wording, so the title should bend to them. Dropping the modifier is cleanest — the body already establishes the subject. |
| update_error_install_launch | 💬 | „Das Installationsprogramm konnte nicht geöffnet werden.“ | „Der **System-Installer** konnte nicht geöffnet werden.“ | Drops EN's "**system** installer", losing the cue that the failing component is Android's, not PlayTranslate's. |
| misc_humble | 💬 | „Bescheiden“ | „Demütig“ | „bescheiden“ colloquially means *lousy / poor* in German („die Aussicht war bescheiden“), so on an italic tag row it can momentarily read as a quality judgement **on the word**. „Demütig“ is the standard German gloss for 謙譲語 (Demutsform / Bescheidenheitssprache), has no second reading, and is shorter. Honorific cluster stays distinct: Ehrerbietig / Demütig / Höflich. (`misc_honorific` = „Ehrerbietig“ is precise but rare/literary; „Respektvoll“ would be more current. Optional.) |
| misc_nonstandard | 💬 | „Nicht standardsprachlich“ (24 ch) | keep — or „Nichtstandard“ if a shorter tag is wanted | Called out for measurement: it is the longest tag in the set (2.4× EN) **but it cannot truncate** (see below), and „nicht standardsprachlich“ is the literal Duden label — i.e. correct, not a defect. Its only cost is pushing the tag row to wrap one line earlier. Shorten only if the tag row's line count is being optimised. |

## Truncation — measured against the render code, not estimated

The German failure mode does **not** materialise in this delta. I read the render sites rather than guessing, and every surface the brief named is either wrapping or self-sizing:

- **The 38 `misc_*` tags are not chips.** `renderMiscText` (`ui/MiscLabels.kt`) joins them with `" · "` into **one string**, rendered by `buildMiscRow` (`ui/WordDefinitionsView.kt:269`) as a plain italic 12.5sp `TextView`, `MATCH_PARENT` × `WRAP_CONTENT`, with **no `maxLines`, no `ellipsize`, no `singleLine`** — identically in `WordAnkiReviewSheet` (:1046) and `WordDetailBottomSheet`; `MagnifierLens` passes `showMisc = false` and never shows them at all. **They wrap; they cannot be cut off.** So „Nicht standardsprachlich“ (24 ch) and „Umgangssprachlich“ (17 ch) are safe, and the real cost of a long label is one extra wrapped line, not a truncated word. *(Note for the docs: `l10n-language-parameters.md` describes these as "short chips … width-constrained" — that is not what the code does, and it drove at least one wrong prior on this review. Worth correcting.)*
- **`probe_initializing`** — safe by construction. `StreamKindProbe.onMeasure` sets the view width to `labelPaint.measureText(labelText) + 2 * labelPadding`; the chip **self-sizes to the localized string** (the code comment says so explicitly: *"Width is MEASURED from the localized string — every locale fits exactly"*). „Initialisierung…“ needs no shortening.
- **`floating_menu_capture_screen`** — **fits at full size.** The primary button gives the label a 66dp text column (78dp button − 2 × 6dp padding), `maxLines = 2`, and `fitLabel` shrinks 11sp → 8.5sp in 0.5sp steps only if the longest *unbreakable run* overflows. The explicit `\n` supplies the two lines; the longest run, „aufnahme“, is ≈50dp at 11sp — comfortably inside 66dp, so `fitLabel` never has to shrink. The suggested „Bildschirm\naufnehmen“ also fits (longest run „aufnehmen“ ≈56dp), and even if a glyph-width estimate is off, the failure mode is a graceful 0.5sp shrink, never a clip. This also **closes the open 💬 on `floating_menu_btn_capture_region`** from the previous round ("verify at 9sp/54dp"): the column is 66dp with auto-shrink, so „Aufnahme-\nbereich“ was never at risk.
- **`service_llm_badge`** = „LLM“ — unchanged from EN, `wrap_content` in all three layouts. Zero risk.
- **`tr_service_status_*`, `service_account_required*`, `tr_service_key_tail_fmt`, `llm_backend_preset_custom`** — all `wrap_content`/`match_parent` with no `ellipsize` (`item_online_service.xml`, `item_add_online_service.xml`). „Kein Internet, Nutzung kann nicht geprüft werden“ (47 ch) and „Benutzerdefiniert“ (17 ch) wrap rather than clip.

## Clean areas (checked, no findings)

- **Register:** `du` throughout, zero formal address. The four capitalised „Sie“ hits in the file are all 3rd-person feminine pronouns (die Übersetzung / die Berechtigung / die Benachrichtigung); three are committed+reviewed precedent, and the delta's `error_single_app_not_fullscreen` („**Sie** wird fortgesetzt“ = die Übersetzung) follows it — the finite verb `wird` disambiguates it from formal *Sie werden* immediately. Not a defect.
- **The `misc_*` clusters are all four-way distinct** and use real Duden labels, not English glosses: offensiveness **Abwertend / Beleidigend / Vulgär / Diskriminierend**; obsolescence **Archaisch / Veraltet / Veraltend / Historisch**; informality **Umgangssprachlich / Informell / Familiär / Slang**; honorifics **Ehrerbietig / Bescheiden / Höflich**. Genuine Duden labels used throughout: *abwertend, veraltet, veraltend, umgangssprachlich, familiär, scherzhaft, ironisch, dichterisch, lautmalerisch, selten, verhüllend*→euphemistisch. Two calls I checked and **endorsed**: `misc_figurative` = „**Bildlich**“ (not Duden's „übertragen“ — correctly avoids colliding with *transmit*), and `misc_sarcastic` = „**Ironisch**“ (a real Duden label; „sarkastisch“ is not). `misc_kana_only`/`misc_kanji_only` keep the kana/kanji loanwords ✓; `misc_yojijukugo` = „Vier-Zeichen-Idiom“ describes rather than romanizes ✓.
- **`Stapel` (deck) collision avoided.** `llm_prompt_row_batch_*` / `llm_prompt_kw_count_desc` use **„Batch“**, not „Stapel“ — correct, since „Stapel“ = Anki deck in 8 committed strings. Deliberate and right.
- **`ocr_source_label` mirrors its sibling exactly.** „**Erkannt von** %1$s“ against committed `translation_source_label` „**Übersetzt von** %1$s“ — same participle + *von* + engine structure, which is precisely what the glossary asks for.
- **Remove vs Delete kept apart** as EN does: services are *entfernt* (`tr_service_remove_confirm` „Entfernen“, `tr_service_delete_cd` „Dienst entfernen“, `tr_service_remove_title_fmt`), entries and models are *gelöscht* (`history_action_delete`, `settings_ocr_disable_delete`). `tr_service_remove_message` correctly uses both verbs in one sentence.
- **The MangaOCR disable dialog matches its committed siblings verbatim.** `settings_ocr_disable_manga_title` = „MangaOCR **deaktivieren**?“ follows the committed „X deaktivieren?“ pattern (bergamot/qwen/gemma/hymt); `settings_ocr_disable_delete` = „Modell löschen“ is byte-identical to `bergamot_disable_delete`/`qwen_mnn_disable_delete`/`hymt_disable_delete`; `settings_ocr_disable_manga_msg` reuses the committed phrasing „…behalten oder löschen, um Speicherplatz freizugeben“.
- **Other terminology, all 1:1 with the committed file:** „Anbieter“ (Provider — matches `tr_service_order_footer`), „Übersetzungsdienst(e)“ (matches `settings_cell_translation_services`), „Bereich“ (region — matches `nav_regions`/`region_picker_editing_title`/`cd_delete_region`), „Overlays“ (matches `settings_overlay_mode_title`), „Verlauf“ (History — one noun across `settings_cell_history`/`history_screen_title`/`history_toggle_title`), „aufnehmen/Aufnahme“ as the single capture verb (per the glossary's "Captured" rule), „Sprachausgabe“ (TTS), „getaktetes Netzwerk“ (metered), „zuschneiden“ + „Auswahl“ (Trim / selection, consistent across all `game_audio_trim_*`), „Prompt“ as the single LLM-template noun.
- **Grammar around placeholders — read with real values dropped in:** „OpenAI entfernen?“, „PlayTranslate wird aktualisiert“, „Downloadgröße: 128 MB“, „(230 MB erforderlich)“, „Heute: 12.345 Tokens“, „Erkannt von PaddleOCR“, „Das heruntergeladene Modell (68 MB) behalten oder löschen…“, „3 Wörterbücher importiert“, „Auto-Furigana“ / „Auto-Pinyin“. All agree in case, gender and number. `tr_service_remove_title_fmt` and `floating_menu_panel_open_app` correctly **move the whole `<xliff:g>` span to the front** so the German verb lands final — no article or case suffix is ever attached to a runtime value.
- **Hotkey family is parallel:** „Gedrückt halten, um X anzuzeigen“ (hold) vs „Tippen, um X zu starten/stoppen“ (tap), with the hyphenated „Auto-%1$s“ compound sidestepping any article-agreement trap when `%1$s` = Furigana / Pinyin / Bopomofo.
- **Plurals:** `settings_yomitan_count_summary` is correct for German — one „1 **Wörterbuch** importiert“, other „3 **Wörterbücher** importiert“, and *other* also carries 0 („0 Wörterbücher importiert“ ✓).
- **AOSP share-scope buttons** (`stream_kind_share_one_app` / `_entire_screen`) left alone per the brief. „Gesamten Bildschirm teilen“ matches AOSP German; if anyone has a German device handy, `_share_one_app` is worth one look — AOSP's own string may read „Eine einzelne App teilen“ — but that is an AOSP-match question, not an EN-match one, and is explicitly out of scope here.
- **Overall (delta):** **fix-then-ship.** One ❌ (the Clear/Delete collapse — a free fix, the right word is already in the file), then the two terminology collisions (`floating_menu_capture_screen`, `settings_debug_log_trace`) and the two `misc_*` label calls. The remaining 174-key body is natural, du-consistent, and reuses the committed file's terminology with real discipline — the `Batch`/`Stapel` and `Bildlich`/`Übertragen` avoidances in particular show the translator was checking for collisions.

---

# Delta review round 2 — 2026-07-14

Fresh, independent re-derivation of the 174 delta keys in the **corrected** file. Primary target: regressions introduced by round 1's 15 edits.

**Mechanical layer re-verified _after_ the edits — no 🛑.** This matters: round 1's mechanical pass ran on the pre-fix file. Re-run on the current file, all 174 keys are present with no extras; every `%1$s`/`%2$s`/`%d` matches EN; all `<xliff:g>` spans are byte-identical to EN including `id`/`example`; every bare `{keyword}` token (`{N}`, `{source}`, `{source_code}`, `{target}`, `{target_code}`, `{text}`, `{strings}`, `{context}`) is byte-identical Latin; `\n` preserved in `floating_menu_capture_screen`; no unescaped `'`/`"`; `<plurals>` = one/other; XML parses. **The edits broke nothing mechanical.**

## Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| game_audio_trim_use_tts | ❌ | „Stattdessen Sprachausgabe verwenden“ (273.3dp button) | „Sprachausgabe“ (121.9dp) — or „Stattdessen TTS“ (128.2dp) | **The primary confirm is unreachable in German. Measured, not estimated — see the section below.** This label alone eats 273dp of a 336dp budget and shoves `game_audio_trim_save` („Auswahl verwenden“) off the right edge: on a **360dp** phone Save's left edge lands at **x=348dp** — **7% of the button on screen, label entirely gone, a 12dp touch target**; on **411dp**, x=381dp — **17% visible, label gone**. The user can only reach the two *decline* buttons. Both suggestions restore Save to **100% visible with its label intact at 411dp**. „Sprachausgabe“ is preferred: it is the file's established TTS noun (`settings_cell_tts`, `audio_source_tts_name`, ×10) and is the *same width* as the acronym variants. Dropping "instead" follows the sanctioned **pt-BR** precedent (`"Usar TTS"`). ⚠️ **This is a mitigation, not the fix — the layout is the real bug (see below); 360dp cannot be rescued by any string.** |
| misc_informal | ❌ | „Salopp“ | „Informell“ (revert) | **Regression introduced by round 1.** Three problems. (1) **Asymmetric fix.** Round 1's rationale was Duden fidelity — „informell“ is not a Duden *Stilschicht*. True, but neither is **„Formell“** (Duden's would be *gehoben*/*bildungssprachlich*), and `misc_formal` was left untouched. The set is therefore now half-Duden, half-gloss, and the reader loses the **Formal ⇄ Informal** pole pair that the EN source comments make explicit (`<!-- Misc tag: informal register. -->` / `<!-- …formal register. -->`). (2) **It inverts a tier.** On the Duden ladder *umgangssprachlich → salopp → derb → vulgär*, „salopp“ sits **below** „umgangssprachlich“ = `misc_colloquial`. So the tag now asserts "coarser than colloquial" — but the source comments put them at the same level (`misc_colloquial` = "casual/everyday speech"; there is no ordering in `MiscVocabulary`). A learner reads a severity that isn't in the data. (3) „Informell“ is current, standard German in exactly this domain — DaF/language pedagogy says „formelle und informelle Sprache“ routinely. Reverting keeps the cluster four-way distinct: **Umgangssprachlich / Informell / Familiär / Slang**. |
| error_capture_blocked_secure | ⚠️ | „…– **die aufgenommene App** blockiert die Bildschirmaufnahme.“ | „…– **die App im Vordergrund** blockiert die Bildschirmaufnahme.“ | **Regression introduced by round 1.** The change was directionally right — EN's "this app" is genuinely ambiguous (could be read as PlayTranslate) — but the replacement is self-contradictory: *"the **captured** app blocks screen **capture**"*. It also over-claims: the emitter (`ReconcilerLiveMode.kt:360`, sustained-black-frame path) fires in **whole-screen** capture too, where nothing was captured "as an app", so the phrasing quietly re-opens the ambiguity it was meant to close. „Die App im Vordergrund“ is precise, has no paradox, and is true in both capture modes. |
| settings_debug_log_trace | 💬 | „Trace des **Übersetzungsverlaufs** aufzeichnen“ | „Trace des **Textverlaufs** aufzeichnen“ | The fix worked — grep confirms „Protokoll“ is now *exclusively* the debug-log sense (Erkennungsprotokoll, protokollieren, Protokolle exportieren, App-Protokolle) and „Verlauf“ *exclusively* the History sense. **No collision remains.** Residual: it coins a **third** German noun for one subsystem, where the file already has „Verlauf“ (`history_screen_title`, `settings_cell_history`) and „Textverlauf“ (`history_toggle_title`, `history_empty_off`). The code comment calls it the "translation-log validation feed", i.e. the History recorder — so „Textverlauf“ names it exactly and ties the debug row to the user-facing noun. Debug-only, hence 💬; EN drifts the same way ("translation-log" vs "text history"). |
| stream_kind_prompt_title | 💬 | „Welche Option hast du gewählt?“ | „Was hast du geteilt?“ | The fix correctly killed the Freigabe/teilen noun-verb clash, but the title now names no subject at all — it leans entirely on the body. „Was hast du geteilt?“ restores the anchor *and* reuses the body's own verb („…was **geteilt** wurde“) and the buttons' („…**teilen**“), so all three agree on one word. |
| probe_initializing | 💬 | „Initialisierung…“ | „Wird initialisiert…“ | The only progress string in the whole file that breaks the house `wird …`-passive idiom — 13 of the other 14 ellipsis strings use it („Wird übersetzt…“, „Text wird erkannt…“, „Stapel werden geladen…“, „Modelle werden geladen…“, „Wird nachgeschlagen…“ …). Round 1 established the chip **self-sizes to the localized string** (`StreamKindProbe.onMeasure`), so the extra width is free. |
| settings_llm_context_subtitle | 💬 | „Nur online. **Gibt** Online-LLM-Übersetzern die letzten Zeilen…“ | „Nur online. **Online-LLM-Übersetzer bekommen** die letzten Zeilen…“ | Round-1 miss, made conspicuous by round 1's own fix. Having normalised `settings_yomitan_empty_summary` to the infinitive, this is now the **only finite-verb subtitle in the file** (0 of the other 27 `*_subtitle`/`*_summary` strings use the elided-subject 3rd person). It is also one letter from the du-imperative „**Gib**“, so it can momentarily read as a typo. An explicit subject removes both problems and keeps the declarative sense. |

## The `game_audio_trim` button row — measured

`activity_game_audio_trim.xml`, bottom bar: `LinearLayout`, `padding=12dp`, `gravity=center_vertical`, no scroll, no ellipsize, children `[btnTrimUseTts] 4dp [btnTrimNoAudio] [Space weight=1] [btnTrimSave]`. Buttons are `wrap_content` `MaterialButton`s: the two secondaries are `Widget.Material3.Button.TextButton` (**12+12dp** padding), the confirm inherits `materialButtonStyle` → `Widget.Material3.Button` (**24+24dp** padding). All three carry AppCompat's **88dp `minWidth`** floor and `android:textAllCaps="false"`. Label typescale = `?attr/textAppearanceLabelLarge` → **14sp**, letterSpacing 0.00714em; the theme's `android:fontFamily="sans-serif"` overrides the M3 medium weight, so it renders **Roboto Regular**. Widths below are HarfBuzz-shaped against real Roboto at 14sp, fontScale 1.0. (Roboto Medium moves every number by <1% and changes no conclusion.)

| element | German | width |
|---|---|---|
| row padding | 12 + 12 | 24.0dp |
| `game_audio_trim_use_tts` | „Stattdessen Sprachausgabe verwenden“ | text 249.3 → **button 273.3dp** |
| margin | | 4.0dp |
| `game_audio_trim_no_audio` | „Kein Audio“ | text 67.8 → **button 91.8dp** |
| `game_audio_trim_save` | „Auswahl verwenden“ | text 126.2 → **button 174.2dp** |
| **TOTAL ROW** | | **567.3dp** |

**567dp of content — 1.58× a 360dp screen, 1.38× a 411dp screen.**

**Is `game_audio_trim_save` reachable? No — on both.** The `Space(weight=1)` collapses to 0 under overflow (`LinearLayout` clamps a negative weight share), and children are laid out left→right from `paddingLeft`, so the confirm is what falls off the end:

| screen | laid-out row | Save left edge | Save visible | label |
|---|---|---|---|---|
| **360dp** | 534.2dp (`no_audio` is crushed to 58.7dp by LinearLayout's sequential `AT_MOST`) | **x = 348.0** | **12.0 / 174.2dp — 7%** | **entirely off-screen** |
| **411dp** | 567.3dp | **x = 381.1** | **29.9 / 174.2dp — 17%** | **entirely off-screen** |

At 360dp the confirm is a **12dp-wide unlabelled sliver** — a quarter of the 48dp minimum touch target. The German user cannot save a trim; only the two decline paths (`use_tts`, `no_audio`) are reachable.

**Root cause is the layout, not German.** Two facts fix the blame:
1. **English overflows too** — the EN row is **375.7dp**, over a 360dp screen by 15.7dp (there it only clips Save's trailing corner, so it reads as fine).
2. **The structural floor is 382.0dp** — 24dp padding + three buttons at their 88dp `minWidth` + the confirm's 48dp padding. **At 360dp, no string in any language can make this row fit.** A locale fix cannot close this; the row needs weights + `ellipsize`, a wrapping/flex container, or the confirm moved to its own line.

**What the string lever buys** (`save` unchanged at „Auswahl verwenden“):

| `game_audio_trim_use_tts` | row | 360dp | 411dp |
|---|---|---|---|
| „Stattdessen Sprachausgabe verwenden“ (current) | 567.3 | 7% visible, label cut | 17% visible, label cut |
| „Sprachausgabe verwenden“ | 488.1 | 33%, cut | 63%, cut |
| **„Stattdessen TTS“** | 422.2 | 71%, cut | **100%, label OK** |
| **„Sprachausgabe“** ← recommended | 415.9 | 75%, cut | **100%, label OK** |

If the layout genuinely cannot change, a second lever exists: shortening the confirm to „Übernehmen“ (128.4dp) *together with* „Sprachausgabe“ brings the row to **370.1dp** — it **fits outright at 411dp**, and at 360dp Save is 100% visible with its label fully readable (the 10dp overflow eats only the row's right padding). That breaks the „Auswahl abspielen“ / „Auswahl verwenden“ pair, so it is a fallback, not a recommendation.

## Regression checks the brief asked for — all clear

- **Does „leeren“ now collide with anything? No.** Four hits in the whole file, all Clear-semantics: `btn_clear` „Leeren“, `dictionary_clear_query` „Leeren“, and the two fixed History keys. „löschen“ remains exclusively Delete (`history_action_delete`, `settings_ocr_disable_delete`, `pack_upgrade_button_delete`). The Clear/Delete split round 1 restored is intact, and `history_clear_confirm_message` correctly still says „gelöscht“ (it describes the *effect*; EN says "deleted" too). ✔
- **Does „Verlauf“ now mean two things? No.** It is never used in German's other sense (course/progression). And the fix fully drained the collision it was aimed at: „Protokoll“ is now *only* the debug log. ✔ (One residual — the third compound „Übersetzungsverlauf“ — is filed 💬 above.)
- **Are the 38 `misc_*` labels still mutually distinct? Yes — verified programmatically.** Zero duplicates, so `renderMisc`'s `.distinct()` collapses nothing. Zero labels contain the `" · "` join separator. The one-letter-neighbour hazard round 1 targeted is **gone**: an edit-distance sweep over all 703 pairs finds no pair within distance 2 except „Nur Kana“/„Nur Kanji“ (inherent to the source, transparent to a JA learner). The four clusters remain readable: offensiveness **Abwertend / Beleidigend / Vulgär / Diskriminierend**; obsolescence **Archaisch / Veraltet / Altmodisch / Historisch**; honorifics **Ehrerbietig / Demütig / Höflich**; informality — see the ❌ above.

## Round-1 fixes re-derived and endorsed

- **`history_clear_menu` / `history_clear_confirm_title` → „leeren“** ✔ correct and collision-free (above).
- **`misc_dated` → „Altmodisch“** ✔ The right call. It kills the „Veraltend“/„Veraltet“ one-letter neighbour at 12.5sp italic *and* preserves the tier (altmodisch is milder than veraltet, which is what "dated" vs "obsolete" means). Contrast this with `misc_informal`, where the same instinct inverted a tier.
- **`misc_humble` → „Demütig“** ✔ Distinct, no second reading (unlike „bescheiden“ = *lousy*), and apt for 謙譲語 (self-lowering).
- **`settings_yomitan_empty_summary` → infinitive** ✔ Matches the house style — 0 of 28 `*_subtitle`/`*_summary` strings in the file are imperatives except `settings_anki_get_app_summary` (already logged in the first review).
- **`update_unknown_sources_message`** ✔ Garden path gone. „erlaube PlayTranslate **auf dem folgenden Einstellungsbildschirm** die Installation von App-Updates“ is a standard Mittelfeld order (dative → locative adverbial → accusative).
- **`update_error_install_launch` → „Das System-Installationsprogramm…“** ✔ Restores EN's "**system** installer" cue, and „Installationsprogramm“ is better German than round 1's suggested anglicism „Installer“.
- **`llm_prompt_advisory_foreign_token` → „…und **daher** als wörtlicher Text gesendet“** ✔ Negation scope fixed.
- **`llm_prompt_warning_title` → „Diesen Prompt prüfen“** ✔ German titles take the infinitive, not the imperative. It still differs in *form* from `llm_prompt_invalid_title` (a statement) — but so does EN, and the difference encodes the real distinction: a soft warning you can override vs a hard block.
- **`settings_ocr_use_manga_subtitle` → „funktioniert nicht allein“** ✔.
- **`floating_menu_capture_screen` → „Bildschirm\naufnehmen“** ✔ correct in itself — **but it is half of a two-string fix.** `FloatingIconMenu.kt:623-624` confirms this is the *same button* swapping its label, and the other state, `floating_menu_btn_capture_region` = „Aufnahme-\nbereich“, is committed and out of this delta's scope. Shipping the fix alone leaves the button's two states non-parallel (verb phrase ⇄ noun) where EN keeps them parallel. **Not re-filed** — round 1 already logged the companion („Bereich\naufnehmen“). Flagged only as a shipping dependency: apply both, or neither.

## Also clean (re-checked independently)

- **Register:** `du` throughout. A scan of the delta for `Sie`/`Ihr`/`Ihnen` returns exactly one hit — `error_single_app_not_fullscreen`'s „**Sie** wird fortgesetzt“ (= die Übersetzung), where the finite `wird` rules out formal address. No new formal address introduced by the edits.
- **On/Off:** `settings_cell_history_summary_on`/`_off` = „An ·“/„Aus ·“ matches the committed `capture_lifecycle_state_on`/`_off` („An“/„Aus“) exactly. ✔
- **„Screenshot“** in `ocr_picker_message` matches the file (8 uses; „Bildschirmfoto“ 0). ✔
- **Plurals:** `settings_yomitan_count_summary` correct at every band — 0 → „0 Wörterbücher importiert“ (*other*), 1 → „1 Wörterbuch importiert“ (*one*), 3 → „3 Wörterbücher importiert“. ✔
- **`{strings}` vs `{text}`:** rendering both as „Text(e)“ is safe — they live in *different* prompts (batch vs translation) and the number tracks. Endorsed over „Phrase“, which in German carries the pejorative *empty phrase* reading.
- **Placeholders with real values:** „OpenAI entfernen?“, „Downloadgröße: 128 MB“, „(230 MB erforderlich)“, „Heute: 12.345 Tokens“, „Erkannt von PaddleOCR“, „Auto-Furigana“ all agree. `Locale.US` second-formatting in `GameAudioTrimActivity` means `game_audio_trim_duration` renders „2.4 s ausgewählt“ with a decimal *point* — noted once, per the brief; it is a code defect, not a locale one.
- **`btnTrimPlay`** („Auswahl abspielen“, 166dp) sits alone in a 264dp column — no risk. The bottom row is the only overflowing surface in the delta.

## Verdict (round 2)

**FIX FIRST.** 🛑 0 · ❌ 2 · ⚠️ 1 · 💬 4.

Round 1's edits were mechanically safe and mostly right — 10 of 15 re-derive cleanly, and the two collision checks the brief called out (`leeren`, `Verlauf`) are genuinely clean. Two of the fixes **regressed**: `misc_informal` → „Salopp“ (breaks the Formal/Informal pair and inverts a register tier) and `error_capture_blocked_secure` → „die aufgenommene App“ (self-contradictory, and false in whole-screen capture). Both are free reverts/rewordings.

The blocker is not a round-1 regression at all: **`game_audio_trim_use_tts` makes the trim screen's primary confirm unreachable in German on every mainstream phone width.** Shortening it is a mitigation that recovers 411dp; **360dp needs a layout change, which no locale can supply — English already overflows there too.**

## Delta review — 2026-07-25 sync (95 keys)

Scope: the 89 keys `scripts/l10n_diff.py` reported MISSING and the 6 it reported
MODIFIED against the `l10n-sync` baseline (`54809b6c`) — the camera tool, the file-import
tool, the slow-OCR rescue prompt, the PaddleOCR accurate/fast tier split, the manual
update check, the History capture + live-session cards, the accessibility-stuck alert,
the audio-recording row, and the capture standby state. Two orphans
(`settings_footer_version`, `settings_ocr_footer`) were deleted.

Two of the six MODIFIED keys — `capture_lifecycle_on_subtitle` and
`capture_lifecycle_off_subtitle` — were already carrying the current English meaning in
every locale; they flag only because the baseline tag has not advanced since 2026-07-14.
No change was needed. The other four (`game_screen_controls_title`,
`settings_ocr_use_manga_subtitle`, `yomitan_page_description`, `yomitan_importing_message`)
were genuinely stale and were re-translated.

Mechanical layer verified programmatically over the delta: every translatable EN key
present and no extras; placeholder multisets identical to EN; all `<xliff:g>` spans
byte-identical to EN; `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match; no unescaped
quotes; `<plurals>` categories exactly one/other. `./gradlew :app:processDebugResources`
is green. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | was | now | why |
|---|---|---|---|---|
| `settings_ocr_note_mlkit` | ⚠️ | "Schnell, auch bei viel Text auf dem Bildschirm" | "Flott, auch bei viel Text auf dem Bildschirm" | The English comment forbids reusing the literal Fast tier label; the first pass reused «Schnell», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

Lowercase **du** throughout and never Sie — Wechsle, Du kannst, Dein Gerät, Schalte, Aktiviere, Füge, probiere, Tippe, wähle, geh näher, Überprüfe, Nutze, Importiere, Richte, Erlaube. (The `Sie` occurrences elsewhere in this file are the feminine pronoun — «Die Übersetzung … Sie wird fortgesetzt» — not formal address.) „ “ quotes in `a11y_stuck_message_xiaomi` and `settings_ocr_footer_guidance`. **Engine** was chosen for *engine* over Modul, because Modul/Modell is a genuine near-collision in German and the file already uses Modell for the downloadable OCR model (`settings_ocr_delete_msg`); **Tool** stays the tool word, matching the committed `settings_header_tools` = Tools. Both meet in `settings_ocr_delete_camera_import_note` and stay separable. **Standbild** for the camera freeze-frame keeps Screenshot free (`anki_group_screenshot`). Compounds kept readable rather than stacked (Dateiimport-Tool, Aufnahmebilder, Einstellungszahnrad). `image_import_no_text` reuses the committed `status_no_text` frame («Kein %1$s-Text … erkannt»). Autostart and „Keine Einschränkungen“ are the wordings the system settings show. Plurals one/other.

**Render constraints read, not guessed.** `capture_show_on_screen` renders through
`Text.PT.GroupHeader` (`textAllCaps`, `letterSpacing` 0.12) at 9sp in
`section_target.xml`, but the view is `wrap_content` in a row whose sibling label carries
`layout_weight="1"` — the label squeezes, this button never clips, so no accuracy was
traded for brevity. `capture_sliver_expand_hint` is `isSingleLine` but sits `WRAP` and
centred in a screen-wide sheet strip. `camera_region_remove` measures itself
`UNSPECIFIED` before placement (`CameraRegionUi`), so the pill grows to its text.
`image_import_no_text` / `camera_snapshot_no_text` locate the tappable language span by
the invisible FSI/PDI sentinels `markNoTextLanguage` injects, not by substring search, so
word order and a tight prefix are both safe.

### Verdict

**PASS.** One ⚠️ found and fixed, no ❌.

---

## Delta review 2026-08-04 (8 keys: one-tap card toasts, first-field guard, hide-translations toggle, waveform zoom hint)

Scope: `card_words_in_sentence`, `anki_added_sentence_success`, `anki_added_word_success`,
`game_audio_zoom_hint`, `anki_first_field_unmapped`, `anki_first_field_empty`,
`history_hide_translations_toggle_title`, `history_hide_translations_toggle_subtitle`.

Mechanical layer verified programmatically over the eight keys: all present, none extra;
every `<xliff:g>` span byte-identical to EN in inner content, `id` and `example`
(`field_name`/`Key`, `brand_anki`/`Anki`); `%1$s` parity in both first-field strings; no
`<b>`, `\n`, `\{ \}` or entity counts to preserve in this set; no raw `'` or `"` outside
the xliff attributes; German „ “ used in the correct low-open/high-close order in
`anki_first_field_unmapped` and `anki_first_field_empty`; `name="…"` untouched; **Anki**
left untranslated everywhere it appears. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `anki_first_field_unmapped` | 💬 | „%1$s“ … damit Anki die Notiz **erkennen kann**. | „%1$s“ … damit Anki die Notiz **erkennt**. | Length safety only. The source comment pins this string to a two-line Android 12+ toast clamp, which *clips* rather than wraps, and `%1$s` is a free-form user-defined field name that can be long. DE is 63 chars to EN's 52 before the name is substituted. Dropping the modal recovers 6 chars with no meaning lost — «damit Anki die Notiz erkennt» is fully idiomatic. Grammar itself is correct: `zuordnen` is the file's committed verb for *map* (`anki_content_source_pick_title` = „%1$s“ zuordnen), the separable prefix lands correctly in final position, and the dative/accusative pair reads right — the overtly marked accusative «einen Wert» forces „%1$s“ into the dative, so the bare unarticled name cannot be misparsed for more than a word. Inserting «dem Feld» would remove even that momentary garden path and cost only +3 chars net alongside this trim, but against a clipping toast the trim alone is the safer half. |
| `anki_first_field_empty` | 💬 | „%1$s“ ist auf dieser Karte leer. Anki erkennt Notizen anhand des ersten Feldes, **deshalb braucht es** auf jeder Karte einen Wert. | **Das Feld** „%1$s“ ist auf dieser Karte leer. Anki erkennt Notizen anhand des ersten Feldes, **deshalb muss dort** auf jeder Karte **ein Wert stehen**. | Two small polish items in a string the comment explicitly frees from any length limit ("Shown in a full alert, so length is fine"), so both are free. (a) The bare quoted name as grammatical subject *does* work for every possible user-defined field name — there is no article to inflect and the copula is invariant `ist` regardless of what the name looks like — but German prefers a head noun before a quoted identifier; «Das Feld „Key“ ist … leer» anchors the token as a referent rather than a mention. (b) `es` in the final clause is meant to be *das erste Feld* (neuter, correct antecedent), but it competes with two other readings — Anki as the subject carried over from the preceding clause, and the impersonal «es braucht + Akk.» — and all three happen to converge on the intended meaning, which is why it survives. «deshalb muss dort … ein Wert stehen» names the slot with `dort` and removes the pronoun entirely. Neither item is an error; the current text is grammatical and faithful. |
| `game_audio_zoom_hint` | 💬 | Ziehe zwei Finger zusammen oder auseinander, um mehr oder weniger Audio anzuzeigen | Ziehe zwei Finger zusammen oder auseinander für mehr oder weniger Audio | Purely optional compression. **No truncation risk** — the caption is a `match_parent` / `wrap_content` `TextView` at 11sp in `anki_game_audio_panel.xml` with no `maxLines` and no `ellipsize`, so it wraps rather than clips. But at 82 chars against EN's 32 it takes two lines under the waveform where EN takes one, doubling the height of what is meant to be a whisper-weight hint. The `für`-tail drops 11 chars and the slightly abstract «Audio anzuzeigen» while keeping the du imperative. **Explicit verdict on the proposed alternative «Zum Anpassen des Ausschnitts zwei Finger zusammen- oder auseinanderziehen»: worse, do not adopt.** It saves only ~10 chars (still two lines, so it buys nothing), abandons the du imperative for a nominalized infinitive that breaks with the file's own gesture-hint precedent `floating_menu_drag_instruction` («Ziehe mit dem Finger, um …»), and swaps the concrete «Audio» for the abstract «Ausschnitt», which is exactly the information the caption exists to convey. |

### Clean areas (delta) — checked, no findings

**Register.** All three imperatives in this batch are du and match the file: «Ordne … zu»,
«Tippe auf …», «Ziehe …». A naive grep for `Sie|Ihre` still turns up five hits file-wide,
and all five are re-verified as the *feminine/neuter third-person pronoun* in
sentence-initial position, not formal address — `onboarding_notif_row_silent_sub` (die
Benachrichtigung), `error_single_app_not_fullscreen` (die Übersetzung),
`a11y_required_displays_message` and `a11y_required_hotkey_message` (die Berechtigung),
`dialog_hotkey_setup_typing_key` (die Taste). No Sie leak.

**Compounds and the toast pattern.** `anki_added_sentence_success` / `anki_added_word_success`
sit on the committed frame from `anki_added_no_audio` («Zu Anki hinzugefügt»), just with
the card noun fronted — the elliptical verb-final toast fragment is consistent across all
three. **Satzkarte** is not a new coinage: it is already committed four times
(`anki_game_audio_row_subtitle`, `anki_content_words_table`, `anki_content_flag_sentence`,
`anki_content_flag_targeted_sentence`), and **Wortkarte** is its obvious and standard
parallel. Correctly *not* collapsed to the bare `anki_mode_sentence` / `anki_mode_word`
values (Satz / Wort): those two are mode chips, whereas these toasts exist precisely to
name the *card shape* that one-tap silently produced, so the `-karte` compound is
load-bearing, exactly as in EN.

**Notiz — first use, and it is the right call.** The translator introduces *Notiz* for
Anki's *note*, a word this file had never used (it says Karte / Kartentyp, 11 occurrences).
Checked and accepted on three grounds. (1) It is AnkiDroid's own German term — a German
AnkiDroid user has already seen Notiz / Notiztyp in that app, so the toast points at
something nameable rather than inventing vocabulary. (2) It is faithful to a deliberate
split in the source: EN uses "card type" in 12 user-facing strings and "note" only in these
two, so DE's Kartentyp/Notiz pairing mirrors EN's Karte-vs-Notiz distinction rather than
drifting from it. (3) The distinction is carried, not blurred, in `anki_first_field_empty`:
«Anki erkennt Notizen anhand des ersten Feldes» attributes identification to the *note*
while «ist auf dieser Karte leer» / «auf jeder Karte» keep the user's object the *card* —
the same two-level structure EN uses, and the reader does not need to understand Anki's
note→card model to act on the message. `erkennen` is also the right verb for *identify*
here, matching the duplicate-detection sense.

**`card_words_in_sentence`.** «Wörter im Satz» is baked into the card at send time and
rendered through `gl-section`, which applies `text-transform:uppercase` (`PtCardTemplates.kt`,
`AnkiHtmlStylers.kt`). Verified safe under uppercasing: `ö` → `Ö` is lossless, and the
string contains **no ß**, which is the one German character that would have turned a
CSS-uppercased header into SS or ẞ depending on engine. Sentence case in the source, as the
comment requires.

**History strings.** `history_hide_translations_toggle_title` = «Übersetzungen ausblenden»
uses Android's standard *ausblenden* for hide. In the subtitle, **aufgenommen** is the
correct participle and is not ambiguous with audio recording here: it is the file's
committed screen-capture verb (`history_toggle_subtitle` «Aufgenommene Sätze auf diesem
Gerät speichern», `settings_cell_history_summary_on/_off` «Liste der aufgenommenen Sätze»),
the language-parameters doc names this an explicit hard constraint (reuse the locale's
existing capture verb, never introduce a second), and the noun **Text** in «den
aufgenommenen Text» rules out the audio reading outright — the audio sense of the same
participle lives on a duration, `game_audio_trim_duration` («147 s aufgenommen»), where no
confusion is reachable. **Zeile** for EN's "row" is likewise consistent rather than new: the
DE History family already renders EN's *line* as Zeile in `history_line_count`,
`history_empty_none` and `history_clear_confirm_message`, while *Eintrag* stays reserved for
EN's *entry* (`history_delete_confirm_title`) — so DE keeps one noun per object where EN
itself is looser. The infinitive-then-imperative shift across the two sentences («Nur den
aufgenommenen Text anzeigen. Tippe auf eine Zeile …») is deliberate and correct: sentence
one is a setting description and matches the infinitive style of its sibling subtitles
(`history_toggle_subtitle`, `history_capture_image_toggle_subtitle`), sentence two is an
actual instruction to the user and takes the du imperative. Dropping EN's possessive "its"
in favour of «die Übersetzung» is the more natural German.

### Verdict (delta)

**PASS.** No 🛑, no ❌, no ⚠️ — three 💬 polish items, two of them free (an unconstrained
alert, a wrapping caption) and one a length-safety trim on the clipping toast.

## Delta review 2026-08-19 (25 keys: language wildcard, Bergamot device gate, dictionary-styling toggle, Source Language row, manual dictionary-update flow, debug angle rollback)

Mechanical layer verified programmatically across all 12 locales: all 25 delta names
present, no extras, no duplicate `name=`; every `%n$s` present and matching EN; all
`<xliff:g>` spans byte-identical to EN (`id`, `example`, inner placeholder); `<b>`, `\n`,
`\{ \}`, `&lt;/&gt;/&amp;` counts match; no unescaped `'`/`"`. Analyzer reports
`missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD SUCCESSFUL. No
`<plurals>` in this delta. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | current | suggested | note |
|---|---|---|---|---|
| settings_debug_angle_gate | 💬 | «Klassischer Winkel-Schwellenwert (10°)» | «Klassischer Winkelschwellenwert (10°)» | This file hyphenates only where one half is a loanword, acronym or brand — API-Schlüssel, Offline-Übersetzung, Live-Modus, Auto-Übersetzung, UI-Inhalte, Update-Prüfung, Sprachausgabe-Engine. Native German compounds are written solid: Kameraeinstellungen, Systemeinstellungen, Kontextgrenze, Häufigkeitsbewertung. Winkel + Schwellenwert are both native, so the hyphen is out of pattern. |

### Clean areas (delta) — checked, no findings

**Update vocabulary reused from the app updater.** «Update verfügbar» and
«Update-Prüfung fehlgeschlagen» are byte-identical to `update_dialog_title` /
`update_check_failed_title`; `yomitan_update_check_failed_message` follows
`yomitan_download_error_message`'s «Überprüfe deine Verbindung und versuche es erneut»;
`yomitan_update_scan_active_message` closes with `anki_models_unavailable`'s «Versuche es
gleich noch einmal»; «im Hintergrund» matches `onboarding_notif_row_silent_sub`. The
noun/verb split is kept: «Nach Updates suchen» for the tappable row, «Suche nach Updates»
for the progress popup.

**No agreement exposure around the placeholder.** German predicative adjectives do not
inflect, so «%1$s ist auf dem neuesten Stand» and «%1$s kann auf Version %2$s aktualisiert
werden» are safe for any dictionary title regardless of gender — the same reason
`yomitan_duplicate_message` («%1$s ist bereits importiert») needed no head noun. «Die Daten
von %1$s … damit **sie** … funktionieren» refers to Daten (pl.), which is what EN's "to
work" attaches to; the Arabic locale had the mirror-image bug here and was fixed.

**«Ausgangssprache» is the file's existing term**, already used twice in
`llm_prompt_kw_source_desc` / `_source_code_desc`, and deliberately not «Spielsprache»
(4 occurrences) — that names the capture language, not the dictionary's declared one.

**«jetzt» is present where EN says "now"**, keeping `yomitan_update_done_message` distinct
from `yomitan_update_none_message`.

**Register and length.** Informal lowercase du throughout («Überprüfe», «Versuche»,
«schalte die Option aus»); no Sie. The long styling subtitle and repair message land in
`Text.PT.RowSubtitle` / the alert body, neither of which sets `maxLines` or `ellipsize`,
so German compounding wraps rather than clipping — checked in `settings_row_value.xml` and
`styles.xml` rather than assumed.

### Verdict

**PASS after fix.** One 💬, no ⚠️/❌/🛑. German is structurally the least exposed locale in
this delta: no case, gender or particle contact with the dictionary-title placeholder.

### Delta review round 2 — 2026-08-19 (`lang_pick_any` read against the render code)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| lang_pick_any | ⚠️ | «Alle Sprachen» | «Beliebige Sprache» | The pinned wildcard row repeated `lang_section_all` verbatim: «Alle Sprachen» sat two rows above a header reading «Alle», so it read as a shortcut into the full list rather than "no language restriction". «Beliebige Sprache» is the standard German wildcard and is lexically distinct in both slots. |

**Why round 1 missed it.** The string was reviewed against its English source and its two
slots in isolation. It only fails when read against its *neighbours on screen*:
`LanguageSetupActivity` passes the Any row as `leadingRows` (:282) and heads the list
below it with `lang_section_suggested` then `lang_section_all` (:544, :550), so the pinned
row and the "All" header are two rows apart. This is the doc's own lesson — read the render
code, not the string — arriving from the other direction: not a truncation constraint, but
an adjacency one.

**Cross-locale shape.** Twelve independent renderings split into two camps: five chose an
*any*-flavoured word (zh-rCN 任意语言, ru «Любой язык», ar «أي لغة», es «Cualquier idioma»,
pt-BR «Qualquer idioma») and seven an *all*-flavoured one. Only the *all* camp can collide,
and only where the pinned row repeats the header's own word — ja, tr, de and fr, now fixed.
ko (모든 언어 / 전체), th (ทุกภาษา / ทั้งหมด) and vi (Mọi ngôn ngữ / Tất cả) use lexically
distinct words in the two slots and were left alone. The fix also moves these four closer
to the English, which deliberately says "Any" rather than "All" (and was itself renamed
from "None" — the row means *no restriction*, not *unset*, and not *the whole list*).

### Verdict (revised after round 2)

**PASS after fixes.** One ⚠️ (round 2) and one 💬 (round 1). German remains the least
exposed locale for placeholder grammar in this delta; both findings were lexical.

## Delta review 2026-09-08 (7 keys: oversize-card guard + two debug rows)

Mechanical layer verified programmatically across all 12 locales: all 7 delta names
present, no extras, no duplicate `name=`; every `<xliff:g>` span byte-identical to EN
(`id`, `example`, inner brand text); no `%n$s` in this delta; `<b>`, `\n`, `\{ \}`,
`&lt;/&gt;/&amp;` counts match; no unescaped `'`/`"`; no em/en dashes. Analyzer reports
`missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD SUCCESSFUL. No
`<plurals>` in this delta. **No 🛑 build-breaking issues.**

**Render code read before reviewing** (per the 2026-07-14 lesson): the prompt is an
`OverlayAlert` capped at 280 dp with **full-width, vertically stacked** buttons
(`OverlayAlert.kt` :306-341) and the debug rows are `settings_row_switch.xml` →
`Text.PT.RowTitle`, 15 sp, **no `maxLines`, no `ellipsize`**. Nothing in this delta
clips; long labels wrap. Accuracy was preferred over brevity throughout.

**Source-side finding (EN, applies to all 12 locales) — ❌ fixed in this pass.** The
comment on `anki_card_too_large_title` claimed the title serves "the oversize-card prompt
AND the too-large failure alert". It does not: every failure path shows
`anki_card_too_large_failed` either under `anki_send_failed_title` (`AnkiUiHelper.kt`
:1060, `TranslationResultFragment.kt` :967, `WordDetailBinder.kt` :691) or with **no title
at all** as a `LENGTH_LONG` Toast (`AnkiOneTapDispatch.kt` :162,
`TranslationResultFragment.kt` :1082). The failure string therefore has to name its own
subject, and was reviewed on that basis in every locale. The EN comment now says so.

### Findings (delta)

None. No 🛑/❌/⚠️/💬.

### Clean areas (delta) — checked, no findings

**du throughout, never Sie.** The failure body's imperatives are „Entferne ein paar Wörter
oder kürze den Satz" — the same person and tone as the committed
`anki_send_failed_message` „Stelle sicher, dass AnkiDroid läuft, und versuche es erneut".
The prompt and the toast carry no second person at all, so there is no mixing.

**Infinitive question.** „Eine vereinfachte Karte mit Definitionen als reinem Text
speichern?" matches `llm_prompt_discard_title` „Änderungen verwerfen?" and
`bergamot_disable_title` „Firefox Translations deaktivieren?". The dative *reinem*
agrees with *Definitionen* through *als*, which is the prescriptive form.

**Terminology.** Karte (card), Definitionen, Wörterbuch, „reiner Text" for plain text — the
last from `yomitan_styling_subtitle`'s „immer reinen Text zu verwenden". Wörter and Satz in
the advice match `anki_mode_word` „Wort" and `anki_mode_sentence` „Satz". „auf dem Gerät"
for on-device comes from `llm_prompt_row_system_subtitle` „in der Cloud und auf dem Gerät",
deliberately not a Gerätemodell compound.

**Compounds stay readable.** „Wörterbuchdefinitionen" is one compound and is the only long
one; the mmap row was written „Laden der Gewichte per mmap erzwingen" rather than a
Gewichtsladen compound, which would be opaque. „…erzwingen" matches
`settings_debug_force_single_screen` „Einzelbildschirm erzwingen" and
`settings_debug_force_crash_title` „Absturz erzwingen".

**Length.** German runs long, and both debug rows plus the toast were measured against the
render code before being accepted: `Text.PT.RowTitle` has no `maxLines` and the alert
buttons are full-width and stacked, so nothing in this delta clips.

### Verdict

**PASS.** No fixes required. vereinfach- is one stem across all four oversize strings.

## Delta review 2026-09-23 (6 keys: the results headers' ⋯ overflow menu)

Mechanical layer verified programmatically across all 12 locales: all 6 delta names
present once, no extras; no `<xliff:g>`, placeholders, `<plurals>`, quotes or
apostrophes, and no em/en dashes in this delta. The two orphans `cd_copy_original` /
`cd_copy_translation` (the headers' copy buttons, removed with this change) are deleted.
For this delta's keys the analyzer reports nothing missing and nothing orphaned; the
remaining `missing=14 orphan=1` belong to other, not-yet-synced features (icon gestures,
edge indicator, the Anki words helper). `:app:processDebugResources` BUILD SUCCESSFUL.
**No 🛑 build-breaking issues.**

**Render code read before reviewing.** The four `header_action_*` names, like the reused
`cd_add_to_anki`, `cd_text_size` and `capture_show_on_screen`, are the rows of
`ActionOverflowMenu`: 15 sp medium, `maxLines=1` + `ellipsize=end`, in a card 160 to 280
dp wide less 72 dp of icon and padding, so up to about 208 dp of label. `cd_more_actions`
is spoken only (the ⋯ button's description and the menu's pane title);
`cd_toggle_inline_pinyin` is the furigana button's spoken name when the source is Chinese
(it replaces a hardcoded English "Toggle inline pinyin").

### Findings (delta)

None.

### Clean areas (delta) — checked, no findings

**Siblings, one term swapped.** «Inline-Pinyin ein-/ausschalten» mirrors the furigana
string.

**The ⋯.** «Weitere Aktionen» parallels the parked sheet's «Für weitere Optionen tippen».

**Row names.** «Vorlesen» is `cd_read_original_aloud`'s verb («Originaltext vorlesen»);
«Text bearbeiten» its sibling. Longest row: the reused «Am Bildschirm zeigen», 20
characters, about 170 dp, inside the 208 dp label room. No pronouns, so du/Sie does not
arise.

### Verdict

**PASS.** Every delta string reuses the locale's own committed wording for its sibling
(the furigana toggle, the read-aloud and edit descriptions, the parked sheet's "more"
hint), so the menu reads in the same voice as the buttons it replaces.

## Delta review 2026-09-29 (39 keys + 1 orphan: floating-icon gestures and the no-menu alert, "Change game language", the translation error pill and its discard confirm, the hold failure pill, the Overlay card rows, the Anki words helper)

Mechanical layer verified programmatically across all 12 locales: all 39 delta names
present once, the orphan `anki_words_helper` deleted (its replacement
`anki_words_helper_hide` was translated afresh, as its commit asked), no duplicate `name=`;
every `<xliff:g>` span byte-identical to EN (`id`, `example`, inner text); `%1$s`/`%2$s`
parity; `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match; no unescaped `'`/`"`; each file
parses. Analyzer reports `missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD
SUCCESSFUL. No `<plurals>` in this delta. `settings_filter_furigana_title` (already
translated) was moved from its old Debug position to its English position between the new
Overlay rows, text unchanged, so the file stays diffable against English. **No 🛑
build-breaking issues.**

**Render code read before reviewing.**
- `icon_gesture_*` are bold 15 sp in the Settings cell's `TableLayout`; only the action
  column shrinks (`shrinkColumns="2"`), so the longest gesture word sets the width left for
  all three action titles. They are also the picker page's section headers
  (`Text.PT.GroupHeader`, ALL CAPS, 11 sp). Action titles wrap freely in the cell and in the
  picker's `settings_row_choice` rows (no `maxLines`).
- `icon_action_translating_from` and `hold_translation_failed` are drawn by
  `OverlayUiController.showNoTextPill` as ONE line of `Canvas.drawText` in a window sized to
  the text: no wrapping, so both must stay short. `%1$s` is `SourceLangId.displayName()`,
  the language name in the UI locale with its first letter capitalized.
- The error pill (`TranslationErrorPills`) is a 14 sp TextView, `maxLines=2`, ellipsized,
  spanning most of the display width; every message but the connection one leads with the
  service name.
- The no-menu alert and the discard confirm are `OverlayAlert`s with full-width stacked
  buttons (the confirm in the danger colour, then `btn_cancel`).
- `anki_words_helper_hide`'s `%1$s` becomes an ImageSpan of the eye glyph
  (`inlineIconString`).
- The Overlay card on screen: Overlay Mode, Minimum text size (title, subtitle, warning,
  value + slider, example), Filter furigana (Japanese only), Widen vertical text, Edge
  indicator.

**Source-side observations (EN; reported, not changed):**
1. `settings_overlay_min_text_warning` ends without a period after two sentences; every
   locale mirrors that.
2. The comments on `icon_action_translating_from` and `hold_translation_failed` do not say
   the pill is a single canvas-drawn line that never wraps. A translator who writes a long
   sentence there gets it drawn past the pill's edge.
3. The error-pill banner asks every message to start with the service name. In an RTL
   locale that makes the first strong character Latin, so the pill's TextView resolves an
   LTR paragraph (see the ar report).
4. `icon_gesture_*` asks for "short imperative verbs". Several locales label gestures with
   an infinitive or a noun instead (see each report); the binding constraint is the width
   one above.

### Findings (delta, round 1)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| settings_edge_indicator_subtitle | 💬 | Zeigt … solange das Overlay angezeigt wird. | Zeigt … solange das Overlay zu sehen ist. | zeigt … angezeigt. |
| icon_gesture_hold | 💬 (decision) | Gedrückt halten | — | 15 letters widen the gesture column on narrow phones, but «Halten» alone can read as "stop", so the Android term stays. |

### Clean areas (delta) — checked, no findings

**Register and quotes.** du throughout (Weise … zu, Tippe, du musst); „ “ quotes; the
alert's „Schnellmenü öffnen“ byte-matches, and the gesture names are quoted as names.
Lowercase after a colon unless a noun follows (Duden).

**Terms.** Reused: Schnellmenü, Spielsprache ändern, Bildschirm aufnehmen,
Auto-Übersetzung starten/stoppen, Konnte nicht übersetzt werden, API-Schlüssel,
Kontingent, Guthaben, EXPERIMENTELL., verwerfen, Overlay. The pill «Spielsprache: %1$s»
avoids declining the language name.

### Verdict (round 1)

1 💬 to apply; 1 💬 recorded as a decision.

### Round 2 (2026-09-29), after applying round 1

Round-1 fix present: «…solange das Overlay zu sehen ist.» Mechanical layer re-run after the fixes: 0 problems; analyzer `missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD SUCCESSFUL. Every screen of the delta was re-read in full, not only the changed keys.

No new findings.

**Verdict (round 2):** **PASS.**

## Delta review 2026-10-07 (41 keys + 2 orphans: bug-report email, Support rows, kill notice, Fix disappearing icon page)

Mechanical layer verified: `python3 PKT/../tools/mech_check.py PKT/../keys.txt de` reports
"checked 1 locales x 41 keys; problems: 0" (every key present; `<xliff:g>` spans and
`%1$s` byte-identical to EN; `\n`, `<b>`, `\{ \}`, `&amp;/&lt;/&gt;` counts match; no
unescaped `'`/`"`, stray tags, double spaces or edge whitespace). `python3
scripts/l10n_diff.py --locale app/src/main/res/values-de/strings.xml` reports
`missing=0 orphan=0 modified=0`, "in sync". The two orphans
(`settings_debug_export_logs_title`, `_subtitle`) are gone, `_subject` stays; no duplicate
`name=`; the file parses as XML. **No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `MainActivity.maybeShowKillNotice`: the body is one of the three `kill_notice_body_*`, plus
  `' '` + `kill_notice_restored` only when `CaptureBackendResolver.activeOverlayUi?.hasAnyFloatingIcon`
  is true, so that sentence shows exactly when a floating icon is back on screen.
  `OverlayAlert`: 280 dp card, title 17 sp bold, body 13 sp, both centered and wrapping;
  See options / Not now stacked full width.
- `KeepRunningActivity.titleOf/lineOf`: the Xiaomi battery card reuses `keep_running_battery_line`;
  an enabled-but-unbound accessibility service swaps in `a11y_stuck_title` + the restart line; the
  Report a bug row binds the two Settings strings in a wrapping `settings_row_link`. The Samsung
  card opens `com.samsung.android.lool …BatteryActivity`, the Xiaomi battery card the per-app
  `HiddenAppsConfigActivity` (package extras), Huawei close-after-lock `ACTION_POWER_USAGE_SUMMARY`,
  so the paths in those lines matter mostly for the App info fallback.
- `SettingsRenderer.setupSupportSection` + `settings_row_hub.xml` (summary `singleLine`,
  `ellipsize=end`); `LogExporter.emailFiles`: no email app → `email_no_app_fallback` toast
  (LENGTH_LONG, `SUPPORT_EMAIL`) → `shareFiles` with chooser `share_chooser_share_logs`. The
  packet's Measure/Wrap tools reproduce its figures (226, 378, 214, 219 dp), so the alternatives
  below were measured the same way.

### Findings (delta, round 1)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| kill_notice_restored | ⚠️ | `Jetzt ist es wieder eingeschaltet.` | `Das Symbol ist jetzt wieder da.` | „es“ has three neuter candidates (Smartphone, PlayTranslate, Symbol) and binds differently per body. After `_memory` and `_stopped` the nearest is „das schwebende Symbol“, and an icon is not „eingeschaltet“. After `_other` the sentence before is „Manche Smartphones tun das, um Akku zu sparen.“: „es“ can pick up its „das“ ("that battery saving is on again") or reach back to „Dein Smartphone“, the strongest collocation for „wieder eingeschaltet“. The sentence shows only when an icon is back on screen; „wieder da“ answers „verschwunden“ after all three bodies. („PlayTranslate ist jetzt wieder an.“ also works.) |
| keep_running_tile_line | ⚠️ | `Einmal wischen und tippen, und <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> ist auf jedem Bildschirm wieder an.` | `Mit einmal Wischen und Tippen schaltest du <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> von jedem Bildschirm aus wieder ein.` | „auf jedem Bildschirm“ attaches to „ist … wieder an“: PlayTranslate is back on *on every screen* (on a dual-screen handheld, on every display), not that the tile works *from any* screen. „von jedem Bildschirm aus“ is the English meaning; „schaltest … ein“ is the file's Einschalten. |
| keep_running_oppo_auto_launch_line, keep_running_vivo_autostart_line | ⚠️ | `Sonst beendet das Smartphone <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> kurz nachdem du es verlässt.` | `Sonst beendet das Smartphone <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>, kurz nachdem du es verlässt.` | Missing comma before the subordinate clause; with „kurz nachdem“ it goes before the whole group (Duden: „Er kam, kurz bevor das Spiel begann.“). Same fix in both keys, which stay byte-identical. |
| keep_running_accessibility_line | 💬 | `Führt <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> mit der Priorität eines Bedienungshilfen-Dienstes aus, den Speicherbereinigungen kaum erreichen.` | `Führt <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> mit der Priorität eines Bedienungshilfen-Dienstes aus, den die Speicherbereinigung kaum erreicht.` | Garden path: „den Speicherbereinigungen“ first parses as a dative plural ("to the cleanups"); only „erreichen“ shows „den“ is the relative pronoun. The singular with its article removes the misparse and matches the Xiaomi lock line's „Die Speicherbereinigung überspringt …“. |
| keep_running_xiaomi_lock_line | 💬 | `Öffne die letzten Apps, halte die Karte von <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> gedrückt und tippe auf das Schloss. Die Speicherbereinigung überspringt gesperrte Apps.` | `Öffne die Übersicht der letzten Apps, halte die Karte von <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> gedrückt und tippe auf das Schloss. Die Speicherbereinigung überspringt gesperrte Apps.` | „Öffne die letzten Apps“ first reads as "open your most recent apps". Naming the screen fixes it; the title's „in den letzten Apps“ can stay. No official Xiaomi DE label exists for Recents (AOSP's framework says „Kürzlich geöffnete Apps“; a HyperOS 2 guide, TechBone, secondary, „Kürzliche Apps“), and the card is instructions only, so plain words beat a guessed label. |
| keep_running_xiaomi_autostart_title | 💬 | `Autostart erlauben` | `Autostart zulassen` | Same English "Allow autostart" as the vivo title („Autostart und … zulassen“). The file renders "Allow …" as „… zulassen“ in 4 of 5 strings (Benachrichtigungen, Eingeschränkte Einstellungen, Bildschirmaufnahme, Installation von Updates), and AOSP's German button is „Zulassen“. Xiaomi's own switch is just „Autostart“, so no OEM label is at stake. |
| keep_running_battery_title, keep_running_xiaomi_battery_title | 💬 | `Akkunutzung auf „Uneingeschränkt“ setzen` · `Energiesparmodus auf „Keine Einschränkungen“ setzen` | `Akkunutzung auf „Uneingeschränkt“ stellen` · `Energiesparmodus auf „Keine Einschränkungen“ stellen` | Values in name order. The reviewed `a11y_stuck_message_xiaomi` renders the same English ("set Battery saver to No restrictions") as „stelle den Energiesparmodus … auf „Keine Einschränkungen““, the file's only other "set … to"; „auf … stellen“ is the everyday verb for a setting, „setzen“ leans IT jargon. |

### Clean areas (delta) — checked, no findings

**Kill notice, read as the user meets it.** „Android hat PlayTranslate beendet“, then each body
alone and with the appended sentence (finding above). Every other „es“ binds cleanly:
`_body_memory`'s „deshalb hat es …“ can only be the Smartphone (Arbeitsspeicher is masculine);
in `_body_stopped`, „Wenn du es nicht selbst gestoppt hast“ is PlayTranslate and „war es
vielleicht …“ is a cleft's dummy subject. The bodies keep English's closed/stopped split
(beendet, as in the title; gestoppt, as in the reviewed `a11y_stuck_message`), and „im
laufenden Betrieb“ renders "while it was on" without a pronoun. „Optionen ansehen“; „Nicht
jetzt“ byte-matches `btn_not_now`.

**Support card, five rows in order.** „Nach Updates suchen“, „Discord beitreten“, „Symbol
verschwindet?“, „Fehler melden“, „PlayTranslate unterstützen“. The question title follows the
„Passwort vergessen?“ pattern and leads with the symptom, as the English comment asks; an
infinitive („Verschwindendes Symbol beheben“) measures 342 dp against the 272 dp toolbar. Row
and page titles are byte-identical. „Damit PlayTranslate nicht beendet wird“ is 226 dp, whole at
249 dp. Report a bug: the first sentence is 214 dp, whole at 249 dp; a 360 dp phone shows
„Protokolle per E-Mail an den Suppo…“, which still says what a tap does. The full 378 dp comes
from the hold hint „Zum Teilen gedrückt halten“, the Android idiom (bare „Halten“ reads as
"stop", as the 2026-09-29 round decided); no natural German gets both sentences near English's
256 dp, and on the Fix page the row wraps in full.

**Fix disappearing icon page.** Toolbar 219 of 272 dp. Intro natural („beim Spielen
seltener“). Empty label: „melde dann einen Fehler“ points at the „Fehler melden“ row beneath;
„reproduziere es“ binds to „das Problem“ and echoes the crash body's „Reproduzieren“. The shared
battery line reads right under both the generic and the Xiaomi title, and its „während du es
nutzt“ works whether „es“ is the app or the phone. The OPPO and vivo lines are byte-identical.
Accessibility: „Bedienungshilfen-Dienst“ as in `accessibility_service_description`; the stuck
variant repeats `a11y_stuck_message` word for word under `a11y_stuck_title`, with „den Dienst“
making English's "it" explicit. The tile title byte-matches `quick_tile_add_row_title`. In the
Xiaomi autostart line, „lässt es nicht wieder starten“ can only be PlayTranslate.

**Platform labels.** AOSP: „Akkunutzung“ (from „Akkunutzung von Apps“), „Uneingeschränkt“,
„Bedienungshilfen“, „Schnelleinstellungen“, all exact. Samsung DE, fetched today
(samsung.com/de/support/mobile-devices/hintergrundnutzung-von-apps/, updated 19.12.2023, Galaxy
Z Flip5): „Einstellungen“ › „Akku und Gerätewartung“ › „Akku“ › „Grenzen der
Hintergrundnutzung“, lists „Apps im Standby“ and „Apps, die nie im Standby sind“. The title and
line match exactly; the page prints „und“, so the „&“ in oem-labels.md came from the video
transcript. On One UI 6.1+ the entry is „Akku“ directly; the line's „Akku → Grenzen der
Hintergrundnutzung“ still leads there, and the card opens Samsung's battery screen itself.
Huawei DE: „App-Start“, „Manuell verwalten“, „Auto-Start“, „Sekundärer Start“, „Im Hintergrund
ausführen“ (official), and „Akku“ › „Weitere Akkueinstellungen“, a GUESS in oem-labels.md, now
confirmed on consumer.huawei.com/de/support/content/de-de00428704/ (fetched today). The
close-after-lock label itself is known issue 1. OPPO („Autostart zulassen“,
„Hintergrundaktivität zulassen“) and vivo („Autostart“, „Hohe Akkunutzung im Hintergrund“) match
the Huawei-authored German pages (secondary); folding „hohe“ into the vivo title follows the
on-screen label rather than English's "background power use". Xiaomi: „Autostart“ (official FAQ
titles) and „Keine Einschränkungen“ (secondary) match; „Energiesparmodus“ follows the reviewed
`a11y_stuck_message_xiaomi`. Unverified and left alone: a German HyperOS 1/2 guide (TechBone,
secondary, seen through a search) puts the per-app setting on the app's „Akku“ page, with
„Energiesparmodus“ as one of the options beside „Keine Einschränkungen“, and another of its
articles says „Keine Beschränkungen“. The card opens the per-app screen directly and quotes the
value to pick, so this needs a device check, not a rewrite.

**Email flow.** „Absturzbericht senden“ / „Fehlerbericht senden“; the subject
„PlayTranslate-Fehlerbericht – v3.3.0“ mirrors `crash_email_subject`; the body is natural, with
„aktuelle Protokolle“ as in `crash_dialog_message`. Toast: two lines at 296 and 320 dp, address
whole on line 2 (285 of 296 dp: tight, inside the noise). It drops "found", unlike the file's
seven „… gefunden“ strings, because „Keine E-Mail-App gefunden.“ with „auf anderem Weg“ pushes
the address to line 3 at 296 dp; „… anderweitig an …“ fits but puts line 1 at 287 dp and reads
stiffer. „Keine E-Mail-App.“ is ordinary toast German (like „Keine Internetverbindung“), so the
trade-off stands. Then the share sheet, „Protokolle teilen“.

**Register and typography.** du throughout (Beschreibe, Aktiviere, Öffne, Schalte, Sende,
melde; du nutzt, du verlässt), never Sie. „ “ quotes, balanced; → paths as in
`overlay_icon_a11y_required_message`; the subject's en dash mirrors English; no em dash.
Compounds read naturally (Absturzbericht, Fehlerbericht, Geräteinformationen, Akkunutzung,
Bildschirmsperre, Speicherbereinigung); the two hyphenated ones follow the file's
„Bedienungshilfen-Dienst“. „Smartphone“ for "phone" is known issue 3 (on handhelds and tablets
it is as off as the English; the rest of the app says „Gerät“). „Speicher“ alone can mean
storage, but each use is pinned by context, and the low-memory body says „Arbeitsspeicher“.

### Verdict (round 1)

3 ⚠️ and 4 💬 across 9 keys; no ❌, no 🛑. Apply the three ⚠️: `kill_notice_restored`,
`keep_running_tile_line`, and the comma in `keep_running_oppo_auto_launch_line` /
`keep_running_vivo_autostart_line`. The 💬 are optional; the two consistency ones
(`keep_running_xiaomi_autostart_title`, the two battery titles) cost nothing to take.

## Follow-up review 2026-10-07, round 1 (15 keys after the Fix disappearing icon rewrite, plus the final review of the first pass's 41 keys)

Mechanical layer: `python3 TOOLS/mech_check.py F2/keys2.txt de` reports "checked 1 locales x
15 keys; problems: 0", and `python3 TOOLS/mech_check.py …/scratchpad/keys.txt de` "checked 1
locales x 39 keys; problems: 0" (every key present; `<xliff:g>` spans, `app_name2` included,
and `%1$s` byte-identical to EN; `\n`, `<b>`, `\{ \}`, `&amp;/&lt;/&gt;` counts match; no
unescaped `'`/`"`, stray tags, double spaces or edge whitespace). `python3 scripts/l10n_diff.py
--locale app/src/main/res/values-de/strings.xml` reports `missing=0 orphan=0 modified=0`, "in
sync". The four keys English removed (`restricted_settings_title`, `_message`,
`keep_running_huawei_close_after_lock_title`, `_line`) are gone from the file; no duplicate
`name=`; the file parses as XML. **No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `AccessibilityHelp.withRestrictedSettingsStep` appends `"\n\n" + a11y_restricted_settings_addendum`
  on API 33+ to `accessibility_dialog_message` (MainActivity, AlertDialog),
  `overlay_icon_a11y_required_message` (SettingsRenderer, AlertDialog), the three
  `a11y_required_*_message` (AccessibilityAlert → OverlayAlert, 280 dp card) and, on the Fix page,
  `keep_running_accessibility_line` while the service is not enabled. The stuck alert
  (`SettingsRenderer.showA11yStuckAlert`) is `a11y_stuck_message` plus, on Xiaomi only,
  `"\n\n" + a11y_stuck_message_xiaomi`, without the addendum.
- `KeepRunningActivity`: cards in `KeepRunningItems.ids` order, title + line in a wrapping
  `settings_row_link`; the Xiaomi battery card now has its own line; `openFirst` tries the ROM's
  screens (Xiaomi autostart list and per-app battery screen, Huawei startup list, ColorOS
  startup list, vivo background-startup manager, Samsung's never-sleeping list), and only when
  none launches shows `keep_running_screen_unavailable` (Toast, LENGTH_LONG) and opens App info.
  So the search words in the lines are read on the fallback path, with App info open.
- Kill notice unchanged (`body + ' ' + kill_notice_restored` only when an icon is on screen);
  hub summary still `singleLine` + `ellipsize=end`, the Fix page's Report a bug row wraps.

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| keep_running_vivo_autostart_line | 💬 | `Suche in den Einstellungen nach „Autostart“ und lass ihn für <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> zu. Lass dann in den Akkueinstellungen für die App hohe Akkunutzung im Hintergrund zu. Ohne diese Einstellungen stoppt das Smartphone <xliff:g id="app_name2" example="PlayTranslate">PlayTranslate</xliff:g> womöglich im Hintergrund oder verhindert, dass die App wieder startet.` | `Suche in den Einstellungen nach „Autostart“ und lass ihn für <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> zu. Lass dann in den Akkueinstellungen hohe Akkunutzung im Hintergrund für die App zu. Ohne diese Einstellungen stoppt das Smartphone <xliff:g id="app_name2" example="PlayTranslate">PlayTranslate</xliff:g> womöglich im Hintergrund oder verhindert, dass die App wieder startet.` | Right after „Akkueinstellungen“, „für die App“ can attach to the noun ("the app's battery settings", which is what the OPPO line rightly says for English's "its battery settings") instead of to the allowing. For vivo, English means the system's Battery section ("the Battery settings", Settings › Battery in the comment's source) and "allow it" there. With „für die App“ after the object, only that reading is left; nothing else changes. Optional. |

### Clean areas — checked, no findings

**Round-1 fixes of the first pass, verified in the file.** `kill_notice_restored` „Das Symbol ist
jetzt wieder da.“, `keep_running_accessibility_line` „…, den die Speicherbereinigung kaum
erreicht.“, `keep_running_xiaomi_lock_line`'s „Öffne die Übersicht der letzten Apps, …“ (kept
word for word through the rewrite), `keep_running_xiaomi_autostart_title` „Autostart zulassen“,
and „stellen“ in `keep_running_xiaomi_battery_title`. The rewrite superseded the rest: the tile
line keeps its fix („von jedem Bildschirm aus … wieder ein“), the OPPO/vivo sentence that lacked
the comma is gone, and the generic battery title was rewritten.

**CHANGED keys moved exactly as far as English** (compared with HEAD and the first-pass packet).
`a11y_stuck_message`: only the cause sentence is gone; the rest is byte-identical.
`a11y_stuck_message_xiaomi`: „Aktiviere … den Autostart“ → „Lass … den Autostart … zu“ (allow),
„den Energiesparmodus“ → „die Akkunutzung“ (its battery use), „stoppt … immer wieder“ → „stoppt …
möglicherweise immer wieder“ (may); nothing else touched. `keep_running_tile_line`: „Mit einmal
Wischen und Tippen“ → „Damit“, the rest byte-identical. `keep_running_xiaomi_battery_title`: only
the entry noun changed.

**The addendum after each of its six hosts.** AOSP: „App-Info“ and „Eingeschränkte Einstellungen
zulassen“ exact (the latter also the deleted, reviewed `restricted_settings_title`); „ausgegraut“
is the usual UI word, „Dreipunkt-Menü (⋮)“ the common German name with the glyph. After
`accessibility_dialog_message` and `overlay_icon_a11y_required_message`, whose paths end
„→ PlayTranslate → Aktivieren“, „der Schalter“ is that switch and „darauf“ binds to it; under the
Fix page's accessibility card it reads as the card's own next step. „unter Android 13 oder
neuer“ inside the wenn-clause scopes as English does (the paragraph exists only on 13+). du
imperatives throughout (tippe, öffne, tippe, wähle). The three in-app alerts: see the known gap
below.

**Cards, title then line.** Xiaomi: „Autostart zulassen“ over the search word „Autostart“ and
„lass ihn … zu“ (der Autostart); „Akkunutzung auf „Keine Einschränkungen“ stellen“ over a line
that names the entry by version and the value to pick; the lock card's „Bei manchen Versionen
ziehst du die Karte stattdessen nach unten.“ is natural instruction German. Huawei: the path,
then the switches, all five labels official. OPPO and vivo: search word, the battery step as its
own sentence, then the hedged effect (last sentence byte-identical in both, as in English).
Samsung: „Öffne in den Akkueinstellungen „Grenzen der Hintergrundnutzung“ und füge … zu „Apps,
die nie im Standby sind“ hinzu.“ matches its title, and „Standby“ is Samsung's own German word for
sleep. Generic battery: the title echoes AOSP's „Soll die App immer im Hintergrund ausgeführt
werden?“, and the line's „die App“ avoids an „es“ that could be the Smartphone. Tile: „Damit
schaltest du … von jedem Bildschirm aus wieder ein.“ The three two-span lines carry both spans
and read naturally with the name twice. Closed and stopped stay apart as in the first pass
(„beendet“, „nach dem Beenden“ for close; „stoppt“ for stop).

**Labels.** Huawei DE official (first pass): „App-Start“, „Automatisch verwalten“, „Auto-Start“,
„Sekundärer Start“, „Im Hintergrund ausführen“. Samsung DE official (first pass): „Grenzen der
Hintergrundnutzung“, „Apps, die nie im Standby sind“. vivo (Huawei-authored, secondary):
„Autostart“, „Hohe Akkunutzung im Hintergrund“. OPPO, checked today through a search summary
(secondary): TechBone gives OxygenOS 12 „Einstellungen → Apps → Automatisch starten“
(techbone.de/oneplus/smartphone/apps-autostart-aktivieren-oder-deaktivieren) and ColorOS 13 „App →
Akkuverbrauch → Automatisch Starten erlauben“
(techbone.de/oppo/smartphone/apps-automatisches-starten-einer-app-zulassen); an older OxygenOS
guide (schieb.de/766343) has „Berechtigung → Autostart“. A search for „Automatisch starten“
matches both current forms and „Autostart“ only the old one, so the translator's choice over the
Huawei-authored „Autostart zulassen“ stands; the title's generic „Autostart“ and „lass den
Autostart … zu“ bridge to it without confusion. Xiaomi, two TechBone pages read today (HyperOS 2,
Android 15, secondary): „Einstellungen → Apps → Hintergrund-Autostart“
(techbone.de/xiaomi/smartphone/autostart-von-apps?from=12), which contains the search word
„Autostart“, and the per-app „Apps → App-Info → Akku“ with „App einschränken“ and, in its FAQ,
„Keine Einschränkungen“ (techbone.de/xiaomi/akku-optimierung-fuer-apps-deaktivieren-oder-aktivieren).
That supports „Akku“ for HyperOS 2 and the value; „Energiesparmodus“ (MIUI / HyperOS 1) and
„Energie“ (HyperOS 3) stay unverified (known issue 2), and another TechBone article says „Keine
Beschränkungen“, as the first pass recorded. The card opens the per-app screen itself, so the
list matters on the fallback path only. „Systemeinstellungen von PlayTranslate“ for "app
settings" keeps the line from pointing into PlayTranslate's own settings and matches
`a11y_stuck_message_xiaomi`'s „Systemeinstellungen der App“.

**Toast, then App info.** „Die Seite fehlt auf diesem Smartphone. Suche die Option in den
Einstellungen.“ wraps to 2 lines at 296 dp (line 1 284 dp) and at 320 dp (306 dp). „Die Seite“,
not „Diese“, keeps it off the App-Info page opening underneath; „Option“ avoids „Einstellung …
Einstellungen“ and echoes the kill notice's „Optionen ansehen“, which leads to this page.
Dropping "instead" was a choice, not a constraint („… Suche die Option stattdessen in den
Einstellungen.“ also fits: 284/272 dp at 296, 306/250 dp at 320); the sentence reads complete
without it, and sentence 1's „auf diesem Smartphone“ carries English's "your phone's".

**Stuck alert, as a set.** Title, the two-sentence body, the repair line and, on Xiaomi, the
paragraph whose two settings now say what the two Xiaomi cards say („Autostart … zulassen“,
„Akkunutzung … auf „Keine Einschränkungen““). Two „möglicherweise“ in separate paragraphs are fine.

**The first pass's keys, final read.** Email flow, Support rows, kill notice (each body alone
and with „Das Symbol ist jetzt wieder da.“), intro, empty label, accessibility and restart lines,
card titles: nothing new. Widths reproduce: `settings_support_keep_running_subtitle` 226 dp
(whole at 249), Report a bug's first sentence 214 dp, toolbar „Symbol verschwindet?“ 219 of 272
dp, `email_no_app_fallback` 2 lines at 296 and 320 dp with the address whole on line 2. Row and
page titles are byte-identical; the tile title byte-matches `quick_tile_add_row_title`.

**Register and typography.** du throughout, no Sie/Ihr/Ihnen in the 44 keys (the 39 plus the 5
follow-up keys outside them); „ “ balanced; no straight quotes, em dashes or double spaces.

**Known gap, English source (reported, not a German finding).** As the translator noted, „der
Schalter“ names no switch in the three `a11y_required_*` alerts, exactly like English's "the
switch". It is sharpest after `a11y_required_enhanced_message`: that alert opens from the
„Verbesserte automatische Übersetzung“ row, whose own switch (unchecked, not greyed) sits right
behind the card, and the sentence before says „diese erweiterten Steuerelemente“, so the in-app
switch is the nearest candidate in both languages. If English names it ("if PlayTranslate's
switch in accessibility settings is greyed out"), German follows as „Wenn der Schalter für
PlayTranslate in den Bedienungshilfen unter Android 13 oder neuer ausgegraut ist, …“ (with
whatever span English adds).

### Verdict (round 1)

0 🛑, 0 ❌, 0 ⚠️, 1 💬. **PASS** for the 15 follow-up keys and the final review of the first
pass's 39 remaining keys. The 💬 (`keep_running_vivo_autostart_line`, move „für die App“ behind
the object) is optional and costs nothing to take. One English-source gap is reported above for
the developer.

### Follow-up round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 TOOLS/mech_check.py F2/keys2.txt de` reports "checked 1
locales x 15 keys; problems: 0" (every key present; `<xliff:g>` spans, `app_name2` included,
byte-identical to EN; none of the 15 has a printf placeholder; `\n` counts match; no
unescaped `'`/`"`, stray tags, double spaces or edge whitespace). The same check reports
"checked 1 locales x 39 keys; problems: 0" over the first pass's remaining keys
(`…/scratchpad/keys.txt`) and "checked 1 locales x 3 keys; problems: 0" over
`F2/keys-extra-f2.txt`. `python3 scripts/l10n_diff.py --locale
app/src/main/res/values-de/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync".
The file parses as XML (1047 names, no duplicate). Against `HEAD` the string-level changes are
exactly the 42 added keys, the two changed stuck-alert keys and four deletions
(`restricted_settings_title` / `_message` and the first pass's two orphans).
`keep_running_huawei_close_after_lock_title` / `_line` are absent, and the 15 values equal
`F2/frag/de.src`.
**No 🛑 build-breaking issues.**

**Round-1 fixes:**
- `keep_running_vivo_autostart_line` (💬, move „für die App“ behind the object): applied,
  byte-identical to the suggestion („Lass dann in den Akkueinstellungen hohe Akkunutzung im
  Hintergrund für die App zu.“). Only that phrase moved. The last sentence is still
  byte-identical to the OPPO line's, as in English. Under the title „Autostart und hohe
  Akkunutzung im Hintergrund zulassen“, „für die App“ now attaches only to the allowing or to
  „Akkunutzung“, which mean the same thing, so the "the app's battery settings" reading has
  not come back.
- The first pass's fixes, confirmed in round 1, are still in the file: `kill_notice_restored`
  („Das Symbol ist jetzt wieder da.“), `keep_running_accessibility_line` („…, den die
  Speicherbereinigung kaum erreicht.“), `keep_running_xiaomi_lock_line` („Öffne die Übersicht
  der letzten Apps, …“), `keep_running_xiaomi_autostart_title` („Autostart zulassen“), „…
  stellen“ in `keep_running_xiaomi_battery_title`, and the tile line's „von jedem Bildschirm
  aus … wieder ein“.

#### Findings (round 2)

No new findings.

**Fresh read: checked, no finding.**
- **The addendum after its six hosts:** the two system dialogs, the three OverlayAlert
  messages and the accessibility card line, each composed as `message + "\n\n" + addendum`.
  It reads as a closing paragraph every time, and its four imperatives (tippe, öffne, tippe,
  wähle) run in English's order. „App-Info“ and „Eingeschränkte Einstellungen zulassen“ are
  AOSP's exact labels. The OverlayAlert message scrolls above the buttons, so the German
  length costs nothing. The English-source gap round 1 reported (the unanchored "the switch"
  after the three in-app alerts) is unchanged in English; it is still not a German finding.
- **The stuck alert:** the body is `HEAD` minus exactly the cause sentence. The Xiaomi
  paragraph's two steps repeat the two Xiaomi card titles.
- **Each card, title then line, per ROM, then the shared cards:**
  - Xiaomi: in „lass ihn … zu“, „ihn“ can only be „der Autostart“. The battery entries are
    listed in English's order.
  - Huawei: all five labels are official.
  - OPPO: „Automatisch starten“ is secondary, as round 1 recorded.
  - vivo: the search word and the line match the title (Huawei-authored label, secondary).
  - Samsung: both labels are official, and „Standby“ is Samsung's own word.
  - Generic battery card: the title echoes AOSP's „Soll die App immer im Hintergrund ausgeführt
    werden?“.
  - Close („beendet“) and stop („stoppt“) stay apart. The hedged effects stay hedged
    („womöglich“), and the battery line's verified effect stays plain, as in English.
- **Placeholder grammar:** PlayTranslate never takes an article or a genitive -s („von
  PlayTranslate“, „für PlayTranslate“). None of the three two-span lines uses „es“ for the app,
  so no pronoun can bind to the wrong noun: OPPO and vivo say „die App“ after the second
  mention, and the Xiaomi line needs no pronoun.
- **The toast, then App info:** re-measured with `TOOLS/Wrap` at 14 sp. It is 483 dp in all and
  wraps to 2 lines at 296 dp (284 + 195 dp) and at 320 dp (306 + 173 dp).
- **Register and typography** (checked by script over the 15): du only, no Sie/Ihr/Ihnen. The
  „ “ quotes are balanced and never nested, with no other quote characters and no em dash.
  Every subordinate clause has its comma.
- **Web checks: 3 of 4.**
  - One search for the German name of HyperOS 3's per-app battery entry found no German source,
    so „Energie“ stays unverified (known issue 2). Its summary attributes „Energiesparmodus“ as
    the HyperOS 1 entry to TechBone (secondary, not read).
  - One fetch of a German OnePlus manual URL, built from the French one's pattern, returned 404.
  - One search of oneplus.com and oppo.com for the German auto-launch label found no German
    page.

#### Verdict (round 2)

**PASS** (0 🛑, 0 ❌, 0 ⚠️, 0 💬). The one round-1 fix landed exactly as suggested, and nothing
around it broke. The 15 follow-up keys and the first pass's 39 keys have no open items. Two items
carry over, neither blocking:
1. A device check of the unverified Xiaomi and OPPO labels (known issue 2).
2. The English-source "the switch" gap that round 1 reported.
