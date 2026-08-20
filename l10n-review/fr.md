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
