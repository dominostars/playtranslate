# Russian (values-ru) localization review

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| settings_header_ocr | ❌ | «Изображение в текст (OCR)» | «Распознавание текста (OCR)» | Calque flagged on the hotlist; the fix also matches `status_ocr` («Распознавание текста…») already in this file. |
| label_region_drag_hint | ❌ | «Перетаскивайте верхний или нижний край либо середину, чтобы переместить всю рамку.» | «Перетаскивайте верхний или нижний край, а чтобы переместить всю рамку — тяните за середину.» | Exactly the merge the hotlist warns about: the purpose clause «чтобы переместить всю рамку» now scopes over the edges too. In EN only the middle moves the whole box; edges resize. |
| pack_upgrade_progress_format | ❌ | «Скачивание %1$s…» | «Скачивается %1$s…» | `pack_name` arrives nominative («японский»); «Скачивание японский…» is ungrammatical — «Скачивание» requires genitive. The verb form takes a nominative subject. |
| pack_upgrade_progress_format_with_bytes | ❌ | «Скачивание %1$s… %2$s из %3$s» | «Скачивается %1$s… %2$s из %3$s» | Same break as above. The «X из Y» bytes part is fine. |
| lang_section_offline_models_subtitle | ❌ | «Офлайн-перевод для %1$s → %2$s не скачан.» | «Офлайн-перевод (%1$s → %2$s) не скачан.» | «для японский» — «для» demands genitive; the arrow doesn't rescue it. Parenthetical matches the file's own pattern (`pack_upgrade_label_source` etc.). |
| hotkey_show_hint_title | ❌ | «удерживайте для показа %1$s» | «удерживайте для показа подсказок (%1$s)» | Slot is filled with «Фуригана»/«Пиньинь» in nominative; «для показа Фуригана» needs genitive («фуриганы»). Parenthetical echoes onboarding's «подсказки для чтения (фуригана, пиньинь)». |
| hotkey_show_hint_dialog_title | ❌ | «Показать %1$s» | «Показать: %1$s» | «Показать Фуригана» — feminine accusative would be «фуригану»; nominative fill breaks it («Пиньинь» survives only by acc=nom luck). |
| translate_button_subtitle_hold_to_show_translations_instead_of_hint | ❌ | «показать перевод вместо %1$s» | «показать перевод вместо подсказок (%1$s)» | «вместо» requires genitive; «вместо фуригана» is broken (needs «фуриганы»). |
| translate_button_subtitle_hold_to_show_hint | ❌ | «чтобы показать %1$s на экране игры» | «чтобы показать подсказки (%1$s) на экране игры» | Same accusative break for «фуригана». |
| translate_button_prefix_translate, translate_button_prefix_reload | ⚠ | «Перевести» / «Обновить» (+ space + region label) | «Перевести:» / «Обновить:» | Composed «Перевести Карта» breaks for user-named feminine regions (acc «Карту» ≠ nom). Default «Весь экран» only works by acc=nom coincidence. The trailing colon is the robust fix. |
| custom_region_edit_title | ⚠ | «Изменить %1$s» | «Изменить «%1$s»» | Same feminine-region-name accusative problem; quoting turns the label into a citation form. |
| pack_upgrade_mandatory_message | ⚠ | «Обновите сейчас или удалите, чтобы выбрать другой язык.» | «…или удалите пакет, чтобы выбрать другой язык.» | Hotlist item: bare «удалите» has no object; nearest noun is «версия», which isn't what gets deleted. |
| anki_sort_field_empty | ⚠ | «пустые значения вызывают ошибки отклонения дубликатов при отправке» | «из-за пустого значения карточка при отправке будет отклонена как дубликат» | «ошибки отклонения дубликатов» is the predicted calque — it reads as "errors in rejecting duplicates" and inverts the mechanism (the card itself is rejected as a duplicate). |
| accessibility_dialog_message, overlay_icon_a11y_required_message | ⚠ | «…Специальные возможности → Установленные приложения → …» | «…Специальные возможности → Скачанные приложения → …» | From stock Android Russian (AOSP/Pixel), the accessibility app-list section is «Скачанные приложения»; the RU copies the EN drift ("Installed apps"). OEM skins vary — flagged per hotlist, moderately confident. |
| qwen_mnn / qwen35_2b / gemma_e2b / hymt `_metered_warning_title` + `_message` (8 strings) | ⚠ | «Скачать через лимитную сеть?» / «Эта сеть отмечена как лимитная.» | «Скачать по лимитному подключению?» / «Это подключение отмечено как лимитное.» | Android's own Russian toggle is «Лимитное подключение»; «лимитная сеть» is understandable but not the system's wording. Recommend «лимитное подключение» as the agreed term. |
| overlay_hide_for_now (+ its quote inside overlay_hide_controls_message) | ⚠ | «Скрыть пока» | «Скрыть на время» | Postposed «пока» reads awkwardly (almost like the colloquial "bye"); «Скрыть на время» is natural and the same length. Keep the in-message quote in sync. |
| hymt_legal_message | 💬 | «Нажимая «Согласен»…» vs button «Согласен — включить Hunyuan» | optionally quote the full label | Partial match mirrors the EN source exactly ("Agree" vs "I Agree — Enable Hunyuan") and is unambiguous (the only other button is «Отмена»). Noted per hotlist; not a blocking defect. |
| hymt_legal_message | 💬 | «результаты этой модели» | «результаты работы этой модели» | "Outputs of this model" — slightly elliptical as is; legal meaning preserved either way. |
| live_mode_auto_with_hint | 💬 | «Авто %1$s» | «Авто: %1$s» | «Авто Фуригана» is grammatical apposition and word order is correct; the colon just reads cleaner. |
| quick_tile_add_row_title, quick_tile_added_row_subtitle | 💬 | «…в быстрые настройки» | «…в «Быстрые настройки»» | Android's panel is named «Быстрые настройки»; capitalizing/quoting the feature name helps users find it. |
| quick_tile_add_row_subtitle | 💬 | «Включайте PlayTranslate из строки состояния» | «Включайте и выключайте PlayTranslate из строки состояния» | EN "Toggle" covers both directions; «Включайте» alone narrows it. |
| crash_dialog_discard | 💬 | «Отклонить» | «Не отправлять» | Passes the hotlist test (not «Отмена», not «Удалить»), but «Не отправлять» states the outcome more plainly next to «Отправить»/«Позже». |
| qwen_mnn / qwen35_2b / gemma_e2b / hymt `_disable_message` (4 strings) | 💬 | «Модель %1$s установлена.» | «Модель размером %1$s установлена.» | Bare size apposition («Модель 1,2 ГБ установлена») is telegraphic; «размером» smooths it. |
| settings_ocr_footer | 💬 | «с трудом точно распознаёт текст» | «неточно распознаёт текст» | «с трудом» + «точно» collide; minor style. |
| anki_card_type_basic_no_mapping | 💬 | ««Лицевая» и «Оборотная»» | ««Лицевая сторона» и «Обратная сторона»» | Anki's own Russian field names are likely «Лицевая сторона»/«Обратная сторона» (not «Оборотная»). Uncertain — verify against AnkiDroid ru before changing. |

Hotlist items that came back clean: `status_idle`/`status_hold_hint` (button names «Перевести», «Области», «Авто» quoted and exactly matching the actual labels), `tts_language_unsupported_*` («не поддерживает японский» — CLDR Russian language names are inanimate masculine adjectives/nouns, so acc=nom holds for every value), `anki_permission_rationale_message`/`anki_settings_grant_access_subtitle` («приложению PlayTranslate» cleanly separates the two brands; «Продолжить» matches `btn_continue`), `settings_capture_interval_hint` (uses «с.», agreement-proof for "1" and "0.5"), `backend_cooldown_status_fmt`+`retry_at/_on` («Недоступно · Повтор в 15:42» reads naturally), `btn_clear` («Очистить» — correct), `hymt_legal_message` mechanics (negation «не проживаете **и** не находитесь» — the strong form; §5(b), the EU/UK/South-Korea list, and «подтверждаете и гарантируете» all intact).

## Coverage appendix

**Plurals (all four categories one/few/many/other verified, incl. one=21, few=22–24, many=11–14, other=fractions·gen.sg):**
- word_detail_senses_count — значение/значения/значений/значения ✓
- word_detail_chars_count — символ/символа/символов/символа ✓
- lang_search_match_count — совпадение/совпадения/совпадений/совпадения ✓

**Placeholder sites** (✓ = construction avoids oblique case of the runtime value, or no case demand exists; otherwise pointer to finding row):

