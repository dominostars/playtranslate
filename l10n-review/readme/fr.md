# French (fr) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS, with two warnings: "table 1 header cells left in English: ['Code']" and "table 2 header cells left in English: ['Code']". « Code » is also the correct French word, so both warnings are false positives. **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Fonctionnalités > Traduction automatique | 💬 | "**Traduction automatique** : chaque nouvelle ligne de dialogue est traduite d'elle-même" | "**Mode de traduction automatique** : chaque nouvelle ligne de dialogue est traduite d'elle-même" | Acceptable as is: it is the app's own Auto-translate header (`settings_header_auto_translate`, also `onboarding_welcome_play_body`). But in French « traduction automatique » is first of all the standard term for machine translation, and the app uses it in that sense too (`word_detail_mt_banner` = "Machine translated"). Adding « Mode » restores the English "Auto Translation Mode", matches the sibling « Mode furigana et pinyin », and makes the bold label read as a mode. Alternative: « Traduction auto », the live-mode menu label (`live_mode_auto_translate_label`). |
| Fonctionnalités > Double écran et écran partagé | 💬 | "fonctionne sur les deux écrans des appareils qui en comptent deux, comme l'Ayn Thor" | "fonctionne sur les deux écrans des appareils à double affichage, comme l'Ayn Thor" | « les deux écrans des appareils qui en comptent deux » says "two" twice and reads clumsily. « à double affichage » is the usual way to describe the device, and it mirrors the English "dual-display devices". |
| Fonctionnalités > Export vers Anki | 💬 | "la liste de mots, les mots ciblés, l'audio de synthèse vocale" | "la liste de mots, les mots cibles, l'audio de synthèse vocale" | The app says « mot cible » (`anki_content_flag_targeted_sentence_desc`: « un mot cible mis en évidence »). « ciblés » ("targeted") means nearly the same thing but is a second form of one term. |
| Fonctionnalités > Export vers Anki | 💬 | "Vous pouvez même enregistrer l'audio du jeu et l'ajouter !" | "Vous pouvez même enregistrer l'audio du jeu et l'ajouter à la carte !" | « l'ajouter » has no complement. The sentence before used « enregistrez » to mean "save to AnkiDroid", so it is unclear what the audio is added to. Naming the card settles it. |
| Fonctionnalités > Historique du texte | 💬 | "conservez un registre des phrases capturées" | "gardez une trace des phrases capturées" | « registre » sounds administrative (a ledger or official register). « garder une trace de » is the idiomatic way to say "keep a record of". |
| Intro; Aide et soutien (2 places, identical sentence) | 💬 | "obtenir de l'aide ou faire une demande, rejoignez le" | "obtenir de l'aide ou faire une suggestion, rejoignez le" | « faire une demande » is open-ended (it suggests a form or an application). The English "make requests" means feature requests, which « faire une suggestion » covers. Replace in both places. |
| Installation > step 2 | 💬 | "Android propose aussi d'y accéder directement lorsque vous ouvrez l'APK pour la première fois" | "Android vous propose aussi d'y accéder directement lorsque vous ouvrez l'APK pour la première fois" | Without « vous », « Android propose d'y accéder » can be read as Android offering to go there itself. The English says "offers to take you there". |
| Impossible d'installer l'application ? | 💬 | "« Application dangereuse »" | "« Application dangereuse détectée »" | The framework title (`harmful_app_warning_title`, android-36 values-fr, on disk) is « Application dangereuse détectée ». Because the README presents this as a quoted on-screen message, quoting the full title makes it easier to recognise. The English "harmful app" is only a paraphrase, so the current text is not wrong, just incomplete. |
| Services de traduction en ligne (facultatif) | 💬 | "Chaque service occupe sa propre entrée dans la liste" | "Chaque service a sa propre entrée dans la liste" | « occuper une entrée » is stiff. « avoir sa propre entrée » is the plain way to say the English "is its own entry". |
| Langues prises en charge > both table headers (lines 69, 100) | 💬 | "Nom natif" | "Nom dans la langue" | « nom natif » is a calque of "native name", since « natif » normally describes people (« locuteur natif »). « Nom dans la langue » (or the technical « Autonyme ») is the idiomatic column header. The current header is still understandable. Column padding is cosmetic only, because Markdown tables do not need it. |
| Crédits et licence | 💬 | "figure dans la section [Credits du README en anglais](https://github.com/dominostars/playtranslate#credits)" | "figure dans la [section « Credits » du README en anglais](https://github.com/dominostars/playtranslate#credits)" | The bare English word "Credits" runs into the French link text unmarked and looks like a typo for « Crédits ». Quoting it marks it as the English heading's literal name, and the link keeps the same URL, as the checker requires. |

