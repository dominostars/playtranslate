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