- Brand-only `<xliff:g>` fills (app/brand names in Latin script, no declension demanded — all ✓): accessibility_service_description, status_accessibility_needed, notif_title, notif_text, onboarding_welcome_title, onboarding_welcome_body, onboarding_notif_body, onboarding_a11y_hint, onboarding_a11y_body, restricted_settings_message («приложения PlayTranslate» — genitive marker noun ✓), btn_open_app_settings, word_detail_tatoeba_attribution, settings_capture_display_footer, hint_deepl_key, anki_not_installed_message, anki_not_installed_get, anki_permission_rationale_title/_message ✓, anki_permission_denied, anki_no_deck_selected, anki_added_no_audio, anki_added_success, anki_adding_in_progress, anki_send_failed_message, anki_sheet_title_new_card, anki_save_button_label, anki_words_helper, anki_card_type_row_empty, anki_card_type_no_models, anki_models_unavailable, anki_long_press_footer, anki_content_none_desc, anki_content_examples_desc, anki_content_words_table_desc, anki_content_flag_vocabulary/_sentence/_targeted_sentence_desc, deepl_settings_get_key_title, deepl_settings_about, deepl_api_key_field_label, tr_service_offline_footer, qwen_mnn/qwen35_2b/gemma_e2b/hymt `_disable_title` ✓, hymt_legal_agree, legacy_engines_removed_message, mp_overlay_permission_message, a11y_required_displays/_hotkey/_enhanced_message, anki_settings_get_ankidroid_title, anki_settings_grant_access_title/_subtitle ✓, settings_support_discord_title, settings_debug_export_logs_subject, crash_dialog_title/_message, overlay_turn_off_title («Выключить X?» acc=nom ✓), overlay_turn_off_message («в приложении X» ✓), overlay_hide_controls_title, overlay_hide_controls_message
- Language-name slots: status_no_text ✓ (label «X: текст не найден в «Y»»), lang_setup_requires_64bit_msg ✓ (parenthetical), pack_upgrade_label_source ✓, pack_upgrade_label_target ✓, anki_section_description ✓ (parenthetical), target_pack_migration_title ✓, target_pack_migration_message ✓ (two parentheticals), tts_voices_section_header ✓ (reordered «ГОЛОСА: X»), tts_language_unsupported_with_engine_message ✓, tts_language_unsupported_unknown_engine_message ✓ (acc=nom safe), lang_section_offline_models_subtitle → ❌ row, pack_upgrade_progress_format → ❌ row, pack_upgrade_progress_format_with_bytes → ❌ row
- Hint-label (furigana/pinyin) slots: hotkey_show_hint_title → ❌ row, hotkey_show_hint_dialog_title → ❌ row, translate_button_subtitle_hold_to_show_translations_instead_of_hint → ❌ row, translate_button_subtitle_hold_to_show_hint → ❌ row, live_mode_auto_with_hint → 💬 row
- Free-form user labels: custom_region_edit_title → ⚠ row; anki_field_mapping_title ✓ (foreign card-type names undeclined), anki_sort_field_empty → ⚠ row (placeholder itself ✓, prose calque flagged), anki_content_source_pick_title ✓, anki_deck_label_format ✓, settings_anki_digest ✓, word_anki_deck_badge_cd ✓, word_detail_not_found ✓
- Counts/sizes/numbers: word_anki_in_decks ✓ («Колод Anki: N» — agreement-proof reorder), anki_group_words_count ✓, settings_capture_displays_count ✓ («Экранов: N»), word_detail_numbered_definition ✓, tts_voice_numbered ✓, tts_voice_region_numbered ✓, settings_capture_interval_hint ✓ («с.»), dialog_hotkey_setup_countdown ✓ (terse, matches EN), llm_hardware_unsupported_ram ✓ («не менее N ГБ» — genitive-safe for all N), llm_low_memory_message ✓, tr_service_status_quota_fmt ✓, tr_service_status_quota_with_reset_fmt ✓, settings_footer_version ✓, crash_email_subject ✓, update_dialog_message ✓ («доступен» agrees with brand ✓)
- Byte-progress lines (all «X из Y» / «X / Y» — ✓): pack_upgrade_progress_format_with_bytes (bytes part ✓; prefix ❌ above), bergamot_status_downloading, bergamot_warmup_downloading, bergamot_warmup_downloading_multi, install_downloading_with_bytes, lang_setup_downloading_ocr_model, install_downloading_definitions_with_bytes, qwen_mnn/qwen35_2b/gemma_e2b/hymt `_status_downloading` ✓
- Model stat/status lines: qwen_mnn/qwen35_2b/gemma_e2b/hymt `_status_not_downloaded`/`_ready` ✓ («Требуется X памяти, Y в хранилище»), `_downloaded_disabled` ✓, `_disable_message` → 💬 row, `_download_failed` ✓, `_metered_warning_message` ✓ grammatically («Размер X — Y» is a good label construction; term → ⚠ row), offline_backend_row_a11y_fmt ✓, offline_backend_row_a11y_no_speed_fmt ✓ (the «Качество: Хорошее качество» doubling mirrors the EN source)
- Misc: status_error ✓, word_detail_label_format ✓, word_detail_mt_banner_named ✓, word_detail_char_meanings_mt ✓, translation_source_label ✓ («Перевод: X»), llm_backend_get_key_title_fmt ✓, llm_backend_invalid_key_alert_message_fmt ✓, backend_cooldown_status_fmt ✓, capture_display_row_label ✓, settings_ocr_delete_cd/_title/_msg/_shared_msg/_downloading_title ✓ (Latin engine brands decline invisibly), settings_debug_export_logs_failed ✓, quick_tile_add_row_subtitle → 💬 row, pack_upgrade_mandatory_message → ⚠ row (placeholder ✓; prose object flagged), accessibility_dialog_message / overlay_icon_a11y_required_message → ⚠ row (placeholders ✓; nav-path wording flagged), hymt_legal_message → 💬 rows (placeholders ✓)

## Verdicts

- **Register:** PASS — consistent formal lowercase «вы», no «ты» anywhere.
- **Terminology:** PASS with one miss — core terms (скачать, колода, карточка, языковой пакет, захват экрана, горячая клавиша, синтез речи, наложение) are consistent; `settings_header_ocr` is the outlier.
- **Android-settings wording:** mostly correct («Специальные возможности», «Поверх других приложений», «строка состояния», «плитка»); fix metered («лимитное подключение») and the accessibility app-list section («Скачанные приложения»).
- **Plurals:** PASS — all three blocks correct in all four categories including the fractional `other`.
- **Cases around placeholders:** the translator's label/parenthetical strategy is well executed overall, but 9 strings genuinely break (pack-download progress ×2, offline-models pair line, the four furigana/pinyin slots) plus 2 latent feminine-region-name traps — this is the must-fix cluster.
- **Truncation:** PASS — Авто/Пауза/Настройки/Области fit the 8sp bar; «Область\nзахвата» fits the 9sp two-line button.
- **Legal text:** PASS — §5(b), the EU/UK/South-Korea list, «и»-scoped negation, and «подтверждаете и гарантируете» all faithfully preserved; two cosmetic nits only.
- **Overall:** **fix-then-ship** — no build-breakers, strong overall quality, but the 9 ❌ case/scoping errors (several on the flagship Japanese path) must land before release.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; four-way plural CLDR sets; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| yomitan_import_summary_count (`one`) | ❌ | «Импортирован %1$d из %2$d словаря.» | «Импортировано %1$d из %2$d словаря.» | The `<plurals>` category is keyed to the **total** %2$d (`getQuantityString(…, tally.totalSelected, importedCount, totalSelected)`), but the predicate participle agrees with the **imported** count %1$d. «Импортирован» (masc sing) only fits %1$d == 1. The reachable break: select **one** already-installed dict → importedCount=0, totalSelected=1 → `one` fires → «**Импортирован 0** из 1 словаря.» (zero demands neuter). Neuter impersonal «Импортировано» is invariant for every %1$d while the noun «словаря» (gen. sg. after «из 1») stays correct. The `few/many/other` forms already use «Импортировано». |
| yomitan_import_summary_duplicates | ⚠️ | «Уже импортированы: %1$s» | «Уже импортировано: %1$s» | %1$s is a 1-to-N comma-list of dict names; a single duplicate is reachable (`group.examples` can be one item) → «Уже импортированы: JMdict» mis-agrees (plural participle, one name). EN "Already imported:" is number-neutral. Neuter impersonal «Уже импортировано:» reads correctly for 1 **and** many, and mirrors the `summary_count` fix. The colon keeps the nominative name-list safe. (`_invalid/_no_space/_failed` are already number-neutral — «Не удалось прочитать:», «Недостаточно места:», «Сбой:».) |
| yomitan_import_summary_more (`one`,`few`,`many`,`other`) | 💬 | «+%1$d ещё» | «+ещё %1$d» (or «ещё %1$d») | Postposed «ещё» after the count reads slightly off as a list tail («…, broken.zip, +3 ещё»); «ещё N» is the idiomatic order. «ещё» is invariant, so all four forms are grammatically fine and identical-but-for-the-number — correct as a plural set; purely word-order polish. |

