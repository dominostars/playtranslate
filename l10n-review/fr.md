# French (values-fr) targeted review

*(Targeted hotlist pass + whole-file scans, not a full string-by-string review.)*

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| hymt_legal_message | ❌ | « En appuyant sur **Accepter**, vous affirmez… » | « En appuyant sur « J\'accepte », vous affirmez… » | The actual button (hymt_legal_agree) is « J\'accepte — Activer Hunyuan ». Quoted name ≠ button label — the exact failure 5/6 languages hit. (EN source has the same drift: "Agree" vs "I Agree — Enable Hunyuan", so fix FR to the FR button.) Everything else in the legal block is solid: §5(b) intact, « l\'Union européenne, du Royaume-Uni et de la Corée du Sud » complete, « vous affirmez et garantissez » carries warrant force, and clause (1) « Vous ne résidez pas et ne vous trouvez pas actuellement… » independently negates both residing and located. |
| settings_capture_interval_hint | ❌ | « Minimum <xliff…>%1$s</xliff…> secondes. » | « Minimum : <xliff…>%1$s</xliff…> s. » (or « seconde(s) ») | French plural starts at 2, and the value is "1" or "0.5" — so « 1 secondes » / « 0,5 secondes » is wrong in every case this string can render. The invariant « s » abbreviation is the safe fix. |
| tts_language_unsupported_with_engine_message | ⚠️ | « …mais il ne prend pas en charge <xliff…>%2$s</xliff…>. » | « …mais il ne prend pas en charge la langue suivante : <xliff…>%2$s</xliff…>. » | Code fills it with `Locale.getDisplayLanguage` (verified in `Language.kt:57` / `TtsUiHelper.kt:94`) → « ne prend pas en charge Japonais » — bare name, no article. Hard-coding « le » breaks on elision (« le anglais »), so restructure around the colon. |
| tts_language_unsupported_unknown_engine_message | ⚠️ | « Le moteur de synthèse vocale actif ne prend pas en charge %1$s. » | same colon restructure | Same article/elision problem. |
| settings_header_ocr | ⚠️ | « Image vers texte (OCR) » | « Reconnaissance de texte (OCR) » | Word-for-word calque; not idiomatic French for OCR. |
| accessibility_dialog_message | ⚠️ | « … → Applications installées → … » | « … → Applications téléchargées → … » | Stock Android French labels that Accessibility section « Applications téléchargées » (EN source says "Installed apps" — known upstream drift; FR should match what the user's screen actually says). « Paramètres » and « Accessibilité » in the path are correct. |
| overlay_icon_a11y_required_message | ⚠️ | « … → Applications installées → … » | « … → Applications téléchargées → … » | Same nav path, same fix. |
| onboarding_a11y_title, mp_overlay_permission_title | ⚠️ | « Par-dessus les autres applis » | « Superposition aux autres applis » | AOSP fr titles the "Display over other apps" Settings page « Superposition aux autres applis »; the card should match the screen the user is sent to. « Par-dessus… » is also elliptical (no noun head). |
| quick_tile_add_row_title | ⚠️ | « Ajouter la tuile aux Paramètres rapides » | « Ajouter la tuile aux réglages rapides » | The QS panel is « réglages rapides » in AOSP fr SystemUI (and lowercase mid-sentence); « tuile » itself is fine. OEM skins vary — worth a one-glance check on a French device, but I'd align with AOSP. |
| pack_upgrade_mandatory_message | ⚠️ | « Mettez à jour maintenant, ou supprimez-la pour choisir une autre langue. » | « …, ou supprimez le pack pour choisir une autre langue. » | Two feminine antecedents in range (« cette mise à jour », « la version installée ») — « supprimez-la » can momentarily read as "delete the update". Name the referent. |
| label_region_drag_hint | 💬 | « …le bord supérieur ou inférieur, ou le milieu pour déplacer tout le cadre. » | « …, ou faites glisser le milieu pour déplacer tout le cadre. » | EN repeats "drag" to scope "move the whole box" to the middle only; FR elides the verb, letting the purpose clause float over the whole list. Repeating « faites glisser » restores the scoping. |
| settings_hotkeys_tile_add | 💬 | « Ajouter une tuile » | « Ajouter la tuile » | It's the app's one specific tile, not any tile. |
| anki_sort_field_empty | 💬 | « Mappez une valeur au champ… » | « Associez une valeur au champ… » | The feared calque didn't happen — « erreurs de rejet pour doublon lors de l\'envoi » reads fine. Only « Mappez » is dev-jargon. |

Checked clean: live_mode_auto_with_hint (« Auto Furigana » keeps the visual tie to the « Auto » toggle — keep); status_hold_hint / status_idle (quoted names Zones / Auto / Traduire exactly match nav_regions / live_mode_auto_label / translate_button_prefix_translate, marked by capitals as in EN); translate_button_prefix_translate/reload (« Traduire Plein écran » works as a button with the bolded region label); backend_cooldown_status_fmt + retry_at/retry_on (« Limite atteinte · Nouvel essai à 15:42 » / « Nouvel essai le 1 juin » compose naturally); anki_permission_rationale_message / anki_settings_grant_access_subtitle (comma keeps Anki and PlayTranslate apart; « Continuer » matches btn_continue); crash_dialog_discard « Ignorer » and btn_clear « Effacer » (neither reads as Annuler/Supprimer); truncation — Zones/Auto/Pause fine, « Zone de\ncapture » fits the two-line button, « Paramètres » is the longest 8sp label but is the only possible word.

## Scan results

- **Apostrophes:** clean — 154/154 apostrophes escaped as `\'`, zero unescaped, zero typographic `'`. No build risk.
- **Register:** clean — zero hits for tu/ton/ta/tes/toi or peux-tu; vous throughout.
- **Brands:** clean — PlayTranslate ×36, Anki ×15, AnkiDroid ×15, DeepL ×7, all untranslated; no calqued brand found.
- **Go/GB:** clean — every " GB" hit is inside `example=` attributes (never rendered); the one visible unit is « Go de RAM » in llm_hardware_unsupported_ram.
- **Punctuation spacing:** consistently applied — plain space before « ? » (21), « : » (28), « ! » (1), zero missing, but zero NBSP/NNBSP anywhere. Opinion: keep the convention (it's correct fr-FR and uniform), but since it's a breaking space, punctuation can orphan onto its own line in narrow dialogs — if you ever touch it, convert to U+00A0/U+202F rather than dropping the space.

## Verdicts

- **Register:** clean — formal vous, no slips found.
- **Terminology:** consistent — paquet (Anki deck, matches AnkiDroid fr) cleanly separated from pack de langue; carte, raccourci, synthèse vocale, capture d\'écran, réseau facturé à l\'usage all uniform and Android-aligned.
- **Android-settings wording:** weakest area — three mismatches with stock French (Applications installées, Par-dessus les autres applis, Paramètres rapides), all easy renames.
- **Legal text:** body is strong (list, §5(b), warrant force, dual negation all correct) but the Accepter / J\'accepte button mismatch must be fixed before ship.
- **Truncation:** no problems; all bottom-bar and two-line labels within budget.
- **Overall:** fix-then-ship — two ❌ (legal button name, secondes agreement) plus the settings-wording cluster; with the caveat that this was a targeted pass over a hotlist, not a full review.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR sets `one`+`other` on both `yomitan_import_summary_count` and `yomitan_import_summary_more`; `<xliff:g>` inner contents byte-identical to EN; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| anki_content_pitch_position_desc, anki_content_frequency_values_desc, anki_content_frequency_stylized_desc, anki_content_frequency_harmonic_desc | ⚠️ | …le champ “PitchPosition” de Lapis…, …“Frequency” de Lapis…, …son champ “FrequenciesStylized”…, …“FreqSort” de Lapis ou “FrequencySort” de JPMN… | replace the curly `“ ”` with guillemets `« »` (e.g. « PitchPosition », « FreqSort ») | One recurring fix across all 4 new desc strings. The Anki field *names* are correctly kept as-is — only the **quote glyphs** are the issue. This sync introduced the file's first-ever curly `“ ”` (6 occurrences, all on these 4 lines); the rest of the file uses `« »` (17×), and the already-reviewed sibling flag-descs right below quote Anki field names with guillemets — `« Is Vocabulary Card » de Migaku` (588), `« IsSentenceCard » de Lapis` (591), `« IsTargetedSentenceCard » de JPMN` (594). Same content, two punctuation conventions → align on `« »`. (EN uses `“ ”`; French localizes the quotes, doesn't copy them — exactly what the prior translator did.) |
| llm_backend_base_url_invalid | 💬 | « …http:// n\'est autorisé que pour une adresse locale ou réseau local » | « …pour une adresse locale ou de réseau local » (or « …ou sur le réseau local ») | `ne…que` is correct and natural; the tail « ou réseau local » is a bare noun phrase that doesn't grammatically hook onto « adresse » (no linking preposition/adjective), so it reads telegraphically. Meaning is clear in a terse inline error, hence nit. `réseau local` is the right expansion of "LAN". |
| yomitan_import_summary_duplicates | 💬 | « Déjà importés : <xliff…>%1$s</xliff…> » | « Déjà importés : » is fine as a list label; if the single-name case bothers, « Déjà présents : » sidesteps the agreement | EN « Already imported: » is agreement-neutral; the FR participle « importés » is forced plural, so a one-name list (%1$s = "JMdict") shows « Déjà importés : JMdict ». The three sibling summary lines avoid this — « Lecture impossible : », « Espace insuffisant : », « Échec : » are all invariant. Defensible as a list-category header; flagged only for the single-item edge. |

## Clean areas (delta)
- **Apostrophe escaping:** clean — zero raw `'` in any of the 29 keys; every elision escaped `\'` (`l\'accent`, `L\'évaluation`, `n\'est`, `d\'API` in the neighboring label). No build risk.
- **Space-before-punctuation:** consistent with the file's established convention — a regular space (U+0020), not NBSP/NNBSP, precedes every French `:`/`?` in the new strings (`Exemple : 0,2`; `Déjà importés : `; `Lecture impossible : `; `Espace insuffisant : `; `Échec : `). The whole file still has zero U+00A0/U+202F, so the sync didn't break uniformity. (Same standing caveat as the main review: correct fr-FR, but a breaking space can orphan punctuation onto its own line — if ever migrated, do it file-wide.)
- **`Exemple :` / sample rule:** followed — `anki_content_pitch_position_desc` renders « Exemple : 0,2 » (the `0,2` sample left as-is, matching EN); the `★`/`★★★` glyph and the literal field names (PitchPosition, PAOverride, Frequency, FrequenciesStylized, FreqSort, FrequencySort) are all preserved untranslated. Samples not flagged.
- **vous register:** clean — no tu/ton/ta/tes slip; the only imperatives in scope are noun/infinitive titles and « Utilisez https:// » (vous-form). Consistent with the rest of the file.
- **Terminology reuse:** consistent — « accent tonal » (matches `yomitan_category_pitch_accent` 1190), « fréquence » (matches `yomitan_category_frequency` 1188), « dictionnaire », « importer/importation », « synthèse vocale » (matches the parameters term + line 560), « Espace insuffisant » (matches `yomitan_no_space_title` 1212), « mot mis en évidence » (matches 562/566/575). Brands untouched: Lapis, JPMN, Migaku, PlayTranslate, Wikimedia Commons, Yomitan, OpenAI. « Avancé » and « URL personnalisée » are the standard Android renderings; « URL » is feminine so « personnalisée » agrees.
- **Plurals:** both correct for French (where `one` covers 0 and 1). `yomitan_import_summary_count` keys agreement to the **total** noun (per EN comment): `one` → « %1$d dictionnaire sur %2$d importé. » (singular noun + « importé » hold for total=1, incl. imported=0), `other` → « …dictionnaires…importés. » plural. `yomitan_import_summary_more`: `one` « +%1$d autre », `other` « +%1$d autres » — « autre(s) » agreement correct; only fires for count≥1.
- **Placeholder grammar:** `yomitan_importing_progress` « Importation de %1$d sur %2$d… » keeps EN's deliberate noun-omission, so no agreement trap; the `%1$s` name-list strings sit after a colon (`Lecture impossible : %1$s`), so the runtime value needs no article/elision.
- **Short-label truncation:** no risk — « Avancé », « Audio », « Aucun résultat », « Chargement… », « Synthèse vocale », « Mise à jour automatique », « Fichier inconnu » are all short or sit on roomy toggle/section rows; none is a bottom-bar 8sp label.
- **Naturalness:** reads native, no calques — possessive « X's "Field" » correctly restructured to « le champ "Field" de X » throughout; « À utiliser uniquement sur les cartes JPMN », « plus bas = plus fréquent », « X sur Y » all idiomatic. (« Un nombre unique » for "a single number" leans toward "single" here and is fine; « un seul nombre » would be marginally less ambiguous — not flagged.)

## Verdict (delta)
- Ship-ready after the one ⚠️ punctuation alignment (curly `“ ”` → `« »` on the 4 Anki desc strings) for file-internal consistency; the two 💬 are optional polish. No ❌, no 🛑.

---

# Delta review — 2026-07-14 sync

*(Independent review, 174 delta keys. Reviewer did not write these translations.)*

Mechanical layer verified programmatically across all 174 keys: **apostrophes 0 unescaped / 0 typographic** (file-wide: 233 × `\'`, zero raw `'` — no build risk); every `%n$s`/`%d` placeholder present and matching; all `<xliff:g>` inner contents + `id`/`example` byte-identical to EN; `\n` preserved (`floating_menu_capture_screen` = `Capture\nd\'écran`, still two lines); the eight bare `{token}` keywords byte-identical Latin in running prose; `<plurals>` categories = `one`+`other`; no `name=` touched. **No 🛑 build-breaking issues.**

Two of the findings below are **code** defects, not `values-fr` defects — they surfaced only by dropping real runtime values into the delta strings, exactly as the brief asked. They cannot be fixed by editing the locale file, and they affect **every non-English locale**.

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `update_dialog_size_note`, `update_error_no_space`, `settings_ocr_disable_manga_msg` | ❌ **[CODE — not values-fr]** | renders « Taille du téléchargement : 128 **MB** », « …(230 **MB** requis) », « …(68 **MB**) » | « 128 **Mo** », « 230 **Mo** », « 68 **Mo** » | `humanSize()` (`translation/llm/LlmModelUtils.kt:11-16`) hardcodes `"GB"`/`"MB"`/`"KB"`. The FR parameters doc pins **Go** as the French unit. Because `"%.2f GB".format(…)` passes no explicit Locale, the *number* already localizes but the unit does not → the hybrid « 1,23 GB ». **Fix in Kotlin:** `android.text.format.Formatter.formatShortFileSize(ctx, bytes)` — locale-aware (yields « 230 Mo » / « 1,23 Go ») and **already used in this codebase** at `YomitanSettingsActivity.kt:243`, so `humanSize()` is the outlier. The prior FR review's "every ` GB` hit is inside `example=` (never rendered)" is now falsified: these three delta keys are the first to surface `humanSize()` in a French dialog. Affects all 11 non-EN locales. |
| `game_audio_trim_duration` | ❌ **[CODE — not values-fr]** | renders « Sélection : **2.4** s · Enregistrement : 147 s » | « Sélection : **2,4** s · … » | Both call sites pin `Locale.US`: `SentenceAnkiContentFragment.kt:294` and `GameAudioTrimActivity.kt:206` — `String.format(Locale.US, "%.1f", …)`. French requires the decimal **comma**; the rest of the app already assumes it (committed `anki_content_pitch_position_desc` renders « Exemple : 0,2 »). **Fix:** `Locale.getDefault()` at both sites. Affects fr/de/es/pt-BR/ru/tr/ar. — Credit where due: the *string* is well-built. Restructuring EN's "2.4 s selected" into the noun-readout « Sélection : … · Enregistrement : … » sidesteps the past-participle agreement that a literal rendering would have forced. No agreement bug here. |
| `update_dialog_metered_note` | ⚠️ | « Vous êtes **connecté** à un réseau facturé à l\'usage. » | « Vous êtes **sur** un réseau facturé à l\'usage. » | The participle agrees with the *user's* gender — a female user should read « connectée ». This is the **only** `vous êtes` + past-participle construction in the whole file, so it is a one-off exposure introduced by this sync, not an established pattern. EN is "You're **on** a metered connection" — « sur » is both closer to the source and gender-neutral. « réseau facturé à l\'usage » itself is correct (matches the parameters doc). |
| `history_toggle_subtitle` | ⚠️ | « **Enregistrer** les phrases capturées sur cet appareil » | « **Enregistre** les phrases capturées sur cet appareil » | Switch-row subtitles in this file take the **3rd-person indicative** — committed `settings_vertical_grow_subtitle` « **Agrandit** les cadres étroits… », and this very sync's `anki_game_audio_row_subtitle` « **Conserve** les dernières minutes… » and `settings_llm_context_subtitle` « …**Fournit** aux traducteurs LLM… ». `history_toggle_subtitle` is the lone switch subtitle left in the infinitive. (Infinitive/imperative is the file's form for *action* rows — `settings_debug_export_logs_subtitle`, `quick_tile_add_row_subtitle` — which this is not.) |
| `settings_debug_log_trace` | ⚠️ | « Enregistrer la trace du **journal** de traduction » | « Enregistrer la trace de l\'**historique** de traduction » | **Term collision.** In this same Settings → Debug section « journal » already means *debug log*: `settings_debug_show_detection_log` « Afficher le **journal** de détection », `toast_no_logs_to_share` « Aucun **journal** à partager », `settings_debug_log_pinhole`/`_grouping` « **Journaliser**… ». But the thing this traces is the *translation log* feature, whose user-facing French name is « **Historique** » (`history_screen_title`, `settings_cell_history`). As written, a French reader parses it as "a trace of the translation **debug-log**" — the wrong referent, and it severs the tie to the feature it belongs to. |
| `misc_slur` | ⚠️ | « Injurieux » (beside `misc_offensive` « Offensant ») | « **Insulte** » | The offensiveness cluster must stay internally distinguishable — these chips render **side by side on the same word**. « Péjoratif » and « Vulgaire » are clean and distinct, but « Offensant » and « Injurieux » are near-synonyms in French (both = insulting/offensive) and a reader cannot tell which is stronger. « Insulte » is a **noun** — a slur *is* an insult-word — so it contrasts cleanly against the adjective « Offensant », and the chip family already mixes nouns freely (« Onomatopée », « Néologisme », « Euphémisme », « Argot »). Keep « Offensant » as-is. |
| `llm_prompt_discard_confirm`, `llm_prompt_discard_title` | ⚠️ | « Abandonner » / « Abandonner les modifications ? » | **keep as-is** — fix the committed string instead | One English term ("Discard"), two French words: the committed `crash_dialog_discard` is « **Ignorer** ». **The delta made the right call** and should not be touched: « Ignorer » is already this file's word for **Skip** (`update_dialog_skip` = « Ignorer cette version »), so reusing it for Discard would triple-load it and blur it against Cancel in a confirm dialog. The residual inconsistency lives in the *committed* string; if one word for "Discard" is wanted, align `crash_dialog_discard` → « Abandonner » (outside this delta's scope). Logged so the drift is not silently re-introduced in round 2. |
| `anki_game_audio_cell_untrimmed` | ⚠️ | « Rogner en enregistrant la carte » | « **Rognage à l\'enregistrement** » | This is the sentence-audio row **title** — it occupies the exact slot that `game_audio_trim_duration` otherwise fills with a noun readout (« Sélection : … · Enregistrement : … »). An infinitive in that slot reads as a tappable *command* ("Trim…"), not as the status "this will be trimmed when you save". The noun phrase matches its sibling's grammatical form and is 5 characters shorter. |
| `ocr_picker_message` | ⚠️ | « L\'OCR **est ce qui extrait** le texte d\'une capture d\'écran. » | « L\'OCR **sert à extraire** le texte d\'une capture d\'écran. » | Word-for-word calque of "OCR is what extracts text from…". French does not explain a function with « est ce qui + verbe »; it reads as MT. Second sentence (« Différents outils peuvent mieux convenir à différentes polices ») is fine, and « polices » is the right word for fonts. |
| `settings_ocr_use_manga_subtitle` | ⚠️ | « AVERTISSEMENT : **haute qualité, mais lent**. » | « AVERTISSEMENT : **de haute qualité, mais lent**. » | « haute qualité » is a feminine *noun phrase*; « lent » is a masculine *adjective* predicated of MangaOCR. Coordinated with a bare comma they share no head, so the adjective looks like a failed agreement — a classic MT tell in French. Adding « de » makes both halves adjectival phrases about MangaOCR and the sentence resolves. (« le mode automatique » in the same string is **correct** — it matches the committed `settings_overlay_mode_subtitle` / `settings_hide_overlays_during_auto_mode`, which render EN "auto mode" the same way. Not a finding.) |
| `misc_yojijukugo` | 💬 | « Composé de quatre caractères » (28 chars) | « Composé de 4 caractères » (23) | Longest chip in the family by 2.3×: the longest committed `pos_*` chip is 12 (« Interjection », « Spécificatif ») and the next-longest `misc_*` is 16 (« Langage masculin »). Chips are width-constrained. Correctly *not* romanized as "yojijukugo", per the glossary — only the length is at issue. Drop if the chip is confirmed to wrap. |
| `tr_service_status_usage_today_fmt` | 💬 | « Aujourd\'hui : %1$s **jetons** » | « Aujourd\'hui : %1$s **tokens** » | The file keeps « prompt » and « LLM » as English loanwords; « jetons » is the only LLM term that got translated, and French LLM users read "tokens" on every provider dashboard. Defensible either way — « jetons » is the officially recommended French term — but it is inconsistent with the file's own loanword policy. |
| `llm_prompt_discard_message` | 💬 | « **Vos modifications de ce prompt** n\'ont pas été enregistrées. » | « Vos modifications **apportées à** ce prompt n\'ont pas été enregistrées. » | « modifications **de** ce prompt » is a loose genitive that can momentarily read as "modifications *of the kind that this prompt is*". The verb « apporter des modifications à » is the natural collocation. |
| `update_unknown_sources_message` | 💬 | « …sur l\'écran de paramètres **qui va s\'ouvrir**. » | « …sur l\'écran de paramètres **qui s\'ouvre**. » | Periphrastic future is heavy here; the simple present is what French UI copy uses. Optional: the screen this opens is titled « Installer des applications inconnues » in French Android and its toggle is « Autoriser depuis cette source » — naming the toggle would make it findable, though EN doesn't name it either, so this is not a mismatch against the source. « Ouvrir les paramètres » on the button is correct. |