No ❌ and no ⚠️ findings. All 11 rows are 💬 polish. The translation reads as native French technical prose, and nothing in it misleads a reader or breaks a step.

## Clean areas (checked, no findings)

**Accuracy.** I compared every sentence through "Optional: Anki Flashcards" against the English. Nothing is dropped, softened or added beyond clarifications. Counts are intact (26 and 59, 13 features, 4 install steps, 5 Play Protect steps, 4 restricted-settings steps, 9 services). Three small additions are accurate. Step 2 says which app to pick (« sélectionnez le navigateur ou le gestionnaire de fichiers »). The Custom service names the real field « URL personnalisée » (`llm_backend_base_url_label`). « APK téléchargés en dehors du Play Store » spells out "sideloaded".

**The ML Kit sentence.** « ML Kit sert de solution de secours hors ligne lorsque la traduction en ligne est indisponible » means "used whenever online translation is unavailable". It reuses the noun of `tr_service_offline_footer` (« comme solution de secours lorsque les traductions en ligne sont indisponibles »). Pass.

**Register.** Formal **vous** throughout: 22 distinct vous verb forms (imperatives plus « pouvez », « souhaitez », « ayez ») and no tu/ton/ta/tes/toi. The only third-person phrasings are feature descriptions with "the mode" or "the app" as subject, which is natural in a feature list. PlayTranslate is masculine (« il sait lire », « distribué »), and « Elle prend en charge » correctly refers back to « une application », as the app's own strings do.

**Terminology vs strings.xml.** These match: « Services de traduction » (`settings_cell_translation_services`, also the heading's noun), « Raccourcis » (`settings_cell_hotkeys`), « Synthèse vocale » (`settings_cell_tts`), « Historique du texte » (`history_toggle_title` « Conserver l'historique du texte »; `settings_cell_history` is « Historique »), « Zones de capture » (`nav_regions` « Zones », `menu_capture_region` « Zone de capture »), furigana/pinyin (`header_action_furigana`, `hint_label_pinyin_lower`), « Appareil photo » (`settings_cell_camera`), « dictionnaire » (`settings_cell_dictionary`), « Cartes Anki (facultatif) » (`settings_cell_anki`), « l'audio du jeu » (`audio_source_game_name`), « Autoriser les paramètres restreints » (`restricted_settings_title`/`_message`), « modèles de traduction hors ligne » (`lang_section_offline_models_title`), « paquet » for deck (parameters doc), « clé d'API », « Personnalisé » (`llm_backend_preset_custom`), « prenez un instantané » (`camera_shutter_cd`), « survolez … pour voir … sa définition » (`icon_action_lookup_words`), « accent tonal » (`yomitan_category_pitch_accent`), « s'intègrent de façon transparente dans toute l'application » (`yomitan_page_description`), « type de carte » (`anki_card_type_row_label`), « l'autorisation d'accessibilité » and « écran de jeu » (used throughout the app). The accessibility parenthetical « maintenir un raccourci pour afficher les traductions » echoes the hotkey row `hotkey_show_translations_title` « Maintenir pour afficher les traductions ». Pass.

**Auto mode label.** Covered in the first findings row: acceptable, with an optional « Mode ».

**« (facultatif) » heading suffix.** It is idiomatic. As a meta-label, « (facultatif) » is used invariably in French docs and forms, so its use after the feminine plural « Cartes Anki » is the normal elliptical use, not an agreement error. Putting it after the heading also reads better than an English-style « Facultatif : » prefix. Pass.

**« Aide et soutien ».** A good choice. The English "Support" section holds both senses: Discord help (« aide ») and Ko-fi backing (« soutien »). « Assistance » alone would lose the Ko-fi sense. Pass.