## Clean areas (delta)

**The four plural forms of `yomitan_import_summary_count`** — verified band-by-band, read aloud with the strongest test value. The noun follows «из %2$d», i.e. the count phrase is the **object of «из» (genitive)**, which shifts the `few` band off the file's usual bare-numeral pattern: `one` (total ends 1≠11) «из 1/21 словаря» = gen. sg. ✓; `few` (ends 2–4≠12–14) «из 3/22 словарей» = gen. **pl.** ✓ — correct *because* the numeral two/three/four is itself in genitive after «из» («из трёх словарей», NOT «из трёх словаря»); the translator rightly diverged here from `word_detail_senses_count`'s «2 значения» (gen. sg., bare-numeral frame); `many` (0·5–9·11–14) «из 5/11 словарей» = gen. pl. ✓; `other` (fractions, unreachable — total is integer) «из 1,5 словаря» = gen. sg. ✓. Noun agreement with the total %2$d is right in all four; the **only** defect is the `one`-form participle (row above), which is a predicate-vs-selector mismatch, not a noun-case error.

**`yomitan_import_summary_more`** — selector and arg are the same value (`group.overflow`, `group.overflow`), so the displayed number always matches the chosen category (no total-vs-shown split). «ещё» doesn't inflect, so every band is correct; `other` is unreachable (overflow is an integer ≥1). Only the word-order nit above.

**Placeholder noun-case (runtime values arrive nominative).** All six Yomitan summary lines that take a name/file list (`_duplicates/_invalid/_no_space/_failed` + the two desc field-name slots) use the «Label: %1$s» colon construction — citation/nominative after the colon, exactly the prescribed pattern; **no oblique-case demand on any raw placeholder**. `yomitan_importing_progress` «Импорт %1$d из %2$d…» is noun-headed ("Import N of M") with bare integers under «из» — no agreement trap (the EN deliberately omits the noun and the RU follows). `yomitan_no_space_message` (neighbor) and the audio cells carry no placeholder case risk. The Anki `*_desc` field-name slots («PitchPosition», «PAOverride», «Frequency», «FrequenciesStylized», «FreqSort», «FrequencySort») are restructured as «для поля «X» в Lapis/JPMN», sidestepping the English genitive-'s cleanly.

**Terminology reuse (vs the file).** частот- → «частотность» consistent with the pre-existing `anki_content_frequency`/`_desc` (567–568) and `yomitan_page_description` (1192). Pitch accent → «тональное ударение» matches `yomitan_category_pitch_accent` (1204) and the page description — no competing «высотное ударение»/«питч-акцент». словарь declensions in the new plurals fit the file-wide «словарь». Скачать/Установить both present and consistent in `yomitan_auto_update_subtitle` («скачивать и устанавливать»). Синтез речи (`audio_source_tts_name`) matches the agreed TTS term. Импорт/импортировать consistent across the summary block. «Дополнительно» (`llm_backend_advanced_header`) is Android's standard "Advanced" header; «Свой URL» is tight for a row label.

**Register.** Formal-вы held throughout: «Используйте» imperatives in `anki_content_frequency_stylized_desc` and `llm_backend_base_url_invalid`; no «ты»/«твой» anywhere (whole-file grep clean). Impersonal «Не удалось…» pattern reused consistently (`_title_none`, `audio_error_loading`).

**Short-label truncation (~30% RU expansion).** `audio_source_picker_title` «Аудио», `audio_loading` «Загрузка…», `audio_no_results` «Нет результатов», `yomitan_auto_update_label` «Автообновление», `llm_backend_advanced_header` «Дополнительно», `llm_backend_base_url_label` «Свой URL» are all compact — no toolbar/row truncation risk. The two picker option labels (`anki_content_frequency_harmonic` «Число для сортировки по частотности», `_frequency_stylized` «Список частотности (стиль JPMN)») are longer but live in a full-width dialog option row, not a tight chip. `llm_backend_base_url_invalid` is an inline EditText error (wraps) — length OK; «http:// допустим только для…» reads naturally with no calque.

**The `Example:`/quoted-field-name as-is rule.** `anki_content_pitch_position_desc` keeps «Пример: 0,2» and the ★ glyph / quoted field names verbatim; `_frequency_values_desc` keeps «★» and «Frequency»; `_frequency_stylized_desc` keeps «FrequenciesStylized»; `_frequency_harmonic_desc` keeps «FreqSort»/«FrequencySort» and the «(меньше = чаще)» parenthetical — all correctly left in their original shape, only the surrounding explanation localized. «среднее гармоническое» is the correct math term for "harmonic mean".

**Brands & quotes.** «Wikimedia Commons» (`audio_source_commons_name`), Lapis, JPMN left untranslated; all field-name citations use «» typographic quotes (no escaping needed, matches the file convention); no raw `'`/`"` in any of the 29 scope lines.

---

## Delta review — 2026-07-14 sync

Scope: the 174 delta keys (170 new + 4 changed English). Independent review; the
rest of the file was read only as the established style guide.

Mechanical layer verified programmatically across all 174: every `%n$s`/`%d`
present and matching EN; all `<xliff:g>` inner contents byte-identical; the bare
`{text} {source} {source_code} {target} {target_code} {context} {N} {strings}`
tokens byte-identical Latin in running prose; `\n` preserved
(`floating_menu_capture_screen` = «Захват\nэкрана», two lines); no raw `'`/`"`;
no double spaces; trailing-period parity with EN on all 174; `name=` untouched;
`<plurals>` carries the full RU CLDR set (one/few/many/other).
**No 🛑 build-breaking issues.**

