# Thai (values-th) localization review

Mechanical pass: scripted comparison of all string names, placeholders (%1$s/%2$d/…), escapes (\n, \{ \}, &lt; &gt;), and `<b>` markup found zero differences; plurals use `other` only; no unescaped apostrophes; no ครับ/ค่ะ particles anywhere; brand names all preserved. No 🛑 issues.

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| live_mode_auto_with_hint | ❌ | `อัตโนมัติ <xliff…>%1$s</xliff…>` | `<xliff…>%1$s</xliff…>อัตโนมัติ` | English word order. Composed it renders "อัตโนมัติ ฟุริงานะ"; Thai puts the modifier last — "ฟุริงานะอัตโนมัติ", matching the file's own `live_mode_auto_translate_label` "แปลอัตโนมัติ". |
| tts_language_unsupported_with_engine_message | ❌ | `แต่ไม่รองรับ %2$s` | `แต่ไม่รองรับภาษา%2$s` | Thai language display names lack the "ภาษา" prefix ("ญี่ปุ่น" = both "Japanese" and "Japan"), so this reads "doesn't support Japan". Every sibling string (status_no_text, lang_setup_requires_64bit_msg, tts_voices_section_header, anki_section_description) correctly prepends ภาษา. |
| tts_language_unsupported_unknown_engine_message | ❌ | `ไม่รองรับ %1$s` | `ไม่รองรับภาษา%1$s` | Same issue as above. |
| tr_service_quality_better | ⚠ | `คุณภาพดีขึ้น` | `คุณภาพดีมาก` | "ดีขึ้น" means "has improved (over time)", not a static tier above "ดี". On a model row it implies the quality recently changed. |
| anki_permission_rationale_message | ⚠ | `ไปยัง Anki PlayTranslate ต้องมีสิทธิ์` | `PlayTranslate ต้องมีสิทธิ์เข้าถึง AnkiDroid เพื่อเพิ่มการ์ดไปยัง Anki` | The English comma was dropped, leaving two adjacent Latin brands; renders as "add cards to Anki PlayTranslate". Restructure so the brands don't collide. |
| anki_settings_grant_access_subtitle | ⚠ | `ไปยัง Anki %1$s ต้องมีสิทธิ์` | `%1$s ต้องมีสิทธิ์เข้าถึง AnkiDroid เพื่อเพิ่มการ์ดไปยัง Anki` | Same brand-collision as above ("Anki PlayTranslate"). |
| status_hold_hint | ⚠ | `กดค้างที่พื้นที่หรืออัตโนมัติ` | `กดค้างที่ "พื้นที่" หรือ "อัตโนมัติ"` | Without quotes this garden-paths as "long-press the area, or automatically…". The words are button names and need marking. |
| live_mode_pause_label | ⚠ | `หยุดชั่วคราว` | `พัก` | 11 glyphs at 8sp next to short siblings (อัตโนมัติ/พื้นที่/การตั้งค่า) — real truncation risk. "พัก" is the natural short gaming "pause"; keep "หยุดอัตโนมัติชั่วคราว" for the 16sp overflow item. |
| restricted_settings_message | ⚠ | `"อนุญาตการตั้งค่าที่จำกัด"` | `"อนุญาตการตั้งค่าที่ถูกจำกัด"` | Android 13+ renders the ⋮ menu item ("Allow restricted settings") as "อนุญาตการตั้งค่าที่ถูกจำกัด"; the quoted label must match exactly or users can't find it. Also applies to restricted_settings_title. Verify once on a Thai-locale device. |
| settings_header_ocr | ⚠ | `รูปภาพเป็นข้อความ (OCR)` | `แปลงภาพเป็นข้อความ (OCR)` | Verbless "X เป็น Y" reads "images are text"; conversion needs แปลง. |
| overlay_icon_gesture_drag / _hold / _tap | 💬 | `<b>ลาก</b> บนคำ…` / `<b>กดค้าง</b> เพื่อ…` / `<b>แตะ</b> เพื่อ…` | `<b>ลาก</b>บนคำ…` etc. | Space after the bolded verb sits inside a Thai run; the rest of the file writes "กดค้างเพื่อ…" unspaced. If the gap is a deliberate visual cue for the bold verb, keep it — but then it's the only place that does it. |
| hymt_legal_message | 💬 | `ใบอนุญาตนี้ไม่รวมการใช้งานภายใน` | `ใบอนุญาตนี้ไม่อนุญาตให้ใช้งานภายใน` | "ไม่รวม" ("doesn't include") is softer than "excludes". Also consider quoting the button: `เมื่อแตะ "ยอมรับ" ถือว่า…`. Everything load-bearing is intact: §5(b) reference, both สหภาพยุโรป/สหราชอาณาจักร/เกาหลีใต้ enumerations, and "ยืนยันและรับรอง" carries the affirm-and-warrant force. |
| qwen_mnn_disable_message (also qwen35_2b / gemma_e2b / hymt) | 💬 | `โมเดลขนาด … ถูกติดตั้งไว้` | `มีโมเดลขนาด … ติดตั้งอยู่` | Adversative ถูก-passive on a neutral fact; the existential form is the natural Thai. Same sentence in all four model sections. |
| settings_support_donate_subtitle | 💬 | `ช่วยให้มันดำเนินต่อไปได้` | `ช่วยให้โปรเจกต์นี้ดำเนินต่อไปได้` | "มัน" is too colloquial for the otherwise neutral-polite register. |
| anki_card_type_basic_no_mapping | 💬 | `โดยอัตโนมัติตามว่าคุณกำลังบันทึก` | `โดยอัตโนมัติขึ้นอยู่กับว่าคุณกำลังบันทึก` | "ตามว่า" is non-standard; "ขึ้นอยู่กับว่า" is the idiomatic "depending on whether". |
| settings_hide_overlays_ignored_multi_display | 💬 | `ระบบจะไม่สนใจเมื่อ` | `ระบบจะไม่ใช้การตั้งค่านี้เมื่อ` | "ไม่สนใจ" ("won't care") is anthropomorphic/casual for a settings disclosure. |
| llm_low_memory_start_anyway | 💬 | `เริ่มต่อไป` | `เริ่มใช้งานเลย` | "เริ่มต่อไป" can parse as "start the next one"; "…เลย" carries the "anyway/regardless" force. |
| status_idle (also accessibility_dialog_message) | 💬 | `แตะแปลเพื่อ…` | `แตะ "แปล" เพื่อ…` | Unmarked button name fuses into the verb phrase ("tap-translate"). Lower stakes than status_hold_hint but same pattern. Separately: "แอปที่ติดตั้ง" in the two nav paths faithfully mirrors the EN "Installed apps", but stock Android's Accessibility list section is actually "แอปที่ดาวน์โหลด" — a source-string issue worth fixing in English too. |

Sections checked and clean (not padded above): all download/progress strings ("กำลังดาวน์โหลด… X จาก Y" consistent), metered-network dialogs (agreed term เครือข่ายที่จำกัดปริมาณ used throughout), classifier usage (สำรับ Anki %d ชุด, %d รายการ, %d หน้าจอ, %d ตัวอักษร all read naturally at 1 and many), the backend-cooldown composition ("ลองใหม่เวลา 15:42" / "ลองใหม่วันที่…" composes correctly), the Example: samples correctly left unlocalized, "Capture Region" two-line button (พื้นที่\nจับภาพ — short, correct head-noun order), and the a11y label/colon set (a11y_quality_label etc. match the EN colon placement exactly).

## Verdicts