## Clean areas (delta — checked, no findings)

- **Apostrophe escaping (the French build-breaker):** flawless. All 174 keys checked character-by-character — **zero** raw `'`, zero typographic `’`; every elision escaped (`l\'`, `d\'`, `n\'`, `qu\'`, `s\'`, `j\'`). File-wide: 233 × `\'`, 0 unescaped. Consistent with the file's existing convention.
- **Punctuation spacing:** all 14 delta strings containing `:` `?` `!` carry the French space, none missing. Still plain U+0020 (0 × NBSP/NNBSP file-wide) — the sync did not break the file's uniformity. Same standing caveat as the main review.
- **Quote glyphs:** the delta introduced **no** curly `“ ”` (the ⚠️ from the 2026-06-23 sync did not recur). No guillemets needed in these 174.
- **`settings_yomitan_count_summary` plurals:** correct, including the zero case. French `one` covers **0 and 1** — `one` = « %d dictionnaire importé » renders « **0 dictionnaire importé** » (French takes the singular after 0 ✓) and « 1 dictionnaire importé » ✓; `other` = « %d dictionnaires importés » ✓. The participle agrees with the counted noun in both, so the Russian-style wrong-number agreement bug is absent. (The 0 case is also unreachable — `settings_yomitan_empty_summary` covers it — but the form is right regardless.)
- **Agreement around placeholders — all four the brief named, plus the rest, are safe.** `update_error_no_space`: « (230 Mo **requis**) » — "requis" is invariant masc-sg/masc-pl and *every* byte unit is masculine (octet/Mo/Go), so it can never disagree. `tr_service_remove_title_fmt`: « Retirer OpenAI ? » — imperative + proper noun, no agreement surface. `settings_ocr_disable_manga_msg`: « le modèle **téléchargé** (68 Mo) » — agrees with the fixed « modèle », not the placeholder. `game_audio_trim_duration`: noun+colon restructure, no participle at all. `llm_prompt_advisory_foreign_token`: « {text} n\'est pas **rempli**… **envoyé** tel quel » — a mentioned token defaults to masculine singular ✓. `stream_kind_prompt_title`: « Quelle option de partage avez-vous **choisie** ? » — correct *avoir* + preceding-COD agreement, a subtle one they got right.
- **The "phrase" false friend — handled perfectly.** « phrase » appears **only** where EN says *sentence* (`history_toggle_subtitle`, `settings_cell_history_summary_*`, `history_empty_off`, « cartes de phrase »); the OCR fragments EN calls *phrases* are consistently « **expression** » (`llm_prompt_fatal_missing_text/_strings`, `llm_prompt_kw_strings_desc`, `llm_prompt_kw_count_desc`, `llm_prompt_row_translation_subtitle`, `llm_prompt_advisory_missing_count`). The committed `pos_phrase` = « Locution » is the third sense and is also right. No collision anywhere.
- **`prompt` / `requête` / `invite`:** no collision. « prompt » is the single noun across all `llm_prompt_*` (« Prompt système », « Prompt de traduction », « Prompt de lot », « Le prompt ne peut pas être vide », « Vérifiez ce prompt »). « requête » appears **only** in the two subtitles where EN itself says *"The **request** …"* (`llm_prompt_row_batch_subtitle`, `llm_prompt_row_translation_subtitle`) — it mirrors EN's own two-word split rather than introducing a rival term. « invite » (the Académie's term) is correctly not used: « prompt » is what French LLM users say.
- **`Ignorer`:** the trap was avoided — the delta does **not** use « Ignorer » for Discard (see the ⚠️ above for the residual committed-file drift).
- **The 38 `misc_*` chips — three of the four clusters are clean and internally distinguishable.** Obsolescence: « Archaïque » / « Obsolète » / « Vieilli » / « Historique » — four distinct, and *vieilli* is the genuine Petit-Robert label. Informality: « Familier » / « Informel » / « Intime » / « Argot » — four distinct; using *familier* (the real `fam.` label) for **colloquial** and pushing English "familiar" to « Intime » is the right call, not a false-friend slip. Honorifics: « Honorifique » / « Modeste » / « Poli » — distinct, and *modeste* is the conventional French rendering of 謙譲語. Only the offensiveness cluster needs a fix (⚠️ above). Register and brevity match the committed `pos_*` family; these read as real French lexicographic labels (Soutenu, Familier, Vieilli, Péjoratif, Figuré, Ironique, Onomatopée, Littéraire) rather than glosses.
- **`misc_female_speech` / `misc_male_speech` — the grammatical-gender confusion trap is avoided.** « **Langage** féminin » / « **Langage** masculin », not « Terme féminin/masculin ». « Langage » heads them unambiguously as *speech register*, and the neighbouring `pos_*` chips are bare (« Nom », « Adjectif ») with no gender marking of their own, so there is nothing to collide with. This is also the correct French label for the JMdict `fem`/`male` tags.
- **`misc_kana_only` / `misc_kanji_only`:** kana/kanji kept as loanwords per the glossary ✓.
- **`ocr_source_label` mirrors `translation_source_label`** — the glossary's hard constraint. Committed « **Traduit par** %1$s » → new « **Reconnu par** %1$s ». Same structure, same participle+`par` frame; « Reconnu » ties to *reconnaissance de texte* (OCR). Exactly right.
- **"Captured" is one verb.** « capturer » / « les phrases **capturées** » (`history_toggle_subtitle`, `settings_cell_history_summary_*`) / « les phrases que PlayTranslate **capture** » reuses the file's screen-capture verb (« capture d\'écran », « capturer l\'écran de jeu »); no second verb introduced. `settings_cell_history_summary_*` also correctly reads EN's "Record" as the **noun** (« Registre des phrases capturées »), not the verb — an easy trap.
- **Auto labels — the delta correctly reuses the committed ones.** `hotkey_auto_translation_dialog_title` = « Traduction auto » byte-matches committed `live_mode_auto_translate_label`; `hotkey_auto_hint_dialog_title` = « Auto %1$s » byte-matches committed `live_mode_auto_with_hint`. The apparent asymmetry (« Traduction auto » vs « Auto Furigana ») is inherited from the committed file and was explicitly blessed by the previous review. `settings_ocr_use_manga_subtitle`'s « mode automatique » likewise matches the two committed "auto mode" strings. The translator grepped the file — no drift.
- **Glossary terms, all verified against the committed file:** Remove vs Delete kept apart (« **Retirer** » for services / « **Supprimer** » for entries + models) ✓; Clear = « Effacer » (matches `btn_clear`) ✓; History = « Historique » ✓; Translation service = « service de traduction » (matches `settings_cell_translation_services` « Services de traduction ») ✓; Provider = « Fournisseur » ✓; keyword = « Mots-clés » ✓; LLM kept as-is ✓; Trim = « Rogner » + « la sélection » ✓; Game audio = « Audio du jeu » (works as pill *and* section header) ✓; update = « mise à jour » ✓; metered = « réseau facturé à l\'usage » (matches the parameters doc verbatim) ✓; overlay-mode = « Superpositions » (matches committed `settings_overlay_mode_*`) ✓; region = « zone » (matches `nav_regions` « Zones ») ✓.
- **Deliberate decisions respected:** `stream_kind_share_one_app`/`_entire_screen` left as the AOSP-matching « Partager une appli » / « Partager tout l\'écran »; `llm_prompt_kw_source_desc`/`_target_desc` correctly keep « par ex. **Japanese** » / « par ex. **English** » in Latin; `llm_status_low_memory_badge` untouched. None flagged.
- **Register:** clean — **0** tu/ton/ta/tes/toi hits across all 174; the 7 vous-form strings are consistent with the file.
- **Truncation:** `service_llm_badge` = « LLM » ✓; `probe_initializing` = « Initialisation… » (15 ch, no shorter accurate French exists) ✓; `floating_menu_capture_screen` = « Capture\nd\'écran » — two lines, and a noun phrase matching its sibling « Zone de capture » ✓. Only `misc_yojijukugo` is over budget (💬).
- **Ellipses:** U+2026 in both `probe_initializing` and `update_progress_verifying` ✓.

## Verdict (delta)

- **0 🛑.** The mechanical layer — apostrophes above all — is genuinely clean; this file will build.
- **2 ❌, both in Kotlin, not in `values-fr`.** The French *strings* have no mistranslation. But `humanSize()` puts English size units (« 230 MB ») into three French dialogs, and a pinned `Locale.US` puts an English decimal point (« 2.4 s ») into a fourth. Both are cross-locale and both have a one-line fix; `Formatter.formatShortFileSize` is already used elsewhere in the codebase. These are the highest-value items in this review and they are invisible to a strings-only pass.
- **8 ⚠️**, of which the two worth applying first are the `journal` collision in `settings_debug_log_trace` (wrong referent) and the `misc_slur`/`misc_offensive` indistinguishability (the chips render together). `update_dialog_metered_note` is the file's only gender-agreement exposure on the user and has a free fix.
- **Ship-ready on the strings side** after the ⚠️ pass. The translation is strong: the placeholder-agreement traps were all sidestepped by restructuring rather than guessed at, the *phrase*/*expression*/*locution* three-way split is exactly right, and the committed file was clearly grepped before new terms were invented.

---

# Delta review round 2 — 2026-07-14

*(Fresh independent reviewer. Did not write these translations and did not perform round 1. All 174 delta keys re-derived from scratch; the 11 strings round 1 changed were re-derived against their render sites, not just their diffs.)*

Mechanical layer re-verified programmatically across all 174 keys, with the **11 edited strings checked character-by-character** (an edit is exactly where a raw `'` creeps in): **236 × `\'`, 0 raw `'`, 0 typographic `’`** file-wide. Every `%n$s`/`%d` present and matching EN; all `<xliff:g>` inner contents + `id`/`example` byte-identical; the 8 bare `{token}` keywords intact; `\n` counts match; `<plurals>` = `one`+`other`; 0 curly quotes; 15/15 French spaces before `:`/`?`/`!`; 0 tu/toi register slips. **No 🛑 — the file builds.**

## Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `anki_game_audio_cell_untrimmed` | ❌ | « Rognage à l\'**enregistrement** » | « Rognage à l\'**enregistrement de la carte** » | **Regression introduced by the round-1 fix.** Round 1 replaced « Rogner en enregistrant **la carte** » with « Rognage à l\'enregistrement », correctly moving to a noun phrase — but it dropped the object, and « enregistrement » is already this feature's word for **recording**: `audio_source_game_enable_hint` « **Enregistrement** désactivé », `anki_game_audio_permission_denied` « l\'**enregistrement** de l\'audio du jeu », and `game_audio_trim_duration` « Sélection : 2,4 s · **Enregistrement** : 147 s ». That last one is decisive: `SentenceAnkiContentFragment.refreshSentenceAudioTitle()` (line 280-300) writes **both strings into the same `TextView`** (`sentenceAudioHandle.titleView`) — `anki_game_audio_cell_untrimmed` when the range is null, `game_audio_trim_duration` when it isn't. So the identical widget alternates between « …**Enregistrement** : 147 s » (= the recording) and « Rognage à l\'**enregistrement** » (= at save time). A French reader parses the second as *"trimming on the recording"* — a plausible but wrong referent that loses the *when*. Restoring « de la carte » disambiguates, keeps round-1's noun-phrase form, and ties to the actual button `anki_save_button_label` « **Enregistrer la carte** Anki ». Still shorter than the sibling readout it shares the slot with. |
| `game_audio_trim_use_tts`, `game_audio_trim_save` | ❌ **[LAYOUT — root cause in code]** | « Utiliser plutôt la synthèse vocale » + « Utiliser la sélection » → row = **530.7dp** | layout fix (below); interim: « **Synthèse vocale** » + « **Utiliser** » → 351.6dp | The bottom action bar of `activity_game_audio_trim.xml` is a rigid horizontal `LinearLayout` (12dp padding) with three `wrap_content` MaterialButtons, no ellipsize, no scroll, no shrink weights. Measured with the real **Roboto Medium at 14sp** + M3 `LabelLarge` tracking (0.00714286em) and the real Material 1.14 paddings (TextButton 12+12dp, filled 24+24dp, `minWidth` 88dp from `Widget.AppCompat.Button`): the French row needs **530.7dp**. **360dp phone → overflows by 170.7dp; `game_audio_trim_save` is laid out at x=348→517 and only 12.0dp of its 169.4dp is on screen (7%) — the primary confirm is a sliver at the screen edge, effectively unreachable.** **411dp phone → overflows by 119.7dp; the confirm is 61.7dp of 169.4dp visible (36%), label cut mid-word.** The weighted `<Space>` does **not** save it: it absorbs *slack* and clamps to 0 on overflow, and because a weighted child precedes the confirm, LinearLayout measures the confirm with `usedWidth = 0` (at its full natural width) and then lays it out off the edge. **This is not a French defect** — EN itself needs 377.4dp and overflows a 360dp phone by 17.4dp. See the cross-locale table below. |
| `update_dialog_metered_note` | 💬 | « Vous êtes **sur** un réseau facturé à l\'usage. » | « Vous **utilisez** un réseau facturé à l\'usage. » | Round 1's fix was right to kill « connecté » (it agreed with the *user's* gender — a female user read « connectée »), and « sur » is indeed gender-free. But « être **sur** un réseau » is a calque of EN "on a network"; in French « être sur » collocates with *social* networks (« je suis sur Facebook »), not connectivity. « Vous utilisez… » is idiomatic, equally gender-free, and is the construction Google's own French copy uses. Net: round 1 traded a ⚠️ for a 💬 — this only finishes the job. |
| `llm_prompt_discard_message` | 💬 | « **Vos** modifications **apportées à** ce prompt n\'ont pas été enregistrées. » | « **Les** modifications apportées à ce prompt n\'ont pas été enregistrées. » | Round 1 correctly replaced the loose genitive « modifications **de** ce prompt » with the natural collocation « apporter des modifications **à** ». But « **apportées** » already implies the agent (« les modifications que *vous avez* apportées »), so stacking the possessive « Vos » on top is redundant. Drop one: either « Les modifications apportées à ce prompt… » or plain « Vos modifications n\'ont pas été enregistrées. » Meaning is unaffected — pure polish. |

## The trim-button row — measurement

Requested number, French, 14sp, fontScale 1.0. Method: real **Roboto Medium** (upem 2048, sfnt-verified) shaped with HarfBuzz, cross-validated against a `fontTools`/`hmtx` advance sum — the two agree within **0.32dp** on every label. Row model traced faithfully through `LinearLayout.measureHorizontal` + `layoutHorizontal`.

| button | style | text | + padding | button |
|---|---|---|---|---|
| `game_audio_trim_use_tts` « Utiliser plutôt la synthèse vocale » | TextButton | 206.1dp | 12+12 | **230.1dp** |
| `game_audio_trim_no_audio` « Aucun audio » | TextButton | 79.2dp | 12+12 | **103.2dp** |
| `game_audio_trim_save` « Utiliser la sélection » | filled | 121.4dp | 24+24 | **169.4dp** |

**Total row = 24 (row padding) + 230.1 + 4 (margin) + 103.2 + 169.4 = 530.7dp.**

| phone | overflow | `game_audio_trim_save` reachable? |
|---|---|---|
| **360dp** | **+170.7dp** | **NO** — 12.0dp of 169.4dp visible (**7%**). Laid out at x=348→517 on a 360dp row. A sliver at the edge, far below the 48dp touch minimum, label invisible. (`game_audio_trim_no_audio` is also squeezed to 101.9dp and wraps to two lines.) |
| **411dp** | **+119.7dp** | **Partially** — 61.7dp of 169.4dp visible (**36%**). Tappable but clipped; the label reads as a cut-off « Utilise… ». |

At 360dp the row **cannot** be made to fit while keeping « Utiliser la sélection » (169.4dp): that leaves 59.4dp for the TTS button, below its 88dp `minWidth` floor. **Both** strings must shrink. « Synthèse vocale » + « Utiliser » = 351.6dp, which fits 360dp — but with only **8dp of slack**, and it overflows again at fontScale 1.15. That is the tell: **the layout is the root cause, not the strings.**

**Cross-locale** (same method; CJK/Thai/Arabic need non-Roboto fonts and were not measured):

| locale | row | 360dp | 411dp |
|---|---|---|---|
| pt-BR | 337.4dp | fits (+22.6) | fits |
| en | 377.4dp | **over by 17.4** (save 96%) | fits |
| es | 471.8dp | over by 111.8 (save 36%) | over by 60.8 |
| vi | 487.4dp | over by 127.4 (save 33%) | over by 76.4 |
| **fr** | **530.7dp** | **over by 170.7 (save 7%)** | **over by 119.7 (save 36%)** |
| ru | 533.0dp | over by 173.0 (save 24%) | over by 122.0 |
| tr | 564.2dp | over by 204.2 (save 9%) | over by 153.2 |
| de | 570.3dp | over by 210.3 (save 7%) | over by 159.3 |

**7 of 8 measurable locales overflow, and English overflows a 360dp phone.** The single locale that fits is **pt-BR** — the one that already took the deliberate « Usar TTS » width exception. That is not a coincidence; it is the control. Shortening seven locales' strings to fit a row that already fails in its source language is band-aiding: **fix `activity_game_audio_trim.xml`** (give the buttons `layout_weight` + `android:ellipsize`, or stack the secondary actions, or let the bar wrap to two rows), and the string question disappears in every locale at once.

## Clean areas (round 2 — re-derived, no findings)

- **Apostrophe escaping — the build-breaker, and the thing an edit most often breaks.** All 11 round-1 edits verified individually: `settings_debug_log_trace` (1 × `\'`), `update_dialog_metered_note` (1), `anki_game_audio_cell_untrimmed` (1), `ocr_picker_message` (3), `settings_ocr_use_manga_subtitle` (1), `error_capture_blocked_secure` (2), `tr_service_status_usage_today_fmt` (1), `llm_prompt_discard_message` (1), `update_unknown_sources_message` (6), plus `misc_slur`/`history_toggle_subtitle` (0, correctly). **Zero raw, zero typographic.** The fixes did not regress the mechanical layer.
- **The other 9 round-1 fixes are correct and introduced no collision.** `settings_debug_log_trace` → « historique » now points at the right feature (`history_screen_title`/`settings_cell_history` = « Historique ») and no longer collides with the Debug section's « journal » (= debug log); at 50 chars it sits inside the budget its committed neighbour `settings_debug_save_ocr_seed` (46) already occupies. `misc_slur` → « Insulte » is distinct from all 37 other chips. `error_capture_blocked_secure` → « l\'appli capturée » now **matches** its sibling `error_single_app_not_fullscreen` verbatim — the fix aligned them. `ocr_picker_message` → « sert à extraire » kills the calque. `settings_ocr_use_manga_subtitle` → « de haute qualité, mais lent » makes both halves adjectival so « lent » resolves against MangaOCR. `tr_service_status_usage_today_fmt` → « tokens » matches the file's loanword policy (« prompt », « LLM »). `history_toggle_subtitle` → « Enregistre » joins the 3rd-person switch-subtitle pattern. `update_unknown_sources_message` → « qui s\'ouvre ».
- **`tr_service_status_usage_today_fmt` is *not* a third code defect.** I checked, expecting one: `UsageTracker.todayString()` (line 61) uses `NumberFormat.getNumberInstance(Locale.getDefault())`, so the count renders « 12 345 tokens » with the French thousands space. Unlike `humanSize()` and the `Locale.US` seconds, this call site is already correct.
- **The 38 `misc_*` chips: all distinct, no `.distinct()` collapse.** Verified programmatically — 38 unique strings, no duplicate even under case/accent folding, and no collision with any `pos_*` label (they render on the same word). The four clusters remain internally separable after the `misc_slur` edit: offensiveness « Offensant / Péjoratif / Vulgaire / **Insulte** / Sensible » (the noun « Insulte » now contrasts cleanly against the adjective « Offensant » — round 1's call holds); obsolescence « Archaïque / Obsolète / Vieilli / Historique »; informality « Familier / Informel / Intime / Argot »; honorifics « Honorifique / Modeste / Poli ». « Argot » / « Argot Internet » / « Argot manga » co-render as distinct chips, mirroring EN. Per the brief, `misc_yojijukugo`'s length is **not** flagged — `buildMiscRow` wraps, and accuracy beats brevity.
- **The `-er` 3rd-person/tu-imperative ambiguity — checked and cleared.** « **Enregistre** » (`history_toggle_subtitle`) and « **Conserve** » (`anki_game_audio_row_subtitle`) are formally identical to the *tu*-imperative, which in a vous app would be a register break. But the descriptive reading is forced by the row's function and by the file's established pattern (committed `settings_vertical_grow_subtitle` « Agrandit », and this delta's « Fournit » — both `-ir`/`-re` verbs where the two forms differ, proving the intent is 3rd-person). No register slip; 0 tu/ton/ta/tes/toi hits across all 174.
- **`settings_yomitan_count_summary` plurals, read at every band.** The delta's only `<plurals>`. French `one` covers 0 **and** 1: n=0 → « 0 dictionnaire importé » (French takes the singular after 0 ✓), n=1 → « 1 dictionnaire importé » ✓, n≥2 → « N dictionnaires importés » ✓. Participle agrees with the counted noun in both forms.
- **Agreement around placeholders, re-derived with real runtime values.** `update_error_no_space` « (230 Mo **requis**) » — invariant masc-sg/pl, and every byte unit is masculine, so it cannot disagree. `settings_ocr_disable_manga_msg` « le modèle **téléchargé** (68 Mo) » — agrees with the fixed noun, not the placeholder. `tr_service_remove_title_fmt` « Retirer OpenAI ? » — no agreement surface. `stream_kind_prompt_title` « …avez-vous **choisie** ? » — correct *avoir* + preceding-COD agreement. `llm_prompt_advisory_foreign_token` « {text} n\'est pas **rempli**… » — mentioned token defaults masc-sg ✓. `game_audio_trim_duration` — the noun-readout restructure sidesteps participle agreement entirely.
- **Punctuation & glyphs unchanged by the fixes:** 15/15 French spaces before `:`/`?`/`!` (still plain U+0020, 0 NBSP file-wide — the standing caveat from the main review is untouched); 0 curly quotes (the 2026-06-23 `“ ”` issue did not recur); 2 × U+2026, no `...`; 7 em dashes, all locale-exempt.
- **Known code defects, not re-filed:** `humanSize()` still renders « 230 **MB** » in `update_dialog_size_note` / `update_error_no_space` / `settings_ocr_disable_manga_msg`, and `Locale.US` still forces « 2**.**4 s » in `game_audio_trim_duration`. Both already reported. The French strings around them are correct.

## Verdict (round 2)

- **0 🛑** — the mechanical layer survived the edits intact; the file builds.
- **2 ❌.** One is a genuine round-1 **regression**: `anki_game_audio_cell_untrimmed`'s « enregistrement » now means *recording* in three sibling strings, one of which shares the identical `TextView` — a one-word fix (`de la carte`) restores it. The other is a **layout** defect the strings merely expose: the trim confirm button is **7% visible on a 360dp phone** in French, and the row overflows in 7 of 8 measurable locales including English.
- **0 ⚠️, 2 💬.** Round 1's ⚠️ pass genuinely closed out; the two remaining nits are polish on fixes that already achieved their goal.
- **FIX FIRST** — on `anki_game_audio_cell_untrimmed` (locale, one word) and on `activity_game_audio_trim.xml` (code, cross-locale). The strings are otherwise ship-ready.

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
| `settings_ocr_note_mlkit` | ⚠️ | "Rapide, même sur les écrans chargés en texte" | "Réactif, même sur les écrans chargés en texte" | The English comment forbids reusing the literal Fast tier label; the first pass reused «Rapide», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

**Every apostrophe is escaped `\'`** across the delta — l\'écran, d\'ouvrir, n\'est, l\'appareil photo, s\'ouvrent, qu\'un — verified programmatically, not by eye. French spacing before `:` in `slow_ocr_prompt_message` and `settings_support_check_updates_subtitle`. « » with inner spaces in `settings_ocr_footer_guidance` and `a11y_stuck_message_xiaomi`. **vous** throughout. **Appareil photo** for the camera tool matches Android's French for the CAMERA permission, so `camera_permission_denied` names a findable setting; **moteur** (engine) / **outil** (tool) / **modèle** (model) stay three distinct nouns and all three meet in `settings_ocr_delete_camera_import_note`. **instantané** for the camera freeze-frame keeps « capture d\'écran » free for its existing sense. `settings_ocr_delete_camera_note` quotes the tool by the exact label `settings_cell_camera` carries. Plurals one/other, with past participles agreeing (dictionnaire importé / dictionnaires importés).

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

Scope: the 8 keys added since the 2026-07-25 sync — `card_words_in_sentence`,
`anki_added_sentence_success`, `anki_added_word_success`, `game_audio_zoom_hint`,
`anki_first_field_unmapped`, `anki_first_field_empty`,
`history_hide_translations_toggle_title`, `history_hide_translations_toggle_subtitle`.
Reviewed by a reader who did not write them. **`values-fr/strings.xml` was not edited** —
this section is the report only.

Mechanical layer verified programmatically over the 8 keys: every `<xliff:g>` span
byte-identical to EN, `id` and `example` included (`field_name`, `brand_anki`); placeholder
multisets identical to EN (`%1$s` exactly once in each first-field string, none in the other
six); `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match; **zero unescaped `'`** — checked
file-wide, not just over the delta; `name=` untouched; Anki left untranslated inside and
outside the spans. The English `“ ”` typography is correctly re-cast as `« »` per the locale
parameters, and the new pairs pad with a **plain ASCII space** — the file contains no U+00A0,
U+202F or U+2009 anywhere, so the 2 new pairs match the 26 already committed. (The
narrow-no-break-space question is a file-wide convention, not something these strings should
diverge on.) **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `game_audio_zoom_hint` | 💬 | Pincez pour afficher plus ou moins d\'audio | Pincez pour afficher une portion plus ou moins longue de l\'audio | **Pincez** is the right Android-French verb (Google's own "Pincez pour zoomer"), and *audio* as a mass noun already exists in the file (`audio_source_game_enable_hint`: « capturer l\'audio du jeu »), so the partitive is defensible. The snag is that **plus ou moins** is first read idiomatically ("approximately"): *afficher plus ou moins d\'audio* invites a half-beat of "display roughly some audio" before `d\'` forces the quantifier reading. Naming what actually changes — the visible **portion** — removes the garden path. Length is free here: the caption is a `wrap_content`, `gravity="center_horizontal"` 11sp `TextView` in `anki_game_audio_panel.xml` with no `maxLines` and no `ellipsize`, so it wraps rather than clips. |
| `anki_first_field_unmapped` | 💬 | Mappez une valeur sur « %1$s » pour qu\'Anki identifie la note. | Mappez « %1$s » pour qu\'Anki identifie la note. | Two small things, one fix. (1) The dialog this toast is announcing is titled `anki_content_source_pick_title` = « **Mapper** « %1$s » » — verb + quoted field, no preposition. Dropping *une valeur sur* makes the toast and the screen it opens use one construction instead of two (*mapper … sur* is not wrong; it is simply a second shape for the same action). (2) The source comment pins a hard constraint — "Kept short: Android 12+ clamps toasts to two lines" — and this is a `Toast.LENGTH_LONG` (`AnkiSendDispatch.kt:216`) where Android 12+ pins `maxLines=2`. With `example="Key"` FR runs 61 chars vs EN 51; with a realistic field name (`Expression`) 68 vs 58, **+17%**. Two lines hold roughly 90 chars at 14sp on a 360dp-wide toast, so today's string clears it — but `%1$s` is a **free-form user-defined** field name, and FR spends 20 of the ~22 chars of headroom EN keeps. The shorter form buys that back. |
| `anki_first_field_empty` | 💬 | « %1$s » est vide sur cette carte. Anki se sert du premier champ pour identifier la note : ce champ doit donc contenir une valeur sur chaque carte. | Le champ « %1$s » est vide sur cette carte. Anki utilise le premier champ pour identifier la note : ce champ doit donc contenir une valeur sur chaque carte. | Correct as written — flagged only as polish. **Agreement is a non-issue**: *vide* is epicene, so a feminine field name ( « Expression » ) cannot break it, and a quoted autonym is masculine by default anyway. But the alert opens on a bare guillemet-quoted token the user may not recognise as a field name; the head noun **Le champ** identifies it and reads more naturally at sentence start in French than in English. **se sert de** is standard neutral register, not colloquial — *utilise* is suggested only because it is the verb this file already uses for "uses" (`accessibility_service_description`) and is 4 chars shorter. Length is not a constraint here (full alert, `AnkiSendDispatch.kt:270/303`). |

### Clean areas (delta — checked, no findings)

**The two one-tap toasts are right on every axis I could test.**
`anki_added_sentence_success` / `anki_added_word_success` carry the feminine participle
**ajoutée** agreeing with *carte* — the single agreement trap in the delta, and it is
correctly sprung. The compounds also line up with the mode labels the user set:
`anki_mode_sentence` = **Phrase**, `anki_mode_word` = **Mot**, and the file already says
« carte de phrase » in `anki_content_flag_sentence` and « mode mot » in
`anki_content_flag_vocabulary_desc`. That link is the whole point of these strings — the
source comment says the toast is *where the silently-applied mode becomes visible* — so
« Carte de mot » should **not** later be "improved" to « carte de vocabulaire »: it would
sever the toast from the mode label that produced it. The masculine « Ajouté à Anki » in the
neighbouring `anki_added_no_audio` is not an inconsistency; that string has no subject in
either language.

**`card_words_in_sentence` = « Mots de la phrase ».** Sentence case as the comment asks, the
definite article French requires, and the card CSS `text-transform: uppercase` yields
**MOTS DE LA PHRASE** — no accented capital in the string, so nothing to lose to a
capitalisation pass. Worth noting that this header is *baked into the card at send time*, so
it cannot be retro-fixed on cards already in the user's collection; it is correct now.

**`la note` — a faithful mirror of the source, not a drift.** The word *note* appears in
**exactly two** English strings in the whole file, both of them in this delta; everywhere
else the app deliberately calls Anki's note types **card types** (`anki_card_type_row_label`
→ « Type de carte »), which the locale follows. So the note/carte split in French reproduces
the split English already has. **« note » is also Anki's own French term** (Anki FR:
*note*, *type de note*), so an AnkiDroid user reads it correctly, and the distinction from
« carte » stays visible inside `anki_first_field_empty`, which uses both nouns one clause
apart. **Do not normalise « la note » to « la carte »** in a later pass: Anki checksums the
*note's* first field for duplicate detection, and the message would become factually wrong.

**Both History strings reuse the file's own vocabulary rather than inventing.**
`history_hide_translations_toggle_title` = « Masquer les traductions » is byte-for-byte the
verb phrase already in `translate_button_subtitle_hold_to_hide_translations`, and **Masquer**
is this file's single established word for *hide* (7 prior uses, incl. `overlay_hide_for_now`,
`floating_icon_close_label_hide`) — no second verb introduced. In the subtitle, **texte
capturé** reuses the established capture verb (`history_toggle_subtitle` « phrases
capturées », `settings_cell_history_summary_on` « phrases capturées »), and **ligne** for a
History row matches `history_empty_none` and `history_clear_confirm_message`, which already
call these rows *lignes* — so « Appuyez sur une ligne » points at an object the user has a
name for. **sa traduction** agrees with the feminine *ligne*. The person switch inside the
subtitle is deliberate and correct: the descriptive first clause « N\'affiche que… » matches
the sibling subtitle style exactly (`history_toggle_subtitle` « Enregistre… »,
`history_capture_image_toggle_subtitle` « Conserve… »), and the second sentence is an
instruction, so the **vous** imperative is right — the English makes the same switch.

**Register and punctuation.** **vous** throughout: *Pincez*, *Mappez*, *Appuyez*; no `tu`
form anywhere in the delta. French spacing before `:` is present in `anki_first_field_empty`
(« … identifier la note : ce champ… ») with the plain space this file uses everywhere. The
colon-plus-*donc* construction is idiomatic, and the apparent redundancy of repeating
« champ » is doing real work — a pronoun there ( « il ») would be ambiguous between *Anki*
and *la note*.

**Render constraints measured, not assumed.** `history_hide_translations_toggle_subtitle`
renders through `Text.PT.RowSubtitle` in the `settings_row_switch` include
(`activity_translation_history.xml:83`), which sets only size/colour/line spacing — no
`maxLines`, no `ellipsize` — so the 78-char French (vs 62 EN, +26%) wraps and cannot clip.
Same for the waveform caption. The only clamped surface in the delta is the
`anki_first_field_unmapped` toast, handled above.

### Verdict (delta)

- **0 🛑** — mechanical layer clean, including the `« »` conversion and the file's
  plain-space padding convention.
- **0 ❌, 0 ⚠️, 3 💬.** Nothing here is wrong or unnatural enough to block; all three notes
  are polish, and the only one with a functional edge is the toast-length headroom on
  `anki_first_field_unmapped`.
- **Ship as-is is defensible.** If one fix is taken, take that one.

## Delta review 2026-08-19 (25 keys: language wildcard, Bergamot device gate, dictionary-styling toggle, Source Language row, manual dictionary-update flow, debug angle rollback)