**Counts: 0 🛑 · 3 ❌ · 6 ⚠️ · 8 💬**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| llm_prompt_invalid_title | ❌ | «Не удалось сохранить этот промпт» | «Этот промпт нельзя сохранить» | Modality inverted. EN "Can't save this prompt" is a **permanent capability** statement — the dialog is non-bypassable because the prompt has *fatal* problems. «Не удалось» is past-tense transient failure ("the attempt failed"), which tells the user to **retry** when the truth is "fix your template". The file proves the translator knows the difference: `update_error_signature` correctly renders "can't be installed" as «**нельзя** установить». Contrast the sibling `llm_prompt_warning_title` «Проверьте этот промпт» ✓. |
| llm_prompt_discard_title | ❌ | «Отменить изменения?» | «Не сохранять изменения?» | Breaks a cross-reference with its own dialog's buttons. This dialog's negative button is `btn_cancel` = «**Отмена**» (returns to the editor, **keeps** the edits) and its destructive button is `llm_prompt_discard_confirm` = «Не сохранять». A title asking «**Отменить** изменения?» primes «Отмена» as the *affirmative* answer — but «Отмена» does the opposite. One tap from data loss. Retitling to match the confirm button makes «Отмена» unambiguously "no". |
| misc_slur | ❌ | «Бранное» | «Дискриминационное» | Wrong lexicographic category, and it collapses the cluster the glossary says must stay distinct. Russian «бран.» (бранное) marks **swearing / abuse** («сволочь», «дурак») — it lands squarely inside the вульгарное/оскорбительное space, so `Уничижительное · Оскорбительное · Вульгарное · Бранное` gives a reader three chips that all just read "rude word". A slur is a demeaning label for a *group*; «Дискриминационное» is instantly and categorically distinct. 17 ch — inside the existing chip range (committed `pos_auxiliary` = «Вспомогательный глагол», 22 ch). Russian lexicography has **no native помета** for "slur", so this is necessarily a coinage; alternative: «Оскорбительное прозвище». |
| settings_ocr_use_manga_subtitle | ⚠️ | «…Не рекомендуется для **авто**. MangaOCR дополняет…» | «…Не рекомендуется для **автоперевода**. MangaOCR дополняет…» | Bare «авто» in Russian prose reads as *car* (авто = автомобиль). The committed file's established prose form is «для автоперевода» — verbatim in `tr_service_offline_footer`: «…будьте осторожны при их использовании **для автоперевода**». When the file does reference the *button label* it quotes it (`status_hold_hint`: «Удерживайте «Области» или «Авто»…»). |
| anki_game_audio_row_subtitle | ⚠️ | «**Сохраняет** последние несколько минут звука игры, чтобы…» | «**Сохранять** последние несколько минут звука игры, чтобы…» | Grammatical form disagrees with its **own row title**: `anki_game_audio_row_title` = «Записыв**ать** звук игры» (infinitive), and with the sibling switch subtitle `history_toggle_subtitle` = «Сохран**ять** захваченные предложения…» (infinitive). EN uses one form for both. 3sg «Сохраняет» is the odd one out. |
| misc_informal | ⚠️ | «Неофициальное» | «Неформальное» | «Неофициальное» means *unofficial* (of a document, a statement) — it is not a speech-**register** label. Russian for informal register is «неформальное» («неформальный стиль речи»). As written it reads as a bare antonym of `misc_formal` = «Официальное» rather than as a помета, and it weakens the informality cluster against «Разговорное»/«Фамильярное». (`misc_formal` = «Официальное» should **stay** — «Формальное» is a Russian false friend meaning *perfunctory*.) |
| misc_dated | ⚠️ | «Старомодное» | «Устаревающее» | A gloss of the English, not a Russian помета — «старомодное» judges *fashion* (of clothes, of a hat), not word currency. Russian has an exact distinct pair sitting right there: `misc_obsolete` «Устаревшее» (out of use) vs `misc_dated` «Устаревающее» (on its way out). Keeps the obsolescence cluster native and correctly ordered against «Архаичное» / «Историческое». |
| update_unknown_sources_message | ⚠️ | «…разрешите PlayTranslate устанавливать обновления приложений **на открывшемся экране настроек**.» | «Чтобы завершить обновление, **на открывшемся экране настроек** разрешите **приложению** <xliff:g …>PlayTranslate</xliff:g> устанавливать обновления. Android может перезапустить…» | Two problems, one fix. (1) Word order: the locative lands at the far end of the clause and misattaches to «устанавливать обновления», so it reads "install app updates **onto** the settings screen". (2) «разрешите PlayTranslate устанавливать» leaves the brand bare in a **dative** slot; the params doc's own technique (head noun «приложению») carries the case. Dropping «приложений» from «обновления приложений» avoids «приложению … приложений». |
| stream_kind_prompt_message | ⚠️ | «**Перевод в реальном времени** работает по-разному…» | «**Автоперевод** работает по-разному для одного приложения и для всего экрана, а система не сообщает, что именно было показано.» | Terminology drift: this is the **only** occurrence of «в реальном времени» in the whole file. The RU file names this feature «Автоперевод» seven times over (`live_mode_auto_translate_label`, `settings_header_auto_translate`, `enhanced_auto_translate_title`, `hotkey_auto_translation_dialog_title`) and «авторежим» twice. The dialog fires *immediately after* the user tapped «Авто» — a third name for it here reads like a different feature. |
| error_single_app_not_fullscreen | 💬 | «…не занимает весь **экран**. **Он** возобновится, когда…» | «…не занимает весь экран. **Перевод** возобновится, когда…» | «Он» is two nouns away from its antecedent «Перевод», and the nearer noun «экран» is *also* masculine — momentarily reads "the screen will resume". Semantics rescue it, so this is polish, not a defect. |
| llm_prompt_row_system_subtitle, settings_llm_context_subtitle | 💬 | «переводчикам LLM» / «онлайн-переводчикам LLM» | «LLM-переводчикам» / «онлайн-переводчикам на базе LLM» | Acceptable Russian tech apposition (cf. «протокол HTTP»), but the hyphenated attributive is the more idiomatic modern form. Two keys, one fix. Low priority — do not churn if the orchestrator prefers the current shape. |
| misc_manga_slang | 💬 | «Сленг манги» | «Манга-сленг» | Parallel with `misc_internet_slang` = «Интернет-сленг» (hyphenated compound, not a genitive phrase), and 3 ch shorter on a width-constrained chip. |
| misc_yojijukugo | 💬 | «Идиома из 4 иероглифов» | «Идиома из 4 кандзи» | Correctly *described* rather than romanized, per the glossary ✓. 21 ch is fine against `pos_auxiliary` (22 ch), but «кандзи» is 3 ch shorter and reuses the loanword already committed at `misc_kanji_only` («Только кандзи»). |
| llm_prompt_row_translation_subtitle | 💬 | «Запрос, в **который** оборачивается каждая фраза, **которую** вы ищете.» | «Запрос, в который оборачивается каждая искомая фраза.» | The «который…которую» chain is the classic Russian style flaw; the participial form is tighter and matches the sibling subtitles' length. |
| tr_service_status_usage_today_fmt | 💬 | «Сегодня токенов: %1$s» | «Токенов сегодня: %1$s» | The restructure is **correct and necessary** — the runtime passes a pre-formatted string («12 345»), so `<plurals>` is unavailable and a trailing «токенов» would break on 1/2/5. Fronting the genitive noun is the right dodge; «Токенов сегодня:» is just the more natural word order for a stat line. |
| probe_initializing | 💬 | «Инициализация…» (14 ch) | «Запуск…» (7 ch) if the chip is tight | Truncation watch only, per brief item 7 — the chip is explicitly "keep short" and rides beside a checker glyph for ~1.5 s. The committed `settings_ocr_downloading_msg` already uses «Запуск…» for a comparable start-up slot. Leave as-is if the chip measures fine on Thor. |
| audio_source_game_ready | 💬 | «Из вашей недавней игры» | «Из недавней игры» | «вашей» is redundant in a row subtitle and costs width; the possessive adds nothing EN's "your recent gameplay" doesn't already imply from context. |
| llm_backend_preset_custom | 💬 | «Свой» | «Другой» (only if it reads poorly in the catalog list) | Fine as a Provider dropdown value and pill. Flagged only because it also lands inside the composed catalog subtitle «OpenAI (DeepSeek, Mistral, Свой)», where «Другой» would read marginally better. Leave if intentional. |

### Clean areas (delta) — checked, no findings

**`settings_yomitan_count_summary` — the plurals. Clean; the previous cycle's bug
class does not recur.** Verified structurally *and* by reading every category with
a real number. The prior defect (`yomitan_import_summary_count`, fixed last cycle)
was a **selector-vs-argument split** — the category was keyed to one quantity while
the participle agreed with another, yielding «Импортирован **0**…». That split is
structurally impossible here: the call site is
`RootSettingsViewModel.kt:346` → `getQuantityString(R.plurals.settings_yomitan_count_summary, count, count)`
— **selector and format arg are the same value**. Category by category:
`one` (ends 1, ≠11) «Импортирован **1** словарь» / «Импортирован **21** словарь» — masc. sg. short participle + nom. sg. noun ✓ correct for *every* member of the band;
`few` (ends 2–4, ≠12–14) «Импортировано **2** словаря» / «…**23** словаря» — impersonal neuter + gen. sg. ✓;
`many` (0, 5–9, 11–14) «Импортировано **5** словарей» / «…**11** словарей» / «…**0** словарей» ✓;
`other` (fractions) «Импортировано **1,5** словаря» — gen. sg., correct for Russian fractions ✓ (unreachable anyway: `count` is an `Int`, and `count == 0` short-circuits to `settings_yomitan_empty_summary` at line 344). The four `xliff:g example=` values (1 / 2 / 5 / 1.5) correctly diverge from EN's (1 / 3) because `few`/`many` **have no English counterpart** and EN's `other`="3" would be flatly wrong for a Russian fraction band — the same practice `values-ar` uses for its six categories (0/3/11/100). `example` is stripped by AAPT2 and has no runtime effect.