- **Register consistency**: clean — no politeness particles anywhere, consistent neutral-polite คุณ-register; only "มัน" (donate subtitle) dips colloquial.
- **Terminology consistency**: strong — การช่วยเหลือพิเศษ, สำรับ, การ์ด, แพ็กภาษา, ปุ่มลัด, การอ่านออกเสียงข้อความ, การจับภาพหน้าจอ, การซ้อนทับ all map 1:1 throughout; one tier-label miss (คุณภาพดีขึ้น).
- **Android-settings wording**: good — การตั้งค่า, การช่วยเหลือพิเศษ, แสดงทับแอปอื่น, การตั้งค่าด่วน + ไทล์ all match system Thai; restricted-settings quoted label likely off by one word (ถูกจำกัด).
- **Word spacing**: clean except the three gesture-hint strings (space after the bolded verb).
- **Grammar around placeholders**: solid overall; two TTS strings drop the required ภาษา prefix and one composed label has English word order — the three ❌ items.
- **Truncation risk**: only หยุดชั่วคราว (bottom-bar Pause) is at real risk; everything else fits.
- **Legal text**: faithful — §5(b), both EU/UK/South Korea enumerations, and affirm-and-warrant force all preserved; one softener noted (ไม่รวม → ไม่อนุญาต) as polish.
- **Overall**: fix-then-ship — three ❌ grammar/meaning fixes plus the brand-collision sentences, then this is a high-quality, consistent translation.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR set; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| audio_no_results | ⚠ | `ไม่พบผลลัพธ์` | `ไม่มีผลลัพธ์` | Same English "No results" already ships as `ไม่มีผลลัพธ์` in both `lang_search_no_results` and `dictionary_status_no_results`. Both forms are natural Thai, but one English term must map to one translation; align this third instance to the established `ไม่มีผลลัพธ์`. |
| yomitan_import_summary_more (plurals) | ⚠ | `+<xliff…>%1$d</xliff…> รายการ` | `อีก <xliff…>%1$d</xliff…> รายการ` | English `+%1$d more` is appended to an elided names line ("…, +3 more"); `+3 รายการ` reads as a flat count "+3 items" and drops the "more / not-shown" sense. The file already renders "more" as `อีก %1$d` (`inflection_more`); `อีก %1$d รายการ` restores it. Borderline ❌ (meaning loss) but context-recoverable, so ⚠. |

## Clean areas (delta)
**Word-spacing** — clean at every boundary. `base_url_invalid`: `ใช้ https://`, the em-dash `https:// — http://` (spaced between two Latin URLs), and `LAN เท่านั้น` all correct; the long Thai run `อนุญาตเฉพาะที่อยู่ในเครื่องหรือเครือข่าย` between `http://` and `LAN` carries no stray internal space. `URL ที่กำหนดเอง` (Latin→Thai), `importing_progress` `นำเข้า %1$d จาก %2$d…` (spaces around both numeric placeholders, ellipsis glued), `summary_count` `…%1$d จาก %2$d ฉบับแล้ว`, and `summary_more` `+%1$d รายการ` (the `+` glued to the number) are all spaced correctly. The four Anki `*_desc` brand spans (`“PitchPosition” ของ <Lapis>`, `<JPMN>`) sit Thai-space-Latin-space-Thai with no leakage.

**Classifiers** — `ฉบับ` for dictionaries in `summary_count` (`นำเข้าพจนานุกรม N จาก M ฉบับแล้ว`) reads naturally and collapses correctly at 1 and many (Thai has no singular form). `รายการ` for the elided-item count in `summary_more` matches the file's existing `%d รายการ` list counters (lines 214, 1317). No bare-number-without-classifier anywhere in the new set.

**Terminology reuse** — every load-bearing term matches precedent: `การเน้นระดับเสียง` (pitch-accent) == `yomitan_category_pitch_accent`; `ความถี่` (frequency) == `yomitan_category_frequency`; `พจนานุกรม` (dictionary) and `นำเข้า` (import) consistent across the whole Yomitan block; `การอ่านออกเสียงข้อความ` (TTS, `audio_source_tts_name`) == the file-wide TTS term (`settings_cell_tts`, `tts_no_engine_*`); `เสียง` (`audio_source_picker_title`) == `anki_group_audio`; `ขั้นสูง` (`llm_backend_advanced_header`) == the casing-free adjective already used in `enhanced_auto_translate_title`; `คำจำกัดความ`, `การ์ด`, `ไฮไลต์` all reused. `ตัวเลขจัดเรียงตามความถี่` (frequency-sort) and `รายการความถี่ (สไตล์ JPMN)` read as native compounds, not calques. `audio_error_loading` `ไม่สามารถโหลดได้` follows the file's `ไม่สามารถโหลด…ได้` frame (`word_detail_more_examples_error`); `audio_loading` `กำลังโหลด…` matches the `กำลังโหลด…` family.

**Register** — neutral-polite throughout; no ครับ/ค่ะ/นะคะ in any of the 29 keys; no colloquial pronouns.

**Short-label truncation** — `ขั้นสูง` (2 syllables), `เสียง` (1), `อัปเดตอัตโนมัติ`, `URL ที่กำหนดเอง` all comfortably short for their headers/labels; no risk.

**The `Example:` rule** — `pitch_position_desc` keeps the sample `0,2` verbatim after `ตัวอย่าง:`, and the desc strings leave the quoted field names ("PitchPosition", "PAOverride", "Frequency", "FreqSort", "FrequenciesStylized", "FrequencySort") and brand spans untouched — all correct, not flagged.

**Import-title near-synonyms** — `yomitan_import_summary_title_none` "Couldn't Import" → `นำเข้าไม่สำเร็จ` collapses onto the same Thai as `yomitan_io_error_title` "Import Failed"; accepted (distinct dialogs/contexts, faithful natural rendering, no user-facing collision). `นำเข้าเสร็จสมบูรณ์` for "Import Complete" is natural.

---

# Delta review — 2026-07-14 sync (174 keys)
Scope: game-audio capture + trim, History screen, editable LLM prompts, in-app updater, the 38 `misc_*` dictionary chips, OCR picker, translation-service rows, single-app capture. Independent review (reviewer did not write these strings).

Mechanical layer re-verified programmatically across all 174 keys: placeholder sets identical to EN; every `<xliff:g>` span byte-identical (inner `%n$s`, `id`, `example`); `\n` preserved (`floating_menu_capture_screen` = `จับภาพ\nหน้าจอ`); no unescaped `'`/`"`; all bare `{text} {source} {source_code} {target} {target_code} {context} {N} {strings}` tokens byte-identical Latin; `name=` untouched; `settings_yomitan_count_summary` correctly collapses to `other` only (Thai CLDR). `update_dialog_download` renders EN `&amp;` as `และ` — correct, not a dropped escape. **No 🛑 build-breaking issues.**