Mechanical layer verified programmatically across all 12 locales: all 25 delta names
present, no extras, no duplicate `name=`; every `%n$s` present and matching EN; all
`<xliff:g>` spans byte-identical to EN (`id`, `example`, inner placeholder); `<b>`, `\n`,
`\{ \}`, `&lt;/&gt;/&amp;` counts match; no unescaped `'`/`"`. Analyzer reports
`missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD SUCCESSFUL. No
`<plurals>` in this delta. **No 🛑 build-breaking issues.**

### Findings (delta) — applied

| name | severity | current | suggested | note |
|---|---|---|---|---|
| settings_debug_angle_gate | 💬 | «Seuil d\'angle classique (10°)» | «Seuil classique d\'angle (10°)» | Attachment: at the end of the noun phrase, «classique» binds to «angle», reading "classic angle". The EN comment is explicit that the *threshold* is the legacy one (10° instead of the current 3°). French allows the adjective to sit before the complement, which resolves it without extra words. Same fix applied in ar / es / pt-BR this round. |

### Clean areas (delta) — checked, no findings

**Update vocabulary reused from the app updater.** «Mise à jour disponible» and «Impossible
de vérifier les mises à jour» are byte-identical to `update_dialog_title` /
`update_check_failed_title`; `yomitan_update_check_failed_message` follows
`yomitan_download_error_message`'s «Vérifiez votre connexion et réessayez»;
`yomitan_update_scan_active_message` closes with `anki_models_unavailable`'s «Réessayez
dans un instant»; «Recherche de mises à jour» is the deverbal progress form matching
`update_progress_verifying` «Vérification…»; «en arrière-plan» matches
`onboarding_notif_row_silent_sub`.

**Apostrophes and French spacing.** Every apostrophe is escaped — «S\'applique»,
«l\'application», «qu\'elle», «n\'a», «d\'angle» — and the space before high punctuation is
present where French requires it: «à partir de maintenant **;** désactivez…»,
«à ce dictionnaire **:** elle n\'a donc pas été installée». Verified programmatically as
well as by eye.

**Gender exposure is minimal and anchored where it exists.** «%1$s peut être **mis** à jour»
defaults to masculine, matching the file's own `yomitan_duplicate_message` («%1$s est déjà
importé») — agreement with the implied *dictionnaire*, not with an arbitrary title. And
`yomitan_update_none_message` «%1$s est dans **sa** dernière version» is invariant by
construction: «sa» agrees with *version* (f.), never with the placeholder.

**«Langue source» is the file's own term**, from `llm_prompt_kw_source_desc` («de la langue
source»), and distinct from «Langue du jeu» (`pack_upgrade_label_source`) — the row names
the dictionary's declared language, not the capture language.

**«maintenant» is present where EN says "now"**, keeping `yomitan_update_done_message`
distinct from `yomitan_update_none_message`.

**Register.** vous throughout («Vérifiez», «Réessayez», «désactivez cette option»). Button
labels take the infinitive («Mettre à jour», «Télécharger à nouveau») as the file does
(`update_dialog_download` «Télécharger et installer»).

### Verdict

**PASS after fix.** One 💬, no ⚠️/❌/🛑.

### Delta review round 2 — 2026-08-19 (`lang_pick_any` read against the render code)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| lang_pick_any | ⚠️ | «Toutes les langues» | «N\'importe quelle langue» | The pinned wildcard row repeated `lang_section_all`'s own word: «Toutes les langues» sat two rows above a header reading «Toutes», so it read as a shortcut into the full list rather than "no language restriction". «N\'importe quelle langue» is the ordinary French wildcard and is lexically distinct in both slots; the apostrophe is escaped, and neither row clips (`settings_row_value.xml` sets no `maxLines`/`ellipsize`). |

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

**PASS after fixes.** One ⚠️ (round 2) and one 💬 (round 1). Apostrophe escaping and
space-before-punctuation — the recurring French mechanical risks — were clean in both rounds.

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

None. No 🛑/❌/⚠️; one 💬 recorded as a decision rather than a defect.

| name | severity | current | note |
|---|---|---|---|
| anki_card_too_large_simplify | 💬 | « Enregistrer en version simplifiée » | 33 characters on a dialog button. Kept rather than shortened: `OverlayAlert`'s buttons are `MATCH_PARENT` inside a 280 dp dialog with no `maxLines`, so a long label wraps instead of clipping — and the 2026-07-14 review lesson is explicitly not to trade accuracy for brevity against a constraint that turns out not to exist. The shorter « Enregistrer simplifié » would leave the adjective agreeing with nothing. |

### Clean areas (delta) — checked, no findings

**Apostrophes and spacing.** The one apostrophe in the delta is escaped — « LLM sur
l\'appareil » — and the prompt's question mark carries the French space before it
(« …en texte brut ? »), matching `onboarding_welcome_play_title` « Vous ne parlez pas la
langue ? ».

**vous throughout.** « Essayez de retirer quelques mots ou de raccourcir la phrase » is the
same register as `anki_send_failed_message` « Assurez-vous que AnkiDroid est en cours
d\'exécution, puis réessayez ». The prompt is an infinitive question, so it takes no person
— as `llm_prompt_discard_title` does not.

**Terminology.** carte (card), définitions, dictionnaire, « texte brut » for plain text —
the last from `yomitan_styling_subtitle`'s « toujours utiliser du texte brut ». mots and
phrase in the advice match `anki_mode_word` « Mot » and `anki_mode_sentence` « Phrase ».
« sur l\'appareil » for on-device comes from `llm_prompt_row_system_subtitle` « en ligne
comme sur l\'appareil ».

**Modifier attachment.** « Routage hors ligne des textes courts » puts *hors ligne* directly
on *routage*, so the row cannot be read as "routing of short texts that are offline" — the
failure mode that hit four postposed-adjective locales at once on "Classic angle threshold"
and was recorded in `l10n-updating-locales.md`.

**volumineux, not grand.** Both size sentences use *trop volumineuse*, which is what French
uses for data size; *trop grande* would read as physical dimensions.

### Verdict

**PASS.** No fixes required; one 💬 recorded as a deliberate length decision.

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

**Siblings, one term swapped.** «Activer ou désactiver le pinyin intégré» mirrors the
furigana string.

**The ⋯.** «Autres actions» (no elision, so no apostrophe to escape).

**Row names.** «Lire à voix haute» and «Modifier le texte» come from
`cd_read_original_aloud` / `label_edit`. Longest row: the reused «Afficher à l'écran», 19
characters, fits.

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
| settings_edge_indicator_subtitle | 💬 | Affiche … tant que la superposition est affichée. | Affiche … tant que la superposition est visible. | affiche … affichée. |

### Clean areas (delta) — checked, no findings

**Typography.** vous; every apostrophe escaped; a space before : and ? (Gemini : …,
Abandonner la carte ?); « » with inner spaces as `overlay_hide_controls_message`. The pill
«Langue du jeu : %1$s» avoids elision with a capitalized language name.

**Terms.** Reused: menu rapide, Changer la langue du jeu, Lancer/arrêter la traduction auto
(the hotkey), Impossible de traduire, clé d'API non valide, quota … épuisé, EXPÉRIMENTAL.,
Abandonner (`llm_prompt_discard_title`), superposition. Infinitive gesture labels match
the hotkey rows (Maintenir pour…, Appuyer pour…).

### Verdict (round 1)

1 💬, to apply.

### Round 2 (2026-09-29), after applying round 1

Round-1 fix present: «…tant que la superposition est visible.» Mechanical layer re-run after the fixes: 0 problems; analyzer `missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD SUCCESSFUL. Every screen of the delta was re-read in full, not only the changed keys.

No new findings.

**Verdict (round 2):** **PASS.**

## Delta review 2026-10-07 (41 keys + 2 orphans: bug-report email, Support rows, kill notice, Fix disappearing icon page)

Mechanical layer verified: `python3 PKT/../tools/mech_check.py PKT/../keys.txt fr` reports
"checked 1 locales x 41 keys; problems: 0" (every key present; `<xliff:g>` spans and
placeholders identical to English; `\n` counts match; no unescaped `'` or `"`, no stray tag,
no double space, no leading or trailing space), and `python3 scripts/l10n_diff.py --locale
app/src/main/res/values-fr/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync".
The two orphans (`settings_debug_export_logs_title`, `settings_debug_export_logs_subtitle`)
are gone; `settings_debug_export_logs_subject` stays. **No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `MainActivity.maybeShowKillNotice` builds the body as `getString(body) + ' ' +
  getString(kill_notice_restored)`, and only when a floating icon is actually on screen, so
  the restored sentence must stand after each of the three bodies with nothing in between.
- `settings_row_hub.xml`: the Support summary is `singleLine` + `ellipsize="end"`;
  `dialog_settings.xml` orders the rows Check for updates, Discord, Fix disappearing icon,
  Report a bug, Support PlayTranslate.
  `KeepRunningActivity` sets the same title and subtitle strings on the Report a bug row under
  the empty label, and the toolbar takes `@string/keep_running_title`.
- `LogExporter` shows `email_no_app_fallback` as a `Toast.LENGTH_LONG` with the support
  address; `BugReport` uses `settings_debug_export_logs_subject` and `share_chooser_share_logs`
  for the hold path. Widths were re-measured with the packet's own `Measure`/`Wrap` tools,
  which reproduce the packet's figures (349, 199, 232, 177 dp).

### Findings (delta, round 1)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| settings_support_report_bug_subtitle | ⚠️ | Journaux par e-mail à l\'assistance. Appui long pour partager. | Journaux par e-mail à l\'assistance. Maintenez pour partager. | The file renders "Hold to …" as « Maintenez pour … » (`translate_button_subtitle_hold_*`, the hold-verb precedent the translator's decisions name), and the gesture itself is « Maintenir ». « Appui long » is a third form for the same gesture. The width is unchanged (348 dp), and the first clause (199 dp) still fits 249 dp and even 216 dp. |
| kill_notice_restored | ⚠️ | Il est de nouveau activé. | PlayTranslate est de nouveau activé. | Appended after each body. Gender rules out the sentence just before it (« l'icône flottante »), so « Il » skips past it to the nearest masculine noun: « votre téléphone » (memory; other, where it lands right after « Certains téléphones font cela… ») or « le gestionnaire de batterie ou de mémoire » (stopped). PlayTranslate is two or three sentences back, and only the echo of « activé » recovers it. Naming the app reads cleanly after all three bodies. Use plain text, as `settings_support_donate_title` does: English has no `<xliff:g>` in this string, so adding a span would fail the span check. « L'application est de nouveau activée. » works equally well. |
| keep_running_huawei_close_after_lock_line | ⚠️ | Dans Batterie → Plus de paramètres de batterie. Sinon, <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> se ferme à chaque verrouillage de l\'écran. | Dans Batterie → Plus de paramètres batterie. Sinon, <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> se ferme à chaque verrouillage de l\'écran. | Huawei France's own page (consumer.huawei.com, content fr-fr00428704, fetched today) prints the entry without "de": « Allez à Paramètres > Batterie > Plus de paramètres batterie et activez Connexion maintenue lorsque l'appareil est en veille. » This cell was UNVERIFIED in oem-labels.md (it guessed « Autres paramètres de la batterie »); it is now official. |
| settings_support_keep_running_subtitle | 💬 | Empêcher la fermeture de <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> | Éviter que <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> se ferme | Optional. At 232 dp the current text clips inside the app name on a 360 dp phone (216 dp); the suggestion measures 194 dp and fits every width in the packet. « se ferme » is the delta's own verb for the system closing the app (`keep_running_huawei_close_after_lock_line`). The English clips there too, so this is polish, not a defect. |
| keep_running_xiaomi_lock_line | 💬 | Ouvrez les applis récentes, appuyez de manière prolongée sur la carte de <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>, puis sur le cadenas. Le nettoyage de la mémoire ignore les applis verrouillées. | Ouvrez les applis récentes, appuyez longuement sur la carte de <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>, puis appuyez sur le cadenas. Le nettoyage de la mémoire ignore les applis verrouillées. | « puis sur le cadenas » elides the whole verb phrase, so it can read as a second long press, while English says tap. The file's form for a press-and-hold on a target is « appuyez longuement sur » (`status_hold_hint`, `anki_long_press_footer`). |

### Clean areas (delta) — checked, no findings

**Typography.** Formal vous throughout (Envoyez, Décrivez, Ouvrez, Activez, Désactivez-le).
Every apostrophe is escaped `\'`, and there is no curly ’. There is a plain space before `?`
and `:` and inside « », as in the rest of the file, which has no NBSP anywhere. The only `"`
are inside `<xliff:g>` attributes. The en dash in the subject mirrors English, and no em dash
was added.

**Kill notice, read as a set.** « Android a fermé PlayTranslate », then each body. The tenses
are natural (« manquait de mémoire et a fermé »). The relative clause « qui était activé »
spares the memory and other bodies a second « il ». In the stopped body, « alors qu'il était
activé » and « ne l'avez pas arrêté » both point unambiguously at the subject, and « l'a
peut-être fait » leaves the attribution to the user, as English does. Closed and stopped
stay apart (fermé / arrêté). « activé » is the app's on state (`capture_lifecycle_state_on`),
and « l'icône flottante » matches `settings_show_overlay_icon`. The buttons read « Voir les
options », then « Pas maintenant », which is byte-identical to `btn_not_now`. The only
problem is the appended sentence (finding above).