**Share-scope buttons — verified against AOSP source, not from memory.** Fetched
`aosp-mirror/platform_frameworks_base` → `packages/SystemUI/res/values-ru/strings.xml`:
`screen_share_permission_dialog_option_single_app` = «**Показать приложение**» and
`_entire_screen` = «**Показать весь экран**». Both `stream_kind_share_one_app` and
`stream_kind_share_entire_screen` are **byte-identical to AOSP** ✓. (I had suspected
the single-app one was missing «одно» — it is not; AOSP genuinely omits it. Recorded
so a future reviewer doesn't re-raise it.)

**The 38 `misc_*` chips — cluster distinctness.** Both traps named in the brief were
**avoided**: `misc_nonstandard` is «Нестандартное», *not* «Ненормативное» (which
means *obscene*) ✓; and «Уничижительное» is used **only** at `misc_derogatory` — it
does **not** collide into the humble slot, where `misc_humble` correctly reads
«Скромное» ✓. Honorifics (`Почтительное · Скромное · Вежливое`) are three-way
distinct ✓. Obsolescence (`Архаичное · Устаревшее · Старомодное · Историческое`) is
four-way distinct — the `misc_dated` finding above is about *register*, not about a
collision. Several chips are the genuinely canonical Russian пометы and should be
left alone: «Переносное» (перен.), «Книжное» (книжн.), «Разговорное» (разг.),
«Ласкательное» (ласк.), «Шутливое» (шутл.), «Фамильярное» (фам.), «Звукоподражание»,
«Деликатное» (correctly dodges the «Чувствительное» calque). Adjective-vs-noun mix
(«Сленг», «Идиома», «Эвфемизм») is correct lexicographic practice and is *not* a
defect — the sibling `pos_*` family is all nouns because POS tags are nouns, while
register пометы are adjectives. Chip widths (14–17 ch) sit inside the committed
range (`pos_auxiliary` = 22 ch): **no truncation risk**.

**Noun case around every `<xliff:g>` — read with real values dropped in.** No raw
placeholder is left in an oblique-case slot. Colon/parenthesis restructures used
exactly where the params doc prescribes: `ocr_source_label` «Распознано: PaddleOCR»
(mirrors the committed `translation_source_label` «Перевод: DeepL» — the glossary's
prescribed structure, matched ✓); `update_dialog_size_note` «Размер загрузки: 128 МБ»;
`hotkey_show_hint_title` «…показа подсказок (Фуригана)» and `hotkey_auto_hint_title`
«…/остановки: Авто Фуригана» (both reproduce the committed
`translate_button_subtitle_hold_to_show_hint` / `hotkey_show_hint_dialog_title`
patterns ✓); `settings_ocr_disable_manga_msg` «…модель (68 МБ) или удалить **её**…»
— «её» correctly agrees with fem. «модель» ✓; `update_error_no_space`
«(требуется 230 МБ)»; `tr_service_remove_title_fmt` «Убрать OpenAI?» (indeclinable
brand, acc = nom ✓); `game_audio_trim_duration` «Выбрано 2.4 с · записано 147 с»
(fully restructured out of the numeral-agreement trap ✓);
`tr_service_status_usage_today_fmt` fronts the genitive to dodge «12 345 токенов» vs
«1 токен» ✓; `update_error_wrong_package` «не является обновлением PlayTranslate»
(instrumental ✓). `hotkey_auto_hint_dialog_title` «Авто %1$s» is **not** a new
invention — it reproduces the committed `live_mode_auto_with_hint` verbatim ✓.

**Terminology — grepped against the committed file, not invented.** Every one of
these matches an existing precedent: «Поставщик» (Provider) ← `tr_service_order_footer`
«…каждого поставщика»; «инструмент OCR» ← `settings_ocr_footer` «Разные инструменты
OCR…»; «Синтез речи» (TTS) ← `settings_cell_tts`; «лимитное подключение» (metered) ←
the four `*_metered_warning_*` strings; «Наложения» (overlays) ←
`settings_hide_overlays_during_auto_mode`; «Вкл.»/«Выкл.» ← `capture_lifecycle_state_on/off`;
«захваченные/захватывает» (captured) ← `tr_service_order_footer` «получают захваченный
текст»; «Удалить» for models ← `settings_ocr_delete_confirm`; «Проверка…» ←
`settings_ocr_verifying`. The **Remove vs Delete** split the glossary asks for is
executed cleanly and deliberately: services are «Убрать» (`tr_service_remove_*`,
`tr_service_delete_cd`), history entries and models are «Удалить» — and
`tr_service_remove_message` uses *both* in one sentence exactly as EN does
(«Сервис будет **убран** из списка, а сохранённый API-ключ **удалён**») ✓. «Очистить»
is reserved for Clear-all ✓. `service_llm_badge` keeps Latin «LLM» ✓. «Промпт» is one
noun across all 22 `llm_prompt_*` keys ✓; «Ключевые слова» (keyword) is distinct from
placeholder ✓.

**Register & the deliberate decisions.** Formal lowercase **вы** held throughout
(«вашего сервера», «вашей недавней игры», «которую вы ищете»); no «ты» anywhere in
the delta. Sibling buttons share a form: «Оставить модель»/«Удалить модель»,
«Воспроизвести фрагмент»/«Остановить», «Использовать фрагмент»/«Использовать синтез
речи» — and «фрагмент» is used consistently for the glossary's *selection* ✓. The
brief's four carve-outs were checked and left alone: the AOSP share buttons (above),
the Latin «Japanese»/«English» in `llm_prompt_kw_source_desc`/`_target_desc` ✓,
`llm_status_low_memory_badge` untouched ✓, and em dashes (тире) treated as native
punctuation throughout ✓.

---

## Delta review round 2 — 2026-07-14

Fresh independent re-derivation of all 174 delta keys against EN + the committed
file. Primary target per the brief: **regressions introduced by the round-1
fixes**.

Mechanical layer re-verified programmatically across all 174: placeholders,
`<xliff:g>` inner text + `id` attrs, the bare `{token}` literals, `\n`, markup,
escaping, trailing-period parity, no double spaces, `name=` untouched, full RU
CLDR plural set. **All clean — no 🛑.**

**Counts: 0 🛑 · 1 ❌ · 1 ⚠️ · 3 💬**

### Findings (delta round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| game_audio_trim_use_tts + game_audio_trim_save | ❌ | «Использовать синтез речи» / «Использовать фрагмент» | «Синтез речи» / «Использовать» | **The trim button row overflows by 1.38×.** `activity_game_audio_trim.xml:80–116` is a horizontal `LinearLayout` (padding 12dp) holding three `wrap_content` MaterialButtons — two TextButtons (12dp side padding) and the filled primary (24dp) — separated by a weighted `Space`. At 14sp the three RU labels need **~464dp** of button against **~336dp** available on a 360dp phone. The `Space` collapses to 0 first, then the **last** child — `btnTrimSave`, the *primary* «Использовать фрагмент» — is clipped. The model calibrates on EN, which lands at **335dp vs 336dp** — i.e. EN is exactly at the limit, which is precisely why pt-BR was granted a measured width exception on this same row (`game_audio_trim_use_tts` = "Usar TTS"). RU is the widest locale in the set and got no such trim. The fix lands at **317dp (19dp headroom)**; «Синтез речи» keeps the `settings_cell_tts` term, and the "…instead" contrast is carried by the adjacent primary exactly as pt-BR's sanctioned label does. *Computed, not measured on-device — worth a Thor confirmation, but the EN calibration makes the direction unambiguous.* |
| llm_status_low_memory_badge | ⚠️ | «…перевод через **резервный** вариант» | «…перевод через **запасной** вариант» | Terminology drift on "fallback". The committed file renders it **«запасной вариант»** twice — `tr_service_offline_footer` («используются как запасной вариант») and `yomitan_single_dict_subtitle` («последний запасной вариант») — and all three EN strings use the one word *fallback*. The delta introduces a third synonym for the same mechanism. One-word fix. (The em dash stays — settled.) |
| misc_yojijukugo | 💬 | «Идиома из 4 иероглифов» | `misc_idiomatic` → «Идиоматическое», or `misc_yojijukugo` → «Из 4 иероглифов» | Prefix stutter against `misc_idiomatic` = «Идиома». `build_jmdict.py:526–540` collects **every** `<misc>` tag on a sense (`for m in sense.findall("misc")`), `renderMisc` maps each independently, and `.distinct()` only dedupes *identical* labels — so a sense tagged both `id` and `yoji` renders «**Идиома** · **Идиома** из 4 иероглифов». EN doesn't stutter because it contrasts an adjective ("Idiomatic") with a noun ("Four-character compound"); RU leads both with the same noun. Moving `misc_idiomatic` to «Идиоматическое» also joins the dominant adjectival pattern (Разговорное / Книжное / Переносное). **Caveat: I could not verify how often JMdict actually co-tags `id`+`yoji` — no JMdict on hand. The render path permits it; the corpus frequency is unverified.** Round 1 cleared the noun «Идиома» on lexicographic grounds and was right to — it simply didn't consider the yojijukugo collision. |
| misc_dated | 💬 | «Устаревающее» | (keep — or revert to «Старомодное») | The round-1 fix is *linguistically correct*: the imperfective present participle («becoming obsolete») against `misc_obsolete`'s perfective «Устаревшее» («already obsolete») encodes exactly the EN Dated/Obsolete split. The cost it introduced: the two chips are now a near-minimal pair differing only mid-stem (устарев**ш**ее / устарев**аю**щее), where «Старомодное» was instantly distinct — and EN's own comment glosses *dated* as "old-fashioned". **Not a defect; flagged only so the trade is a conscious one.** Leave as-is if aspectual precision beats glance-legibility. |
| llm_prompt_discard_message | 💬 | «Ваши изменения в этом промпте не сохранены.» | «Изменения в этом промпте пока не сохранены.» | The discard dialog now carries «сохран-» three times (title / body / button). The **title↔button** repetition is load-bearing and must stay — it is what kills the «Отмена» ambiguity (see below) — so only the body can vary, and EN does vary it ("Discard" / "edits" / "saved"). «пока» ("not yet") additionally removes the momentary "already lost?" reading of the bare short passive «не сохранены». Low priority; do not churn. |

### The discard dialog, read as a whole — the round-1 fix holds ✓

Traced against the real call site (`LlmPromptEditorActivity.kt:102–118`), which is
the only thing that settles it:

```
Не сохранять изменения?                    ← llm_prompt_discard_title
Ваши изменения в этом промпте не сохранены. ← llm_prompt_discard_message
                    [Отмена]  [Не сохранять] ← btn_cancel · llm_prompt_discard_confirm (ptDanger → finish())
```

Round 1's defect was that the old title «**Отменить** изменения?» shared a root with
`btn_cancel` = «**Отмена**», priming the *cancel* button as the affirmative answer
when it in fact **keeps** the edits — one tap from data loss. **The fix resolves it
and creates no new ambiguity:**

- The word «Отмен-» no longer appears anywhere in the dialog, so the root collision
  is gone outright — not merely weakened.
- The confirm button label «Не сохранять» is now a **byte-exact restatement of the
  title's predicate**. That is the strongest disambiguation available: the button
  matching the title's verb *is* the affirmative, so the negative-polarity question
  («Не сохранять…?») cannot be mis-answered.
- «Отмена» is left with exactly one reading — "cancel this discard" → back to the
  editor. This mirrors EN's own Cancel/Discard shape, so no locale-specific hazard
  is introduced.

The two sibling dialogs on the same screen were checked with it and are coherent:
`showFatalAlert` is title «Этот промпт нельзя сохранить» + a lone **[ОК]** (`btn_ok`)
— the permanent-capability «нельзя» is right for a dead-end dialog with no save path
(«Не удалось» would have invited a pointless retry), and it matches the delta's own
`update_error_signature` («нельзя установить») ✓; `showAdvisoryAlert` is «Проверьте
этот промпт» + [Отмена] / [Всё равно сохранить] ✓.

The **modality split is consistent across the whole delta**: permanent «нельзя»
(`llm_prompt_invalid_title`, `update_error_signature`); one-shot past «не удалось»
(`tr_service_status_check_failed`, `update_error_verification`, `update_error_install_launch`);
ongoing present «не удаётся» (`error_capture_blocked_secure` — correct, live mode
keeps polling).

### Plurals — read at each count band ✓

`settings_yomitan_count_summary`. Call site verified myself at
`RootSettingsViewModel.kt:343–346`: `getQuantityString(…, count, count)` — **selector
and format arg are the same value**, so last cycle's selector-vs-argument split
(`yomitan_import_summary_count`) is structurally impossible here; and `count == 0`
short-circuits to `settings_yomitan_empty_summary` at line 344, so `many`'s zero case
is unreachable.

- `one` (n≡1 mod 10, ≠11) → «Импортирован **1** словарь» / «Импортирован **21** словарь» — masc. sg. short participle + nom. sg. noun. Correct for *every* member of the band, precisely because selector == arg ✓
- `few` (n≡2–4, ≠12–14) → «Импортировано **2** словаря» / «…**23** словаря» — impersonal neuter + gen. sg. ✓ (the neuter impersonal is the standard quantity-statement form, cf. «Продано 3 билета»)
- `many` (0, 5–9, 11–14) → «Импортировано **5** словарей» / «…**11** словарей» — gen. pl. ✓
- `other` (fractions) → «Импортировано **1,5** словаря» — gen. sg., correct for RU fractions ✓ (unreachable; `count` is an `Int`)

The four `example=` values (1 / 2 / 5 / 1.5) rightly diverge from EN's (1 / 3): `few`
and `many` have no English counterpart, and EN's `other`="3" would be flatly wrong for
a Russian fraction band. `example` is stripped by AAPT2 — no runtime effect.

### The 38 `misc_*` labels after the edits ✓

Checked programmatically: **all 38 are distinct** — no two collapse under
`renderMisc`'s `.distinct()` (`MiscLabels.kt:31`) — and none collides with the
`pos_*` or `inflection_*` families that render in the same card. No label contains
the `" · "` join separator (`MiscLabels.kt:37`).

The four clusters survive round 1's three edits and remain internally distinguishable:

- **Offensiveness** — Уничижительное · Оскорбительное · Вульгарное · **Дискриминационное** · Деликатное. The `misc_slur` fix is right: «Бранное» (swearing/abuse) sat squarely inside the vulgar/offensive space and gave the reader three chips that all just read "rude word". Russian lexicography has no native помета for *slur*, so a coinage is forced; «Дискриминационное» is categorically distinct (a slur demeans a *group*), is unambiguous in Russian, and «дискриминационная лексика» is attested usage. ✓
- **Obsolescence** — Архаичное · Устаревшее · **Устаревающее** · Историческое ✓ (see the 💬 above).
- **Informality** — Разговорное · **Неформальное** · Фамильярное · Сленг · Интернет-сленг · Манга-сленг · Официальное · Книжное. The `misc_informal` fix is right: «Неофициальное» means *unofficial* (of a document), not a speech register. The resulting **root asymmetry** «Официальное» / «Неформальное» is the accepted cost of keeping `misc_formal` off the «Формальное» false friend (settled) — it costs nothing functionally, since the two never co-occur and both are distinct, correct register terms. ✓
- **Honorifics** — Почтительное · Скромное · Вежливое ✓ three-way distinct; «Уничижительное» is still used *only* at `misc_derogatory` and has not leaked into the humble slot.

`misc_manga_slang` → «Манга-сленг» now parallels «Интернет-сленг» (hyphenated compound, not a genitive phrase) ✓.

### Case agreement around every `<xliff:g>`, read with real values ✓

No raw placeholder sits in an oblique-case slot. The round-1 restructures hold:

- `ocr_source_label` → «**Распознавание:** PaddleOCR». The change from «Распознано:» is
  right and is the *better* structure: it is now a noun + colon + name, an exact
  structural mirror of `translation_source_label` «Перевод: DeepL» — the two lines sit
  next to each other on the result screen, so the parallel is *visible*. It also reuses
  the file's established OCR term (`status_ocr` «Распознавание текста…», `settings_header_ocr`
  «Распознавание текста (OCR)») ✓