**Counts: 0 🛑 · 3 ❌ · 6 ⚠️ · 4 💬** (13 rows / 15 keys).

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| hotkey_show_hint_title | ❌ | `กดค้างเพื่อแสดง <xliff…>%1$s</xliff…>` | `กดค้างเพื่อแสดง<xliff…>%1$s</xliff…>` | **`hint_label` fills with Thai, not Latin.** `HotkeysSettingsActivity.kt:108/116` passes the *same* `hintLabel` (→ `hint_label_furigana_lower` = ฟุริงานะ / `hint_label_pinyin_lower` = พินอิน) into this row **and** into `hotkey_auto_hint_title` — two **adjacent rows of the same list**. Rendered: "กดค้างเพื่อแสดง ฟุริงานะ" (spurious space = clause break between verb and object) sitting directly above "แตะเพื่อเริ่ม/หยุดฟุริงานะอัตโนมัติ" (correct, unspaced). The delta's own auto-family is right and matches committed `live_mode_auto_with_hint` (`%1$sอัตโนมัติ`); this one string is the outlier. It is the **only** spaced Thai-filling seam in all 174 keys (see appendix). |
| misc_euphemistic | ❌ | `คำสละสลวย` | `คำเลี่ยง` | Mistranslation. **สละสลวย = "elegant / graceful / well-turned" (a style-quality judgement about eloquence)** — it is not euphemism. A euphemism is a mild substitute for a harsh or taboo term; the Thai linguistic label is `คำเลี่ยง` ("avoidance word"; `คำอ้อม` also works). `คำสุภาพ` is unavailable — `misc_polite` already owns it. `คำเลี่ยง` is also 7 glyphs vs 9, so it improves the chip fit. |
| ocr_source_label | ❌ | `จดจำข้อความโดย <xliff…>%1$s</xliff…>` | `อ่านโดย <xliff…>%1$s</xliff…>` | Breaks the glossary **hard constraint** that this mirror `translation_source_label`. Committed Thai is `แปลโดย %1$s` — bare **verb + โดย + %1$s**. This inserts an object (`ข้อความ`), so the two labels render side by side non-parallel: "แปลโดย DeepL" / "จดจำข้อความโดย PaddleOCR". `อ่านโดย %1$s` restores the structure, mirrors the ja precedent (`%1$sによる翻訳` → `%1$sによる読み取り`), and reuses the verb the file already uses for OCR (`settings_ocr_footer` "PlayTranslate อ่านข้อความบนหน้าจอ", `error_capture_blocked_secure` "อ่านข้อความไม่ได้"). Secondary: `จดจำ` is primarily "memorize / commit to memory"; the technical term for recognition is `รู้จำ`. |
| settings_yomitan_count_summary | ⚠️ | `นำเข้าพจนานุกรมแล้ว <xliff…>%d</xliff…> ฉบับ` | `นำเข้าพจนานุกรมแล้ว <xliff…>%d</xliff…> เล่ม` | **Wrong classifier.** `ฉบับ` counts documents/issues and, for a reference work, **editions of one title** (พจนานุกรมฉบับราชบัณฑิตยสถาน). So "3 ฉบับ" reads "3 *editions*" — but the user imported 3 *different* dictionaries (JMdict, Jitendex, a frequency list). The classifier for a dictionary as a work/volume is `เล่ม` (`ชุด` if you prefer a data-set reading). Note this **also** applies to the committed `yomitan_import_summary_count`, which the 2026-06-23 delta blessed — I'm re-litigating that call deliberately; fix both together or neither, so the two counters agree. |
| settings_ocr_use_manga_subtitle · update_unknown_sources_message | ⚠️ | `…ไม่แนะนำสำหรับโหมดอัตโนมัติ MangaOCR เป็นส่วนเสริม…` / `…หน้าการตั้งค่าที่จะเปิดขึ้น Android อาจรีสตาร์ทแอป…` | insert a Thai clause-opener before the Latin token: `…ไม่แนะนำสำหรับโหมดอัตโนมัติ ทั้งนี้ MangaOCR เป็นส่วนเสริม…` / `…หน้าการตั้งค่าที่จะเปิดขึ้น โดย Android อาจรีสตาร์ทแอป…` | **Latin/Thai seam, 2 keys, one fix.** In both, an EN **sentence boundary** lands immediately before a Latin token — and Thai spells a sentence break with the *same single space* it uses to delimit Latin. The reader cannot tell which it is, and the greedy parse binds the brand backwards: "ไม่แนะนำสำหรับโหมดอัตโนมัติ MangaOCR" = "auto-mode MangaOCR is not recommended" (meaning inverted); "หน้าการตั้งค่าที่จะเปิดขึ้น Android" = "the Android settings page that opens". A Thai discourse opener (`ทั้งนี้` / `โดย`) makes the boundary unambiguous. The delta gets this right everywhere else — `update_error_signature` / `_wrong_package` open the new sentence with `ให้`. |
| error_single_app_not_fullscreen | ⚠️ | `หยุดการแปลชั่วคราว — … ระบบจะแปลต่อเมื่อแอปกลับมาเต็มหน้าจออีกครั้ง` | `การแปลหยุดชั่วคราว — … ระบบจะกลับมาแปลอีกครั้งเมื่อแอปแสดงเต็มหน้าจอ` | Two problems in one banner. (1) Verb-initial `หยุดการแปลชั่วคราว` reads as an **imperative** ("Pause the translation") on a string that is a *status*; topic-first `การแปลหยุดชั่วคราว` is unambiguously stative. (2) `จะแปลต่อเมื่อ` garden-paths on the fixed conjunction **ต่อเมื่อ = "only when"** — the intended parse is `แปลต่อ` ("resume") + `เมื่อ`. `กลับมาแปลอีกครั้งเมื่อ…` removes the ambiguity. |
| misc_nonstandard | ⚠️ | `ไม่มาตรฐาน` | `ไม่เป็นมาตรฐาน` | `ไม่` cannot negate the bare noun `มาตรฐาน`; `ไม่มาตรฐาน` is telegraphic/colloquial. `ไม่เป็นมาตรฐาน` is grammatical **and** parallels the set's own `เป็นทางการ` / `ไม่เป็นทางการ` (`misc_formal` / `misc_informal`). (`ไม่ได้มาตรฐาน` is the wrong sense — it means "substandard quality".) |
| misc_historical | ⚠️ | `ทางประวัติศาสตร์` | `ประวัติศาสตร์` | Two problems: it is an **adverbial adjunct** ("historically"), not a label — every sibling chip is a noun phrase or an adjective; and at **13 advancing glyphs it is the widest chip in the set**, 3 over the committed `pos_*` ceiling (`คำบอกจำนวน`, 10). Dropping `ทาง` yields a domain-style label ("History"), which is exactly the tag's sense per the EN comment ("refers to a thing of the past"). |
| settings_debug_log_trace | ⚠️ | `บันทึกเทรซของบันทึกการแปล` | `เก็บ trace ของบันทึกการแปล` | `เทรซ` is an ad-hoc transliteration; the file's own precedent keeps the term in Latin (`crash_dialog_message`: "ซึ่งจะรวมถึง stack trace"). Also a `บันทึก…บันทึก` stutter (verb "record" + noun "log") that makes the row hard to parse. Swapping the verb to `เก็บ` kills the stutter. Debug-builds-only, hence ⚠️ not ❌. |
| misc_internet_slang · misc_endearing | 💬 | `สแลงอินเทอร์เน็ต` (13) · `คำแสดงความรัก` (12) | `สแลงเน็ต` (8) · `คำเอ็นดู` (7) | Chip width. Both exceed the committed `pos_*` ceiling (max 10, `คำบอกจำนวน`). `เน็ต` is the everyday Thai clipping and reads naturally next to `คำสแลง` / `สแลงมังงะ`. Optional — neither is *wrong*. |
| misc_offensive | 💬 | `คำไม่เหมาะสม` | (keep) | The weakest member of the offensiveness cluster — `ไม่เหมาะสม` ("inappropriate") is the natural *umbrella* for all four, so using it for one is slightly off; it is also 11 glyphs. But it **is** distinguishable from `คำดูถูก` / `คำหยาบ` / `คำเหยียด`, and every shorter alternative (`คำหยาบคาย`) collides with `misc_vulgar`. Flagging for the record; recommend keeping. |
| probe_initializing | 💬 | `กำลังเริ่มต้น…` | `กำลังเริ่ม…` | EN comment says "Keep short" — it is a ~1.5 s chip beside a colour swatch. 11 advancing glyphs is above the `pos_*` ceiling; `กำลังเริ่ม…` saves 3 with no loss. |
| settings_cell_history_summary_on / _off | 💬 | `เปิด · บันทึกประโยคที่จับภาพไว้` | `เปิด · ประวัติประโยคที่จับภาพไว้` | EN is a **noun** ("Record **of** captured sentences" — what the feature *is*); Thai `บันทึก…` reads first as a **verb** ("record the captured sentences"), which collapses it onto `history_toggle_subtitle` ("บันทึกประโยคที่จับภาพไว้ลงในอุปกรณ์นี้" — what the toggle *does*). `บันทึก` *can* be the noun "a record", and the `เปิด ·` / `ปิด ·` prefix disambiguates, so this is benign — `ประวัติ` (the file's own noun for History) would make it unambiguous. |

## Clean areas (delta — checked, no findings)

**Word spacing at Latin/Thai seams — pristine but for the one ❌.** I audited every seam in all 174 keys programmatically (Thai glyph ↔ whitespace ↔ placeholder/Latin run, both directions). **65 spaced seams; 64 are correct** — all delimit a genuinely Latin or numeric run: brands (`Anki`, `PlayTranslate`, `GitHub`, `MangaOCR`, `Android`, `PaddleOCR`, `OpenAI`), initialisms (`OCR`, `TTS`, `LLM`, `API`, `URL`, `JSON`), the bare keyword tokens (`{N}`, `{source}`, `{source_code}`, `{target}`, `{target_code}`, `{strings}`, `{text}`), the un-localized code/name examples (`ja`, `en`, `Japanese`, `English`), and numeric fills (`%1$s วิ`, `(ต้องการ 230 MB)`, `%d ฉบับ`, `12,345 โทเค็น`, `Key ••••4f2a`). The 65th is `hotkey_show_hint_title`. No stray space appears *inside* any Thai run anywhere in the delta.

**The `hint_label` family.** `hotkey_auto_hint_title` (`แตะเพื่อเริ่ม/หยุด%1$sอัตโนมัติ`) and `hotkey_auto_hint_dialog_title` (`%1$sอัตโนมัติ`) both correctly treat the placeholder as a Thai run — unspaced, modifier last — and are consistent with the committed `live_mode_auto_with_hint`, confirming the 2026-06-23 ❌ fix landed. (Companion note, outside delta scope: the same spurious space as the ❌ above also sits in three *committed* siblings — `hotkey_show_hint_dialog_title` (`แสดง %1$s`), `translate_button_subtitle_hold_to_show_hint` (`กดค้างเพื่อแสดง %1$s บนหน้าจอเกม` — needs **both** spaces removed), and `translate_button_subtitle_hold_to_show_translations_instead_of_hint` (`…แทน %1$s`). Worth sweeping in the same pass, since `hotkey_show_hint_title` and `hotkey_show_hint_dialog_title` are the row and the dialog it opens.)

**Remove / Delete / Clear — all three kept apart**, exactly as the glossary requires: `นำ…ออก` for services (`tr_service_delete_cd` นำบริการออก, `tr_service_remove_confirm` นำออก, `tr_service_remove_title_fmt` `นำ %1$s ออกหรือไม่` — correct discontinuous verb around the placeholder), `ลบ` for entries and models (`history_action_delete`, `history_delete_confirm_title`, `settings_ocr_disable_delete`), `ล้าง` for Clear (`history_clear_menu` ล้างประวัติ, `history_clear_confirm_title`). No cross-contamination.

**Terminology vs the committed file.** `บริการแปล` matches the page title `settings_cell_translation_services` (glossary requirement) in both `add_online_service_title` and `tr_service_add_online`. `พรอมต์` is the single noun across all 24 `llm_prompt_*` keys — never "คำขอ"/"คำสั่ง" as the *term*. `ประวัติ` = History everywhere (`settings_cell_history`, `history_screen_title`, `history_toggle_title`). `จับภาพ` reused for "captured" per the glossary's "one capture verb" rule. `การซ้อนทับ` (overlays), `พื้นที่` (region), `การเชื่อมต่อที่จำกัดปริมาณ` (metered — matches the agreed `เครือข่ายที่จำกัดปริมาณ`), `ช่วงที่เลือก` (selection, both trim buttons), `ตัด` (Trim, toolbar + Anki cell), `กลุ่ม` (batch), `ผู้ให้บริการ` (Provider), `คีย์เวิร์ด` (keyword), `LLM` kept as the initialism. `เสียงเกม` works as both pill and section header. `การแปล` (section header) vs `คำแปล` (the rendered translation layer) are correctly assigned in the hotkey block — not a collision.

**Seconds.** `game_audio_trim_duration`'s `วิ` is not a register slip — it matches the committed `settings_capture_interval_seconds_suffix` (`วิ`), while prose keeps `วินาที` (`settings_capture_interval_hint`, `dialog_hotkey_setup_instruction`). Correct split.

**Classifiers elsewhere.** `tr_service_status_usage_today_fmt` (`วันนี้: 12,345 โทเค็น`) and `update_error_no_space` (`ต้องการ 230 MB`) are unit-nouns in a NUM+NOUN stat frame — idiomatic Thai, no classifier wanted. Only the dictionary counter is wrong (above).

**Register.** Zero `ครับ/ค่ะ/นะคะ/จ้า` in any of the 174 keys (verified programmatically); consistent neutral-polite `คุณ`-register; sibling buttons share a grammatical form (`เก็บโมเดลไว้`/`ลบโมเดล`, `เล่นช่วงที่เลือก`/`ใช้ช่วงที่เลือก`). `บันทึกเลย` (`llm_prompt_save_anyway`) correctly reuses the `…เลย` "anyway" frame the committed `llm_low_memory_start_anyway` (`เริ่มใช้งานเลย`) established.

**Deliberate decisions honoured** (not flagged, per brief): `stream_kind_share_one_app` / `_entire_screen` = `แชร์แอปเดียว` / `แชร์ทั้งหน้าจอ` — AOSP Thai SystemUI wording, and `stream_kind_prompt_message` quotes both back verbatim, so the dialog cross-references the buttons exactly; `llm_prompt_kw_source_desc` / `_target_desc` keep `Japanese` / `English` in Latin; `llm_status_low_memory_badge` dash untouched.

**Plurals.** `settings_yomitan_count_summary` correctly ships `other` only (EN's `one` dropped — right for Thai's CLDR set). Classifier flagged above; the form itself collapses correctly at 1 and at many.

**Orthography.** `history_empty_none`'s `ต่างๆ` (no space before ไม้ยมก) is non-RID but is the file's consistent house style (`ใดๆ`, `อื่นๆ`, `เงียบๆ`, `เร็วๆ นี้`, `ต่างๆ` in 6 committed strings) — left alone rather than made inconsistent.

**`misc_*` cluster distinctness — all four clusters pass.** offensiveness: `คำดูถูก` (belittling) / `คำไม่เหมาะสม` (inappropriate) / `คำหยาบ` (coarse) / `คำเหยียด` (discriminatory) — four different roots, mutually legible on one word. obsolescence: `คำโบราณ` / `เลิกใช้แล้ว` / `ล้าสมัย` / `ทางประวัติศาสตร์` — distinct (the last is re-worded above for form/width, not for collision). informality: `ภาษาพูด` / `ไม่เป็นทางการ` / `ภาษากันเอง` / `คำสแลง` — distinct. **honorifics is the strongest cluster:** `คำยกย่อง` (尊敬語) / `คำถ่อมตน` (謙譲語) / `คำสุภาพ` (丁寧語) — the correct three-way Thai split, no collapse. `ภาษาพูด`/`ภาษาเขียน` (colloquial/literary) is the canonical Thai pair. `โดยปริยาย` is the genuine RID figurative label, not a gloss of the English. `คานะ`/`คันจิ` kept as loanwords per glossary; `misc_yojijukugo` described (`สำนวนสี่อักษร`), not romanized. The chips follow the committed `pos_*` register (`คำ-` / `ภาษา-` prefixed lexicographic nouns).

**Truncation.** `service_llm_badge` (`LLM`, 3), `floating_menu_capture_screen` (`จับภาพ\nหน้าจอ` — 6/6 per line, verb/object split, `\n` intact) both comfortable. Chip widths measured against the `pos_*` ceiling (10); the five over it are called out above.

---

## Delta review round 2 — 2026-07-14

Fresh independent reviewer (wrote none of these strings, reviewed none of them in round 1). Scope: the same 174 delta keys, re-derived from scratch, with the 13 keys round 1 changed as the primary target.

Mechanical layer re-verified programmatically across all 174: placeholder sets identical to EN; every `<xliff:g>` span byte-identical; bare `{text} {source} {source_code} {target} {target_code} {context} {N} {strings}` tokens intact and Latin; `\n` preserved; no unescaped `'`; `name=` untouched; `settings_yomitan_count_summary` correctly `other`-only (Thai CLDR); zero ครับ/ค่ะ/นะคะ/จ้า. **No 🛑.**

**Counts: 0 🛑 · 1 ❌ · 3 ⚠️ · 6 💬** (10 rows / 13 keys).

### The 13 changed strings — verdict on each fix

| key | fix | verdict |
|---|---|---|
| `hotkey_show_hint_title` | deleted the space before `<xliff:g>` | ✅ **correct.** Premise verified in code: `hint_label_furigana_lower` = ฟุริงานะ, `hint_label_pinyin_lower` = พินอิน — the placeholder really does fill with **Thai**, so the space was a spurious clause break. Now parallel with its own delta siblings `hotkey_show_translations_title` (กดค้างเพื่อแสดงคำแปล) and `hotkey_auto_hint_title` (…หยุด%1$sอัตโนมัติ). No space leaked anywhere else. |
| `ocr_source_label` → `อ่านโดย %1$s` | mirror `translation_source_label` | ✅ **correct.** Committed `translation_source_label` = `แปลโดย %1$s` (verified, line 263). Bare verb + โดย + placeholder now matches exactly; side by side they render "แปลโดย DeepL" / "อ่านโดย PaddleOCR". Reuses the file's own OCR verb (`settings_ocr_footer`). |
| `misc_nonstandard` → `ไม่เป็นมาตรฐาน` | grammatical negation | ✅ **correct**, and it now parallels the set's own `เป็นทางการ` / `ไม่เป็นทางการ` without colliding with either. |
| `misc_historical` → `ประวัติศาสตร์` | drop the adverbial `ทาง` | ✅ **correct.** Domain-style noun label; collides with nothing, incl. `ประวัติ` (History screen) and `คำโบราณ` / `ล้าสมัย` / `เลิกใช้แล้ว`. |
| `settings_debug_log_trace` → `เก็บ trace ของ…` | kill the บันทึก…บันทึก stutter | ✅ **correct.** Latin `trace` matches the file's own precedent (`crash_dialog_message` "stack trace"), spaced correctly on both sides. One residual noted below (💬). |
| `error_single_app_not_fullscreen` | topic-first + `แอปที่ถูกจับภาพ` | ✅ **correct.** Stative reading restored; the `แปลต่อเมื่อ` ("only when") garden path is gone. |
| `error_capture_blocked_secure` | `แอปที่ถูกจับภาพ` | ✅ **passive is unambiguous** (see below); one stylistic residual (💬). |
| `update_unknown_sources_message` | insert `ทั้งนี้` before `Android` | ✅ **correct.** The brand can no longer bind backwards. |
| `settings_ocr_use_manga_subtitle` | insert `ทั้งนี้` before `MangaOCR` | ⚠️ **fixed the cited seam, left the identical bug at the next one** — see findings. |
| `settings_cell_history_summary_on` / `_off` → `ประวัติประโยค…` | noun, not verb | ✅ correct; minor echo noted (💬). |
| `probe_initializing` → `กำลังเริ่ม…` | shorten | ✅ correct; created a benign byte-merge (💬). |
| `misc_euphemistic` → `คำเลี่ยง` | "สละสลวย means elegant" | 🔴 **REGRESSION — revert.** See ❌ below. |

**Is the `ถูก` passive now unambiguous in both error strings? Yes — in both.** `ที่ถูก` + V is a hard passive in Thai: `ถูก` marks the following verb as done **to** the head noun, so no subject-relative reading ("the app that *is capturing*" = PlayTranslate) is grammatically available. `แอปที่ถูกจับภาพ` can only mean "the app that is being captured". The round-1 defect is closed in `error_single_app_not_fullscreen` **and** `error_capture_blocked_secure`, and the two banners now use one term for one concept.

### Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| misc_euphemistic | ❌ | `คำเลี่ยง` | `คำสละสลวย` **(revert round 1)** | **Regression introduced by the round-1 fix.** Round 1 parsed the compound compositionally — the *bare adjective* สละสลวย does mean "elegant / well-turned" — and missed that **คำสละสลวย is lexicalised as the ordinary Thai noun for "a euphemism."** Attested in running Thai Wikipedia prose in exactly this sense: *"เน็ตฟลิกซ์แอนด์ชิล … ซึ่งเป็น**คำสละสลวย**สำหรับการมีกิจกรรมทางเพศ"* ("…which is a euphemism for sexual activity") and *"พระนามเนบตี … เป็น**คำสละสลวย**ในทางศาสนา"* ("…is a religious euphemism"); Thai reference sources gloss it as *ถ้อยคำสุภาพซึ่งใช้แทนคำที่อาจหยาบคายหรือรุนแรงเกินไป* — precisely a euphemism. `คำเลี่ยง` has **no attestation** as a lexicographic label. Worse, it is a `คำ`+V compound sitting beside five taboo tags (`คำหยาบ` · `คำเหยียด` · `คำดูถูก` · `คำไม่เหมาะสม` · `คำอ่อนไหว`), which primes the inverted reading **"a word to avoid"** — the opposite of a euphemism, which is the *safe substitute*. The failure modes are asymmetric: a reader who doesn't know `คำสละสลวย` still lands on "genteel wording" (near-correct); a reader who mis-parses `คำเลี่ยง` lands on "taboo" (inverted). Chips don't truncate, so the 9 glyphs cost nothing. (`การเกลื่อนคำ` is th-wiki's title for the *phenomenon*; it names the process, not the word, so it is wrong for a chip.) |
| settings_ocr_use_manga_subtitle | ⚠️ | `…MangaOCR เป็นส่วนเสริมของ OCR ที่เลือกไว้ ไม่สามารถทำงานเพียงลำพังได้` | `…เป็นส่วนเสริมของ OCR ที่เลือกไว้ จึงไม่สามารถทำงานเพียงลำพังได้` | **The round-1 fix repaired the first ambiguous seam and left the identical bug at the next one.** `ทั้งนี้` correctly stops `MangaOCR` binding backwards — but one clause later the zero-subject predicate `ไม่สามารถทำงานเพียงลำพังได้` sits immediately after the NP `OCR ที่เลือกไว้`, which is *itself an OCR engine* and therefore a plausible subject. Linear adjacency gives **"the selected OCR cannot work alone"** — inverting which engine is the dependent one, in a WARNING string. Thai topic-continuity and pragmatics favour the intended reading, so it is recoverable (hence ⚠️, not ❌), but `จึง` closes it for two glyphs: it forces the null subject back to the topic (MangaOCR) **and** supplies the causal link EN's comma splice implies. Same generator as the seam round 1 fixed — sweep the string, not the sentence. |
| anki_game_audio_cell_untrimmed | ⚠️ | `ตัดเมื่อบันทึก` | `ตัดเมื่อบันทึกการ์ด` | **Record/Save lexical merge, in the one string where both senses are live.** Thai `บันทึก` is the file's word for *both* "Save" (`btn_save`, `anki_field_mapping_save`) and "Record" (`anki_game_audio_row_title` = `บันทึกเสียงเกม`, "Record game audio"). This row is a bare, context-free title **inside the game-audio recording feature**, so `ตัดเมื่อบันทึก` reads as easily "trim when *recording*" as "trim when *saving*". Naming the object (`การ์ด`) resolves it and matches the EN comment ("card editor"). |
| settings_yomitan_count_summary | ⚠️ | `นำเข้าพจนานุกรมแล้ว <xliff…>%d</xliff…> ฉบับ` | `…<xliff…>%d</xliff…> เล่ม` | **Carried forward from round 1 — proposed, not applied.** Independently re-derived; I reach the same answer. `ฉบับ` counts documents/issues and, for a reference work, *editions of one title* (พจนานุกรม**ฉบับ**ราชบัณฑิตยสถาน), so "3 ฉบับ" reads "3 **editions**" — but the user imported 3 *different* dictionaries. `เล่ม` is the classifier for a dictionary as a work. **Not a ship-blocker:** leaving it also leaves it *consistent* with the committed `yomitan_import_summary_count`, which uses the same classifier — so the two counters agree either way. Fix both together or neither. |
| error_capture_blocked_secure | 💬 | `อ่านข้อความไม่ได้ — แอปที่ถูกจับภาพบล็อกการจับภาพหน้าจอ` | `อ่านข้อความไม่ได้ — แอปนี้บล็อกการจับภาพหน้าจอ` | The passive fix is right (above). The residual: EN says **"this app"**, not "the captured app" — the fix imported the sibling banner's term, yielding **จับภาพ twice in one nine-word clause** plus a small logical stumble ("the app that *is captured* *blocks* capture"). EN carries the same repetition and reads fine, so this is polish, not a defect. Weigh against the counter-argument for keeping it: `แอปนี้` in an in-app panel can be misread as PlayTranslate itself — presumably why the disambiguating relative was chosen — and keeping it holds the two capture-error banners on one term. Either call is defensible; flagged for the record. |
| settings_cell_history_summary_on · _off | 💬 | `เปิด · ประวัติประโยคที่จับภาพไว้` | `เปิด · รายการประโยคที่จับภาพไว้` | The fix is right — it is now a noun, and no longer collapses onto `history_toggle_subtitle` (what the toggle *does*). Residual: it now **echoes its own cell title** — the hub renders `ประวัติ` / `เปิด · **ประวัติ**ประโยค…`. EN says "Record of…", not "History of…", precisely to avoid that echo. `รายการ` is unambiguously a noun and is already the file's word for a History *entry* (`history_delete_confirm_title` = `ลบรายการนี้หรือไม่`), so it removes the echo and ties the summary to the thing being counted. |
| llm_prompt_fatal_missing_strings · llm_prompt_fatal_missing_text | 💬 | `…— หากไม่มี วลีที่จะแปลจะไม่ถูกส่งไปยังโมเดลเลย` | `…— หากขาดคีย์เวิร์ดนี้ วลีที่จะแปลจะไม่ถูกส่งไปยังโมเดลเลย` | **Same bug class round 1 fixed elsewhere**, caught in two more keys: a single space is the only thing preventing the greedy read `หากไม่มีวลีที่จะแปล` ("if there are **no phrases to translate**…"). The space is the correct Thai device, the misparse is *vacuous* rather than harmful, and the preceding clause (`พรอมต์ต้องมี {strings}`) supplies the antecedent — so it is recoverable, hence 💬 not ⚠️. Naming the referent removes the ambiguity outright, which is worth doing in a *validation* dialog. |
| probe_initializing | 💬 | `กำลังเริ่ม…` | (keep) | The shortening made it **byte-identical to the committed `settings_ocr_downloading_msg`** ("Starting…"), merging EN's `Initializing…` / `Starting…` distinction. Verified benign: different surfaces (live-mode probe chip vs OCR download dialog), never co-visible, and nothing runs `.distinct()` across them. No action needed — logged only because it is fix-introduced and a later reviewer will otherwise re-find it. |
| settings_debug_log_trace | 💬 | `เก็บ trace ของบันทึกการแปล` | (keep) | Good fix. Residual: EN "Record" is now `เก็บ` here but `บันทึก` in `anki_game_audio_row_title` — a one-EN-term-two-TH-words split. Forced by the `บันทึก…บันทึก` stutter and confined to a debug-builds-only row. Accept. |
| update_unknown_sources_message | 💬 | `หากต้องการอัปเดตให้เสร็จสิ้น ให้อนุญาตให้ PlayTranslate…` | `หากต้องการอัปเดตให้เสร็จสิ้น โปรดอนุญาตให้ PlayTranslate…` | The `ทั้งนี้` fix is right. Residual: three `ให้` in eight words (resultative / imperative / permissive). Each is grammatical; together they are a mouthful. `โปรด` for the imperative one breaks it up. |

### Clean areas (round 2 — checked, no findings)

**Word spacing at every Thai/Latin seam — clean, and no fix leaked a space.** Re-audited all 174 keys programmatically (Thai glyph ↔ whitespace ↔ Latin/placeholder run, both directions): **119 whitespace seams touching Thai; every one is correct.** Each `TH|Latin` seam delimits a genuinely Latin, numeric or punctuation run — brands (`Anki`, `PlayTranslate`, `GitHub`, `MangaOCR`, `Android`, `PaddleOCR`), initialisms (`OCR`, `TTS`, `LLM`, `API`, `URL`, `JSON`), the bare keyword tokens, the un-localised code/name samples (`ja`, `en`, `Japanese`, `English`), the new Latin `trace`, numeric fills (`%1$s วิ`, `(ต้องการ 230 MB)`, `%d ฉบับ`, `12,345 โทเค็น`, `••••4f2a`), the `·` middot and the ` — ` em dash. Every `TH|TH` seam is a genuine Thai sentence/clause break. **No stray space survives inside any Thai run**, and `hotkey_show_hint_title` — round 1's only spaced Thai-filling seam — is gone. This was the axis most likely to break under an edit round, and it is clean.

**Em-dash house style.** Five delta strings use a `X — Y` frame (`anki_game_audio_permission_denied`, `audio_source_game_enable_hint`, `llm_status_low_memory_badge`, and both capture-error banners). All spaced identically, all matching EN. `llm_status_low_memory_badge` keeps its dash per the settled call.

**`misc_*` distinctness — all 38 labels distinct, verified programmatically.** `renderMisc` (`MiscLabels.kt:31`) calls `.distinct()` **on the localised strings**, so a collision would silently collapse two tags into one — there are none. None of the 38 contains the `" · "` join separator. The four clusters all survive the three edits: **offensiveness** (`คำดูถูก` / `คำไม่เหมาะสม` / `คำหยาบ` / `คำเหยียด` / `คำอ่อนไหว`) — five roots, mutually legible on one word; **obsolescence** (`คำโบราณ` / `เลิกใช้แล้ว` / `ล้าสมัย` / `ประวัติศาสตร์`); **informality** (`ภาษาพูด` / `ไม่เป็นทางการ` / `ภาษากันเอง` / `คำสแลง`) — and the new `ไม่เป็นมาตรฐาน` parallels `ไม่เป็นทางการ` structurally without blurring into it (`มาตรฐาน` ≠ `ทางการ`); **honorifics** (`คำยกย่อง` / `คำถ่อมตน` / `คำสุภาพ`) — the correct three-way 尊敬語 / 謙譲語 / 丁寧語 split. **Reverting `misc_euphemistic` to `คำสละสลวย` keeps all 38 distinct** (`คำสุภาพ` is a different string).

**Benign file-wide value merges.** Swept every duplicate value in the whole file. All are either same-EN→same-TH (correct: `ocr_picker_title` = `cd_choose_ocr`, `history_copied_toast` = `toast_copied`, `update_unknown_sources_button` = `btn_open_overlay_settings`, `settings_ocr_disable_delete` = the six other `*_disable_delete`) or different-EN→same-TH across surfaces that never co-render (`misc_obsolete` = `deprecated_badge_label`; `probe_initializing` = `settings_ocr_downloading_msg`, noted above). No merge feeds a `.distinct()`.

**Classifiers.** `%d ฉบับ` is the only wrong one (above). `วลีกี่วลี` (`llm_prompt_advisory_missing_count`) correctly uses `วลี` as its own classifier. `โทเค็น` and `MB` are unit-nouns in a NUM+NOUN stat frame — idiomatic, no classifier wanted. `วิ` (`game_audio_trim_duration`) matches the committed `settings_capture_interval_seconds_suffix` while prose keeps `วินาที` — correct split.

**Plurals.** `settings_yomitan_count_summary` ships `other` only (EN's `one` correctly dropped for Thai's CLDR set) and collapses correctly at 1 and at many.

**The trim-editor button trio** reads as a coherent set: `ใช้ TTS แทน` / `ไม่ใช้เสียง` / `ใช้ช่วงที่เลือก` — one ใช้/ไม่ใช้ frame across all three, with the primary confirm parallel to `game_audio_trim_play` (`เล่นช่วงที่เลือก`).

**Terminology, post-edit.** `อ่านโดย` (new) collides with nothing: `อ่าน` is already the file's OCR verb (`settings_ocr_footer`, `error_capture_blocked_secure`). `พรอมต์` is still the single noun across all 24 `llm_prompt_*` keys, with `คำขอ` correctly reserved for EN "request". Remove / Delete / Clear stay apart (`นำ…ออก` / `ลบ` / `ล้าง`). `ประวัติ` = History throughout.

**Already logged in round 1, not re-filed** (committed, out of scope): `hotkey_show_hint_dialog_title` (`แสดง %1$s`) and the two `translate_button_subtitle_*` siblings still carry the spurious space that `hotkey_show_hint_title` just lost — so the hotkey **row** and the **dialog it opens** now visibly disagree. Round 1 flagged it as a follow-up sweep; it remains open.

### Appendix — `activity_game_audio_trim.xml` bottom row, measured for Thai

**Premise correction.** The row is *not* three `wrap_content` buttons with no weights: there **is** a `<Space android:layout_width="0dp" android:layout_weight="1"/>` between `btnTrimNoAudio` and `btnTrimSave`. It does not rescue the row. With `layout_width=0dp` + a weight, LinearLayout computes the weighted child's width as `share` (its slice of `delta = available − totalLength`) and clamps it with `Math.max(0, …)`. When the buttons overrun, `delta` is negative, the Space collapses to **0**, and LinearLayout **never re-measures non-weighted `wrap_content` children smaller**. So the excess overflows; horizontal gravity is START (`android:gravity="center_vertical"` sets only the vertical axis), so the **last child — `btnTrimSave`, the primary confirm — is exactly what gets clipped.** The premise's conclusion holds; only the "no weights" detail was wrong.

**Metrics** (resolved from the AAR, not assumed — `Theme.PlayTranslate` = `Theme.Material3.Dark.NoActionBar`, material 1.14.0, no `materialButtonStyle` override):

- `btnTrimSave` → `Widget.Material3.Button`: `m3_btn_padding_left/right` = **24dp**, `insetLeft/Right` = 0dp
- `btnTrimUseTts` / `btnTrimNoAudio` → `Widget.Material3.Button.TextButton`: `m3_btn_text_btn_padding_left/right` = **12dp**
- all three inherit `android:minWidth` = **88dp** from `Base.Widget.AppCompat.Button`
- text = `?attr/textAppearanceLabelLarge` → **14sp**, `sans-serif-medium`, letterSpacing 0.00714 em
- row `padding` = 12dp; `btnTrimNoAudio` `layout_marginStart` = 4dp

**Measured** with the real Android fonts (Noto Sans Thai VF instanced at wght 500 for Thai, Roboto Medium for Latin; Thai combining marks confirmed zero-advance; cross-checked against PIL's rasteriser):

| button | Thai label | text @14sp | button width |
|---|---|---|---|
| `game_audio_trim_use_tts` | ใช้ TTS แทน | 71.2 dp | **95.2 dp** |
| `game_audio_trim_no_audio` | ไม่ใช้เสียง | 54.6 dp | **88.0 dp** (minWidth floor) + 4dp margin |
| `game_audio_trim_save` | ใช้ช่วงที่เลือก | 73.4 dp | **121.4 dp** |

**Row total (Thai) = 12 + 95.2 + 4 + 88.0 + 121.4 + 12 = ~333 dp.**

| screen | Thai row | is `game_audio_trim_save` reachable? |
|---|---|---|
| **360 dp** | 333 dp — **fits**, 27 dp slack | ✅ **yes, fully** |
| **411 dp** | 333 dp — **fits**, 78 dp slack | ✅ **yes, fully** |

**Thai is not one of the overflowing locales — it is one of the safest.** Thai runs words together and takes no inter-word spaces, so the row is **45 dp narrower than English**. The measurement that matters: **English itself is 377.8 dp and overflows a 360 dp phone by ~18 dp**, clipping ~13% off the right edge of its own primary confirm. The trim row is a **source/layout defect, not a locale defect** — it is broken in `values/` before any translation is applied, and no `values-th` edit can fix it. Two caveats for whoever owns the layout:

- **Font scale.** Thai clears 360 dp only up to fontScale ≈ 1.2. At **fontScale 1.3** (a common accessibility setting) the Thai row is **383 dp and overflows 360 dp by 23 dp**; at 1.5 it overflows 411 dp too. Padding doesn't scale; text does.
- **Small phones.** Thai overflows a 320 dp screen by ~13 dp even at fontScale 1.0.

The structural fix is in the layout, not the strings: let the two secondary text buttons shrink (`layout_weight` + `android:maxLines="1"` + `android:ellipsize="end"`), or move `btnTrimSave` to its own row. **No Thai string change is warranted for width, and shortening `game_audio_trim_save` would not help** — English, not Thai, is the binding case.

### Verdict

**FIX FIRST** — one revert.

- `misc_euphemistic`: **`คำเลี่ยง` → `คำสละสลวย`** (undo the round-1 change; it replaced the attested Thai noun for "euphemism" with an unattested coinage that can invert next to the set's taboo tags).
- Recommended in the same pass: `settings_ocr_use_manga_subtitle` (add `จึง` — the round-1 fix stopped one clause short of a meaning-inverting seam) and `anki_game_audio_cell_untrimmed` (add `การ์ด`).

Everything else round 1 changed is **correct and landed cleanly**. Spacing — the axis most likely to break under an edit round — is pristine across all 174 keys; the `ถูก` passive is unambiguous in both error banners; all 38 `misc_*` labels remain distinct. The trim row needs no Thai change: it needs a layout change, and it needs it for English first.

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
quotes; `<plurals>` categories exactly other. `./gradlew :app:processDebugResources`
is green. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | was | now | why |
|---|---|---|---|---|
| `settings_ocr_note_mlkit` | ⚠️ | "รวดเร็วแม้หน้าจอมีข้อความจำนวนมาก" | "ฉับไวแม้หน้าจอมีข้อความมาก" | The English comment forbids reusing the literal Fast tier label; the first pass reused the เร็ว root, the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

The ภาษา prefix is written **tight** against both source-language placeholders — `image_import_no_text` (ไม่พบข้อความภาษา%1$s ในรูปภาพนี้, no space) and `camera_snapshot_no_text` — matching the committed `status_no_text`, because these fill from `SourceLangId.displayName()` and come back Thai on a Thai device; a space would sever the ภาษาญี่ปุ่น compound. Spaces appear only at Latin, numeral and symbol borders (PaddleOCR (แม่นยำ), ไฟล์ PDF, <xliff:g>%d</xliff:g> บรรทัด, the → path). No ครับ/ค่ะ. No sentence-final periods, matching the file (`settings_ocr_download_failed`). เอนจิน was adopted for *engine* specifically so เครื่องมือ stays free for *tool* — the two collide inside `settings_ocr_delete_camera_import_note`, where reusing เครื่องมือ for both would have produced เครื่องมือกล้องก็ใช้เครื่องมือนี้. ภาพนิ่ง for the camera freeze-frame stays distinct from ภาพหน้าจอ (`anki_group_screenshot`). Plurals `other` only, with the ฉบับ and บรรทัด classifiers. “ ” quotes in the Xiaomi paragraph.

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

## Delta review 2026-08-04 (8 keys: one-tap card toasts, first-field guard, hide-translations toggle, waveform zoom hint)

Mechanical layer verified programmatically over the eight delta keys: all eight present in
`values-th`, none extra, none duplicated, and each sits in the same EN-relative position as its
source (no ordering drift). Every `<xliff:g>` span is byte-identical to English including `id`
and `example` (`brand_anki`/`Anki` x4, `field_name`/`%1$s`/`Key` x2); placeholder multisets are
identical (`%1$s` once in each first-field string, none elsewhere); `“ ”` counts match EN exactly
(1/1 in `anki_first_field_unmapped` and `anki_first_field_empty`, zero in the other six); no
`<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` deltas; no unescaped `'` or `"` in any text run (the
only quote characters in the file's text are the typographic pair, which needs no escaping);
`name=` untouched; the Anki brand left untranslated inside its span. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `game_audio_zoom_hint` | ⚠️ | `บีบนิ้วเพื่อแสดงช่วงเสียงให้กว้างขึ้นหรือแคบลง` | `บีบนิ้วเพื่อแสดงเสียงช่วงยาวขึ้นหรือสั้นลง` | The decision to render "more or less audio" as an extent rather than a quantity is **right** — a bare เสียงมากขึ้น/น้อยลง would have read as volume. The execution picks the wrong compound. **ช่วงเสียง** is an established Thai collocation for *vocal / pitch range* (ช่วงเสียงของนักร้อง), so "แสดงช่วงเสียงให้กว้างขึ้น" lands in the frequency domain — near the very audio-property misreading the translator was dodging. Two further wobbles: `แสดง X ให้กว้างขึ้น` predicates on how X is *rendered* (cf. แสดงกราฟให้ใหญ่ขึ้น), which reads as stretching the waveform rather than choosing how much of it is on screen; and กว้าง/แคบ is a spatial axis, while the thing being sized is a **time** window — the readout directly above it is denominated in วิ (`game_audio_trim_duration`: เลือกไว้ 2.4 วิ), so a reader arriving from that line takes ให้กว้างขึ้นหรือแคบลง as *resize my 2.4-second selection*, which is what the drag handles do, not what pinch does. The suggested form flips the noun order to the file's own attested pattern — `anki_game_audio_row_subtitle` already writes เก็บ**เสียง**เกม**ช่วง**ไม่กี่นาทีล่าสุด, i.e. [เสียง] + [ช่วง + duration] — which dissolves the ช่วงเสียง compound, moves to the ยาว/สั้น axis that matches วิ, keeps แสดง as the head verb, and introduces no new noun (the file has no waveform term; คลื่น appears nowhere in `values-th`). |
| `anki_first_field_empty` | 💬 | `…ในการ์ดนี้ว่างอยู่ <xliff…>Anki</xliff…> ใช้ฟิลด์แรก…` | `…ในการ์ดนี้ว่างอยู่ โดย <xliff…>Anki</xliff…> ใช้ฟิลด์แรก…` | Dropping the English full stops is correct for this file, but here the sentence break falls immediately **before** a Latin token, so the boundary space is indistinguishable from the space Thai must put beside Latin anyway — the two sentences fuse into one run at a glance. The file's precedent (`anki_words_helper`: …ในการ์ด Anki แตะที่คำ…) has the Latin token *ending* the first clause, where the following space still reads as a break; this is the inverted, riskier shape. A one-syllable connective restores the seam at no cost, and this string is a full alert with room to spare (84 chars vs 112 in EN). Optional — nothing is mistranslated. |

### Clean areas (delta — checked, no findings)

**The two one-tap toasts are exactly right.** `anki_added_sentence_success` / `anki_added_word_success`
reuse the sibling's frame verbatim — `anki_added_no_audio` is เพิ่มไปยัง Anki แล้ว, and these are
เพิ่ม**การ์ดประโยค**ไปยัง Anki แล้ว / เพิ่ม**การ์ดคำ**ไปยัง Anki แล้ว, same เพิ่ม…ไปยัง…แล้ว shape,
same ไปยัง as the action label `history_action_anki` (เพิ่มไปยัง Anki), with แล้ว carrying the
completed sense that distinguishes the toast from the button. The card shapes derive cleanly from the
mode chips: `anki_mode_sentence` ประโยค → การ์ดประโยค, `anki_mode_word` คำ → การ์ดคำ, head-initial and
unambiguous. Notably การ์ดคำ was **not** collapsed into การ์ดคำศัพท์, which `anki_content_flag_vocabulary`
already owns for Migaku's "Vocabulary card" flag — English keeps "Word card" and "Vocabulary card"
apart and so does Thai. Since the whole point of these strings is to surface a silently-applied mode,
naming the shape in the first two words (การ์ดประโยค / การ์ดคำ) puts the payload where a two-second
toast can deliver it.

**โน้ต for Anki's *note* is the correct call, and applied consistently.** บันทึก is genuinely
unavailable — it carries save/record 38 times across this file, including in the immediately adjacent
`anki_card_type_basic_no_mapping` (คุณกำลัง**บันทึก**คำหรือประโยค) and `anki_game_audio_row_title`
(**บันทึก**เสียงเกม); reusing it here would have produced "so Anki can identify the save". โน้ต's
competing everyday reading is the musical one, but in both strings the word sits immediately after the
Latin brand (`Anki ระบุโน้ตได้`, `Anki ใช้ฟิลด์แรกเพื่อระบุโน้ต`), which forecloses it. The two
strings agree on both the noun and the verb (ระบุโน้ต in each), so the term is introduced once and
never drifts. That the app's Thai UI otherwise says ประเภทการ์ด rather than ประเภทโน้ต is inherited
from English, which likewise says "card type" everywhere and "note" only in this pair — Thai mirrors
the source rather than inventing a distinction.

**แมป / ฟิลด์ register matches the established siblings.** แมป is already the file's verb for field
mapping (`anki_card_type_edit_mapping_row_label` แก้ไขการแมปฟิลด์, `anki_content_source_pick_title`
แมป “%1$s”, `anki_card_type_basic_no_mapping` ไม่จำเป็นต้องแมปฟิลด์), and ฟิลด์ is the noun throughout
the `anki_content_*_desc` family. `anki_first_field_unmapped` reverses the argument structure to
แมป[ค่า]ให้กับ[ฟิลด์] to track the English "Map a value to X" — correct Thai, and adding the ฟิลด์ head
noun before the quoted name is an improvement over a bare quote, applied identically in both new
strings. Quote spacing follows the file exactly: a space before “ and after ”, matching
`anki_content_frequency_stylized_desc` (สำหรับฟิลด์ “FrequenciesStylized” ใช้กับ…) and `status_idle`
(แตะ “แปล” เพื่อ…) — correct, since the enclosed field name is a user-supplied Latin token and Thai
spaces border Latin and symbols.

**Toast clamp measured, not assumed.** `anki_first_field_unmapped` fires through
`Toast.makeText(..., LENGTH_LONG)` in `AnkiSendDispatch.kt:218`, so Android 12+ clamps it to two lines.
The Thai body is 50 characters against English's 52 (the dropped full stop nets out against Thai's
longer verb phrase), so it fits wherever English fits; the free variable is the user-defined field name,
which is identical in both locales. No shortening warranted. `game_audio_zoom_hint`'s own surface was
read rather than guessed: in `anki_game_audio_panel.xml` it is a `match_parent` / `wrap_content`
TextView at 11sp with `gravity="center_horizontal"` and **no** `maxLines` or `ellipsize`, so the Thai
line wraps harmlessly and length is not a constraint there — the finding above is about sense, not width.

**History terminology reuses what is already committed.** `history_hide_translations_toggle_title`
ซ่อนคำแปล matches the app's existing hide-translations vocabulary letter for letter
(`translate_button_subtitle_hold_to_hide_translations` กดค้างเพื่อ**ซ่อนคำแปล**บนหน้าจอเกม,
`cd_toggle_translation_visibility` สลับการแสดง**คำแปล**), and คำแปล is the file-wide noun for
*translation* across `section_translation`, `anki_group_translation`, `overlay_mode_option_translation`
and eleven more. The subtitle's ข้อความที่**จับภาพ**ไว้ satisfies the hard constraint that "captured"
reuse the locale's screen-capture verb — `history_toggle_subtitle` is บันทึกประโยคที่จับภาพไว้ and
`settings_cell_history_summary_on` is รายการประโยคที่จับภาพไว้, same จับภาพ…ไว้ frame — while correctly
tracking English's own ข้อความ/ประโยค split ("captured text" here, "captured sentences" there).
แตะ**รายการ** for "Tap a row" is the right unit noun: it matches `history_delete_confirm_title`
(ลบรายการนี้หรือไม่), the one other string that names the History entry as a tappable object, and
correctly avoids บรรทัด, which this feature reserves for the *content* lines
(`history_line_count` %d บรรทัด, `history_empty_none`, `history_clear_confirm_message`) exactly as
English reserves "lines". The two-clause subtitle uses a single space as the sentence break with no
full stop, consistent with the file.

**`card_words_in_sentence` คำในประโยค** is a bare noun phrase in the same register as the file's other
section headers (`section_translation` คำแปล, `anki_group_translation` คำแปล), number-neutral as Thai
requires, and unaffected by the `.gl-section` CSS that wraps it (`text-transform:uppercase` is a no-op
for Thai; `letter-spacing:0.12em` applies between grapheme clusters, so combining vowels and tone marks
stay welded to their base consonants). It is baked into the card at send time via
`AnkiSendPipeline.kt:192`, so no runtime relocalization concern.

**Register and spacing across all eight.** Neutral-polite throughout, no ครับ/ค่ะ, no あなた-equivalent
second person forced in where Thai would elide it. No sentence-final periods anywhere, matching the
file. Spaces appear only at Latin borders (Anki, the quoted field name) and at clause boundaries; no
space was introduced inside a Thai run. None of the eight strings contains a language-name placeholder,
so the ภาษา prefix rule is not in play here.

### Verdict

**PASS with one ⚠️.** `game_audio_zoom_hint` should be reworded (the ช่วงเสียง compound reads as pitch
range); `anki_first_field_empty` has an optional 💬. The other six are clean, and the two judgement
calls the translator flagged — โน้ต over บันทึก, and rendering "more or less audio" as an extent rather
than a quantity — are both correct in principle; only the second one's wording needs work.