**Tables.** The two section headings are translated (« Langues de jeu (lues à l'écran) », « Langues de traduction (celles que vous lisez) »), and the header cells are « Langue » and « Code ». The checker confirms the native-name and code columns are identical to English. The French language names follow standard French and CLDR usage: Ourdou, Télougou, Tamoul, Goudjarati, Créole haïtien, Espéranto, Norvégien. The CJK cells are padded as if each character were one column wide, which is invisible once rendered. The only point is the « Nom natif » header (see its row).

**Links.** The checker confirms every URL is byte-identical. The link texts read naturally (« Cliquez ici pour télécharger la dernière version », « le dernier APK », « serveur Discord », « PlayTranslate sur Persona 3 Reload »). The pointer section says what it should: Credits are in the English README, and the licence is GPL 3.0, linked.

**Typography (whole file, checked byte by byte).**
- Every colon (27 of them) has U+00A0 before it.
- Every ; ! ? (5 in total) has U+202F before it.
- All 5 « » pairs have U+202F inside.
- No plain space before high punctuation, and none missing.
- This split (no-break space before the colon, narrow no-break space before ; ! ? and inside guillemets) is the Imprimerie nationale convention, applied without exception.
- Apostrophes are straight (59) with zero curly ones, so they are consistent.
- There are no em dashes: the English ones became full stops or « ; ».
- The arrows → in paths and the ⋮ glyph are preserved.
- Feature bullets all end with a full stop and numbered steps all omit it, mirroring English, so each list is internally consistent.

**Labels.** UI labels in paths and steps are in bold. The two on-screen messages and the « Installer » button are in « ». That split is coherent.

## French appendix: Android and Play Store wording

None of the Settings or Play Store labels are on disk. The SDK has framework strings only, and no system image is installed. Everything below except `harmful_app_warning_title` is **from memory**.

**Install step 2: settled in favour of the translation. Keep it as is.**
- « Accès spéciaux des applications » ("Special app access"). This matches AOSP/Pixel French as I remember it. Medium-high confidence.
- « Installer des applis inconnues » ("Install unknown apps", the page title and list row). This matches AOSP/Pixel French as I remember it. Google's French Settings uses the short « applis » in several page titles, as with « Superposition aux autres applis », which the app's own review confirmed for the overlay page. About 75% confident.
- « Autoriser cette source » ("Allow from this source", the switch). This matches my memory of Pixel French and French-language how-to guides. About 75% confident.
- The earlier app review's alternatives (`l10n-review/fr.md`, the `update_unknown_sources_message` row) were an unsourced aside: « Installer des applications inconnues » and « Autoriser depuis cette source ». « Autoriser depuis cette source » reads like a back-translation of the English. The long form « installer des applications inconnues » is, from memory, the wording of the PackageInstaller's blocking dialog prose (« … pas autorisé à installer des applications inconnues provenant de cette source »), which may be where that suggestion came from.
- No README/app conflict exists today, because the app's French `update_unknown_sources_message` names neither label.
- OEM skins (Samsung One UI, Xiaomi) word these pages differently, so no single set of labels will match every device. The README's set is the stock-Android one.

**Play Store and installer:**
- « Play Store », « Google Play Protect » and « Play Protect » are correct. They are brand and app labels, and Google's French help says « Ouvrez l'application Google Play Store ».
- « icône de profil … (en haut à droite) » matches Google Help FR phrasing.
- « Analyser les applications avec Play Protect » is the Play Store toggle as Google's French help names it. High confidence.
- « Installer » is the PackageInstaller button. High confidence.
- « Application non installée » is the PackageInstaller failure message ("App not installed."). High confidence.
- « Application dangereuse » is a fragment of the framework title « Application dangereuse détectée », verified on disk (`harmful_app_warning_title`, android-36 values-fr; values-fr-rCA has « Une appli nuisible a été détectée », not relevant to fr-FR). See its findings row.

**Restricted settings:**
- « Paramètre restreint » (dialog title) and « Autoriser les paramètres restreints » (⋮ menu item) match AOSP Settings French as I remember them, medium-high confidence. Both are identical to the app's reviewed `restricted_settings_title`/`restricted_settings_message`, so the README and the app agree.
- « Paramètres → Applications → PlayTranslate » is the Android 12+ path, as in the English.

## Disposition (2026-10-06)

Applied all 11 💬 as suggested (Mode de traduction automatique, à double affichage, mots cibles,
l'ajouter à la carte, gardez une trace, faire une suggestion ×2, Android vous propose,
« Application dangereuse détectée », a sa propre entrée, Nom dans la langue ×2, « Credits » quoted).
The install-step labels stay as the translator wrote them (« Accès spéciaux des applications »,
« Installer des applis inconnues », « Autoriser cette source »), per the reviewer's call.