- `update_unknown_sources_message` → the locative now precedes the verb («…на открывшемся
  экране настроек **разрешите**…»), so it can no longer misattach as "install updates
  *onto* the settings screen"; and the brand sits in apposition to the dative head noun
  «приложению», which carries the case for the indeclinable Latin name ✓. Dropping
  «приложений» avoids «приложению … приложений» without losing the pointer (the button
  opens the screen anyway) ✓
- `tr_service_status_usage_today_fmt` → «Токенов сегодня: 12 345» fronts the genitive
  plural, which is agreement-proof for 1 / 2 / 5 (the runtime passes a *pre-formatted*
  string, so `<plurals>` is unavailable). This is a genuinely idiomatic RU stat-line
  shape («Шагов сегодня: 8 421») ✓
- Nominative/citation fills: `hotkey_show_hint_title` «…показа подсказок (Фуригана)»,
  `hotkey_show_hint_dialog_title` «Показать: Фуригана», `hotkey_auto_hint_title` «…/остановки:
  Авто Фуригана», `update_dialog_size_note`, `settings_ocr_disable_manga_msg` («…или удалить
  **её**» — fem., agrees with «модель» ✓), `update_error_no_space`, `tr_service_key_tail_fmt` ✓
- Indeclinable-brand slots (acc = gen = nom): `tr_service_remove_title_fmt` «Убрать OpenAI?»,
  `update_progress_title` «Обновление PlayTranslate», `cd_add_to_anki`, `floating_menu_panel_open_app`,
  `update_error_wrong_package` («не является обновлением PlayTranslate» — instrumental ✓) ✓