**Support card, read as a set.** The titles read Rechercher des mises à jour, Rejoindre
Discord, Si l'icône disparaît, Signaler un bug, Soutenir PlayTranslate. « Si l'icône
disparaît » breaks its neighbours' infinitive pattern, but it leads with the symptom, which
is what the English comment asks for. It reads as a normal French help heading, and with its
subtitle it says problem, then remedy. A verb-first form (« Empêcher l'icône de disparaître »,
306 dp) overflows the 272 dp toolbar; the current 177 dp fits, and it is byte-identical to
`keep_running_title`. « Signaler un bug » and the empty label's « signalez un bug » use the
same words. « à l'assistance » matches the header « Assistance ». The Report a bug
subtitle's first clause is telegraphic, but it carries what, how and to whom in 199 dp, and
any verb-led form (« Envoyer les journaux à l'assistance par e-mail. », 264 dp) loses the
end at 249 dp.

**Fix disappearing icon page.** The intro uses « réduisent le risque que » + subjunctive
correctly. The empty label reads naturally, and « Si le problème persiste » takes its
problem from the page title. The battery card's « Sans restriction » is the AOSP label, and
« l'utilisation de la batterie » is the head of AOSP's « Utilisation de la batterie par les
applis ». The shared battery line (« Sans cela, … ») reads correctly under both the generic
card and Xiaomi's. The generic card says « Définir … sur » and Xiaomi's says « Régler … sur »
(from `a11y_stuck_message_xiaomi`). They never show together, because Xiaomi gets its own
card instead of the generic one, and both verbs are standard, so I left them. The
accessibility card's « le mode accessibilité » follows the « mode avion » pattern; English
has no earlier term for it. « que les nettoyages de mémoire atteignent rarement » reads as
"rarely affect". The restart line reuses `a11y_stuck_message` and names « le service »,
which is clearer under « L'accessibilité doit être redémarrée » than English "it". The tile
title is byte-identical to `quick_tile_add_row_title`, and AOSP's own label is « Réglages
rapides ». The OPPO and vivo lines are identical, as in English. Their « peu après que vous
l'avez quitté » correctly takes the indicative after « après que », with masculine agreement
for PlayTranslate.

**OEM labels.** Samsung matches the official Samsung France page: « Maintenance de
l'appareil », « Batterie », « Limites utilisation arrière-plan » (Samsung's own, without
"d'"), « Apps jamais mises en veille auto. » and « applications en veille ». « Ajouter aux
« … » » folds the article in cleanly. Huawei's « Lancement d'application », « Lancement
automatique », « Lancement secondaire » and « Exécution en arrière-plan » are official.
« Gérer manuellement » is a paraphrase, not a quoted label; the fr label for "Manage
manually" is unverified. Xiaomi's « démarrage automatique », « économiseur de batterie » and
« Aucune restriction » match AdGuard FR (a secondary source) and the reviewed
`a11y_stuck_message_xiaomi`. For Recents, AdGuard FR (secondary, Xiaomi) and Huawei France's
own page (for Huawei's screen) say « tâches récentes », but MIUI's Recents screen shows no
title to match, so « les applis récentes » is fine. The OPPO and vivo titles are UNVERIFIED:
two searches found no French OPPO or vivo source. They read naturally, and they keep
auto-launch as « lancement automatique » (Huawei's official term) and autostart as
« démarrage automatique ». The Huawei close-after-lock title is the known English-source
problem, rendered faithfully.

**Email flow.** The two chooser titles are parallel (« Envoyer le rapport de plantage / de
bug ») and match `crash_dialog_message`. The subject mirrors `crash_email_subject`'s
structure (« Rapport de bug de PlayTranslate – v3.3.0 »). The body is natural, and « Les
informations … et les journaux récents sont joints » agrees correctly. Logs are « journaux »
as in `share_chooser_share_logs` and `settings_debug_export_logs_subject`. The toast wraps
to 2 lines at both 296 and 320 dp, with the address whole on line 2. The file's usual toast
form (« Aucune appli de messagerie. … ») and the more usual word order (« … à
support@playtranslate.com par un autre moyen. ») both lose a third line at 296 dp. The
current « Envoyez les fichiers autrement à … » is the order that fits, and it is grammatical.
« appli » already appears in running text (`error_single_app_not_fullscreen`,
`mp_overlay_permission_message`). « e-mail » and « bug » have no precedent in the file and
are standard in France.

### Verdict (round 1)

3 ⚠️ and 2 💬. Apply all five (the 💬 on `settings_support_keep_running_subtitle` is
optional). **Not a PASS until the three ⚠️ are applied**:
`settings_support_report_bug_subtitle` (« Maintenez pour partager. »),
`kill_notice_restored` (« PlayTranslate est de nouveau activé. ») and
`keep_running_huawei_close_after_lock_line` (« Plus de paramètres batterie », Huawei France
official).

## Follow-up review 2026-10-07, round 1 (15 keys after the Fix disappearing icon rewrite, plus the final review of the first pass's 41 keys)

Mechanical layer: `python3 TOOLS/mech_check.py F2/keys2.txt fr` reports "checked 1 locales x
15 keys; problems: 0", and `python3 TOOLS/mech_check.py …/scratchpad/keys.txt fr` (the first
pass's keys still in English: 41 less the two Huawei close-after-lock keys, so 39) reports
"checked 1 locales x 39 keys; problems: 0": every key present, `<xliff:g>` spans (both
`app_name` and `app_name2` in the three two-span lines) and placeholders identical to
English, `\n` counts match, no unescaped `'` or `"`, no stray tag, no double space, no
leading or trailing space. `python3 scripts/l10n_diff.py --locale
app/src/main/res/values-fr/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync";
the four keys English removed (`restricted_settings_title`, `restricted_settings_message`,
`keep_running_huawei_close_after_lock_title`, `keep_running_huawei_close_after_lock_line`)
are gone from the file.
**No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `AccessibilityHelp.withRestrictedSettingsStep` appends `"\n\n"` + the addendum on Android 13+
  to `accessibility_dialog_message` (MainActivity) and `overlay_icon_a11y_required_message`
  (SettingsRenderer), both system AlertDialogs, to the three `a11y_required_*_message`
  (AccessibilityAlert, OverlayAlert), and to `keep_running_accessibility_line` only while the
  service still has to be enabled (`KeepRunningActivity.lineOf`). The stuck alert never gets
  it; it gets `"\n\n"` + `a11y_stuck_message_xiaomi` when `Build.MANUFACTURER` is Xiaomi.
- `KeepRunningActivity.openFirst`: every card with an action (the ROM cards, the battery
  dialog, accessibility) shows `keep_running_screen_unavailable` as a `LENGTH_LONG` toast when
  none of its intents launches, then opens App info. `KeepRunningItems.ids`: Xiaomi gets
  autostart, battery (with its own line) and lock; Huawei, OPPO, vivo and Samsung one card
  each; then the generic battery card (never on Xiaomi), accessibility (Android 11+) and the
  tile (Android 13+). `MainActivity.maybeShowKillNotice` still appends `' '` +
  `kill_notice_restored` only with an icon on screen.
- The toast, wrapped with `TOOLS/Wrap` at 14 sp: 2 lines at 296 dp (« Page indisponible sur
  ce téléphone. » / « Recherchez ce paramètre dans les Paramètres. ») and 2 at 320 dp; the
  English needs 3 at 296.

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| a11y_required_hotkey_message | ⚠️ | Définir des raccourcis nécessite l\'autorisation d\'accessibilité. Elle permet à <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> de détecter les touches que vous appuyez pendant que vous jouez. | Définir des raccourcis nécessite l\'autorisation d\'accessibilité. Elle permet à <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> de détecter les touches sur lesquelles vous appuyez pendant que vous jouez. | **Outside the 15 + 41 keys** (in HEAD, never reported). Found while reading the addendum after its five host messages: in the hotkey alert this paragraph now sits directly above it. « appuyer » is intransitive (« appuyer sur une touche »), so « les touches que vous appuyez » is a solecism. The fix keeps the file's verb for tap and press; « les touches que vous pressez » would also be correct. |
| quick_tile_added_row_subtitle | ⚠️ | Ajouté aux Paramètres rapides | Ajoutée aux réglages rapides | **Outside the 15 + 41 keys.** Found while checking the tile card's term. The full review renamed the row title to « Ajouter la tuile aux réglages rapides », which matches Android's « Réglages rapides » (framework `accessibility_system_action_quick_settings_label`, android-36 on disk), and `keep_running_tile_title` copies that title. This added-state subtitle kept « Paramètres rapides », so `HotkeysSettingsActivity.renderAddTile` shows both terms on one row. The feminine agrees with « la tuile » in the title above it. |

### Clean areas — checked, no findings

**CHANGED keys moved only as far as the English.** `a11y_stuck_message` is the HEAD text
with the cause sentence deleted and nothing else changed (string comparison).
`a11y_stuck_message_xiaomi` differs from HEAD in exactly the three places the English moved:
« activez » became « autorisez » (enable → allow), « l'économiseur de batterie » became « son
utilisation de la batterie » (Battery saver → its battery use), and « continuera » became
« pourrait continuer » (will → may). The tile line keeps « réactiver … depuis n'importe quel
écran » without the swipe and tap. The Xiaomi battery title swaps only its object. The lock
line keeps its round-1 first sentence and adds a natural « faites plutôt glisser la carte vers
le bas ». The Huawei line keeps its three switch labels and adds the path. Where the meaning
changed (battery title and line, the Xiaomi autostart, OPPO, vivo and Samsung lines), the
line is translated afresh.

**The addendum, read after each of its six hosts.** It reads as a closing paragraph after
both system dialogs, whose last line is the « Accédez à Paramètres → … → Activer » path, so
« l'interrupteur » lands on the switch just named. After the three OverlayAlert messages and
the accessibility card line it is as unanchored as English "the switch", and no less clear.
« Infos sur l'appli » and « Autoriser les paramètres restreints » are AOSP's labels, quoted in
« ». The first is also the framework's `app_info` on disk, and the second matches the
deleted, reviewed `restricted_settings_message`. « Sur Android 13 ou une version
ultérieure » follows `requires_android_11_message`. « l'interrupteur » has no precedent (no
other string names a switch control), but it is the plain French word for an on/off switch
and is clear here; « le bouton » is more colloquial and « l'option » vaguer, so I left it. It
is one sentence, as in English.

**Stuck alert as a set.** The title, both paragraphs and the Xiaomi paragraph read in
sequence. « autorisez également le démarrage automatique » and « réglez son utilisation de la
batterie sur « Aucune restriction » » repeat the two Xiaomi card titles, as the English
comment intends, and « son » points at PlayTranslate. Only the constant « Aucune
restriction » is quoted, as in the card title.

**Cards, title plus line, per ROM.** Every title is an infinitive and every line a vous
imperative or a subjectless present, as before. The battery title opens with « Toujours
autoriser », the first words of the dialog the card opens (AOSP « Toujours autoriser en
arrière-plan ? »). Like English, it quotes no state label (« Sans restriction »). The battery
line's « cette autorisation » names what the card grants, and « de lui-même » matches the
stuck message. The three two-span lines keep both spans and read naturally. In the Xiaomi
line, Xiaomi is the implied subject of « après l'avoir fermé », as in English, and « fermé »
agrees with « l' ». OPPO's "its battery settings" is « ses paramètres de batterie » (the
app's), and vivo's "the Battery settings" is « les paramètres de la batterie » (the phone's):
the same distinction English makes. vivo's line drops "it" from "allow it high background
power use", but PlayTranslate is the sentence's topic, so nothing is lost, and « consommation
élevée en arrière-plan » matches the title's « consommation en arrière-plan ». The Xiaomi
battery line's « paramètres système de PlayTranslate » repeats the stuck paragraph's
« paramètres système de l'application ». It also keeps the system page apart from
PlayTranslate's own Settings screen, which « les paramètres de PlayTranslate » would suggest.
« l'option liée à la batterie » is a natural "battery entry". The Huawei title's « Gérer
manuellement » pairs with the line's « désactivez « Gérée automatiquement » ». The Samsung
line moves « sous « Limites utilisation arrière-plan » » after the list name, and it reads
naturally. Put in path order, the sentence would end on « auto. ». or still carry the
label's period mid-sentence, so I left it. « jugées inutilisées depuis quelques jours »
renders "apps it has not seen used" well.

**Labels and search words.** These match official sources: Huawei France's « Lancement
d'application », « Gérée automatiquement », « Lancement automatique », « Lancement
secondaire » and « Exécution en arrière-plan » (the search word is the label itself), and
Samsung France's « Limites utilisation arrière-plan » and « Apps jamais mises en veille
auto. ». These come from a secondary source (AdGuard FR): Xiaomi's « Démarrage automatique »
and HyperOS « Démarrage automatique en arrière-plan », both matched by the search word
« démarrage automatique », plus « Économiseur de batterie » and « Aucune restriction ».
These are unverified, with no French source found: Xiaomi's « Batterie » (HyperOS 2) and
« Énergie » (HyperOS 3 "Power"; « Alimentation » is the other plausible rendering), OPPO's
search word « lancement auto », and vivo's « démarrage automatique ». « lancement auto »
differs from the card title's « lancement automatique ». As a search query, though, it
matches the label by prefix whether the label is written short or in full, while the full
form would miss a short label, so it is the safer word. One search for a French ColorOS
source found none (the first pass's two searches found none either). A second, for OnePlus,
was declined, and I made no further web checks.

**The toast, then App info.** The toast fits 2 lines at both widths. « Page » follows the
addendum's « la page « Infos sur l'appli » » and keeps « écran » free for displays. The
telegraphic « Page indisponible » is the usual French toast form. Dropping « plutôt » costs
little, because App info opening under the toast already says "instead". « Cherchez plutôt
ce paramètre dans les Paramètres. » would also fit 296 dp, but « Recherchez » is the verb of
the Settings search field (« Rechercher ») the toast sends the user to, so the current wording
is better. The paramètre / Paramètres echo is harmless.

**The first pass's keys, final review.** The round-1 fixes landed:
`settings_support_report_bug_subtitle` (« Maintenez pour partager. »), `kill_notice_restored`
(« PlayTranslate est de nouveau activé. ») and both 💬
(`settings_support_keep_running_subtitle` « Éviter que PlayTranslate se ferme », and the lock
line's « appuyez longuement … puis appuyez sur le cadenas »). The third ⚠️'s key,
`keep_running_huawei_close_after_lock_line`, left English along with its card. I re-measured
the widths with the packet's tools. The Support summaries are 194 dp (fits 216) and 348 dp,
whose first clause is 199 dp, so the tap action shows whole at 216 dp. The toolbar title is
177 dp of 272. `email_no_app_fallback` takes 2 lines at 296 and 320 dp, with the address
whole on line 2. The kill notice reads correctly after each of the three bodies with the
restored sentence appended. The Support rows, email flow, intro, empty label, accessibility
card (both states), tile card and the five ROM titles read as in round 1, and I hold its
decisions (« Si l'icône disparaît », « Envoyez les fichiers autrement à … »).

**Typography, all 44 values, checked by script.** vous throughout. Every apostrophe is `\'`,
with no curly ’. « » take plain spaces, and a plain space precedes `?` and `:`; the file has
no NBSP anywhere. No em dash.

### Verdict (round 1)

In scope (the 15 follow-up keys and the first pass's 39 remaining keys): 0 findings.
**PASS.** All the round-1 fixes are in place.

Outside the scope, 2 ⚠️ turned up while reading the surfaces as sets. Both are pre-existing
and do not block this PASS; apply them separately: `a11y_required_hotkey_message` (« les
touches sur lesquelles vous appuyez ») and `quick_tile_added_row_subtitle` (« Ajoutée aux
réglages rapides »).

### Follow-up round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 TOOLS/mech_check.py F2/keys2.txt fr` reports "checked 1
locales x 15 keys; problems: 0". The same check over `F2/keys-extra-f2.txt` (the two round-1
keys plus `requires_android_11_message`) reports "checked 1 locales x 3 keys; problems: 0",
and over the first pass's remaining keys (`…/scratchpad/keys.txt`) "checked 1 locales x 39
keys; problems: 0": every key present, `<xliff:g>` spans (both `app_name` and `app_name2` in
the three two-span lines) and placeholders identical to English, `\n` counts match, no
unescaped `'` or `"`, no stray tag, no double space, no leading or trailing space.
`python3 scripts/l10n_diff.py --locale app/src/main/res/values-fr/strings.xml` reports
`missing=0 orphan=0 modified=0`, "in sync". The file parses as XML (1047 names, no
duplicate), and the four keys English removed are still gone.
**No 🛑 build-breaking issues.**

**Round-1 fixes:**
- `a11y_required_hotkey_message`: applied as suggested (« les touches sur lesquelles vous
  appuyez »); against HEAD only that phrase changed. It reads cleanly above the Android 13+
  addendum in the 280 dp OverlayAlert card, whose message scrolls and has no line cap.
- `quick_tile_added_row_subtitle`: applied as suggested (« Ajoutée aux réglages rapides »).
  Under the row title « Ajouter la tuile aux réglages rapides »
  (`HotkeysSettingsActivity.renderAddTile`) the feminine agrees with « la tuile », and
  « Paramètres rapides » has no hit left in the file: all three Quick Settings strings now use
  Android's « réglages rapides ».
- The first pass's fixes that round 1 confirmed are still in the file:
  `settings_support_report_bug_subtitle` (« Maintenez pour partager. »),
  `kill_notice_restored` (« PlayTranslate est de nouveau activé. »),
  `settings_support_keep_running_subtitle` (« Éviter que PlayTranslate se ferme ») and the
  lock line's « appuyez longuement … puis appuyez sur le cadenas ».

#### Findings (round 2)

No new findings.

Checked: the addendum after its six hosts (both system dialogs, the three OverlayAlert
messages, the accessibility card line), with its labels re-read from the Android 16 QPR2
French Settings file on disk (« Infos sur l'appli », « Autoriser les paramètres restreints »;
the battery title's « Toujours autoriser » opens like « Toujours autoriser en arrière-plan ? »).
The stuck alert reads correctly with and without the Xiaomi paragraph, which repeats both
Xiaomi card titles. Each card's title and line agree. Placeholder grammar holds, with
PlayTranslate masculine throughout (« après l'avoir fermé », « de lui-même »), and
« autorisez-le » agrees with the masculine search word. The toast wraps to 2 lines at
296 dp (second line 294 dp) and at 320 dp (second line 199 dp). Typography, checked by
script over the 17 values: vous only, every apostrophe `\'`, no curly ’, « » with plain
spaces, a plain space before `:` `?` `!`, no NBSP and no em dash. The lock line's
« appuyez longuement sur » is the file's form for a hold on a target (`status_hold_hint`,
`anki_long_press_footer`), and « Maintenez pour … » is its form for "Hold to …", so the
two hold wordings do not conflict.

OEM label check (4 web lookups: 2 searches, 2 PDFs). OnePlus France's own user manual for
the OnePlus Open (OxygenOS,
https://service.oneplus.com/content/dam/support/user-manuals/fr/OnePlus_Open_User_Manual_fr.pdf)
lists « Lancement automatique » under Paramètres > Applis as the auto-launch list. The OPPO
line's search word « lancement auto » is a prefix of it, and the card title's « lancement
automatique » matches it, so on OnePlus, which gets the OPPO card, both now rest on an
official source. OPPO and realme French ColorOS stay unverified: OPPO's French ColorOS 7
guide (https://ipics.oppo.com/oppo_fr/user_manual/ColorOS7_French_User_Guide_V1.0_200710.pdf)
names no auto-launch entry. vivo « démarrage automatique » and Xiaomi « Batterie » /
« Énergie » remain unverified (known issue 2).

#### Verdict (round 2)
**PASS.** Both round-1 fixes landed exactly as suggested, nothing around them broke, and the
15 follow-up keys have no open items.

## Delta review 2026-10-14 (Bilibili Index-Translate: 3 keys)

Mechanical layer: `python3 scripts/l10n_diff.py --locale app/src/main/res/values-fr/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync". The three keys are present once each, beside the same neighbours as in English, and the file parses as XML. Both apostrophes in the note are escaped (`clé d\'API`, `limite d\'utilisation`). This delta has no `<xliff:g>`, placeholder, plural, markup, ASCII quote or dash. "Bilibili" stays in Latin script, twice in the note. **No 🛑 build-breaking issues.**

Render code read before reviewing: the note is `tvProviderNote` (RowSubtitle, wraps, no `maxLines`), shown in place of the API-key section under the untranslated title "Bilibili Index-Translate". The picker line is a 13 sp wrapping TextView, used when the account takes no key. The keyless body appears under `tr_service_remove_title_fmt` for Lingva and Bilibili Index-Translate.

No findings.

Clean areas: The note keeps all three English facts: free and keyless; run by Bilibili, with no published limits; the captured text goes to Bilibili. «Gratuit» agrees with the service. In «Service géré par Bilibili, qui ne publie aucune limite d'utilisation», the noun «Service» that heads the sentence carries English's "for it", so leaving it out loses nothing. «limite d'utilisation» is in line with «limite quotidienne» (`translation_error_daily_quota`). «Le texte capturé» is the captured-text term of `tr_service_order_footer` and `history_hide_translations_toggle_subtitle`, and «clé d'API» is `llm_backend_api_key_label`. The keyless picker line keeps the sibling's first sentence and its «, ou saisissez un identifiant de modèle via Personnalisé….» byte for byte, comma included. «Personnalisé…» matches `llm_backend_model_custom_entry`. «Réessayez» is the file's retry verb (`hidden_word_save_failed`, `settings_ocr_download_failed`), in the vous form. Read as one screen, the delete alert is «Retirer Lingva ?», then «Cette action retire le service de la liste.», then the button «Retirer». The body is the sibling without its key clause, and it keeps retirer (remove) apart from supprimer (delete).

### Disposition (2026-10-14)

No findings; nothing to apply.

## Delta review 2026-10-27 (PlayTranslate Cloud: 24 keys; plus the owed review of 15 keys localized outside the sync)

Mechanical layer: `python3 SP/mechcheck.py <the 39 keys>` reports "39 keys x 13 locales: 0 problems", with no line for fr (every key present; `<xliff:g>` spans byte-identical to English; placeholder multiset, `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match; no unescaped `'` or `"`; no edge or double space; the file parses as XML); `python3 scripts/l10n_diff.py --locale app/src/main/res/values-fr/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync". **No 🛑 build-breaking issues.**

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| llm_backend_reasoning_subtitle | ⚠️ | Combien le modèle réfléchit avant de répondre. Plus le niveau est élevé, plus c\'est lent, et la réflexion est facturée comme des tokens de sortie. | Détermine à quel point le modèle réfléchit avant de répondre. Plus le niveau est élevé, plus c\'est lent, et la réflexion est facturée comme des tokens de sortie. | « Combien le modèle réfléchit… » carries English's verbless fragment ("How much the model reasons…") into French. On its own line, a bare « combien » + clause reads as an exclamation or an unfinished indirect question. The file never does this: the same English shape, "How often the game screen is captured…" (`settings_capture_interval_hint`), is « Fréquence à laquelle… ». The suggestion uses the file's third-person subtitle form (« Affiche une légère lueur… », `settings_edge_indicator_subtitle`), and the second sentence is unchanged. |
| pt_cloud_translate_row_subtitle_no_key | ⚠️ | Les meilleurs modèles de traduction payants, inclus | Les meilleurs modèles de traduction payants, déjà inclus | English's tacked-on ", bundled in" does not carry over. In French a bare participle after the comma reads as cut off: « …, inclus » wants a complement or an adverb (« inclus dans le service », « déjà inclus »). The suggestion keeps the line a noun-phrase pitch, the shape of the « Compte requis » it stands in for on this page. « Inclut les meilleurs modèles de traduction payants » also works. The line wraps (`tvRowSubtitle2`, no `maxLines`). |
| anki_screenshot_crop_instruction | ⚠️ | Faites glisser les bords pour rogner la capture d\'écran | Faites glisser les bords pour rogner l\'image | Clipping confirmed in code: `RegionEditorControls.pill` is `isSingleLine` with `ellipsize=END` at 14 sp, with a 16 dp margin and 16 dp padding on each side. The review sheets open the crop editor as a full-screen dialog, which leaves 296 dp of text on a 360 dp portrait phone; the workspace page sits in a card inset 12 dp, which leaves 272 dp. The current text measures 334 dp (Arial) and ends « …rogner la captu… ». English is 215 dp. The suggestion is 267 dp and fits both. It keeps `region_overlay_drag_instruction`'s « Faites glisser les bords pour … » and the title's « rogner », and the screenshot is the only image on the page. |
| pt_cloud_ocr_toggle_title, pt_cloud_translate_toggle_title | 💬 | Reconnaissance du texte · Traduction du texte | Reconnaître le texte · Traduire le texte | Values in name order. The file renders an English verb-phrase switch title as an infinitive (« Conserver l'historique du texte », « Enregistrer les images de capture », « Masquer les traductions ») and keeps nouns for English noun titles (« Mise à jour automatique »). The two nouns read fine as a pair; the infinitives follow the file and the English verbs. « Lire l'écran » would echo `slow_ocr_prompt_message`, but as a bare title it can suggest a screen reader. |
| pt_cloud_ocr_toggle_subtitle | 💬 | Reconnaissance plus puissante sur un GPU de serveur. Toute la puissance de votre appareil est réservée au jeu. | Reconnaissance plus performante sur un GPU de serveur. Toute la puissance de votre appareil est réservée au jeu. | « puissante » then « puissance » in back-to-back sentences, where English uses two different words (stronger, power). « performante » is the usual French for a more capable engine. |
| pt_cloud_translate_toggle_subtitle | 💬 | Modèles de traduction payants inclus, le meilleur choisi pour chaque tâche. | Modèles de traduction payants inclus : le meilleur est choisi pour chaque tâche. | « le meilleur choisi pour chaque tâche » is a verbless participle clause, English's telegraphic shape. A colon and « est » turn the second half into a sentence that explains the first. The colon takes a space before it, as every prose colon in the file does (the only unspaced ones are inside the Anki `{{furigana:}}` token). |
| llm_backend_thinking_subtitle | 💬 | Le modèle réfléchit avant de répondre. Plus lent, et la réflexion est facturée comme des tokens de sortie. | Le modèle réfléchit avant de répondre. C\'est plus lent, et la réflexion est facturée comme des tokens de sortie. | « Plus lent, et … » uses « et » to join a verbless adjective to a full clause, which is English's shape. « C'est plus lent » makes one sentence, and it matches the sibling's « plus c'est lent ». |

### Clean areas (checked, no findings)

**Translation error pill** (`TranslationErrorMessages`, `TranslationErrorPills`: 14 sp, `maxLines=2`). « PlayTranslate Cloud : arrêté » joins the family (« connexion impossible », « ne répond pas »), with a space before the colon and a lowercase status. « arrêté » is masculine for the implied service, and it is the usual French status for a stopped service; « hors service » would mean broken.

**PlayTranslate Cloud page.** The two `settings_row_switch` rows wrap (no `maxLines`). « Toute la puissance de votre appareil est réservée au jeu. » reads naturally, with « réservée » agreeing with « puissance »; « GPU » is masculine (« un GPU »). For the key, « Clé client » sits beside « Clé d'API » (`llm_backend_api_key_label`) and « Clé •••• » (`tr_service_key_tail_fmt`). « Collez la clé qui vous a été fournie » takes `hint_deepl_key`'s « Collez », and « fournie » agrees with « la clé ». The field is a `singleLine` 13 sp monospace EditText, 7.8 dp a character (Droid Sans Mono's 0.6 em advance, a font metric from memory). The hint fits with no margin at 360 dp: 36 characters are 281 dp of advance against 280 dp, so the final « e » loses at most part of its blank side bearing. It is whole at 393 dp and wider. The status row is « Service », with the values Actif, Démarrage en cours, Arrêté, Connexion impossible, Réponse inattendue (500), Vérification en cours and Non vérifié. The adjectives agree with « Service » (Actif, Arrêté, Non vérifié). « Actif », « Vérification en cours… » and « Démarrage … » are AOSP's own status words (framework values-fr: `hearing_device_status_active`, `ext_media_status_checking`, `android_start_title`). The three values that pair with pill strings match them (« connexion impossible », « réponse inattendue », « arrêté »).

**Settings hub, Configure card** (`cloudDigest`; summary `singleLine`, `ellipsize=end`, 13 sp, 216 dp on a 360 dp phone). All four values show whole: « OCR et traduction côté serveur » (179 dp), « OCR côté serveur uniquement » (176), « Traduction uniquement » (133) and « Désactivé ». « Désactivé » is the History cell's off word, masculine for the service. « côté serveur » renders "Server-grade" as the English comment defines it: on the server, not on the device.

**Capture & overlay, OCR section** (`bindCloudOcrRow`). « OCR côté serveur, aucune charge pour l'appareil » has the shape of the engine notes below it (« Détecte le petit texte, plus lent », « Réactif, même sur les écrans chargés en texte ») and uses the hub's « OCR côté serveur ».

**Floating icon quick menu** (contentDescription). « disponible » and « indisponible » are `backend_status_unavailable_default`'s « Indisponible » and AOSP's media-route pair (« Disponible » / « Indisponible »), each with a space before the colon.

**Fix disappearing icon toast** (`openFirst`, LENGTH_LONG). « Impossible d'ouvrir cette page. Recherchez plutôt ce paramètre dans les Paramètres. » now says what the English says, and the old "unavailable on this phone" is gone. « Impossible de … » is the file's error form (« Impossible de joindre le serveur… »), and « Recherchez … dans les Paramètres » matches the cards' « Dans les Paramètres, recherchez … ». It wraps to 2 lines at 296 dp (268 + 257 dp) and at 320 dp. « cette » covers English's "that", since French has one demonstrative. The textbook « la page demandée » takes a third line at 296 dp in the measurement. The capital in « Paramètres » names the Settings app, which carries English's "your phone's Settings"; the paramètre / Paramètres echo was judged harmless on 2026-10-07.

**Part B.** The group headers « Conjugaison », « Déclinaison » and « Formes » go through `addGroupHeader`, which uppercases with `Locale.ROOT`. That keeps the accent, as French typography wants: DÉCLINAISON, beside DÉFINITIONS. « Rogner la capture d'écran » pairs with « Retirer la capture d'écran » on the button beside it, and both crop strings say « rogner ». « Mode réflexion » (the « mode avion » pattern) and « Niveau de réflexion » share « réflexion », as both English comments require, and « tokens » is the file's word (`tr_service_status_usage_today_fmt`). The segments Désactivé, Minimal, Faible, Moyen and Élevé are masculine, agreeing with « niveau ». « Désactivé » has 9 characters, 57 dp at 13 sp in Arial, against about 56 dp per label in a four-segment row at 360 dp (Roboto runs narrower). At worst it drops one size step when bold, since `buildPillToggle` shrinks from 13 to 10 sp before it ellipsizes; the others are at most 45 dp. « Charger plus (2) » is a common French UI label and reads well beside the lens's « Autre correspondance » and « Recherche… ».

**Outside the delta: code, not a string (read from code, not seen on a device).** The cooldown line under the cloud page's status row is `cooldownLine(…, PtCloudService.unavailableDescription(), …)`. `PtCloudErrors` records that description from English constants (« Not running », « Rate limited », « Server error »), so a French device can show « Arrêté » with « Not running · Nouvel essai à 14:05 » under it. The Translation services rows build the same line the same way for the other backends (« Connection failed »), so this is app-wide and predates the delta. It is not a French finding.

### French: typography, agreement, marketing lines

- **vous.** « votre appareil », « qui vous a été fournie », and the imperatives Collez, Recherchez and Faites glisser. A script over all 39 values finds no tu, ton, ta or toi.
- **Apostrophes and spacing.** The five apostrophes are all `\'` (« l\'appareil », « d\'ouvrir », « c\'est », « d\'écran » twice), with no curly ’ (checked by script). The three colon strings have a plain space before « : », as in the rest of the file, which has no NBSP. None of the 39 has ? ! ; or « ».
- **Agreement with an implied noun.** With the service (masculine): « arrêté » / « Arrêté », « Actif », « Non vérifié », and « Désactivé » in the hub. With « niveau » (masculine): « Désactivé », « Minimal », « Moyen », « Élevé ». « Plus lent » takes the mode or the model, both masculine. With an explicit noun: « réservée » (puissance), « fournie » (la clé), « aucune charge », « le meilleur » (modèle), « inclus » (modèles). All correct.
- **Marketing lines.** The hub pitch, the OCR-only summary and the Capture row subtitle read as native UI copy. The two switch subtitles keep English's telegraphic shape (💬 each). The Translation services pitch ends on a bare participle (⚠️).

### Disposition (2026-10-27)

All seven findings applied: `llm_backend_reasoning_subtitle` « Détermine à quel point… », `pt_cloud_translate_row_subtitle_no_key` « …, déjà inclus », `anki_screenshot_crop_instruction` « Faites glisser les bords pour rogner l\'image », the infinitive switch titles « Reconnaître le texte » / « Traduire le texte », `pt_cloud_ocr_toggle_subtitle` « performante », `pt_cloud_translate_toggle_subtitle` « … inclus : le meilleur est choisi … », and `llm_backend_thinking_subtitle` « C\'est plus lent, … ». One change is the translator's own, not the reviewer's: `pt_cloud_key_hint` « Collez la clé qui vous a été fournie » measured about 282 dp in the 280 dp one-line monospace field (the same field the German review found clipping), so it is now « Collez la clé que vous avez reçue » (about 258 dp). The final review covers it. The cooldown line's English description is a code finding, verified in code; reported, not fixed.

### Round 2 (2026-10-27), final review after applying round 1

Mechanical layer: `python3 SP/mechcheck.py <the 39 keys>` reports "39 keys x 13 locales: 0 problems", with no line for fr (every key present; `<xliff:g>` spans byte-identical to English; placeholders, markup and escapes match; no unescaped `'` or `"`; the file parses as XML); `python3 scripts/l10n_diff.py --locale app/src/main/res/values-fr/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync". **No 🛑 build-breaking issues.**

Round-1 changes checked. Every applied value is byte-identical to round 1's suggestion, and the file's hash equals `SP/locale-hashes-after-fixes.txt`. Against the sync's drafts (`SP/drafts.py`), exactly the six Part A keys below changed. Against `HEAD`, exactly the three Part B keys below changed.
- `llm_backend_reasoning_subtitle`, « Détermine à quel point le modèle réfléchit avant de répondre. … »: confirmed. It is the file's subject-less third-person subtitle (`settings_edge_indicator_subtitle`), « à quel point » carries English's "how much", and the second sentence is unchanged.
- `pt_cloud_translate_row_subtitle_no_key`, « Les meilleurs modèles de traduction payants, déjà inclus »: confirmed. The participle now has its adverb, and the line is still a noun phrase where other rows read « Compte requis ». It wraps (`tvRowSubtitle2`, no `maxLines`).
- `anki_screenshot_crop_instruction`, « Faites glisser les bords pour rogner l\'image »: confirmed.
  - Width: 267 dp (Arial, 14 sp) in the `isSingleLine`, `ellipsize=END` pill. The pill has 272 dp in the workspace card and 296 dp in the review sheets' dialog (re-read in `RegionEditor`, `RegionEditorControls.pill` and `pt_workspace_inset`).
  - Wording: it keeps `region_overlay_drag_instruction`'s opening and the title's « rogner ».
- `pt_cloud_ocr_toggle_title` and `pt_cloud_translate_toggle_title`, « Reconnaître le texte » / « Traduire le texte »: confirmed. Both are infinitives, as the file renders verb-phrase switch titles. « reconnaître » is the file's OCR verb (« Reconnu par … », « Reconnaissance du texte… »).
- `pt_cloud_ocr_toggle_subtitle`, « Reconnaissance plus performante … »: confirmed. « performante » agrees with « reconnaissance », and the puissante/puissance echo is gone.
- `pt_cloud_translate_toggle_subtitle`, « Modèles de traduction payants inclus : le meilleur est choisi pour chaque tâche. »: confirmed. The colon has a plain space before it, like the file's 63 other spaced colons; the file has no NBSP.
- `llm_backend_thinking_subtitle`, « … C\'est plus lent, et … »: confirmed. It matches the sibling's « plus c\'est lent ».
- `pt_cloud_key_hint`, « Collez la clé que vous avez reçue » (the translator's own change): confirmed.
  - Width: 33 characters are 257 dp of advance at 13 sp in a 0.6 em monospace (258 dp measured in Menlo), against the field's 280 dp. The old « … qui vous a été fournie » needed 281 dp.
  - Grammar and wording: « reçue » agrees with the preceding object « que » (la clé), and the register is vous. « Collez » is `hint_deepl_key`'s verb and AOSP's « Coller ». The meaning is still "the key you were given".

#### Findings (round 2)

No findings.

Fresh read of all 39 values (checked, no finding):
- **Error pill.** « PlayTranslate Cloud : arrêté » has the family's shape: a space before the colon, then a lowercase status. Only `PtCloudErrors` raises `NOT_RUNNING`, so the masculine always stands for the service.
- **Cloud page.**
  - Agreement: the adjectives under « Service » agree with it (Actif, Arrêté, Non vérifié).
  - The three values that echo pill strings match them.
  - Width: the longest value, « Réponse inattendue (500) » (150 dp at 13 sp), fits beside the title in the 300 dp row.
  - Key: « Clé client » sits with « Clé d\'API » and « Clé •••• ».
- **Hub** (13 sp, `singleLine`, 216 dp). All four summaries fit on one line; the widest, the pitch, is 179 dp. « Désactivé » is the History cell's off word, masculine for the service.
- **Capture & overlay row.** « OCR côté serveur, aucune charge pour l\'appareil » has the shape of the engine notes below it.
- **Screen-reader labels.** « disponible » / « indisponible » match `backend_status_unavailable_default`.
- **Toast.** It wraps to 2 lines at 296 dp: « Impossible d\'ouvrir cette page. Recherchez » / « plutôt ce paramètre dans les Paramètres. »
- **Part B.**
  - `Locale.ROOT` keeps the accent in DÉCLINAISON.
  - « Mode réflexion » and « Niveau de réflexion » share « réflexion ».
  - « Charger plus (2) » shows « Recherche… » in the same row while it loads.
- **The one tight label.** « Désactivé » measures 57 dp at 13 sp in Arial (61 dp in bold, when it is the active segment), against about 56 dp per segment in a four-segment row. `buildPillToggle` drops it at most one size step, to 12 sp (53 dp, or 56 dp in bold), so it never ellipsizes; Roboto runs narrower than Arial. Round 1 recorded this and passed it, and nothing new changes that.
- **Typography** (checked by script over the 39). No tu form. All six apostrophes are `\'`, with no curly ’. The four colons each have a plain space before them, and there are no « » and no NBSP.

#### Verdict (round 2)

**PASS** (0 🛑, 0 ❌, 0 ⚠️, 0 💬). All seven round-1 fixes and the translator's own `pt_cloud_key_hint` change are in the file as the Disposition says, and none of them broke anything around it. The fresh read of the 39 keys found nothing new. One recorded item stays open, the cloud page's cooldown line in English. It is a code finding and does not block the sync.

## Delta review 2026-11-16 (Ask and word explain, voice service, imported recordings, PlayTranslate Cloud account, Anki notes, Japanese part-of-speech labels: 206 new keys and 7 changed)

Mechanical layer: `mech_check.py --locale fr --aapt2` -> "fr: 213 keys checked, 0 errors, 2 warnings, aapt2 ok" (the two warnings, `pt_cloud_code_label` « Code » and `voice_service_row_service` « Service », are identical to English because the French word is the same; both correct); `l10n_diff.py` against `l10n-sync` -> `missing=0 orphan=0 modified=7`, the seven being the delta's MODIFIED keys, each now carrying the new English meaning. **No 🛑 issues.**

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| pt_cloud_signed_in_with_key | 💬 | Connexion avec une clé | Connecté avec une clé | The English is a state ("Signed in with a key"). The noun « Connexion » reads as a sign-in method, and as the card's title (when no standing is known) it can pass for a heading or an action. « Connecté … » is the usual French state wording (« Connecté en tant que … »). |
| pt_cloud_signin_rate_limited | 💬 | Trop de requêtes. Si vous avez reçu un code, saisissez-le ci-dessous ; sinon, demandez-en un nouveau dans une minute. | Trop de requêtes. Si vous avez reçu un code, saisissez-le ci-dessous ; sinon, envoyez-en un nouveau dans une minute. | The English "send a new one" points at the "Send a new code" button on the same card, and the file's other two mentions use its verb (`pt_cloud_code_invalid` « envoyez un nouveau code », `pt_cloud_key_not_stored` « Envoyez un nouveau code »). « demandez-en » leaves the user looking for a "Demander" button that does not exist. |
| pt_cloud_show_terms | 💬 | Voir les conditions | Consulter | Measured, since the translator flagged it. Nothing clips: the button is `wrap_content` (14 sp bold, 16 dp padding a side), and the column beside it has weight 1 and gives way. At 360 dp, though, the column keeps only about 135 dp (360 − 28 page − 32 card − 12 margin − about 153 button), so the warning title « Conditions non acceptées » (174 dp at 15 sp medium) wraps to two lines, and the line under it runs to four. The same happens at 393 dp (168 dp column); the title fits only at 411. « Consulter » (about 63 dp bold, so a 95 dp button) leaves 193 dp at 360: the title fits on one line and the line wraps to three. The comment asks for a short label, and the line beside it already says what is shown. Optional: the current label is correct. |
| pt_cloud_terms_link | 💬 | conditions d\'utilisation et de confidentialité | conditions d\'utilisation et la politique de confidentialité | Filled in, the checkbox reads « J'accepte les conditions d'utilisation et la politique de confidentialité », the standard French sign-up wording. It names the privacy notice by its usual French name, where « conditions … de confidentialité » is a contraction. It is still grammatical after « les », and it wraps to two lines at 13 sp in the 268 dp column, as the current one already does (393 against 326 dp; the row's minimum height is 64 dp). |
| pt_cloud_ocr_toggle_subtitle | 💬 | Reconnaissance plus performante sur un GPU de serveur, sans solliciter la puissance de votre appareil. | Reconnaissance plus performante sur un GPU de serveur, sans solliciter votre appareil. | The new title above it is « Traitement d\'image puissant », so title and subtitle now say puissant / puissance, and the signed-out headline (« La puissance d\'un serveur … ») is a third on the same screen. The 2026-10-27 review removed this very echo from the subtitle once. « sans solliciter votre appareil » still says that the device does none of the work, which is what the comment asks. |
| pos_nu_verb | 💬 | Verbe nu | Verbe en -nu | After a noun, « nu » reads as the French adjective "bare" (« infinitif nu » is the grammar term for a bare infinitive), so « Verbe nu » can read as "bare verb". « en -nu » is the convention the translator already uses for the adjective classes (« Adjectif en -i »), and the comment defines the class by that ending. The class is archaic and rare, so this is optional. |

### Source-side notes

- Code, not a string (from the code; the translator reported it too). `RootSettingsViewModel.cloudStandingDigest` formats the hub digest's trial end with `CooldownTimeLabel.format`. For a date, that is a fixed `SimpleDateFormat("MMM d")`, so French shows « Fin de l'essai gratuit : nov. 3 » where French writes « 3 nov. ». The account card's dates go through `formatSkeleton` (`getBestDateTimePattern`) and come out day-first, so « Essai gratuit jusqu'au 3 nov. … », « Essai terminé le 3 nov. » and « Abonnement inactif depuis le 3 nov. » read correctly. The translator's colon shape in the digest works with both a time (« 15:42 ») and a date, and it avoids « jusqu'à » / « jusqu'au ».
- The brief describes the Ask hint as sharing its row with two 36 dp buttons. `section_ask.xml` has one, the send button. The second 36 dp `ImageButton` (line 157) is the answer's ×, in the other group. The widths below use the real row.

### Clean areas (checked, no findings)

All widths are measured with Android's own fonts (`Measure.java`, Roboto; bold taken as medium + 4 %) at the widget's text size, against the paddings read from the layouts.

**Ask section and the word page's "In this sentence" group.**
- Wording. « Questions » is a noun header like its neighbours Original / Traduction / Mots. `cd_toggle_ask_visibility` « Afficher ou masquer les questions » copies `cd_toggle_words_visibility`.
- The "Ask needs an AI model" alert reads as one unit. The title « Les questions nécessitent un modèle d'IA » works whether « questions » is read as the section or as the user's questions. The body is « Vos questions … sont envoyées à un modèle d'IA. Ajoutez un service d'IA en ligne, ou activez un modèle sur l'appareil, dans « Services de traduction » ». The button « Services de traduction » is byte-identical to `settings_cell_translation_services`, and so is the quoted name in the body.
- Service names. « Question posée à %s… » and « Réponse fournie par %s » keep « de » / « d' » away from the name, so Gemini, OpenAI and DeepSeek all read correctly. « Réponse fournie par » pairs with « Traduit par » / « Reconnu par ». « %s ne répond qu'aux questions sur cette phrase … » reads correctly with PlayTranslate Cloud.
- Failure and retry. « Impossible d'obtenir une réponse. » is the file's error form, and the reason line under it is the pill family (« Gemini : trop de requêtes »). « Réessayer » is `update_error_retry`.
- Word page. « Dans cette phrase » (DANS CETTE PHRASE, 109 dp at 11 sp) and « Expliquer comment ce mot est employé » (266 dp at 15 sp medium, in a row with no line limit, so it can wrap) both pass.
- Widths:
  - Header « QUESTIONS »: 60 dp at 11 sp medium, `maxLines 1`.
  - Hint « Posez votre propre question… »: 211 dp at 16 sp. At 360 dp the field gets about 276 dp, after the 16 + 4 dp paddings and the one 36 dp send button.
  - Preset chip « Analyse grammaticale » (the natural French term): 120 dp at 12 sp medium, plus 20 dp padding, against the English 113.

**Word list and definitions.**
- « Mots · 6 » keeps `section_words`.
- « Intégré » (INTÉGRÉ, 44 dp) matches `settings_ocr_note_builtin`.
- « Les définitions nécessitent Android System WebView. » keeps the app's name as Android shows it.
- The POS labels sit with the file's « Nom / Verbe / Adjectif » set: capitalized, and no `·`, `・` or `/`; the hyphen in « en -i » is no separator. Each label passes:
  - « Verbe godan / ichidan / suru / kuru » keeps the romanized class, as the comment asks.
  - « Adjectif en -i / -na » is how French grammars of Japanese name these classes.
  - « Transitif » / « Intransitif » agree with the implied « verbe ».
  - « (spécial) » and « (irrégulier) » are masculine like « Verbe ».
  - « Copule », « Non classé », « Prénominal » and « Adverbe en -to » are standard terms.

**Anki.**
- Terms. « Remarques » is both the group header and the Map field option (`anki_group_notes` = `anki_content_notes`). The translator kept « note » for Anki's note object (`anki_first_field_unmapped` « identifie la note »), and that split is sound.
- The alert reads as one unit. « Aucun champ pour votre remarque » / « … aucun champ mappé sur « Remarques » : votre remarque ne peut donc pas être enregistrée sur la carte. Enregistrer la carte sans elle ? » / « Enregistrer quand même ». « mappé » is the picker's own verb (« Mapper « %s » »), and the button is `llm_prompt_save_anyway`.
- The two Yomitan options are « Définitions (format Yomitan) » / « Dictionnaire principal (format Yomitan) ». They sit apart from the plain « Définition », and their descriptions follow the file's « mot mis en évidence » and « Pour le champ « … » de Lapis » patterns.
- Toast. « Ajouté à Anki (service vocal indisponible, voix de l'appareil utilisée) » follows the « (audio indisponible) » family. As a `LENGTH_SHORT` text toast it wraps to two lines at 296 dp (412 dp in total).

**Capture overlay.**
- « Superpositions : %s. Appuyez pour modifier. » matches `floating_menu_panel_overlays`.
- « Aucune » agrees with « superposition ». After the colon it reads « Superpositions : Aucune », where French keeps « aucune » singular.
- The segments are « Définitions » 63 dp and « Aucune » 44 dp at 13 sp medium, no wider than English « Translation » (66). `OverlayModePill` sizes the segments to the widest label, and `buildPillToggle` shrinks from 13 to 10 sp before it ellipsizes.
- « Activer la recherche au toucher » sits with the infinitive icon actions (« Capturer l'écran », « Survoler un mot … »).

**PlayTranslate Cloud: each state as a set.**

| state | pill | hub | page |
|---|---|---|---|
| trial | — | « Fin de l'essai gratuit : … » | « Essai gratuit jusqu'au … » |
| trial ended | « essai gratuit terminé » | « Essai terminé » | « Essai terminé (le …) » |
| membership inactive | « abonnement inactif » | « Abonnement inactif » | « Abonnement inactif depuis le … » / « Aucun abonnement actif » |
| terms | « conditions non acceptées » | « Conditions non acceptées » | « Conditions non acceptées » |
| closed | « service fermé » | — | status « Fermé », card « PlayTranslate Cloud a fermé » |

- Each state uses one noun on every surface, and the five stay distinct. Where the English says "Free trial" or "Trial", the French follows it.
- Membership is « abonnement » throughout, with « Membre », « Déjà membre ? », « Rejoindre » and « Abonnez-vous » around it.
- The new status values « Hors ligne pour le moment » and « Fermé » agree with « Service », like « Actif » and « Arrêté ».
- Widths:
  - Pills: `TranslationErrorPills`, 14 sp, `maxLines 2`. The text gets about 174 dp at 360 dp (328 dp pill, minus the logo, two 48 dp buttons and the paddings). The widest, « PlayTranslate Cloud : conditions non acceptées » (295 dp), takes two lines: « PlayTranslate Cloud : » is 135 dp and the rest 160.
  - Hub digests (13 sp, one line, 216 dp): at most 160 dp, « Fin de l'essai gratuit : nov. 3 ».
  - Toolbar titles at Material 3's 22 sp: « PlayTranslate Cloud (alpha) » 270 dp and « Conditions et confidentialité » 275 dp, against about 296 dp at 360 dp (the 56 dp navigation button, 0 dp inset with navigation, 4 dp title margins).
- Headline (measured, since the translator flagged it): 26 sp bold, `wrap_content` with no line limit, 324 dp wide at 360 dp. « La puissance d'un serveur / pour le traitement d'image / et la traduction » takes three lines (each piece is about 301 to 305 dp before the -0.02 letter spacing) where English takes two. It reads as native marketing copy, and « traitement d'image » matches the feature row.
- « Essayer PlayTranslate Cloud gratuitement » wraps to two lines (about 293 dp bold at 15 sp) in its full-width 56 dp button, where the text gets 252 dp at 360 and 285 at 393 (it fits at 411). Nothing clips: the button has no line limit, and two 15 sp lines (about 35 dp) fit its 44 dp of content height. Every shorter wording either drops "for Free" or the brand the English names, so no row.
- The email hint « vous@example.com » is 118 dp at 13 sp, one line.
- The sign-in flow:
  - « Commencer l'essai gratuit », « Envoyer un nouveau code » and « Utiliser une autre adresse e-mail » (203 dp, a `wrap_content` text button).
  - The plural « Il est valable 1 minute. » / « … 10 minutes. ». French "one" covers 0 and 1, and 0 takes the singular.
  - Every notice, with « Réessayez plus tard » / « Réessayez dans une minute » and « Impossible de joindre PlayTranslate Cloud ». « Ce code n'a pas fonctionné … envoyez un nouveau code » echoes the button.
  - « Se déconnecter de PlayTranslate Cloud ? », with the button « Se déconnecter ».
- Terms:
  - « J'accepte les %s » with the link filled in is grammatical (see the 💬 for the noun).
  - « Acceptez les conditions pour continuer. », « Lisez et acceptez les conditions pour continuer à utiliser … », « En pause jusqu'à l'acceptation des conditions. » and « Accepter » all pass.
- The 23 cloud keys of the last sync on the same page still agree with the new ones (« Fonctionnalités », the two new switch titles, « OCR côté serveur … »).

**Voice service.**
- The Text-to-speech page rows (« Service vocal », « Connecter un service vocal », « Paramètres du service », « Aucune voix », « Aucun moteur de synthèse vocale ») use the file's « synthèse vocale », and « voix IA » uses the chip's word.
- The failure family reads as a set under « Le service vocal n'a pas répondu »: « Le service a refusé la clé d'API. », « … a atteint sa limite de requêtes ou épuisé son quota. », « … n'a pas répondu à temps. », « Impossible de joindre le service. Vérifiez l'URL et votre connexion. », « … a rencontré une erreur (HTTP 503). », « … a refusé la requête (HTTP 400). ».
- Cross-references are byte-identical:
  - « dans « Paramètres du service » » = `voice_service_settings_row`.
  - « Appuyez sur « Modèle » » (twice) = `voice_service_row_model`.
  - « annulez » names the standard Cancel.
  - « Enregistrer quand même » = `llm_prompt_save_anyway`.
- The tokens `{text}`, `{voice}`, `{model}`, `{instructions}`, `{speed}`, `{language}`, `{language_name}` and `{key}` are untouched, and the format names MP3, WAV, Ogg and M4A stay as written.
- Labels: « Clé d'API » and « Modèle » match `llm_backend_*`, « identifiant de modèle » matches `llm_model_picker_fetch_failed`, and « Retirer le service » / « Retirer le service vocal ? » match `tr_service_remove_confirm` « Retirer ».
- With values dropped in: « La requête demandait la voix Coral et le modèle gpt-4o-mini-tts. », « … ne contient pas tts-1-hd. », « Le modèle tts-1 ne peut pas utiliser les voix ci-dessous. ».

**Imported recordings.**
- « Enregistrements importés » and « Fichier android.db » keep the file name as is.
- No string names a language, the file's community name or a website. « Vous devez vous procurer ce fichier vous-même » carries "source it yourself".
- The progress popup works with real sizes: « Chargement du fichier android.db » / « 1,2 Go sur 5,8 Go copiés » (Android's French formatter writes « Go ») / « Vérification du fichier… ».
- The remove confirmation reads as one unit: « Supprimer le fichier android.db ? » / « Le fichier est supprimé de l'application, et chaque mot est de nouveau prononcé par la synthèse vocale. » / « Supprimer le fichier android.db ». « Supprimer » fits a file that is deleted, as `settings_ocr_disable_delete` does for models.
- The failure lines (« Impossible de charger le fichier », « Ce fichier est incomplet : 2,1 Go sur 5,8 Go. … », the database's English cause after a colon) read correctly.

**The 7 MODIFIED keys.**
- The new English meaning landed in each, and the old one is gone. « Traitement d'image puissant » and « Traductions premium » replace the infinitives, and the subtitle no longer says the power goes to the game.
- "AI" is « IA » in all six places: the chip `service_llm_badge`, the header « Configuration IA avancée », « traducteurs IA » in both subtitles, and « modèle d'IA » / « service d'IA » in the Ask alert. The only « LLM » left in the file is the debug row `settings_debug_force_mmap_weights`, as the glossary says.

**Typography, by script over all 213 values.**
- Apostrophes: every one is `\'`, with no curly ’ and no raw `'` or `"`.
- Spacing: every `:`, `?`, `!` and `;` has a space before it. It is a plain space, as in the rest of the file, which has no NBSP.
- Guillemets: « » with a space inside, everywhere.
- The ellipsis is the … character.
- No tu, ton, ta or toi.

### French appendix

- **Elision with service names.** Where `%s` can be any service, no delta string puts « de », « le » or « que » directly before it. The translator wrote around it each time: « Question posée à », « Réponse fournie par », the name as subject (« %s ne répond qu'aux… », « %s est hors ligne… »), « Rejoindre %s », « Essayer %s gratuitement », « continuer à utiliser %s ». `pt_cloud_sign_out_title` « Se déconnecter de %s ? » and `pt_cloud_signin_unreachable` « Impossible de joindre %s » get only `pt_cloud_display_name` (PlayTranslate Cloud), per their comments, so « de » never meets a vowel.
- **Dates.** On the account card, `getBestDateTimePattern` gives French a day-first date, so « jusqu'au », « le » and « depuis le » are right before « 3 nov. », « 3 nov. 2025 » or a date with a time (from the code; not seen on a device). In the hub digest, the colon takes both of `CooldownTimeLabel.format`'s outputs, a time or the fixed « nov. 3 » (see the source-side note).
- **Agreement.** « Fermé », « Hors ligne », « Actif » and « Arrêté » agree with « Service ». « Aucune » agrees with « superposition ». In « conditions non acceptées », « essai terminé » and « abonnement inactif », each participle or adjective agrees with its noun. « Transitif » and « (irrégulier) » agree with « verbe ».
- **Plurals.** `pt_cloud_code_sent_to` has one/other, both with « minute(s) » agreeing.

### Disposition (2026-11-16)

All 6 findings in the table applied byte-exact (1 applied: `💬`; 5 💬: `pt_cloud_signin_rate_limited`, `pt_cloud_show_terms`, `pt_cloud_terms_link`, `pt_cloud_ocr_toggle_subtitle`, `pos_nu_verb`). The CooldownTimeLabel.format date order (fixed "MMM d") is a code finding reported to the developer. After applying: `fr: 213 keys checked, 0 errors, 2 warnings, aapt2 ok`; only delta keys changed (scope check).

### Round 2 (2026-11-16), final review after applying round 1

Mechanical layer: `mech_check.py --locale fr --aapt2` -> "fr: 213 keys checked, 0 errors, 2 warnings, aapt2 ok" (the two standing warnings, `pt_cloud_code_label` « Code » and `voice_service_row_service` « Service », are correct French identical to English); `l10n_diff.py` against `l10n-sync` -> `values-fr missing=0 orphan=0 modified=7` (the seven changed keys, each now carrying the new English); README `readme_l10n_check.py fr` -> `[PASS] fr -> readme/README.fr.md` with the two standing "table 1/2 header cells left in English: ['Code']" warnings (false positives).

Round-1 changes checked (each value compared byte for byte with the table's `suggested` cell; all six landed):
- `pt_cloud_signed_in_with_key` « Connecté avec une clé »: landed. It reads as a state both under « Essai gratuit jusqu'au … » / « Membre jusqu'au … » and as the card's title alone. (The Disposition's "1 applied: `💬`" stands for this key; the file holds it.)
- `pt_cloud_signin_rate_limited` « … sinon, envoyez-en un nouveau dans une minute. »: landed. It now echoes the button « Envoyer un nouveau code », as `pt_cloud_code_invalid` and `pt_cloud_key_not_stored` already did.
- `pt_cloud_show_terms` « Consulter »: landed. No string quotes the old « Voir les conditions » (grep of the file: none), and the key renders only in `activity_pt_cloud_settings.xml:116`.
- `pt_cloud_terms_link` « conditions d'utilisation et la politique de confidentialité »: landed. Filled into `pt_cloud_agree_terms` it reads « J'accepte les conditions d'utilisation et la politique de confidentialité », and `bindAgreeSentence` finds the link by `indexOf`, so the span still covers it. Measured at 13 sp: « J'accepte les » 78.7 dp + the medium link 317.8 dp, two lines in the 268 dp column (no `maxLines`, the row's minimum 64 dp). The page title `pt_cloud_terms_title` « Conditions et confidentialité » need not match: the comment gives the link its own form.
- `pt_cloud_ocr_toggle_subtitle` « … sans solliciter votre appareil. »: landed; the puissant / puissance echo is gone from the row.
- `pos_nu_verb` « Verbe en -nu »: landed, and it is right on its own. It leaves one neighbour asymmetric: see the 💬 on `pos_ri_verb` below.

#### Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| pos_ri_verb | 💬 | Verbe ri | Verbe en -ri | A neighbour of the applied `pos_nu_verb` fix. The dictionary's two classical irregular classes are ナ変 (`pos_nu_verb`) and ラ変 (`pos_ri_verb`), and their comments define both by the ending ("ending in ぬ", "whose plain form ends in り"). After round 1 the first reads « Verbe en -nu » and the second still « Verbe ri », so the pair follows two conventions. « en -ri » is the shape the file uses for every ending-defined class (« Adjectif en -i », « Adverbe en -to »). The two labels never stand on the same word and the class is archaic, so this is optional. |
| voice_service_manual_voices_hint | 💬 | Pour un service qui ne peut pas lister ses propres voix. Ils s\'ajoutent aux voix que l\'application connaît déjà pour ce service. | Pour un service qui ne peut pas lister ses propres voix. Ces identifiants s\'ajoutent aux voix que l\'application connaît déjà pour ce service. | The hint is a `Text.PT.RowSubtitle` under the field (`activity_online_tts_service.xml:271`). « Ils » has no antecedent in its own text: the nearest masculine noun is « service », singular, and the « Identifiants de voix » it means are in the label above the field. English can drop the subject ("Added to…"); French needs one, so the noun is named. |

Fresh read by screen, over all 213 keys (`S/delta_en.xml` with each comment beside the French value):
- **Ask and "In this sentence".** « Questions » as the header, the alert's title, body and button read as one unit, and the button and the quoted page name are byte-identical to `settings_cell_translation_services`. « Question posée à %s… », « Réponse fournie par %s » and « %s ne répond qu'aux questions … » keep « de » away from the name. The failure is « Impossible d'obtenir une réponse. » with « Réessayer ». « Dans cette phrase » and « Expliquer comment ce mot est employé » sit together. Round 1's widths stand, since no string here changed.
- **Word list and POS.** « Mots · 6 » and « Intégré » pass. All 26 new POS labels are capitalized like the file's « Nom / Verbe », and none holds `·`, `・` or `/`. The ending convention is checked above.
- **Anki.** « Remarques » is both the group header and the Map option, and « note » stays Anki's object. The alert reads as one unit: « Aucun champ pour votre remarque », « … mappé sur « Remarques » … », « Enregistrer quand même ». The Yomitan options and descriptions pass, and so does the voice fallback toast.
- **Capture overlay.** « Aucune » agrees with « superpositions » (`settings_overlay_mode_subtitle` « Quelles superpositions afficher … »). « Superpositions : %s. Appuyez pour modifier. » takes each mode name. « Activer la recherche au toucher » is the only place the mode is named, so nothing can disagree with it.
- **PlayTranslate Cloud.** Each state is still one noun on the pill, the hub and the page, with the new « Connecté avec une clé » and « Consulter » in place. The dates: `CooldownTimeLabel.formatSkeleton` builds its pattern with `getBestDateTimePattern` from `MMMd` / `yMMMd` plus `Hm` / `hm`. French puts the day first, so « jusqu'au », « le » and « depuis le » come before a day number (from the code). The hub digest's fixed « nov. 3 » is the code finding round 1 already reported. Sign-in notices, the sign-out alert, the terms flow and the join card all pass.
- **Voice service.** The failure lines read as a family under « Le service vocal n'a pas répondu ». The cross-references are byte-identical: « Paramètres du service », « Modèle » ×2, the standard Cancel and « Enregistrer quand même ». The tokens `{text}` `{voice}` `{model}` `{instructions}` `{speed}` `{language}` `{language_name}` `{key}` are untouched. « Clé d'API » and « Retirer » follow the file. The one gap is the hint's pronoun (💬 above).
- **Imported recordings.** No string names a language, the file's community name or a website. The progress popup, the remove confirmation (« Supprimer » for a file that is deleted) and the failure lines read correctly with sizes such as « 1,2 Go ».
- **The 7 MODIFIED keys.** Each carries the new English. "AI" is « IA » in all of them: the chip, « Configuration IA avancée », « traducteurs IA » ×2, and « modèle d'IA » / « service d'IA » in the Ask alert.
- **Typography, by script over all 213 values after the fixes.** No raw `'` or `"` and no curly apostrophe. A space before every `:` `;` `?` `!`, and « » with inner spaces. No NBSP, as in the rest of the file. The … character throughout, and no tu, te, ton, ta or toi.

#### Verdict (round 2)

**PASS**: 🛑 0 / ❌ 0 / ⚠️ 0 / 💬 2 (`pos_ri_verb`, `voice_service_manual_voices_hint`), both optional.

Correction to the `pos_ri_verb` note above: « en -X » is the file's shape for the adjective and adverb classes, not for every class defined by its ending. « Verbe suru », « Verbe su » and « Verbe zuru » keep the bare word, and « Verbe nu » became « Verbe en -nu » because « nu » reads as the French adjective. What the 💬 rests on is the ナ変 / ラ変 pair, two sibling classical irregular classes with parallel comments. It stays optional, and leaving « Verbe ri » as it is is equally defensible.

**Correction to the round-1 Disposition (2026-11-16):** its list names the first applied finding as "1 applied: `💬`", a slip of the coordinator's script; that finding is 💬 `pt_cloud_signed_in_with_key`, applied byte-exact like the others.

#### Disposition (round 2, 2026-11-16)

Applied byte-exact at the developer's request: `voice_service_manual_voices_hint` («Ces identifiants…»). Not applied: `pos_ri_verb`, whose note itself weakens the case (« Verbe su » and « Verbe zuru » also keep the bare word), so it stays a judgment call left as is. After applying: mech_check 0 errors, aapt2 ok, only delta keys changed, `:app:processDebugResources` green.

## Delta review 2026-11-23 (translation service picker, detached window, Auto Copy to Clipboard, text-to-speech auto-play, AI Voice Service: 27 new keys, 1 changed, 1 orphan removed)

Mechanical layer: `mech_check.py fr` -> "fr: 28 keys checked, 0 problems, 1 warnings" (`warn detached_menu_group_capture: identical to English`: « Capture » is the French noun, spelled as in English, like the standing « Code » / « Service » warnings); `l10n_diff.py --english-base en-base.xml` -> `values-fr   missing=0  orphan=0  modified=1`, the one being `audio_source_voice_service_name` (base "Voice service", now "AI Voice Service", loc « Service vocal IA »), listed by design. `git diff` of the locale file holds only the 27 additions, the one replaced line and the removed `tts_voices_section_header`; nothing outside the delta changed. My own pass over the 28 values: every apostrophe `\'`, no raw `'` or `"`, no curly ’, no NBSP, no em or en dash, « » with inner spaces, no `:` `;` `?` `!` in the delta, the … character not needed, no tu / te / ton / ta / toi. **No 🛑 issues.**

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| tts_auto_play_words_subtitle | 💬 | Lit à voix haute chaque mot que vous recherchez | Lit à voix haute chaque mot que vous recherchez. | The two auto-play rows stand one under the other with the same frame (« Lit à voix haute … »), and the second, `tts_auto_play_original_subtitle`, ends with a period. English has no period on the first because it is a fragment ("When you look up a word"); the French made it a full sentence like its sibling, so the missing period is now the only difference between two parallel sentences on one card. 285.0 dp at 13 sp with the period against 281.6 without: the same wrap. Optional. |

### Clean areas (checked, no findings)

Widths measured with `Measure.java` (Roboto) at each widget's size; constraints taken from the code where a claim depends on them (`DetachedWindow.kt`, `DetachedWindowBar.kt`, `TtsVoiceActivity.kt`, `settings_row_switch.xml` + `Text.PT.RowTitle`).

**Translation service picker, as one alert.**
- Title « Choisir le service de traduction » mirrors `ocr_picker_title` « Choisir l'outil OCR » (infinitive + definite article), and `cd_choose_translation_service` is byte-identical to it, as `cd_choose_ocr` = `ocr_picker_title`. Read after the credit « Traduit par DeepL » it names the gear's action.
- Message « Le service que vous choisissez traduit à nouveau ce texte. Vos paramètres restent inchangés. » carries both facts of the comment (this text only, no setting changed). Its subject is the service to be picked, so it points at the service buttons under « Traductions en ligne » / « Traductions hors ligne » and cannot be read as describing the footer's « Services de traduction ». The present tense for a choice not yet made is ordinary French UI copy; a future (« que vous choisirez … traduira ») would also do and is not better enough to ask for.
- Footer « Services de traduction » / « Annuler » are existing keys; nothing in the message quotes them, so no cross-reference to match.
- Failure alert: « Impossible de traduire » is byte-identical to `anki_translation_error` and `hold_translation_failed` and works as a title. « %1$s n'a pas pu traduire ce texte. » with the name as subject reads correctly with Firefox Translations, DeepL, Gemini, ML Kit, Hunyuan-MT 7B and a user-typed name: no article, no « de » / « le » / « que » before the placeholder, and the passé composé with avoir needs no agreement with the name's gender or number (« Firefox Translations n'a pas pu … » is grammatical as a brand). Then `btn_ok` « OK ».

**Detached window.**
- Gestures page: `icon_action_pull_out_window` « Sortir la fenêtre de résultats » is an infinitive like its siblings (« Survoler un mot pour voir sa définition », « Afficher les traductions à l'écran », « Capturer l'écran »), under `icon_gesture_drag` « Faire glisser ». « Sortir » keeps the drawer image (sortir un tiroir) without « tirer », which would read as shoot/print. It names the window « fenêtre de résultats », and the hint then says « cette fenêtre »; no other French string names the window, so there is nothing to disagree with. 185.0 dp at 15 sp, shorter than the 253.6 dp sibling.
- Panel row `floating_menu_panel_detach` « Détacher » is a command beside « Ouvrir PlayTranslate », as English "Detach" beside "Open PlayTranslate"; the usual French UI verb (« Détacher l'onglet »). 60.5 dp.
- Bar: `detached_bar_capture` « Capturer » is the command, the verb of `icon_action_capture_screen` « Capturer l'écran », and differs from the noun heading `detached_menu_group_capture` « Capture » as the brief asks. Pill at 14 sp regular: label 53.8 dp, so 11 + 18 + 6 + 53.8 + 14 = 102.8 dp against English 98.1 dp; beside it Auto 29.4 dp / Pause 38.8 dp, the existing labels. `DetachedWindowBar.applyFit` drops both pills' names before anything clips, so the 4.7 dp only moves that point. `detached_bar_minimize` « Réduire » is the standard French window-control word for minimize (51.4 dp as a menu row).
- Idle text « Appuyez sur « Capturer » pour traduire l'écran de jeu »: the quoted name is byte-identical to `detached_bar_capture`; « l'écran de jeu » is the file's phrase (`status_idle`, `label_region_drag_hint`, `anki_content_picture_desc`). The file quotes button names both ways (`status_idle` « Appuyez sur Traduire » bare, `voice_service_no_model_list_type_model` « Appuyez sur « Modèle » » quoted); with a verb as the button's name the guillemets are the clearer of the two. No final period, as English and as `status_idle`.
- Hint « Faites glisser la barre du haut pour déplacer cette fenêtre. Faites glisser un coin inférieur pour la redimensionner. Tout le reste se trouve dans le menu ⋯. »: three short sentences, « Faites glisser » as `floating_menu_drag_instruction` and `label_region_drag_hint`, the ⋯ kept as is.
- Menu headings at 12 sp medium (room 248 dp): « Ce résultat » 58.3, « Texte » 30.2, « Capture » 42.4, with `app_name` « PlayTranslate » 74.0 as the fourth. Read as a set they are four short noun headings, none a sentence. Under them: « Ajouter à Anki » / « Lire à voix haute »; « Furigana », the eye rows, « Taille du texte »; « Modifier la zone », the languages, the OCR rows; « Ouvrir PlayTranslate » / « Désactiver » or « Masquer ».
- Menu rows at 15 sp medium (room 208 dp): « Masquer l'original » 119.6, « Afficher l'original » 114.4, « Masquer la traduction » 147.4, « Afficher la traduction » 142.2, « Réduire » 51.4; at font scale 1.3 the widest is 191.6 dp, still inside 208. The two pairs use one verb pair, Masquer / Afficher, identically, and match the eye buttons' spoken labels `cd_toggle_original_visibility` « Afficher ou masquer l'original » and `cd_toggle_translation_visibility` « Afficher ou masquer la traduction ». The PlayTranslate group's « Masquer » (hide the icon) is the same verb in the same menu, as English has "Hide" and "Hide original" together.

**Auto Copy to Clipboard (Overlay group, last row).**
- Title « Copie automatique dans le presse-papiers » is in sentence case like its neighbour « Indicateur de bordure ». « presse-papiers » is Android's French word (the system's « Copié dans le presse-papiers »); the file had no clipboard term before, only « Copié » / « Copier ». 283.3 dp at 15 sp medium: two lines on a 360 dp phone, and `Text.PT.RowTitle` has no line limit, so it wraps; every shorter wording drops "automatic" or the Android term.
- Subtitle « Copie le texte original après chaque capture, sauf pendant la traduction automatique. » follows the group's subtitle form (third-person verb with no subject, as « Affiche une légère lueur … » above it). Joining English's two sentences with « sauf » avoids the verbless calque "Not during auto-translate." and states the one-off-capture rule clearly. « texte original » is `tts_auto_play_original_title`'s term (and `cd_read_original_aloud`'s), « traduction automatique » is `settings_header_auto_translate`. In French that phrase also means machine translation in general; it is the file's established name for the mode, and « pendant » with « après chaque capture » makes it read as the mode, so not a finding. The words « après chaque capture, sauf pendant la traduction automatique » are byte-identical in the TTS subtitle, as the brief asks.

**Text-to-speech page.**
- Voice row subtitle parts « En ligne » / « Hors ligne »: short standalone status words, capitalized like the `tts_voice_needs_download` « Téléchargement requis » that can follow them, and the file's « en ligne » / « hors ligne » (`settings_header_online_translations` / `_offline_translations`). « High quality · Hors ligne · Téléchargement requis » reads as three tags.
- Header « Lecture automatique » (LECTURE AUTOMATIQUE: 125.5 dp + 19 × 1.32 = 150.6 dp at 11 sp, wraps anyway) is the usual French name of the setting. « lecture » also means a kana reading elsewhere in the file (`anki_content_reading`), but on the Synthèse vocale page, over « Mots » and « Texte original », it can only mean playback.
- Rows: « Mots » = `section_words`; « Texte original » is the text under the results' « Original » header, not the translation. Subtitles « Lit à voix haute chaque mot que vous recherchez » and « Lit à voix haute le texte original après chaque capture, sauf pendant la traduction automatique. » share one frame; « rechercher » is the file's look-up verb (`llm_prompt_row_translation_subtitle` « chaque expression que vous recherchez », `onboarding_a11y_row_lookup_title`). The one asymmetry, the period, is the 💬 above.
- MODIFIED `audio_source_voice_service_name` « Service vocal IA »: carries the new English, uses the file's « IA » (`service_llm_badge` « IA », `settings_header_advanced_llm` « Configuration IA avancée », `voice_service_connect_subtitle` « voix IA »), and is not « en ligne ». As the toolbar title at 22 sp regular: 154.7 dp (English 157.7) against 296 dp on a 360 dp phone; 201.0 dp at font scale 1.3. As the page's and the Anki audio picker's group header: SERVICE VOCAL IA, 94.4 + 16 × 1.32 = 115.5 dp, wraps anyway (`settings_group_header`). The other `voice_service_*` strings keep « service vocal » (« Connecter un service vocal », « Le service vocal n'a pas répondu », « Retirer le service vocal ? »), as English keeps "voice service"; no French string quotes the old name.

**Orphan.** `tts_voices_section_header` is gone from the file, with nothing else on that line touched.

### Source-side notes

- Code, not a string (known, in the brief and in the translator's hand-back): `TtsVoiceLabels.subtitle` hard-codes the quality part in English, so a French voice row reads « High quality · En ligne ».
- English comment, stale after this delta: `anki_added_voice_fell_back`'s comment ends with "\"Voice service\" is the name in audio_source_voice_service_name", which is now "AI Voice Service". The string itself still says "voice service unavailable", and French follows it (« service vocal indisponible »), which is right by the brief's rule that only the name changed; the comment should say so or drop the sentence, so the next localizer does not "fix" the toast to match the name.

### French appendix

- **Placeholder.** The delta has one: `translation_pick_failed_generic`, with the service name as the sentence's subject and nothing elided against it (see above). No language name is filled in anywhere in the delta, so no colon restructuring was needed.
- **Verb / noun pairs.** Capturer (bar command) / Capture (menu heading); Copie (title, noun) / Copie (subtitle, verb, « Copie le texte … »): the second pair reads unambiguously because the subtitle's object follows at once.
- **Register.** Every string that addresses the user uses vous (« que vous choisissez », « Vos paramètres », « que vous recherchez »); the rest is impersonal or imperative (« Appuyez », « Faites glisser »).

### Verdict (round 1)

**PASS**: 🛑 0 / ❌ 0 / ⚠️ 0 / 💬 1 (`tts_auto_play_words_subtitle`, optional final period).

### Disposition (round 1, 2026-11-23)

Applied by the coordinator: `tts_auto_play_words_subtitle` (💬, final period, now parallel with the row below). Nothing left open. Mechanical check after the fix: `fr: 28 keys checked, 0 problems, 1 warnings` (the warning is « Capture » being the French noun).

### Round 2 (2026-11-23), final review after applying round 1

Mechanical layer: `mech_check.py fr` -> "fr: 28 keys checked, 0 problems, 1 warnings" / "warn detached_menu_group_capture: identical to English" (« Capture » is the French noun, spelled as in English; correct, like the standing « Code » / « Service »). `l10n_diff.py --english-base en-base.xml` -> "values-fr   missing=0  orphan=0  modified=1", the one being `audio_source_voice_service_name` (base "Voice service", now "AI Voice Service", loc « Service vocal IA »), listed by design. `git diff` of the locale file (read-only): the 27 additions, the one replaced line, the removed `tts_voices_section_header`, nothing else. A script over the 28 values: every apostrophe `\'`, no raw `'` or `"`, no curly apostrophe, no NBSP, no em or en dash, « » with inner spaces, no `:` `;` `?` `!` without a space before it, no tu / te / ton / ta / toi. **No 🛑 issues.**

Round-1 change checked: `tts_auto_play_words_subtitle` « Lit à voix haute chaque mot que vous recherchez. » landed byte-exact. The two auto-play subtitles are now two parallel full sentences with the same frame and the same final period; no string quotes this one, and the period does not change its wrap (round 1's 285.0 against 281.6 dp at 13 sp). It introduced no new problem.

#### Findings (round 2)

None.

#### Clean areas (round 2)

Widths re-measured with `Measure.java` (Roboto) and the constraints read in the code (`DetachedWindow.kt`, `DetachedWindowBar.kt`, `TtsVoiceActivity.kt`, `OnlineTtsServiceActivity.kt`, `CaptureOverlaySettingsActivity.kt`, `settings_row_switch.xml`, `AudioSelection.kt`); every figure matches round 1's.

- **Picker, as one alert.** « Choisir le service de traduction » mirrors `ocr_picker_title` « Choisir l'outil OCR », and the gear's spoken label is the same text, as `cd_choose_ocr` = `ocr_picker_title`; read after « Traduit par DeepL » it names the gear. The message's subject, « Le service que vous choisissez », points at the service buttons under « Traductions en ligne » / « Traductions hors ligne », not at the footer's « Services de traduction », and it states both facts (this text again, settings unchanged). No string quotes the footer buttons. The failure alert reads as one unit: « Impossible de traduire » (byte-identical to `anki_translation_error` / `hold_translation_failed`), « DeepL n'a pas pu traduire ce texte. », « OK »; with Firefox Translations, ML Kit, Hunyuan-MT 7B or a typed name the placeholder is the subject, with nothing elided against it.
- **Detached window.** The bar: Auto / Pause (existing) beside « Capturer », the pill 11 + 18 + 6 + 53.8 + 14 = 102.8 dp; `applyFit` drops the names before anything clips. `detached_bar_minimize` « Réduire » is pinned on the bar as an icon (its spoken label; `DetachedWindowBar` never folds it), Android's French minimize word. The idle text quotes « Capturer », the bar's own label and also the row's label when a very narrow bar folds Capture into the menu (same `HeaderAction`); below the page width the card is one capture button and the idle text is not shown, so it can never name a button that is not there. The hint keeps ⋯ and says « cette fenêtre » after the gestures page's « fenêtre de résultats ». The menu: headings « Ce résultat » 58.3 / « Texte » 30.2 / « Capture » 42.4 dp at 12 sp medium (room 248), with « PlayTranslate » fourth: four short noun headings. Rows at 15 sp medium (room 208): « Masquer l'original » 119.6, « Afficher l'original » 114.4, « Masquer la traduction » 147.4, « Afficher la traduction » 142.2; 191.6 dp at font scale 1.3. One verb pair, Masquer / Afficher, in both pairs and in the eye buttons' spoken labels; the PlayTranslate group's « Masquer » / « Désactiver » use the same verbs. « Capture » (heading, noun) over « Capturer » (folded row, command) reads correctly. The panel row « Détacher » is a command beside « Ouvrir PlayTranslate ». The gesture row « Sortir la fenêtre de résultats » is an infinitive like its siblings under « Faire glisser » and is shorter than « Survoler un mot pour voir sa définition », so any limit of the cell is met first by the existing sibling. Round 1's « Sortir » (the drawer image, which « tirer » would blur) is not re-opened: no new evidence.
- **Auto Copy to Clipboard.** « Copie automatique dans le presse-papiers » is a noun phrase in sentence case like « Indicateur de bordure » above it, with Android's « presse-papiers » (and its « dans », as « Copié dans le presse-papiers »); 283.3 dp, two lines, and `Text.PT.RowTitle` sets no line limit. The subtitle « Copie le texte original après chaque capture, sauf pendant la traduction automatique. » has the group's subject-less third-person form (« Affiche une légère lueur … »), states the one-off-capture rule in one sentence where English's verbless "Not during auto-translate." would be a calque, and shares « après chaque capture, sauf pendant la traduction automatique » byte for byte with `tts_auto_play_original_subtitle`; « texte original » and « traduction automatique » are the file's terms.
- **Text-to-speech page.** The page's headers as a set: ENREGISTREMENTS IMPORTÉS, SERVICE VOCAL IA, SYNTHÈSE VOCALE (or the engine's name), LECTURE AUTOMATIQUE, all noun phrases. « Mots » = `section_words`, « Texte original » = the text under « Original »; the two subtitles share « Lit à voix haute … ». « En ligne » / « Hors ligne » are short status tags beside « Téléchargement requis ». « Lecture » here can only mean playback (the file's other « Lecture » is `anki_content_reading`, on the Anki page).
- **AI Voice Service.** « Service vocal IA » uses the file's « IA » (chip « IA », « Configuration IA avancée », « voix IA »), not « en ligne ». Toolbar at 22 sp: 154.7 dp, 201.0 at scale 1.3, against 296 dp. It also serves as the Anki audio picker's section title and, from `OnlineTtsAudioSource.selectionLabel`, as a pill's fallback label when a selection has no voice name; a noun phrase reads right in all three. The other `voice_service_*` strings and `anki_added_voice_fell_back` keep « service vocal », as English keeps "voice service".
- **Register and orphan.** vous wherever the user is addressed (« que vous choisissez », « Vos paramètres », « que vous recherchez »), imperatives elsewhere (« Appuyez », « Faites glisser »). `tts_voices_section_header` is gone with nothing else on its line touched.

#### Verdict (round 2)

**PASS**: 🛑 0 / ❌ 0 / ⚠️ 0 / 💬 0. Nothing for the coordinator to decide.

### Disposition (round 2, 2026-11-23)

Round 2 found nothing. Applied after the final review at the developer's request, by the coordinator, without a further review round: `ocr_picker_message` (outside the delta) gets the final stop its English has, as its new twin `translation_picker_message` does. Analyzer, mechanical check and `:app:processDebugResources` green afterwards.

## Feature-time review 2026-11-23 (voice quality levels, the detached Capture button's spoken name: 7 keys localized with the code change)

Mechanical: `python3 scripts/l10n_diff.py --locale app/src/main/res/values-fr/strings.xml` -> "values-fr   missing=0  orphan=0  modified=0"; each of the 7 keys has English's previous and next key as its neighbours (the two spoken names after `detached_bar_capture`, before `detached_bar_minimize`; the five levels after `tts_voice_needs_download`, before `tts_voice_online`); no raw quote or apostrophe (`Capturer l\'écran` escapes its apostrophe), no em dash, no leading or trailing space, no line break. **No 🛑 issues.**

### Findings

None.

### Checked (no findings)

- **Values:** «Très haute qualité» / «Haute qualité» / «Qualité standard» / «Basse qualité» / «Très basse qualité».
- **The five levels as a set:** one noun, «qualité», the word of `note_mlkit_*` («qualité inférieure») and `offline_backend_quality_label`; haute / basse with «Très» at both ends. The middle level puts the noun first because «standard» is postnominal in French; «haute qualité» / «qualité standard» / «basse qualité» is the ordinary French pairing and the file already mixes orders on its other scale («Mauvaise qualité», «Qualité correcte»). «standard» does not collide with the default voice, which is «Par défaut» in this file, and it matches the comment's "a standard voice". Weighed and not raised: an all-postnominal set («Qualité élevée … Qualité faible») would be equally natural and no clearer.
- **Style against what follows:** sentence case like «En ligne» / «Hors ligne» / «Téléchargement requis».
- **The spoken names:** «Capturer l\'écran» is `icon_action_capture_screen` byte for byte; «Capturer la zone» uses «zone», the word of `floating_menu_btn_capture_region` «Zone de\ncapture», with the article of its sibling `floating_menu_edit_region` «Modifier la zone» (the zone the user set). Both hold `detached_bar_capture` «Capturer» as a word; infinitive commands as in the rest of the file.
- Render, from the code: the subtitle is `TtsVoiceLabels.subtitle` (quality · Online/Offline [· Needs download], joined with " · "), drawn in `settings_row_choice`'s `tvRowSubtitle` (13 sp, wrap_content, no maxLines) by `TtsVoiceActivity.addChoiceRow` and by the Anki audio picker (`AudioSourcePickerView.renderRows`, same layout), so a long subtitle wraps and no width limit applies. The spoken name is set by `DetachedWindow.refreshActions` as the button's contentDescription; the pill keeps `detached_bar_capture`.

### Verdict

**PASS**: 🛑 0 / ❌ 0 / ⚠️ 0 / 💬 0. Not checked: nothing was seen on a device or heard through TalkBack.
