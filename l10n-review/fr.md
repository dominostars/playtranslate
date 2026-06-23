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