- `history_empty_off` — PlayTranslate is the nominative **subject** of «захватывает» ✓
- `llm_prompt_advisory_foreign_token` — EN has `{text}` in *subject* position; RU flips to
  active («Этот промпт не заполняет {text} — ключевое слово будет…»), which keeps the token
  as an object where its indeclinability is harmless ✓. «без него» in `llm_prompt_fatal_missing_text`
  /`_missing_strings` is safe for both masc. and neut. antecedents (same form) and matches the
  neuter «ключевое слово» used in the sibling ✓

### Other things checked, no finding

- **`cd_change_source_language` / `cd_change_target_language`** = «Изменить язык **оригинала**» /
  «…язык **перевода**». These look like a term drift against the delta's own
  `llm_prompt_kw_source_desc` («исходного языка») — they are **not**. They are
  contentDescriptions for the *tappable result-screen section headers*, and the visible
  headers are `section_original` = «**Оригинал**» and `section_translation` = «**Перевод**».
  Binding the a11y label to the label the user actually sees is exactly right (and is better
  than the EN, whose headers say Original/Translation while its cd says source/target).
  «исходный/целевой язык» is correct in its own place — the keyword legend explaining the
  literal `{source}`/`{target}` tokens. Recorded so a future reviewer doesn't "fix" it.
- **`probe_initializing`** — round 1's truncation watch was a **false positive**. The chip
  measures its own localized string (`StreamKindProbe.kt:576–580`:
  `labelPaint.measureText(labelText) + 2 * labelPadding`, code comment: *"Width is MEASURED
  from the localized string — every locale fits exactly, no fixed guess"*). «Инициализация…»
  cannot truncate. Leave it.
- **`anki_game_audio_row_subtitle`** — the «Сохраняет» → «Сохранять» fix is right: the row
  title `anki_game_audio_row_title` is the infinitive «Записывать звук игры», and the sibling
  switch subtitle `history_toggle_subtitle` is the infinitive «Сохранять захваченные
  предложения…». The 3sg was the odd one out; it now matches the file's dominant switch-row shape ✓
- **`error_capture_blocked_secure`** — «захватываемое приложение» is right (EN's "this app" is
  ambiguous between PlayTranslate and the capture target) and it now matches its panel sibling
  `error_single_app_not_fullscreen` verbatim ✓. Ongoing-present «не удаётся» is correct — live
  mode keeps polling.
- **`settings_ocr_use_manga_subtitle`** — «для автоперевода» is right («авто» alone reads as
  *car*) and matches the file's established «Автоперевод» (`hotkey_auto_translation_dialog_title`,
  `settings_header_auto_translate`) ✓
- **`llm_prompt_row_system_subtitle` / `settings_llm_context_subtitle`** — «LLM-переводчикам» vs
  «онлайн-переводчикам на базе LLM» is a *motivated* variation, not a drift: the second stacks a
  second modifier ("online") that would otherwise force the triple-hyphen «онлайн-LLM-переводчикам».
  «облачным и локальным» correctly takes dative to agree with «LLM-переводчикам» ✓
- **Seconds abbreviation** — `game_audio_trim_duration` uses «с» (no period), matching
  `settings_capture_interval_seconds_suffix` = «с»; the period in `settings_capture_interval_hint`
  is a sentence-final stop, not part of the symbol. Consistent ✓
- **`stream_kind_*`** — «Какой вариант **демонстрации**…» + AOSP's «Показать приложение» /
  «Показать весь экран» + «…что именно было **показано**» form one coherent set on Android's own
  «демонстрация экрана» term ✓ (the AOSP buttons and `stream_kind_prompt_message`'s «в реальном
  времени» are settled and were not re-litigated).
- **`settings_ocr_disable_manga_*`** — the body's verbs («**Оставить** скачанную модель … или
  **удалить** её») byte-match the button labels («Оставить модель» / «Удалить модель») ✓
- **Remove vs Delete** — «Убрать» for services, «Удалить» for models/history entries, «Очистить»
  for clear-all, with `tr_service_remove_message` using both in one sentence exactly as EN does ✓
- **Truncation, remaining surfaces** — `service_account_required_free` (35 ch) renders in a
  `match_parent` wrapping subtitle (`item_add_online_service.xml`, only the *title* has
  `maxLines=1`), no risk ✓; `floating_menu_capture_screen` «Захват\nэкрана» (6/6 per line) is
  shorter than the committed, known-fitting `floating_menu_btn_capture_region` «Область\nзахвата»
  (7/7) ✓; the `misc_*` chips wrap (settled) ✓
- **Known code defects (not locale bugs, per the brief; noted once)** — `game_audio_trim_duration`
  will render «Выбрано 2**.**4 с» because `GameAudioTrimActivity` formats with `Locale.US`; Russian
  wants a decimal comma. The RU *string* is structurally correct and needs no change.
- **Register** — formal lowercase «вы» throughout; no «ты» anywhere in the delta.

### Verdict

**FIX FIRST.** One ❌ (the trim button row clips its primary action in RU — a two-string
fix) and one ⚠️ (a one-word "fallback" term alignment). Everything round 1 changed
re-derives as correct, including all three of its ❌ calls; **no regression was
introduced by any of the fixes**, and the discard-dialog ambiguity it targeted is
genuinely gone.

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
quotes; `<plurals>` categories exactly one/few/many/other. `./gradlew :app:processDebugResources`
is green. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | was | now | why |
|---|---|---|---|---|
| `settings_ocr_note_mlkit` | ⚠️ | "Быстрый даже при обилии текста на экране" | "Отзывчивый даже при обилии текста на экране" | The English comment forbids reusing the literal Fast tier label; the first pass reused «быстрый», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |
| `hotkey_capture_screen_title` | 💬 | "Нажмите, чтобы захватить экран" | "Нажмите для захвата экрана" | Its two siblings in the same list (`hotkey_auto_translation_title`, `hotkey_show_translations_title`) both use «Нажмите/Удерживайте для + genitive»; the subordinate-clause form broke the column's rhythm for no gain. |

### Clean areas (delta) — checked, no findings

All four new `<plurals>` written out at every band and read with a real count: `history_line_count` (строка / строки / строк), `settings_yomitan_outdated_summary`, `yomitan_collection_imported_count`, `yomitan_collection_skipped_count` — none is a copied English one/other pair, and `other` carries the fractional example the file already uses. Every placeholder is left in the nominative: `update_none_message` hangs the version off a dash predicate («PlayTranslate 2.4.1 — последняя версия»), and `image_import_no_text` / `camera_snapshot_no_text` reuse the colon frame `status_no_text` already established («%1$s: текст не найден…») rather than forcing an oblique case. «модуль» was chosen for *engine* to match Android's own Russian for a pluggable engine («Модуль синтеза речи») and because «движок» is below this file's register; it also stays clear of «модель» (the downloaded OCR model, `settings_ocr_delete_msg`) and «инструмент» (tool) — all three meet in `settings_ocr_delete_camera_import_note`. «Камера» inside `settings_ocr_delete_camera_note` byte-matches `settings_cell_camera`. `settings_support_check_updates_title_available` byte-matches `update_dialog_title`. «снимок» for the camera freeze-frame stays distinct from «снимок экрана» (`anki_group_screenshot`). Formal lowercase «вы» throughout; « » quotes; no «ты».

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

**PASS.** One ⚠️ and one 💬 found and fixed, no ❌.

---

## Delta review 2026-08-04 (8 keys: one-tap card toasts, first-field guard, hide-translations toggle, waveform zoom hint)

Scope: the eight keys added to `values-ru/strings.xml` by the working-tree diff —
`card_words_in_sentence`, `anki_added_sentence_success`, `anki_added_word_success`,
`game_audio_zoom_hint`, `anki_first_field_unmapped`, `anki_first_field_empty`,
`history_hide_translations_toggle_title`, `history_hide_translations_toggle_subtitle`.
Reviewed by a second pair of eyes; nothing else in the file was touched.

Mechanical layer verified programmatically over the eight: file is well-formed XML; all
eight names present, no extras anywhere in the locale and no `translatable="false"`
orphans; placeholder multisets identical to EN (`%1$s` in the two first-field strings,
none elsewhere); every `<xliff:g>` span byte-identical to EN including `id` and `example`
(`field_name`/`Key`, `brand_anki`/`Anki`); `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts
match; no unescaped `'` or `"` in any text node; « » balanced and used in place of EN's
curly “ ”, per the locale's quote convention. `./gradlew :app:processDebugResources` is
green. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `game_audio_zoom_hint` | 💬 | «Сведите или разведите пальцы, чтобы показать больше или меньше **аудио**» | «…больше или меньше **записи**» | This file already names the thing being zoomed: `audio_source_game_name` is «Звук игры» and `anki_game_audio_row_title` «Записывать звук игры». «аудио» is a second noun for it, borrowed from `anki_added_no_audio` («аудио недоступно»). A straight swap to «звука» would be worse, not better — «больше или меньше звука» reads as *volume*. «записи» names what the waveform actually is (the buffered recording), dodges the volume reading, keeps the game-audio family intact, and is 7 characters shorter. Optional: the current wording is correct and clear as it stands. |
| `anki_first_field_unmapped`, `anki_first_field_empty` | 💬 | «…<xliff:g>Anki</xliff:g> определяет **заметку**…» (both) | keep — the note is on the neighbour | «заметка» is the right word: it is AnkiDroid's own Russian for *note*, so it byte-matches what the user sees in the app being written to, and the note-vs-card distinction is exactly what these two strings are about (Anki checksums the **note's** first field, not the card). Worth recording that this is its first appearance in a file that says «карточка» ~25 times — including `anki_card_type_row_label` «Тип карточки», which renders AnkiDroid's «Тип заметки». That collision is inherited from the English source (EN likewise says "Card Type" in the row and "the note" here); RU merely makes it visible, because Russian users have the AnkiDroid term in front of them. Not a defect in the delta — flagged so the EN row is on the record if the pair is ever revisited. |

No ❌ and no ⚠️ in these eight.

### Clean areas (delta) — checked, no findings

**The two first-field strings hold under a real field name.** Both front the head noun
«поле» and leave the free-form AnkiDroid field name inside « » as an undeclined citation,
which is the technique this locale's parameters prescribe and the only thing that survives
a user-defined name of unknown gender and declinability. `anki_first_field_unmapped`:
«Сопоставьте поле «X»» — «поле» carries the accusative, so "Key", "Expression",
"Слово" or "Выражение" all drop in unchanged. `anki_first_field_empty`: «Поле «X» пусто» —
«поле» carries the nominative subject. Read with each of those four values, neither
sentence bends. The anaphor «по нему» in the first string binds to «поле» (neuter,
dative after «по»), correct; «оно» in the second binds to «первому полю», the nearest
neuter and the intended referent — «заметку» is feminine and «Anki» is a brand, so there
is no competing antecedent.

**«пусто», not «пустое», is the right predicative.** The short-form neuter states a
condition of this card ("is empty on this card"); the long form «пустое» would be
attributive and read as a property of the field itself — wrong, since the same field is
non-empty on other cards. The string's own «на этой карточке» confirms the state reading.
Word order mirrors EN (field first, locative last), which is a deliberate scanability
choice in a full alert: the user needs the field name before the condition.

**Dropping "a value" from `anki_first_field_unmapped` does not cost the action.** EN maps
value → field ("Map a value to X"); RU maps field → (source implied) («Сопоставьте поле
«X»»). The RU direction is the one the app's own UI uses: the picker that opens
immediately after this toast is titled `anki_content_source_pick_title` «Сопоставить
«X»» — the same verb, the same object. The toast therefore names the verb the user is
about to see, and the missing argument is supplied by the screen one tap later. If
anything RU tracks the app's model more closely than EN does. `anki_field_mapping_unconfigured`
keeps its distinct verb («Настройте поля…»), matching EN's own "Configure" on that
different surface.

**The «—» in a toast is fine, and it is sanctioned here.** Russian тире before a fronted
explanatory clause («…— по нему Anki определяет заметку») is idiomatic and reads more
naturally than a colon would, because the second clause justifies the imperative rather
than stating its cause. The project's em-dash hook (`.claude/hooks/check-em-dash.py`) is
scoped to `values/strings.xml` only and names Russian тире as the reason the locales are
exempt, so this is not a hook or policy violation.

**Toast line budget.** `anki_first_field_unmapped` renders 57 characters against EN's 51
with the documented `Key` example — +12%, well under the ~30% Russian expansion this
locale plans for, so it lands on the same two lines EN does under the Android 12+ clamp.
A field name past roughly twenty characters would push a third line, but EN carries that
exposure identically; nothing was gained by shortening the Russian further, and shortening
it would have meant dropping «поле», the head noun the whole case-safety rests on.

**Card-shape toasts.** «Карточка предложения добавлена» / «Карточка слова добавлена»:
genitive of the mode labels as they appear in the toggle — `anki_mode_sentence`
«Предложение» → «предложения», `anki_mode_word` «Слово» → «слова» — with feminine
«добавлена» agreeing with «Карточка». Subject-first + participle-last is the standard
Russian notification shape («Сообщение отправлено»), so these read as native toasts, not
as translated headlines. «карточка предложения» is not invented: `anki_content_flag_sentence`
(«Маркер карточки предложения»), `anki_content_words_table` («карточки предложений») and
`anki_game_audio_row_subtitle` («новые карточки предложений») already use it. For the word
shape the translator correctly chose «Карточка слова» over the file's «карточка лексики»
(`anki_content_flag_vocabulary`) — these toasts exist precisely to surface which side of
the Предложение/Слово toggle fired, so echoing the toggle's own label is the point.
Divergence from `anki_added_no_audio` («Добавлено в Anki», impersonal) mirrors EN's own
split, and all three end on «в Anki», so the family stays coherent.

**Hide-translations toggle — aspect confirmed.** «Скрывать переводы» is imperfective, and
this file splits aspect by surface: toggle titles take the imperfective
(`history_toggle_title` «Хранить историю текста», `history_capture_image_toggle_title`
«Сохранять изображения захвата», and the direct analogue
`settings_hide_overlays_during_auto_mode` «Скрывать наложения в авторежиме»), while
one-shot actions take the perfective («Скрыть» in `floating_icon_close_label_hide`,
`overlay_hide_for_now`). The new title lands on the correct side of that split. Plural
«переводы» is right for a list-wide setting and does not conflict with the singular in
`hotkey_show_translations_title` («…для показа перевода»), where a single on-screen
overlay is meant — Russian number here follows the referent, as it should.

**Hide-translations subtitle — terminology.** «захваченный текст» reuses the app's
established capture verb rather than inventing a second one, exactly as the hard
constraint requires: `history_toggle_subtitle` «Сохранять захваченные предложения»,
`settings_cell_history_summary_on/off` «Журнал захваченных предложений». It also stays
clear of «распознанный», which this file reserves for OCR (`status_ocr`, `settings_header_ocr`
«Распознавание текста»). «строку» matches `history_empty_none` («Строки появляются по мере
перевода») and `history_clear_confirm_message` («Все сохранённые строки»); «Нажмите на
строку» matches `anki_words_helper`'s «Нажмите на слово». The infinitive → imperative shift
between the two sentences mirrors EN's own, and bare «перевод» in the second sentence is
unambiguous after «Нажмите на строку» — «её перевод» would only add weight. 78 chars vs
EN 62 (+26%) in a wrapping subtitle.

**Card-back header.** `card_words_in_sentence` «Слова в предложении» is sentence case as
the EN comment requires; the uppercasing is CSS (`.gl-section`, `text-transform:uppercase`
with `letter-spacing:0.12em` at `0.55em` in `PtCardTemplates.kt` / `AnkiHtmlStylers.kt`),
and Cyrillic uppercases cleanly, so «СЛОВА В ПРЕДЛОЖЕНИИ» is what renders. Baked at send
time, full-width block with 20px/4px margins — 19 chars against EN's 17 clips nothing.

**Zoom-hint length read against the real view, not guessed.** 68 chars vs EN 32 (+112%)
looked alarming, so the host was checked: the caption in `anki_game_audio_panel.xml` is
`match_parent` / `wrap_content` at 11sp with no `maxLines` and no `ellipsize`, inside a
24dp-padded sheet panel, so it wraps to a second line and clips nothing. The expansion is
not padding either — Russian has no one-word "pinch", and «Сведите или разведите пальцы»
is Google's own Russian for the gesture, which is the wording a Russian Android user has
already been taught. Naming both directions is arguably more informative than EN's bare
"Pinch" for a gesture with no visual affordance. No accuracy was traded for brevity here,
and none should be.

**Register and punctuation.** Formal lowercase «вы» throughout the delta («Сопоставьте»,
«Нажмите»); no «ты»; « » quotes in both first-field strings where EN uses “ ”; terminal
periods present exactly where EN has them (both first-field strings, the history subtitle)
and absent exactly where EN omits them (both toasts, the zoom hint, both headers/titles).

### Verdict

**PASS.** Two 💬, no ⚠️, no ❌, no 🛑. The delta's hardest spot — a free-form,
user-supplied AnkiDroid field name dropped into two Russian sentences — is handled with
the head-noun-plus-citation-quotes construction and holds for any name.
