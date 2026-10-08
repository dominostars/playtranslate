# Korean (values-ko) localization review

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| label_region_drag_hint | ❌ | 위쪽 또는 아래쪽 가장자리를 드래그하거나 가운데를 드래그하여 상자 전체를 이동하세요 | 위쪽 또는 아래쪽 가장자리를 드래그하세요. 상자 전체를 이동하려면 가운데를 드래그하세요 | The purpose clause 「~하여 상자 전체를 이동하세요」 scopes over the whole 「~하거나」 disjunction — the dominant parse is "drag the edges OR the middle to move the whole box," merging the three drag targets. EN scopes "move the whole box" to the middle only. This is the exact cross-language merge failure. |
| settings_header_ocr | ⚠ | 이미지를 텍스트로(OCR) | 텍스트 인식(OCR) | Clause-fragment calque of "Image-to-text" — not a natural Korean section header. The app itself already uses the standard term in status_ocr (텍스트 인식 중…); 문자 인식(OCR) also fine. |
| status_idle | ⚠ | 번역을 눌러 | "번역" 버튼을 눌러 | Unmarked button name garden-paths as the common noun ("press translation"). |
| status_hold_hint | ⚠ | 영역 또는 자동을 길게 누르세요 | "영역" 또는 "자동" 버튼을 길게 누르세요 | 자동을 길게 누르세요 reads as "long-press automatically/automatic"; button names need marking. |
| backend_cooldown_status_fmt + backend_cooldown_retry_at/_on | ⚠ | %1$s · 재시도 3:42 PM (composed) | status_fmt → `%1$s · %3$s에 %2$s`, keep 재시도 for both connectors | Current composition yields a dangling label ("재시도 3:42 PM"). Reordering the placeholders gives natural "사용 불가 · 오후 3:42에 재시도"; 에 covers both time and date, so at/on collapsing to one word is fine. |
| a11y_out_of_5_stars | ⚠ | 별 5개 중 | (별 5개 만점) | Code appends this after the number: "품질 4 별 5개 중" is garbled for TalkBack. An appended parenthetical "품질 4 (별 5개 만점)" reads naturally in the fixed slot. |
| translate_button_prefix_translate / translate_button_prefix_reload | ⚠ | 번역 / 새로 고침 | 번역: / 새로고침: | Code composes prefix + space + region label → "번역 전체 화면" reads as two stacked nouns ("translation full screen"). A trailing colon ("번역: 전체 화면") fixes the parse within the composition constraint. Also Android/Chrome UI convention is 새로고침 (no space). |
| qwen_mnn_disable_title, qwen35_2b_mnn_disable_title, gemma_e2b_mnn_disable_title, hymt_disable_title | ⚠ | …사용 중지하시겠습니까? | …사용을 중지하시겠습니까? | Object particle missing in a full -하시겠습니까 sentence ("Qwen (MNN) 사용 중지하시겠습니까?"). Putting 을 on 사용 avoids attaching a particle after the parenthetical. Same fix for all four keys. |
| bergamot_warmup_downloading_multi | ⚠ | 오프라인 모델 다운로드 중 2 중 1… | 오프라인 모델 다운로드 중(2개 중 1번째)… | "다운로드 중 %2$d 중 %1$d" stutters 중 twice in a row and is hard to parse. |
| anki_sort_field_empty | ⚠ | 중복 거부 오류가 발생합니다 | 중복으로 거부되는 오류가 발생합니다 | "중복 거부 오류" is an opaque noun-pile calque of "duplicate-rejection errors"; unpacking it ("rejected as a duplicate") restores the meaning. |
| overlay_icon_a11y_required_message | ⚠ | 플로팅 아이콘이 게임 화면 위에 그리려면 | 플로팅 아이콘을 게임 화면 위에 표시하려면 | 그리다 is transitive; "아이콘이 …위에 그리려면" has the icon drawing an unstated object. |
| enhanced_auto_translate_subtitle_off | ⚠ | 접근성 접근 권한이 필요합니다 | 접근성 권한이 필요합니다 | "접근성 접근" stutters; every other string says 접근성 권한. |
| accessibility_dialog_message, overlay_icon_a11y_required_message | ⚠ | 설정 → 접근성 → 설치된 앱 | 설정 → 접근성 → 다운로드된 앱 | KO faithfully follows EN's "Installed apps", but stock Android Korean labels that accessibility section 다운로드된 앱 — users navigating by the printed path won't find 설치된 앱. (EN has the same known drift.) |
| word_detail_common | ⚠ | 상용 | 자주 쓰임 | As a standalone badge, 상용 is a 商用/常用 homograph and in software context most readily reads "commercial." |
| anki_content_frequency / anki_content_frequency_desc | ⚠ | 빈도 별 / 등급을 별로 표시 | 빈도 별점 / 등급을 별점으로 표시 | "빈도 별" collides with the suffix -별 ("by frequency"); "별로 표시" momentarily reads as colloquial 별로 ("not great"). 별점 dodges both. |
| llm_backend_invalid_key_alert_message_fmt | ⚠ | %1$s에서 입력한 키를 거부했습니다 | 입력하신 키를 %1$s에서 거부했습니다 | First parse is "[the key entered at OpenAI]" — the relative-clause attachment is ambiguous; fronting the object resolves it. |
| settings_overlay_mode_subtitle | ⚠ | 자동 모드 또는 길게 눌러 미리 보기 중에 표시할 오버레이. | 자동 모드나 길게 누르는 동안 표시할 오버레이입니다. | "길게 눌러 미리 보기 중에" forces a verb phrase into a noun slot; hard to parse. |
| onboarding_welcome_tagline | ⚠ | 동반 앱입니다 | 컴패니언 앱입니다 | 동반 앱 is not an established Korean term (동반 evokes 동반자); first-screen copy should read native. |
| deepl_settings_about | 💬 | DeepL은(는) | DeepL은 | DeepL is fixed text in this string, not a runtime variable — the combined form is unnecessary (딥엘 → 은). Convention elsewhere attaches plain particles to fixed Latin names. |
| pack_upgrade_mandatory_message | 💬 | 지금 업데이트하거나 삭제하여 다른 언어를 선택하세요 | 지금 업데이트하거나, 해당 언어 팩을 삭제하고 다른 언어를 선택하세요 | Dropped object for "delete it" is recoverable but the 삭제하여…선택하세요 chaining slightly blurs what gets deleted. |
| crash_dialog_discard | 💬 | 삭제 | 보고서 삭제 | Identical to the generic destructive Delete label (pack_upgrade_button_delete, settings_ocr_delete_confirm). It does delete the report, so it's defensible, but scoping it removes any "deletes my data?" alarm. btn_clear (지우기) is correctly distinct — no issue there. |
| update_dialog_message | 💬 | GitHub에서 사용할 수 있습니다 | GitHub에서 받을 수 있습니다 | "Can be used on GitHub" calque; the action is downloading a release. |
| quick_tile_add_row_subtitle | 💬 | 상태 표시줄에서 PlayTranslate 전환 | 상태 표시줄에서 PlayTranslate 켜기/끄기 | Bare 전환 ("switch") leaves "switch to what?" open. |
| dialog_hotkey_setup_countdown | 💬 | 유지 1.4 (composed) | 계속 누르세요… %1$s | "유지 1.4" reads like a spec label, not a countdown instruction. |
| menu_translations | 💬 | 번역 | 번역 기록 | This menu item opens translation history; bare 번역 collides with the Translate action one menu over. |
| cd_read_original_aloud, tts_no_engine_dialog_message | 💬 | 소리내어 | 소리 내어 | Standard orthography spaces 소리 내다. |
| lang_setup_requires_64bit_msg | 💬 | 필요하지만, 이 기기는 그렇지 않습니다 | 필요하지만, 이 기기는 64비트가 아닙니다 | "그렇지 않습니다" has a fuzzy antecedent ("needs" vs "is 64-bit"). |
| hymt_legal_message | 💬 | (2) 귀하는 …사용하지 않습니다 | (2) 귀하는 …사용하지 않을 것입니다 | Clause (2) is a forward-looking undertaking ("will not use"); present tense reads as a statement of current practice. Everything else in the legal text checks out — see verdicts. |

Clean areas not padded above: plurals (all three use natural counters 개/자), the onboarding body copy, all Anki review-sheet and content-source strings (Examples correctly left unlocalized), the metered-network dialogs, the low-memory gate, and the ML Kit fallback banners are natural, consistent 합니다체.

## Particle coverage appendix

**PlayTranslate (fixed; direct plain particles; reading 플레이트랜슬레이트, vowel-final → 는/가/를/로):**
accessibility_service_description 는 ✓ · accessibility_dialog_message 는, 는 ✓ · status_accessibility_needed 를 ✓ · notif_text 로 ✓ · onboarding_welcome_title 에 ✓ · onboarding_notif_body 가 ✓ · onboarding_a11y_hint 를 ✓ · onboarding_a11y_body 는 ✓ · restricted_settings_message 의 ✓ · settings_capture_display_footer 를 ✓ · mp_overlay_permission_message 에 ✓ · a11y_required_displays_message 가 ✓ · a11y_required_hotkey_message 가 ✓ · a11y_required_enhanced_message 는 ✓ · anki_not_installed_message 는 ✓ · anki_permission_rationale_message 에 ✓ · anki_content_words_table_desc 가 ✓ · crash_dialog_title 가 ✓ · crash_dialog_message 가 ✓ · overlay_turn_off_title (%1$s)를 ✓ · overlay_hide_controls_message (%1$s)를 ✓ · anki_settings_grant_access_subtitle (%1$s)에 ✓

**Other fixed brands, direct particles:**
anki_section_description AnkiDroid로 ✓ (안키드로이드, vowel-final) · anki_send_failed_message AnkiDroid가 ×2 ✓ · anki_no_deck_selected AnkiDroid에서 ✓ · anki_models_unavailable AnkiDroid에 ✓ · anki_not_installed_message AnkiDroid에 ✓ · anki_added_no_audio / anki_added_success / anki_adding_in_progress Anki에 ✓ · anki_sort_field_empty Anki는 ✓ (안키, vowel-final) · hymt_legal_message Tencent의 / Agreement에 / §5(b)에 ✓ · anki_content_flag_vocabulary_desc Migaku의 ✓ · anki_content_flag_targeted_sentence_desc JPMN의 ✓ · legacy_engines_removed_message (…TranslateGemma)가 — attaches to host noun 번역기 ✓ · deepl_settings_about DeepL은(는) → see finding row (works, but combined form on a fixed brand)

**Variable placeholders, combined forms:**
update_dialog_message %1$s을(를) ✓ · target_pack_migration_message %2$s(으)로 ✓ · settings_ocr_delete_title %1$s을(를) ✓ · settings_ocr_delete_shared_msg %1$s은(는) ✓ · tts_language_unsupported_with_engine_message %2$s을(를) ✓ (and (%1$s)은 cleverly restructured so 은 attaches to 엔진) · tts_language_unsupported_unknown_engine_message %1$s을(를) ✓

**Variable placeholders followed by invariant particles/counters (no batchim sensitivity):**
status_no_text "%2$s"에서 ✓ · word_detail_not_found "%1$s"에 ✓ · llm_backend_invalid_key_alert_message_fmt %1$s에서, %2$s에서 ✓ (phrasing flagged separately) · llm_low_memory_message %2$s의, %3$s만 ✓ · word_anki_in_decks %1$d개 ✓ · word_detail_senses_count %d개 ✓ · word_detail_chars_count %d자 ✓ · lang_search_match_count %d개 ✓ · settings_capture_displays_count %1$d개 ✓ · tr_service_status_quota_fmt %2$s자 ✓ · all *_status_downloading "%2$s 중 %1$s" (noun 중) ✓

**Missing-particle sites:** qwen_mnn_disable_title, qwen35_2b_mnn_disable_title, gemma_e2b_mnn_disable_title, hymt_disable_title → see findings row (사용을 중지).

## Verdicts

- **Register consistency:** clean — 합니다체 throughout, noun-form buttons, zero 해요체/반말, 당신 absent (귀하 only in legal, correctly), 내 언어 confirmed.
- **Terminology consistency:** good — 설정/번역/다운로드/삭제/접근성/덱/카드 유형/언어 팩/단축키/텍스트 음성 변환/화면 캡처/종량제 네트워크 all uniform; one stutter (접근성 접근 권한) and one fragment-vs-standard-term gap (settings_header_ocr).
- **Android-settings wording:** "다른 앱 위에 표시" and "빠른 설정 타일" match stock Android Korean exactly; accessibility nav path says 설치된 앱 where stock says 다운로드된 앱 (inherited EN drift — flagged).
- **Particles:** very strong — every PlayTranslate direct particle is correct for the vowel-final reading; combined forms used consistently on variables; only the four disable-dialog titles drop a particle, and DeepL gets an unneeded combined form.
- **Plurals/counters:** clean — `other` only, natural counters (개/자) everywhere.
- **Truncation risk:** none — bottom bar 자동/일시정지/설정/영역 and the two-line 캡처\n영역 are all comfortably short.
- **Legal text:** faithful and conservative — §5(b) kept, EU/UK/South Korea enumeration kept, negation in clause (1) correctly scopes both 거주 and 위치, in-text 동의 matches the 동의 — Hunyuan 사용 button's leading word; only a tense nuance in clause (2) (💬).
- **Overall:** fix-then-ship — one real scoping error (label_region_drag_hint) and a cluster of composed-string and calque awkwardnesses; no build-breaking issues found.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR set — both `<plurals>` collapsed to Korean's single `other`; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| audio_error_loading | 💬 | 불러올 수 없습니다 | 불러올 수 없음 | Terse status cell, parallel to its own siblings audio_no_results (결과 없음) and audio_loading (불러오는 중…) and to the dictionary-status cell family, where dictionary_status_error uses the noun-form 검색할 수 없음 for the exact same "couldn't X" slot. EN is the equally-terse "Couldn't load". The full 합니다체 sentence is heavier than the cell register; noun-form 없음 matches better. (Defensible as-is — word_detail_more_examples_error is also a full sentence — hence nit only.) |

## Clean areas (delta)
**Particle / counter handling at every placeholder — the Korean-critical axis — is clean, and notably it never attaches a bare particle to a raw runtime variable:**
- `yomitan_importing_progress` — `%2$d개 중 %1$d개 가져오는 중…`: the 개 counter sits between the number and any grammar, so no batchim-sensitive particle ever lands on `%1$d`/`%2$d`. The two `<xliff:g>` spans were reordered (total-first, 개 중, current) — placeholders are positional so this is legitimate, and it matches the established `…%2$s 중 %1$s` download-progress idiom (qwen/hymt/install rows) exactly.
- `yomitan_import_summary_count` (other) — `사전 %2$d개 중 %1$d개를 가져왔습니다.`: the object particle 를 attaches to the counter 개, never to the variable; reads as a natural full 합니다체 sentence. The single `other` form is correct for Korean and the counter makes it read naturally at any count. Mirrors the file's own counter idiom and the committed `bergamot_warmup_downloading_multi` fix (…%2$d개 중 %1$d번째).
- `yomitan_import_summary_more` (other) — `+%1$d개 더`: 개 counter again; clean.
- The four `%1$s` file-name summary lines (`_duplicates` 이미 가져옴:, `_invalid` 읽을 수 없음:, `_no_space` 저장공간 부족:, `_failed` 실패:) all use the `라벨: %1$s` colon-list form, leaving the comma-joined names sentence-final with no particle on the variable — the safe pattern, and consistent label phrasing across the four.
- `llm_backend_base_url_invalid` — `https://를 사용하세요. http://는 …`: particles attach to fixed literal tokens (not variables); by pronunciation HTTPS → …에스 (vowel) → 를 ✓, HTTP → …피 (vowel) → 는 ✓. The EN em-dash was rendered as a sentence break (…사용하세요. http://는…), which reads more naturally in Korean than a dash; the conditional "…에만 허용됩니다" preserves the "only allowed for" force.

**Terminology — reused, not reinvented:** 고저 악센트 (pitch accent) matches `yomitan_category_pitch_accent` and `yomitan_page_description`; 빈도 (frequency), 사전 (dictionary), 가져오기/가져오는 중/가져왔습니다 (import), 다운로드, 저장공간 (no internal space — matches offline_backend_disk_label and the qwen status rows), 자동 업데이트, 텍스트 음성 변환 (TTS — matches audio_source_tts_name itself and anki audio descs), 오디오 (audio) all uniform with the file. 고급 (Advanced header) and 사용자 지정 URL (Custom URL) are the standard Android/MS Korean renderings; 사용자 지정 is the conventional "Custom" and reads fine next to the neighbouring 직접 입력… custom-model affordance. Brand/field names left as-is: Lapis/JPMN, the quoted Anki field names ("PitchPosition", "PAOverride", "Frequency", "FrequenciesStylized", "FreqSort", "FrequencySort"), and Wikimedia Commons all untranslated.

**The `Example:` / quoted-field-name / glyph rule is honored:** `anki_content_pitch_position_desc` keeps `예: 0,2`; `anki_content_frequency_values_desc` keeps the raw `★` glyph (matching EN's `★`, not spelled out as 별/별점) — correct, since here ★ is output shape, whereas the sibling `anki_content_frequency_desc` legitimately uses 별점 because EN there said "★ rating" as prose. No field name or sample was localized.

**Register — consistent with the file's own mixed-but-bounded convention for this family:** the four `anki_content_*_desc` bodies use full 합니다체 (…사용합니다, …표시합니다) and polite imperative (…사용하세요), which is exactly the established split in the existing `anki_content_*_desc` block (definition_desc/picture_desc/word_audio_desc are 합니다체 sentences; flag_*_desc are …사용하세요). Labels are noun phrases (고저 악센트 위치, 빈도 목록, 빈도 목록(JPMN 스타일), 빈도 정렬 번호, 고급, 자동 업데이트, 오디오) or polite imperative-free short forms — all on-register. `yomitan_auto_update_subtitle` (…다운로드하고 설치합니다) and `yomitan_import_summary_title`/`_title_none` (가져오기 완료 / 가져오지 못함) match neighbouring 합니다체 bodies and noun-form titles. No 해요체/반말, no 당신/내 언어 contexts in this batch.

**Truncation:** the short labels (고급, 오디오, 결과 없음, 자동 업데이트, 텍스트 음성 변환) are all comfortably short for their header/cell slots; none risk clipping. 사용자 지정 URL is a normal row label width.

**Plurals:** both `<plurals>` correctly collapse to the single Korean `other`; each reads naturally because a counter (개) carries the quantity, so there is no English "1 dictionary / N dictionaries" singular/plural artifact bleeding through.

**Net:** ship-ready. Zero ❌/⚠️ in the 29 keys; one 💬 cell-register nit (audio_error_loading). The particle-sensitive sites — the whole reason Korean is high-risk — are handled correctly via counters and colon-lists, never a bare particle on a variable.

---

# Delta review — 2026-07-14 sync (174 keys)

Scope: the 170 newly translated + 4 changed-English keys (History screen, Advanced
LLM prompt editor, in-app updater, game-audio trim, single-app capture, OCR picker,
the 38 `misc_*` dictionary tags). Independent reviewer; the rest of the file is in
scope only where a delta string **drifts from a committed one**.

**Mechanical layer verified programmatically over all 174 keys:** every `name=`
present in both files; `%n$s`/`%d` placeholder sets identical; all `<xliff:g>` spans
byte-identical to EN (`id`, `example`, inner text); `\n` preserved
(`floating_menu_capture_screen`); no raw `'`; `<plurals>` collapses to Korean's single
`other` (**`settings_yomitan_count_summary` has `other` only — no invented `one`**);
the bare Latin keyword tokens `{text}` `{source}` `{source_code}` `{target}`
`{target_code}` `{context}` `{N}` `{strings}` survive verbatim in running prose; all 38
`misc_*` labels are mutually distinct (required — `MiscLabels.renderMisc` calls
`.distinct()` on the *localized* strings, so two codes sharing one label would silently
drop a tag). The only diff-flag, `update_dialog_download`, is EN's `&amp;` rendered as
Korean 및 — correct, not an escape bug. **No 🛑 build-breaking issues.**

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| floating_menu_capture_screen | ❌ | 캡처\n화면 | 화면\n캡처 | **Noun order inverted.** Korean noun compounds are head-final, so 캡처 화면 = "the captured screen / the capture screen" (a screenshot, or the capture UI) — not the button's action. Per the EN comment this button performs "a single capture-and-translate of the whole screen": verb + object, which in Korean is 화면 캡처. That is also the file's own canonical term, 8× committed (`status_capturing` 화면 캡처 중…, `notif_channel_name` 화면 캡처, `error_screen_capture_denied`, `error_live_mode_unsupported_backend`, `region_overlay_drag_instruction`, `capture_lifecycle_on_title` 화면 캡처 허용됨, `capture_lifecycle_off_title`, and this very delta's `error_capture_blocked_secure` …화면 캡처를 차단합니다). The delta string inverts the app's own term. Same 2+2 two-line shape → no truncation change. |
| misc_onomatopoeia | ⚠️ | 의성·의태어 | 상징어 | **Collides with the tag separator.** `MiscLabels.renderMiscText` joins the misc tags with `" · "` (5 live sites: `WordDetailBottomSheet` ×2, `WordAnkiReviewSheet` ×2, `WordDefinitionsView`). This is the only one of the 38 labels containing a middle dot, so a word tagged onomatopoeia + slang renders **의성·의태어 · 속어** — a phantom third tag. 상징어 is the standard Korean umbrella term covering both 의성어 and 의태어, one word, no separator, and matches the Sino-Korean register of the rest of the set. Conservative alternative if both senses must stay explicit: 의성/의태어. |
| misc_familiar | ⚠️ | 친밀체 | 친밀 표현 | Coinage, and the only label in the set taking **-체** — a suffix that in Korean names the six *speech levels* (합니다체/해요체/해라체…), so it reads as a grammatical claim rather than a register label. Its own cluster siblings deliberately drop it (`misc_colloquial` 구어, `misc_literary` 문어 — not 구어체/문어체). 친밀 표현 parallels `misc_idiomatic` 관용 표현 and `misc_sensitive` 민감한 표현, already in this set. Cluster stays fully distinct: 구어 · 비격식 · 친밀 표현 · 속어. |
| error_capture_blocked_secure | ⚠️ | …— 이 앱은 화면 캡처를 차단합니다. | …— 캡처 중인 앱이 화면 캡처를 차단합니다. | "이 앱" is ambiguous *in Korean specifically*: the message renders inside **PlayTranslate's own** in-app panel, so "this app" most readily attaches to the app the user is looking at — i.e. ours, which is the app doing the capturing, not blocking it. Its own delta sibling `error_single_app_not_fullscreen` already says 캡처 중인 앱 for exactly this referent; reusing it costs nothing and removes the misparse. (EN is equally loose, but Korean pays for it more here.) |
| llm_prompt_discard_title + llm_prompt_discard_confirm | 💬 | 변경사항을 버리시겠습니까? / 버리기 | 저장하지 않고 나가시겠습니까? / 저장 안 함 | 버리다 for "discard" does appear in Korean UI (draft-discard dialogs), so this is defensible. But the EN comment says the dialog fires "when **leaving** the prompt editor with unsaved edits", and the idiomatic Korean for that dialog is 저장하지 않고 나가시겠습니까? / [저장 안 함]. Note the obvious alternative 취소 is unavailable (it is the Cancel button beside it) and 삭제 is the app's Delete — 저장 안 함 is the one clean non-colliding choice. |
| misc_endearing | 💬 | 애칭 | 애정 표현 | 애칭 means specifically *pet name / nickname*; the tag marks affectionate **usage**, which is broader than names. Short and covers the common case, so low priority. |
| update_progress_verifying + update_error_verification | 💬 | 확인 중… / 확인할 수 없습니다 | 검증 중… / 검증할 수 없습니다 | What is being verified is a checksum + signing certificate; Korean's precise verb is 검증. 확인 is also the file's verb for "check usage" (`tr_service_status_check_failed` 사용량을 확인할 수 없음), so the two senses currently share one word. Internally consistent as-is → optional. |
| cd_change_source_language + cd_change_target_language | 💬 | 원본 언어 변경 / 대상 언어 변경 | (게임 언어 변경 / 내 언어 변경) | Faithful to EN, and 대상 언어 has committed precedent (`anki_content_sentence_translation_desc`). But these are TalkBack labels: the user hears 원본 언어, opens the picker, and it says **게임 언어** (`lang_translate_from` / `lang_translate_to` = 게임 언어 / 내 언어). EN carries the identical drift, so **declining is entirely reasonable** — raised only so the choice is deliberate rather than accidental. |
| update_error_install_launch | 💬 | 시스템 설치 프로그램 | 패키지 설치 프로그램 | Android Korean names this component 패키지 설치 프로그램. EN says "the system installer", so the current text is faithful and understandable; nit only. |

## Clean areas (delta)

**Particles — the reason Korean is the highest-risk locale — are clean at every one of
the 17 placeholder sites.** Not one bare batchim-sensitive particle lands on a runtime
variable. Full census:

- **Combined form on a variable (the mandated pattern):** `tr_service_remove_title_fmt`
  `%1$s을(를) 제거하시겠습니까?` — works for both "OpenAI" (vowel-final) and "DeepL"
  (ㄹ-final). Matches the committed `settings_ocr_delete_title` exactly. It is the only
  site that needs a combined form, and it has one.
- **Head noun carries the particle (the best fix):** `llm_prompt_advisory_foreign_token`
  → `%1$s 키워드는 …` — 는 attaches to 키워드, never to the substituted token, and 키워드
  matches `llm_prompt_keywords_header`. `settings_ocr_disable_manga_msg` →
  `다운로드한 %1$s 모델을 …` (particle on 모델; the size sits attributively, mirroring the
  committed `qwen_mnn_disable_message` `%1$s 모델이 설치되어 있습니다`).
- **Counter absorbs the quantity:** `game_audio_trim_duration` `%1$s초 … %2$s초`;
  `settings_yomitan_count_summary` `사전 %d개 가져옴`.
- **No particle at all (deliberate, and the trap correctly dodged):** the three
  reading-hint hotkey strings (`hotkey_show_hint_title` `길게 눌러 %1$s 표시`,
  `hotkey_auto_hint_title`, `hotkey_auto_hint_dialog_title`) — `%1$s` is a *localized*
  guide name (후리가나 vowel-final, 병음 consonant-final), so any bare particle would have
  been wrong for one of them; none was attached. Likewise `ocr_source_label` `%1$s 인식`,
  `floating_menu_panel_open_app` `%1$s 열기`, `tr_service_key_tail_fmt`,
  `tr_service_status_usage_today_fmt`, `update_dialog_size_note`, `update_error_no_space`.
- **Fixed names, direct particles, correct for the Korean reading:** `PlayTranslate가`
  (플레이트랜슬레이트, vowel-final) ×2 · `Android가` (안드로이드, vowel-final) ·
  `GitHub에서` ×3, `Anki에` (invariant) · `업데이트가 아닙니다` (particle on 업데이트, not on
  the brand).
- **Literal keyword tokens, pronunciation-based particles — all four correct:**
  `{N}이` (엔 → ㄴ batchim → 이) · `{source_code}가` / `{target_code}가` (…코드 → 드,
  open syllable → 가) · `{strings}를` (…스) · `{text}를` (…트). This follows the
  convention the committed `llm_backend_base_url_invalid` (`https://를`, `http://는`)
  already established. `llm_backend_base_url_custom_hint` `URL을` is right too (유아르**엘**
  → ㄹ batchim → 을).
- **Checked and deliberately NOT flagged:** `ocr_picker_message` `OCR은`. Under the
  국립국어원 letter name 아르 (open syllable) this would want 는; under the dominant
  colloquial reading 알 (cf. R&D → 알앤디) it wants 은. Real Korean tech prose
  overwhelmingly writes OCR을/OCR이/OCR은, and there is no committed precedent either way
  (this and `URL을` are the file's first two). **Leave it — a round-2 "fix" to OCR는 would
  be a regression.**

**The 38 `misc_*` chips — the four clusters are all internally distinguishable, and the
headline risk passed:**

- **Honorifics (the trio the brief called out): `misc_honorific` 존경어 / `misc_humble`
  겸양어 / `misc_polite` 정중어.** These are exactly the standard Korean terms for
  sonkeigo (尊敬語) / kenjougo (謙讓語) / teineigo (丁寧語) — the precise native
  lexicographic set, not collapsed, and each distinct from `misc_formal` 격식 /
  `misc_informal` 비격식. Nothing to fix.
- **Offensiveness:** 비하 · 모욕 · 비속어 · 멸칭 — four distinct words. (멸칭 for *slur* is
  exactly right. 모욕 leans "an insult (the act)" over a register label, but it is
  unambiguous beside its three siblings — acceptable.)
- **Obsolescence:** 고어 · 폐어 · 구식 · 역사 용어 — distinct; 고어/폐어 are the standard
  dictionary labels. 구식 is the least dictionary-like of the four but Korean has no
  established "dated" tier separate from 옛말, and it is clearly distinct — acceptable.
- **Informality:** 구어 · 비격식 · 친밀체(→see finding) · 속어. Note the deliberate
  oppositions the translator built: 구어/문어 and 격식/비격식. 속어 vs 비속어 differ by one
  character but are the standard Korean pair for slang/vulgar and sit in different
  clusters — collapsing either would be worse.
- `misc_kana_only` 가나 전용 / `misc_kanji_only` 한자 전용 — correct: Korean does not
  transliterate 漢字 as *간지*; 한자 **is** the loanword, and 전용 is the canonical
  "written only in X" suffix (cf. 한글 전용). Matches the committed `yomitan_category_kanji`
  한자.
- `misc_yojijukugo` **사자성어** — exactly the term the glossary specifies for ko, not
  romanized.
- `misc_rare` **드물게 쓰임** is the only non-noun label, and that is deliberate: it is the
  exact antonym of the committed `word_detail_common` **자주 쓰임**, which renders as a pill
  on the same word-detail surface. Good catch by the translator, not drift.
- **Register/brevity vs the committed `pos_*` tags:** pos_* are 2–4-char Sino-Korean nouns
  (명사/동사/형용사/분류사…); the misc_* set is 2–4 chars for 29 of 38, with the longer ones
  (인터넷 속어, 역사 용어, 민감한 표현, 드물게 쓰임) matching EN entries that are themselves
  long. Since the tags render as a `" · "`-joined **text run**, not individual pills, and
  Korean is far more compact than English here ("Colloquial · Vulgar · Slang · Male term"
  → "구어 · 비속어 · 속어 · 남성어"), **truncation risk is strictly lower than EN's.** No
  finding.

**Terminology — reused from the committed file, not reinvented.** Every glossary term was
grepped against the existing locale before judging: **Provider** → 제공업체 (already
committed in `tr_service_order_footer` 각 제공업체의 개인정보처리방침) · **Translation
service** → 번역 서비스, matching the committed page title `settings_cell_translation_services`,
and 온라인 번역 서비스 추가 matching `settings_header_online_translations` 온라인 번역 ·
**prompt** → 프롬프트 as the one noun across every `llm_prompt_*` **title**, with 요청 only in
the two *subtitles*, which is where EN itself says "The request" (faithful, not drift) ·
**keyword** → 키워드, one word, header and advisory · **History** → 기록 (`settings_cell_history`,
`history_screen_title`, `history_toggle_title` 텍스트 기록 유지) · **Remove vs Delete** kept
apart exactly as EN does: services are 제거 (`tr_service_remove_confirm`/`_delete_cd`,
and `tr_service_remove_message` correctly uses **both** — 서비스를 제거하고 … API 키를 삭제합니다),
history entries and models are 삭제 · **Clear** → 기록 전체 삭제, which is what Korean Android/Chrome
actually say for clearing history, and 전체 carries the all-vs-one distinction against
`history_action_delete` 삭제 / `history_delete_confirm_title` 이 항목 · **Trim** → 자르기, its
selection consistently 선택 구간 (재생/사용) · **Game audio** → 게임 오디오, reads as a noun
phrase both as the pill and as the section header · **LLM** kept as the initialism ·
**metered** → 종량제 네트워크, the parameters-doc term, exactly · **Captured** → 캡처, the
app's established verb, in `history_toggle_subtitle` / `settings_cell_history_summary_*` ·
**Custom** → 사용자 지정, matching the committed 사용자 지정 URL · **on-device** → 온디바이스,
which EN also distinguishes from "offline" (오프라인, committed) · **TTS** → the bare
initialism in `game_audio_trim_use_tts`, which is what EN does there too and what the
committed `tts_no_engine_get_google` / `_open_settings` already do, while the source name
stays 텍스트 음성 변환.

**`ocr_source_label` mirrors its sibling's structure, as the glossary requires:** committed
`translation_source_label` = `%1$s 번역` → delta `ocr_source_label` = `%1$s 인식`. Same shape,
no particle, and 인식 is the app's own OCR verb (`status_ocr` 텍스트 인식 중…).

**Android wording — verified against AOSP source, not from memory:**

- `stream_kind_share_one_app` **앱 하나 공유** and `stream_kind_share_entire_screen`
  **전체 화면 공유** are **byte-identical to AOSP SystemUI `values-ko`**
  (`screen_share_permission_dialog_option_single_app` / `_entire_screen`). This is the
  deliberate decision working exactly as intended — the buttons quote the system consent
  dialog the user just tapped. Positively confirmed, not merely left alone.
- `update_unknown_sources_message`: the screen the intent opens shows, in Korean,
  the switch **이 소스에서 가져온 앱 설치 허용** (AOSP Settings `external_source_switch_title`).
  Our copy says "…앱 업데이트를 **설치**하도록 **허용**하세요" — both operative words present,
  so the toggle is findable. EN paraphrases here too; KO matching EN is correct.

**Register:** uniform 합니다체 in bodies; noun / ~하기 / ~하세요 for buttons and titles; no
해요체, no 반말, no 당신. `llm_prompt_invalid_title` **이 프롬프트를 저장할 수 없음** is *not* a
register break — the noun-form 「…할 수 없음」 is the file's established pattern for
impossibility/failure dialog titles (`anki_send_failed_title` 카드를 추가할 수 없음,
`llm_backend_invalid_key_alert_title` API 키를 확인할 수 없음), and it correctly contrasts with
the *bypassable* advisory dialog's imperative title `llm_prompt_warning_title` 이 프롬프트를
확인하세요. `settings_ocr_disable_manga_title` **MangaOCR 사용을 중지하시겠습니까?** matches the
committed 사용을 중지 pattern (qwen/qwen35/gemma/hymt disable titles) and negates its own
toggle label `settings_ocr_use_manga_title` MangaOCR 사용 — coherent.

**띄어쓰기:** clean throughout. Space between a Latin run and the following Korean word
(MangaOCR 사용, 고급 LLM 설정, 잘못된 API 키, 백엔드 URL, 대신 TTS 사용); particle glued directly
to the Latin/token with no space (OCR은, URL을, Anki에, GitHub에서, PlayTranslate가, {text}를);
unit glued to the numeral (2.4초, 3개); no space before an opening parenthesis, consistent
with the committed file (계정 필요(무료 요금제 있음), …부족합니다(230 MB 필요), 최근 문장(문맥 사용 시),
번역할 구문(JSON 배열) — cf. committed 게임 언어(Japanese), 현재 활성 엔진(Google TTS)은).

**Plurals:** `settings_yomitan_count_summary` uses **`other` only** — correct for Korean, no
invented `one`. It reads naturally at every count because the counter 개 carries the quantity
(사전 1개 가져옴 / 사전 3개 가져옴), so EN's singular/plural split leaves no artifact.

**Truncation:** `service_llm_badge` LLM (shortest possible) · `probe_initializing` 초기화 중…
(5 chars vs EN's 13) · `floating_menu_capture_screen` 2+2 chars per line (unchanged by the
suggested fix) · the misc_* run is shorter than EN's. No truncation risk anywhere in the delta.

**Deliberate decisions honored, not flagged:** `llm_status_low_memory_badge` left untouched
(its 줄표 is native punctuation) · `llm_prompt_kw_source_desc` / `_target_desc` keep
**Japanese** / **English** in Latin, because those are the literal runtime expansions of
`{source}` / `{target}` · the `stream_kind_share_*` AOSP wording (verified above).

## Net

**One ❌ to fix before ship — `floating_menu_capture_screen` (캡처\n화면 → 화면\n캡처), a
head-final noun-order inversion that contradicts the app's own 8×-committed term 화면 캡처.**
Three ⚠️: the `misc_onomatopoeia` middle dot colliding with the `" · "` tag separator, the
coined `친밀체`, and the ambiguous 이 앱 in `error_capture_blocked_secure`. Five 💬.

The two axes that make Korean the highest-risk locale both came back clean: **every one of
the 17 placeholder sites is batchim-safe** (combined form where required, head noun or
counter everywhere else, and the reading-hint trap correctly dodged with no particle at
all), and **the four `misc_*` clusters are internally distinguishable**, with the honorific
trio using the precise native terms 존경어 / 겸양어 / 정중어.

### Same bug, outside the delta (FYI, not filed)

`floating_menu_btn_capture_region` = **캡처\n영역** (committed) carries the identical
inversion, and worse: it is byte-identical to `menu_capture_region` = 캡처 영역, which is a
*noun* there ("the capture region") and correctly so. The button should be **영역\n캡처**
— which fixes the verb reading and disambiguates the two at once. It sits in the same
floating-menu slot as the delta key above, so fixing only one leaves the pair inconsistent.

---

## Delta review round 2 — 2026-07-14

Fresh independent reviewer; wrote none of round 1 and reviewed none of it. Every one
of the 174 keys re-derived from scratch against EN, with the eight round-1 fixes
re-litigated on their merits and traced into the code that renders them.

**Mechanical layer re-verified independently (not taken from round 1):** placeholder
sets identical EN↔KO at all 174 keys (the lone diff is `settings_yomitan_count_summary`,
where Korean correctly drops EN's `one` item — CLDR-correct, not a gap); every
`<xliff:g>` span byte-identical in `id`/`example`/inner text; `\n` preserved in
`floating_menu_capture_screen`; zero unescaped `'`, zero raw `&`; all eight literal
`{token}` keywords survive verbatim; **all 38 `misc_*` labels mutually distinct**
(required — `MiscLabels.renderMisc` calls `.distinct()` on the *localized* strings) and
**none of the 38 contains a `·`**, so the `" · "` join in `renderMiscText` can no longer
manufacture a phantom tag. **No 🛑.**

### Verdict on the eight round-1 fixes

| key | round-1 fix | round-2 verdict |
|---|---|---|
| `floating_menu_capture_screen` | 캡처\n화면 → 화면\n캡처 | **LANDED.** Head-final order is right, and it now matches the app's own 8×-committed 화면 캡처. Truncation re-checked *in the layout code*, not by eye: `FloatingIconMenu` gives the label `maxLines = 2` inside a 78dp primary with 6dp side padding (66dp text column) and `fitLabel()` shrinks 11sp→8.5sp only if a run overflows. Two 2-char lines at 11sp never come close. Also confirmed `floating_menu_capture_screen` and `floating_menu_btn_capture_region` occupy **one** button slot (`updateCaptureButton()` swaps them on `activeRegion.isFullScreen`), so they never render side by side — round 1's out-of-delta FYI on 캡처\n영역 stands, but there is no simultaneous inconsistency. `contentDescription` is set from the label, so TalkBack reads 화면 캡처. ✓ |
| `misc_onomatopoeia` | 의성·의태어 → 상징어 | **LANDED.** 상징어(象徵語) is 표준국어대사전's umbrella term, explicitly defined as covering 의성어 + 의태어. One word, no `·`, distinct from all 37 siblings, and shorter than EN. ✓ |
| `misc_familiar` | 친밀체 → 친밀 표현 | **LANDED**, with a side effect — see 💬 below. Dropping -체 was right (it names the six speech levels). |
| `misc_endearing` | 애칭 → 애정 표현 | **LANDED**, same side effect. 애칭 was genuinely too narrow (pet *name*). |
| `error_capture_blocked_secure` | 이 앱 → 캡처 중인 앱 | **DID NOT LAND — see ⚠️ below.** The diagnosis was right; the replacement is ambiguous in the same direction. |
| `llm_prompt_discard_title` / `_confirm` | → 저장하지 않고 나가시겠습니까? / 저장 안 함 | **LANDED.** Traced to `LlmPromptEditorActivity.confirmDiscardOrFinish()` — it fires on leaving with unsaved edits and `finish()`es on confirm, so 나가시겠습니까 is literally what happens. Buttons resolve to **[저장 안 함]** (ptDanger) / **[취소]** (`btn_cancel`); 저장 안 함 collides with nothing (삭제/취소/확인/지우기 all checked). ✓ |
| `update_error_install_launch` | 시스템 설치 프로그램 → 패키지 설치 프로그램 | **LANDED.** That is AOSP PackageInstaller's own Korean `app_name`, so the component the user lands on is the one we named. ✓ |

### Findings (delta, round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| error_single_app_not_fullscreen | ⚠️ | …— **캡처 중인 앱이** 화면을 가득 채우지 않습니다. 앱이 다시 전체 화면이 되면 재개됩니다. | …— **캡처 대상 앱이** 화면을 가득 채우지 않습니다. 앱이 다시 전체 화면이 되면 재개됩니다. | **`N-중인 X` assigns X the agent role of N whenever X *can* perform N** (통화 중인 사람, 공부 중인 학생); the patient reading only wins when it can't (공사 중인 도로, 다운로드 중인 파일). An **앱** can capture — and in *this* app it is the thing that captures. Worse, the committed `status_capturing` = **화면 캡처 중…** ("PlayTranslate is capturing") renders in **the very same TextView**: `emitError()` → `PanelState.Error` → `TranslationResultFragment` wraps it in `status_error` = 오류: %1$s. So one surface uses 캡처 중 for PlayTranslate one moment and for its opposite the next. Here the wrong reading is **not** self-defeating — "the app that is capturing isn't filling the screen" is a perfectly sensible false statement about our floating overlay, and the follow-up 「앱이 다시 전체 화면이 되면」 then points the user at the wrong app. 캡처 대상 앱 (or the explicit passive 캡처되는 앱) is unambiguous. EN's "the captured app" is a passive participle and carries no such ambiguity. |
| error_capture_blocked_secure | ⚠️ | 아무것도 읽을 수 없습니다 — **캡처 중인 앱이** 화면 캡처를 차단합니다. | 아무것도 읽을 수 없습니다 — **캡처 대상 앱이** 화면 캡처를 차단합니다. | **Round 1's fix did not land.** It correctly killed 이 앱 (which, on PlayTranslate's own panel, attaches to PlayTranslate) — but it replaced it by copying 캡처 중인 앱 from the sibling above, and that phrasing was never itself examined. Same agent/patient ambiguity, same surface, plus a 캡처…캡처 stutter. This one *does* self-correct (an app that is capturing cannot also block capture, so the reader flips), which is why it is ⚠️ and not worse — but the same two-word change fixes both strings and costs nothing. |
| misc_familiar + misc_endearing | 💬 | 친밀 표현 / 애정 표현 | 허물없는 말 / 애정 표현 (or 친밀 표현 / 다정한 표현) | **Side effect of round 1 editing both.** Before, the two labels named different *kinds* of thing (친밀체 = a speech style; 애칭 = a name). Now both are "___ 표현" separated only by 친밀 vs 애정 — near-synonyms in Korean, so a user reading the chip run cannot tell what distinguishes them. Not a bug: the strings are distinct (verified — no `.distinct()` collapse), the two codes essentially never co-occur on one entry, and JMdict's own `fam`/`end` are equally blurry in English. Worth noting only that the set now carries **four** `___ 표현` labels (관용/민감한/친밀/애정), which drains the suffix of contrast. |
| misc_sarcastic | 💬 | 반어 | 비꼼 | 반어(反語) is *irony/antiphrasis* — the rhetorical figure taught as 반어법. "Sarcastic" is the mocking *tone*: 비꼼 / 빈정거림. Sitting between 비유 and 완곡어, 반어 will be read as a device, not a usage register. 비꼼 is 2 chars, distinct from all 37 siblings, and adds no fifth 표현. Defensible as-is (반어적 does get used for sarcastic usage), so nit only. Not a round-1 item — this is a fresh look. |

### Clean areas (round 2) — independently re-derived, not inherited

**Particles after `<xliff:g>` spans, read with the real runtime values.** All 17 sites, and
the one that needs a combined form has one:
- `tr_service_remove_title_fmt` `%1$s을(를) 제거하시겠습니까?` — the **only** site where a
  batchim-sensitive particle touches a runtime variable, and the service list spans both
  classes: **OpenAI** (오픈에이아이, vowel-final → 를) and **DeepL** (딥엘, ㄹ-final → 을).
  The combined form is mandatory here and present. ✓
- The reading-hint trap is dodged three times over: `hotkey_show_hint_title` 길게 눌러 %1$s **표시**,
  `hotkey_auto_hint_title` 탭하여 자동 %1$s **시작/중지**, `hotkey_auto_hint_dialog_title` 자동 %1$s —
  `%1$s` is the *localized* guide name (**후리가나** vowel-final vs **병음** ㅇ-final, resolved in
  `HotkeysSettingsActivity.render()` from `HintTextKind`), so any bare particle would be wrong for
  one of them. None is attached. ✓
- Head noun carries the particle: `llm_prompt_advisory_foreign_token` `%1$s 키워드는…`;
  `settings_ocr_disable_manga_msg` `다운로드한 %1$s 모델을…`. Counter absorbs the quantity:
  `game_audio_trim_duration` `%1$s초 … %2$s초`, `settings_yomitan_count_summary` `사전 %d개`.
  No particle at all where none is needed: `ocr_source_label`, `floating_menu_panel_open_app`,
  `tr_service_key_tail_fmt`, `tr_service_status_usage_today_fmt` (…%1$s 토큰), `update_dialog_size_note`,
  `update_error_no_space`.
- Literal keyword tokens, particles by Korean pronunciation, all four right: `{N}`**이** (엔 → ㄴ),
  `{source_code}`/`{target_code}`**가** (…코드, open), `{strings}`**를** (…스), `{text}`**를** (…트).
  `URL을` (유아르엘 → ㄹ) ✓. `OCR은` — **checked and deliberately left** per the settled decision.

**`llm_prompt_advisory_foreign_token` is more precise than EN, not less.** Read
`LlmPromptTemplates.validate()`: a ForeignToken is `allTokens - available` — a **recognized**
keyword that *this* prompt kind never fills (e.g. `{strings}` typed into the translation
prompt). EN's bare "%1$s isn't filled in by this prompt" leaves that unstated; KO's
「%1$s **키워드**는…」 names it correctly, and 키워드 matches `llm_prompt_keywords_header`. ✓

**The 38 `misc_*` chips — every cluster still separable after the edits.**
Honorifics 존경어/겸양어/정중어 are the exact native sonkeigo/kenjougo/teineigo set and stay clear
of 격식/비격식. Offensiveness 비하·모욕·비속어·멸칭: four distinct words. Obsolescence
고어·폐어·구식·역사 용어: distinct. Informality 구어·비격식·친밀 표현·속어: distinct, and the
translator's deliberate 구어/문어 and 격식/비격식 oppositions survive. 속어 vs 비속어 differ by one
character but are the standard Korean pair and sit in different clusters. `misc_rare` **드물게 쓰임**
is the only verb-form label and that is correct: I verified in the committed file that
`word_detail_common` **is** 자주 쓰임 (not the older 상용), so the two render as a true antonym pair
on the same word-detail surface.

**Round 1's declined 💬s re-examined — the declines were right.** `update_progress_verifying`
확인 중… is **byte-identical to the committed `settings_ocr_verifying`** 확인 중…, exactly as EN's
two "Verifying…" are; switching it to 검증 중… would have broken that parallel to fix nothing.
`cd_change_source_language`/`_target_language` carry EN's own picker-vs-label drift and matching EN
is the right call.

**띄어쓰기.** Space between a Latin run and the following Korean word (MangaOCR 사용, 고급 LLM 설정,
OCR 도구 선택, 대신 TTS 사용, 온라인 LLM 번역기, 백엔드 URL, 잘못된 API 키); particle glued with no space
(OCR은, URL을, Anki에, GitHub에서, PlayTranslate가, {text}를); unit glued to the numeral (2.4초, 3개);
no space before `(`. That last one I checked against a committed *predicate*-final case, not just
noun-final ones: `anki_content_frequency_harmonic_desc` 「…조화 평균입니다(낮을수록 더 자주 쓰임)」 —
so `update_error_no_space` 「…부족합니다(230 MB 필요).」 follows the file's own convention. ✓

**Register.** Uniform 합니다체 in bodies; noun / ~하기 / ~하세요 in buttons and titles; no 해요체, no
반말, no 당신. The new **탭하여** (tap) is a third press-verb beside the committed 눌러 / 길게 누르세요 —
and that is correct, not drift: EN contrasts *Tap to…* against *Hold to…* in adjacent hotkey rows,
and Korean 눌러 cannot carry that contrast. `llm_prompt_invalid_title` (noun-form 저장할 수 없음, no
save path) vs `llm_prompt_warning_title` (imperative 확인하세요, bypassable) matches what the two
code paths actually do — `showFatalAlert` offers only OK, `showAdvisoryAlert` offers Save-anyway.

**Plurals.** `settings_yomitan_count_summary` is `other`-only (CLDR-correct for ko) and never has
to read at zero: `RootSettingsViewModel:344` routes `count == 0` to `settings_yomitan_empty_summary`.
The 개 counter carries the quantity, so 사전 1개 가져옴 / 사전 3개 가져옴 both read naturally.

**Duplicate-value sweep across the whole file.** Every exact-duplicate KO value involving a delta
key is a duplicate in EN too (OCR 도구 선택 ×2, 기록 ×2, 삭제/모델 삭제, 설정 열기, 복사됨, 자동 번역,
번역, 확인 중…). No round-1 edit created a new collision.

### Net

**Zero 🛑, zero ❌, two ⚠️, two 💬.** The two ⚠️s are one bug: **캡처 중인 앱** in
`error_capture_blocked_secure` and `error_single_app_not_fullscreen`. Round 1 correctly saw that
이 앱 was ambiguous and then replaced it with a phrase that is ambiguous the same way — Korean
`캡처 중인 X` defaults to *X is capturing*, which is what PlayTranslate does, on the exact TextView
that says 화면 캡처 중… about itself. One term (**캡처 대상 앱**) fixes both strings.

Six of the eight round-1 fixes are clean and two produced only a cosmetic side effect (the
친밀 표현 / 애정 표현 near-synonymy, 💬). The two axes that make Korean the highest-risk locale both
came back clean on a fully independent pass: **every placeholder site is batchim-safe**, and
**all 38 `misc_*` labels are mutually distinct with no `·` anywhere** — the `.distinct()` collapse
and the phantom-tag collision are both structurally impossible now.

**Verdict: SHIP** (the two ⚠️s are a cheap pre-ship polish, not a blocker).

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
| — | — | — | — | No findings. The delta re-derives as correct on every axis below. |

### Clean areas (delta) — checked, no findings

합니다체 in bodies, noun / ~하기 / ~하세요 in buttons and row titles. Combined particles after every variable placeholder: `update_none_message` uses `%1$s`이(가), `settings_ocr_footer_guidance` uses “`%1$s`”(으)로 — never a bare particle after a value the runtime supplies. Bare 는/가/를 appears only after the fixed name PlayTranslate (`a11y_stuck_message`, `camera_permission_rationale`). 접근성 / 캡처 / 오버레이 / 덱 reused from the committed file. 엔진 (engine) stays distinct from 도구 (tool) and 모델 (model), which meet in `settings_ocr_delete_camera_import_note`. 스냅샷 for the camera freeze-frame is kept apart from 스크린샷 (`anki_group_screenshot`). `settings_support_check_updates_title_available` byte-matches `update_dialog_title` (업데이트 사용 가능) so the row and the dialog name the same event. 정밀 / 고속 as the PaddleOCR tier words are parallel and neither collides with 빠름 in `settings_ocr_note_mlkit`. Plurals `other` only. 띄어쓰기 observed throughout.

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

**PASS.** No findings; nothing to apply.

---

## Delta review 2026-08-04 (8 keys: one-tap card toasts, first-field guard, hide-translations toggle, waveform zoom hint)

Mechanical layer verified programmatically over the eight keys: every key present, no
extras, no duplicates, no `translatable="false"` orphans; placeholder multisets identical
to EN (`%1$s` in `anki_first_field_unmapped` / `anki_first_field_empty`, none elsewhere);
all five `<xliff:g>` spans byte-identical to EN including `id` and `example`
(`brand_anki`, `field_name`); `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match; no raw
`'` or `"` (the `“ ”` in both first-field strings are the intended typographic quotes and
need no escaping); ordering follows EN. `./gradlew :app:processDebugResources` exits 0.
**No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `game_audio_zoom_hint` | 💬 | 두 손가락을 모으거나 벌려 표시할 오디오 범위를 조절하세요 | 두 손가락을 모으거나 벌려 표시 범위를 조절하세요 | 25 Hangul + 7 spaces ≈ 296dp at the caption's 11sp; the `TextView` in `anki_game_audio_panel.xml` is `match_parent` inside 24dp horizontal padding, so a 360dp-wide sheet leaves ~312dp — one line only at font scale 1.0, two lines at any larger scale or narrower sheet. It is `wrap_content` height so nothing clips, but this is the longest of the twelve locales (ja is 18 chars, zh 15). Dropping 오디오 saves ~47dp and loses nothing: the caption sits directly under the waveform. Keep 표시(할) — it is load-bearing, since the handles on the same view adjust the *selection* and pinch adjusts the *view*, and a bare 오디오 범위를 조절 would collapse the two. |
| `anki_first_field_unmapped` | 💬 | Anki가 노트를 식별할 수 있도록 “%1$s” 필드에 값을 매핑하세요. | Anki가 노트를 식별하려면 “%1$s” 필드에 값을 매핑하세요. | Toast clamps to two lines on Android 12+. Current text with a 3-char field name is ~400dp at 14sp against roughly 560dp of two-line capacity — it fits, but the field name is user-defined and unbounded, so headroom is the whole budget. 식별할 수 있도록 → 식별하려면 returns 3 Hangul + 2 spaces (~50dp, ~12%) at no cost to meaning, and matches the ~하려면 … ~하세요 pattern the file already uses in `a11y_required_hotkey_message` and `overlay_icon_a11y_required_message`. |
| `card_words_in_sentence` | 💬 | 문장의 단어 | 문장 속 단어 | Genitive 의 reads as "the sentence's words" — grammatical but bookish for a card-back section header. 속 is the idiomatic "in" for this construction and matches how ja/zh framed it (文中の単語 / 句子中的单词). Optional polish; no render risk either way. |
| `anki_first_field_unmapped`, `anki_first_field_empty` | 💬 | 노트 (Anki "note") | (keep 노트) | Judged, not a defect. 노트 / 노트 유형 is AnkiDroid's own Korean vocabulary, ja and zh made the same call (ノート / 笔记), and EN deliberately says "note" because the first-field checksum is a *note*-level identity, not a card-level one. Flagging only the inherited asymmetry: the app's picker calls the note type 카드 유형 (`anki_card_type_row_label`, `anki_field_mapping_unconfigured`, `settings_anki_digest`), so a Korean reader of `anki_first_field_empty` meets 카드 and 노트 in adjacent sentences with nothing linking them. That drift is in the English source (Card Type / note), not in this translation — do not "fix" it by flattening 노트 to 카드, which would destroy the note-vs-card distinction the string exists to explain. |

### Clean areas (delta) — checked, no findings

**Particles around the free-form field name.** The translator's head-noun strategy holds
in both first-field strings and is verified, not assumed: `“%1$s” 필드에` and
`“%1$s” 필드가` put every particle on 필드, never on the placeholder, so the 이/가 and
을/를 alternation is decided by 필드 (open syllable 드 → 가 is correct) and is invariant
under whatever AnkiDroid hands back. Dropped in real values — "Key", "Expression",
"단어", "번역문", "Front" — both sentences read identically well; a consonant-final field
name like 번역문 or "Front" never touches a particle. This is the same fix pattern the
Turkish locale uses for vowel harmony, and it is the right one here.

**Brand-name particles.** Anki가 (`anki_first_field_unmapped`) and Anki는
(`anki_first_field_empty`) are both correct — 앙키 ends in a vowel — and match the
file's existing precedent of a bare particle after a fixed brand name
(`anki_send_failed_message`: AnkiDroid가). Anki에 in both new toasts is invariant.

**The two one-tap toasts.** `anki_added_sentence_success` / `anki_added_word_success`
name the card shape with 문장 / 단어, byte-matching `anki_mode_sentence` and
`anki_mode_word` — which is the whole point of these strings, since one-tap applies the
remembered mode with no other UI showing it. Both pattern-match `anki_added_no_audio`'s
`Anki에 추가됨` exactly, and 카드가 takes the correct particle (카드 is open-syllable).
The -됨 ending is the file's established toast style (`history_copied_toast` 복사됨,
`anki_permission_denied` 권한이 거부됨), so these do not need 합니다체.

**Register.** ~하세요 in the two instructional strings (`game_audio_zoom_hint`,
`anki_first_field_unmapped`) and in `history_hide_translations_toggle_subtitle`'s second
sentence; 합니다체 in the declarative bodies (`anki_first_field_empty`,
`history_hide_translations_toggle_subtitle`'s first sentence). This matches the immediate
neighbours — `anki_field_mapping_unconfigured` 구성하세요,
`history_capture_image_toggle_subtitle` 보관합니다 — and the declarative+imperative mix
inside the subtitle mirrors EN and reads naturally in Korean. Row title
`history_hide_translations_toggle_title` uses the ~하기 noun form the parameters
prescribe for row titles.

**Pinch wording.** 두 손가락을 모으거나 벌려 is the standard Korean rendering of a pinch
gesture in Android UI (Google's own Korean strings use 손가락을 모으거나 벌려 for
pinch-zoom); it is not a calque and needed no 핀치 loanword. Only its length is flagged
above.

**History terminology.** 번역 (not 번역문) for the translated output is the file's
established noun — `cd_copy_translation` 번역 복사, `cd_toggle_translation_visibility`
번역 표시 전환, `hotkey_show_translations_title` 번역 표시 — so 번역 숨기기 is the exact
antonym of the shipped 번역 표시 and introducing 번역문 here would have broken the
one-term rule. 캡처한 텍스트 reuses the app's established capture verb and matches
`history_toggle_title` 텍스트 기록 유지 (EN likewise says "captured text" here and
"sentences" in `settings_cell_history_summary_*`, so this is faithful, not drift).
항목 for EN's "row" matches `history_delete_confirm_title` 이 항목 and is what Korean
list UIs actually say — 행 would be wrong. The collision with
`translate_button_subtitle_hold_to_hide_translations` (번역 숨기기 for the in-game
overlay) is inherited from EN's own reuse of "hide translations" and is unambiguous
inside the History settings group.

**Render constraints read, not guessed.** `card_words_in_sentence` is baked into the card
HTML under `gl-section`, which applies `text-transform:uppercase` (a no-op on Hangul) and
`letter-spacing:0.12em` (normal for a Korean header); there is no clipping path, and the
string needs no shortening. `game_audio_zoom_hint`'s TextView is `wrap_content` height, so
the length note above is a two-line aesthetic risk, not truncation.
`anki_first_field_empty` is a full alert with no length budget, which is why its longer
합니다체 phrasing is correct there and the toast is the only string tightened.

**Word order.** `anki_first_field_unmapped` front-loads the purpose clause where EN
front-loads the action. That is the correct Korean order, not an MT artifact, and it is
what ja and zh also did.

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
| yomitan_update_done_title | ⚠️ | 「사전 업데이트됨」 | 「사전 업데이트 완료」 | This file's own idiom for a completion alert is 완료: `yomitan_import_summary_title` 「가져오기 완료」, `label_done` 「완료」. The ~됨 nominalised passive is a status-badge form, not an alert title, and reads flatter than the neighbouring 「업데이트 사용 가능」. |
| yomitan_update_done_message | ⚠️ | 「%1$s이(가) 최신 상태입니다.」 | 「이제 %1$s이(가) 최신 상태입니다.」 | EN is "X is **now** up to date" — the alert fires immediately after a successful install, and the "now" is what separates it from `yomitan_update_none_message` ("X **is on** the latest version"), which fires when nothing happened. Without 이제 the two success/no-op alerts say the same thing in Korean. |

### Clean areas (delta) — checked, no findings

**Particles after variable placeholders are all in combined form.** 「%1$s을(를)」,
「%1$s이(가)」 and 「%2$s(으)로」 in `yomitan_update_available_message`, `_none_message` and
`_done_message`. No bare 을/를/이/가/로 follows a runtime value anywhere in the delta, which
is the file's hard rule — a dictionary title can end in a consonant (JMdict), a vowel
(Jitendex.org 로 reading) or a bracket, and the combined form is the only safe choice.
「%1$s의 데이터를」 in `_repair_message` uses 의, which is invariant.

**Update vocabulary reused wholesale.** 「업데이트 사용 가능」 and
「업데이트를 확인할 수 없습니다」 are byte-identical to `update_dialog_title` /
`update_check_failed_title`; `yomitan_update_check_failed_message` follows
`yomitan_download_error_message`'s 「연결을 확인하고 다시 시도하세요」;
`yomitan_update_scan_active_message` closes with `anki_models_unavailable`'s
「잠시 후 다시 시도하세요」. 「업데이트 확인 중」 matches `update_progress_verifying`
「확인 중…」.

**원본 언어 is the file's existing term.** Taken from `llm_prompt_kw_source_desc`
(「원본 언어의 영어 이름」), not freshly coined, and deliberately not 「게임 언어」 — the row
names the *dictionary's* declared language, not the capture language.

**Register.** 합니다체 in every body; the row/button labels use nouns
(업데이트, 다시 다운로드, 업데이트 확인) and the guidance sentences use 하세요 imperatives —
the split the ko parameters call for. 띄어쓰기 observed throughout; no 당신.

**모든 언어 works in both slots.** Pinned first row of the language picker and muted value
of the Source Language row. It states *all*, not *none*, matching the deliberate EN rename
from "None" to "Any".

### Verdict

**PASS after fixes.** Two ⚠️, no ❌, no 🛑. Both were the same underlying miss: the update
alerts were translated string-by-string rather than as a set, so the success alert lost the
contrast with the no-op alert. Particle handling — the usual Korean risk here — was clean
on the first pass.

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
| settings_debug_short_text_routing | 💬 | 「짧은 텍스트 오프라인 라우팅」 | A bare noun stack with no verb. Kept because the debug block already contains both shapes and the noun form is what the *mode* rows use — `settings_debug_angle_gate` 「기존 각도 임계값(10°)」 — while the verb form (「…표시」, 「…로깅」) belongs to rows that describe a repeated action. Short-text routing is a mode, so it takes the mode shape. |

### Clean areas (delta) — checked, no findings

**Particles after the brand token.** 「AnkiDroid에」 in the prompt and 「AnkiDroid가」 in the
failure body. 에 is invariant, and 가 is safe here for the same reason the committed
`anki_send_failed_message` already writes 「AnkiDroid가 카드를 수락하지 않았습니다」 — AnkiDroid
is a fixed brand name, not a runtime fill, so the combined 이(가) form is not required and
would read worse. No bare particle follows a variable anywhere in the delta.

**Title register.** 「카드가 너무 큼」 uses the -ㅁ nominalisation that `anki_send_failed_title`
「카드를 추가할 수 없음」 established for alert titles, rather than the 합니다체 that the bodies
take. The two titles can appear minutes apart in the same flow and now read as one family.

**Confirm question.** 「저장하시겠습니까?」 matches `llm_prompt_discard_title`
「저장하지 않고 나가시겠습니까?」 and `bergamot_disable_title` 「…끄시겠습니까?」. ASCII `?` is the
file's convention (33 occurrences, zero full-width).

**Terminology.** 카드 / 사전 / 정의 / 「일반 텍스트」 — the last from
`yomitan_styling_subtitle`'s 「항상 일반 텍스트로 표시합니다」. 단어 and 문장 in the failure body
match `anki_mode_word` / `anki_mode_sentence`. 온디바이스 for on-device comes from
`llm_prompt_advisory_too_long` 「온디바이스 모델은」 rather than a fresh 기기 내 coinage.

**Toast shape.** 「Anki에 추가됨(크기에 맞게 정의 간소화)」 keeps the family's no-space-before-paren
spacing from `anki_added_no_audio` 「Anki에 추가됨(오디오 없음)」.

### Verdict

**PASS.** No fixes required. 간소화 is one root across all four oversize strings, so the
prompt, the button, the failure and the toast cannot drift apart on the delta's central
term.

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

**Siblings, one term swapped.** 「인라인 병음 전환」 mirrors 「인라인 후리가나 전환」; 병음 is
`overlay_mode_option_pinyin`.

**The ⋯.** 「작업 더보기」. No particles anywhere in the delta, so the consonant/vowel
rule does not arise.

**Row names.** 「소리 내어 읽기」 is `cd_read_original_aloud` 「원문 소리 내어 읽기」 without its
object; 「텍스트 편집」 follows `cd_edit_original` 「원문 편집」. Longest row: 8 characters.

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
| settings_edge_indicator_subtitle | 💬 | 오버레이가 표시되는 동안 화면 가장자리에 은은한 빛을 표시합니다. | 오버레이가 표시되는 동안 화면 가장자리가 은은하게 빛납니다. | 표시 twice. |

### Clean areas (delta) — checked, no findings

**Particles.** “빠른 메뉴 열기”를 (vowel-final 기, so 를). The pill 「%1$s에서 번역 중」 uses 에서,
which does not alternate, so no combined form is needed. The helper names the icon as
「%1$s 아이콘을」, putting the particle on 아이콘 instead of an 을(를) after an image.

**Terms.** 빠른 메뉴 is the removed tap hint's (not onboarding's 빠른 액세스 메뉴); 드래그 / 길게
누르기 / 탭 are that set's gestures. Reused verbatim: 게임 언어 변경, 화면 캡처, 자동 번역 시작/중지,
번역할 수 없음, 잘못된 API 키; 할당량 and 크레딧 as `degraded_warning_quota` and
`note_mlkit_account_issue` have them; 실험적 기능입니다.

**Register.** 합니다체 in the alert and dialog bodies; noun-form rows; half-width colon in
the pills.

### Verdict (round 1)

1 💬, to apply.

### Round 2 (2026-09-29), after applying round 1

Round-1 fix present: 오버레이가 표시되는 동안 화면 가장자리가 은은하게 빛납니다. Mechanical layer re-run after the fixes: 0 problems; analyzer `missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD SUCCESSFUL. Every screen of the delta was re-read in full, not only the changed keys.

No new findings.

**Verdict (round 2):** **PASS.**

## Delta review 2026-10-07 (41 keys + 2 orphans: bug-report email, Support rows, kill notice, Fix disappearing icon page)

Mechanical layer verified: `python3 PKT/../tools/mech_check.py PKT/../keys.txt ko` reports
"checked 1 locales x 41 keys; problems: 0" (every key present; `<xliff:g>` spans and
`%1$s` placeholders byte-identical to EN; `\n`, `<b>`, `\{ \}`, `&amp;/&lt;/&gt;` counts
match; no unescaped `'`/`"`, stray tag, double space or edge whitespace), and
`python3 scripts/l10n_diff.py --locale app/src/main/res/values-ko/strings.xml` reports
`values-ko missing=0 orphan=0 modified=0`, "in sync". Each of the 41 names occurs exactly
once; the two orphans `settings_debug_export_logs_title` / `_subtitle` are gone while
`settings_debug_export_logs_subject` stays; the file parses as XML. **No 🛑 build-breaking
issues.**

**Render code read before reviewing.**
- `MainActivity.maybeShowKillNotice` (l. 2235-2263): the body is one of the three
  `kill_notice_body_*`, then `append(' ').append(kill_notice_restored)` only when an icon is
  on screen; title, accent `kill_notice_see_options` (opens `KeepRunningActivity`), cancel
  `kill_notice_not_now`. So 지금은 다시 켜져 있습니다 was read after all three bodies.
- `SettingsRenderer.setupSupportSection` (l. 1170-1214): Check for updates, Discord, Fix
  disappearing icon, Report a bug (tap `BugReport.email`, hold `BugReport.share`), Donate;
  `settings_row_hub.xml` makes the summary `singleLine` + `ellipsize="end"`.
  `KeepRunningActivity` (l. 57-63, 114-142) binds the same two Report a bug strings under the
  cards, gives the Xiaomi battery card `keep_running_battery_line`, and swaps in
  `a11y_stuck_title` + `keep_running_accessibility_restart_line` when the service is stuck.
- `LogExporter.emailFiles` (l. 112-121): the no-email toast is `LENGTH_LONG` with
  `SUPPORT_EMAIL`, then `shareFiles` opens the plain share sheet titled
  `share_chooser_share_logs` (로그 공유), so the toast's 파일 is followed by a sheet about 로그.

### Findings (delta, round 1)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| kill_notice_body_other | ⚠️ | `휴대전화가 켜져 있던 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>를 종료했습니다. 플로팅 아이콘이 사라진 것은 이 때문입니다. 일부 휴대전화는 배터리를 절약하려고 이렇게 합니다.` | `켜져 있던 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>를 휴대전화가 종료했습니다. 플로팅 아이콘이 사라진 것은 이 때문입니다. 일부 휴대전화는 배터리를 절약하려고 이렇게 합니다.` | Garden path: 휴대전화가 켜져 있던 first parses as one relative clause, "the … in which the phone was on" (휴대전화가 켜지다 is the everyday phrase for the phone itself being on), so 휴대전화가 has to be re-read as the subject of 종료했습니다 once PlayTranslate를 arrives. Fronting the object removes the misparse. It also keeps 켜져 있던 PlayTranslate parallel with `_body_stopped` and with the 다시 켜져 있습니다 echo in `kill_notice_restored`, and it stresses 휴대전화가, which the next sentence (일부 휴대전화는…) explains. |
| keep_running_empty | 💬 | `더 이상 권장하는 설정이 없습니다. 문제가 계속되면 문제를 재현한 다음 버그를 신고해 주세요.` | `더 이상 권장하는 설정이 없습니다. 문제가 계속되면 증상을 재현한 다음 버그를 신고해 주세요.` | 문제가 계속되면 문제를 has 문제 twice in three words. 증상을 재현 is the usual Korean bug-report phrase and keeps the meaning. The "report a bug" half (버그를 신고해 주세요) already matches the 버그 신고 row under the label. |

### Clean areas (delta) — checked, no findings

**Particles.** After the fixed name PlayTranslate (vowel-final): 를 in `kill_notice_title`,
`_body_other`, `keep_running_intro`, `_battery_line`, `_accessibility_line`, `_tile_line`,
`_xiaomi_autostart_line`; 가 in `_body_memory`, `_body_stopped`,
`_huawei_close_after_lock_line`; 의 in `_huawei_app_launch_line`; 에서 (no alternation) in the
OPPO and vivo lines; none where the name modifies a noun (PlayTranslate 자동 종료 방지,
PlayTranslate 카드를, PlayTranslate 잠그기, the email subject). After the address placeholder
the toast uses 에, which does not alternate, so no combined form is needed (a (으)로 would
have needed one after …com), and ~에 보내다 reads naturally with a destination. Also right:
Android가, Xiaomi가 (샤오미), “백그라운드에서 실행”을 (consonant-final 행), 제한 없음으로,
배터리 세이버를, 배터리 사용량을, 메일로 (ㄹ-final takes 로), 스와이프와, 기기 정보와 최근 로그가,
우선순위로.

**Register and title forms.** Titles are Sino-Korean verbal nouns (설정, 허용, 추가, 관리,
사용, 신고) or ~기 forms of native verbs (잠그기, 끄기, 보내기, 보기), the file's noun / ~하기
convention; `kill_notice_title` is a sentence title without a period, like
`crash_dialog_title` and `a11y_stuck_title`. Bodies are 합니다체 with requests in ~하세요 /
~해 주세요, as in `a11y_stuck_message` and `crash_email_body`. The email prompt 무슨 일이
있었나요? uses the softer ~나요 question the file already has (`onboarding_welcome_play_title`
언어를 모르시나요?); 무슨 일이 있었습니까? would sound like an interrogation in a fill-in
template. The page and row title 아이콘이 사라질 때 is neither a noun nor ~하기, but it is the
idiomatic Korean heading for a troubleshooting entry (the help-centre "~할 때" form), it
leads with the symptom as the EN comment asks, and it is byte-identical in both keys at
173 dp of the toolbar's 272. In the Support card its summary PlayTranslate 자동 종료 방지
supplies the "fix", and the kill notice's 해결 방법 보기 lands on it naturally. The noun form
아이콘이 사라지는 문제 해결 measures 259 dp, which fits with little margin and is no clearer.
Passed. The Report a bug summary uses 개조식 noun endings (전송. / 공유.), terse but standard
for a one-line Korean summary, and its hold clause follows the file's 길게 눌러 … pattern
(`translate_button_subtitle_hold_*`). The whole is 241 dp: it fits 249 dp and is shorter than
EN's 256. The first clause is 157 dp, so the tap action stays whole even at 216 dp.

**Surfaces read as sets.** The kill notice: title, each body, each body + " 지금은 다시
켜져 있습니다.", 해결 방법 보기 / 나중에. The restored sentence's null subject resolves to
PlayTranslate through the 켜져 있던 echo after all three bodies, including after
`_body_other`'s 일부 휴대전화는 (phones being "back on" makes no sense there), and the
contrastive 지금은 carries "now, unlike before". 해결 방법 보기 names what the page holds;
나중에 = `btn_not_now` / `crash_dialog_later`. The Support card in order: 업데이트 확인 ·
Discord 참여하기 · 아이콘이 사라질 때 · 버그 신고 · PlayTranslate 후원하기. The Fix page as each
ROM gets it: Samsung (never-sleeping, battery, accessibility, tile), Xiaomi (autostart,
battery saver, lock), Huawei (App launch, close-after-lock), OPPO and vivo (one card each),
then the empty label and the Report a bug row. The battery line (설정하지 않으면…) works under
both the 배터리 사용량을… and the 배터리 세이버를… titles; the OPPO and vivo lines are
byte-identical; the restart line under `a11y_stuck_title` reads as one unit. The email flow
in order:
- 충돌 보고서 보내기, the crash precedent of `crash_dialog_message`.
- 버그 신고 보내기 and PlayTranslate 버그 신고 – v3.3.0. 버그 신고 is Android's own Korean
  noun for a bug report (framework `global_action_bug_report` and `bugreport_title`), so the
  two families stay apart as EN's "crash report" and "bug report" do.
- The body, mirroring `crash_email_body` (설명해 주세요, 첨부되어 있습니다, 기기 정보).
- The toast: 2 lines at 296 and at 320 dp, with the address whole at the start of line 2.
  Line 2 measures 286 dp, within the ±10 dp noise of 296, so on a 360 dp phone at worst the
  final 보내세요 could clip, never the address.
- Then the 로그 공유 sheet.

**Terms.** 종료 for every "close" (title, bodies, intro, the battery, OPPO, vivo, Huawei and
Samsung lines, the summary's 자동 종료) and 중지 for every "stop" (`_body_stopped`, the Xiaomi
autostart line, the restart line), mirroring EN's split. 중지 is also the file's own
force-stop word (강제 중지 in `a11y_stuck_message`); EN names no Force stop button here, so
AOSP's 강제 종료 is not a label to match. Also:
- 휴대전화 for "phone" in all 8 places (known issue 3), with 기기 for "Device info" in the
  email body, as in `crash_email_body`.
- 플로팅 아이콘 (`settings_show_overlay_icon`); 메모리 정리 for "memory cleanup" in both
  lines; 로그 (`share_chooser_share_logs`); 지원팀 for "support".
- autostart → 자동 시작 (Xiaomi, vivo) and auto-launch → 자동 실행 (OPPO title, Huawei switch).
- Reused verbatim: the restart line's wording from `a11y_stuck_message`; 빠른 설정 타일 추가
  (`quick_tile_add_row_title`); 자동 시작 and 배터리 세이버를 제한 없음으로 설정
  (`a11y_stuck_message_xiaomi`).
- `keep_running_empty`'s 버그를 신고해 주세요 = the 버그 신고 row title.

**Platform labels.**
- AOSP ko: 제한 없음 (`manager_battery_usage_unrestricted_title`), 배터리 사용량 (the page is
  앱 배터리 사용량, shortened as in EN), 접근성.
- Samsung (OFFICIAL): re-checked today on samsung.com/sec/support/galaxy-battery/optimization,
  which prints 설정 > 디바이스 케어 > 배터리 > 백그라운드 앱 사용 제한 > 자동 절전 예외 앱 and
  절전 상태 앱, and no newer 절전 예외 앱 form. The card title and line match every label.
- Xiaomi (UNVERIFIED in oem-labels.md, though Xiaomi does sell in Korea): 자동 시작 / 배터리
  세이버 / 제한 없음 / 최근 앱 match the reviewed `a11y_stuck_message_xiaomi`. One Korean
  secondary source (a yellowit.co.kr article on a Redmi Note 12 bought abroad; the UI language
  is not stated) prints 자동 시작, 제한 없음 and 최근 앱, but 배터리 절약 / 배터리 사용량 for the
  per-app entry. That source is not official, and EN's entry name itself changes per HyperOS
  version (Battery saver / Battery / Power), so no change is asked. 최근 앱 is also Android's
  own ko label for Recents (framework `accessibility_system_action_recents_label`).
- Huawei, OPPO, vivo (no Korean source; judged as plain Korean): 앱 실행, 자동 실행, 보조 실행,
  백그라운드에서 실행, 추가 배터리 설정, 화면 잠금 후 앱 닫기, 백그라운드 활동, 백그라운드 전력
  사용 are all plain, readable renderings. 보조 실행 is the least self-explanatory, but it
  names a switch shown beside the other two. Known issue 1 is not re-reported.
- Quotes: “ ” wrap the labels that are verb phrases (the three Huawei switches, and 화면 잠금
  후 앱 닫기 before 끄기), where an unquoted label would run into the sentence's own verb. Noun
  labels stay unquoted as in `a11y_stuck_message_xiaomi`, and paths use → as in
  `overlay_icon_a11y_required_message`.

**Typography.** 띄어쓰기 checked on all 41 (해 주세요, 이 때문, 게임 중에, 한 번, 때마다, 직후,
지원팀). There is no NBSP, zero-width character or em dash. The only non-Hangul, non-ASCII
glyphs are the en dash in the subject (as in `crash_email_subject`), “ ” and →.

### Verdict (round 1)

1 ⚠️, 1 💬. To apply: `kill_notice_body_other`, `keep_running_empty`. The other 39 keys pass.

### Round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 PKT/../tools/mech_check.py PKT/../keys.txt ko` reports
"checked 1 locales x 41 keys; problems: 0", and
`python3 scripts/l10n_diff.py --locale app/src/main/res/values-ko/strings.xml` reports
`values-ko missing=0 orphan=0 modified=0`, "in sync". The file parses as XML; each of the 41
names occurs exactly once, in English order (the KILL NOTICE and KEEP RUNNING PAGE banners
sit where English has them, between `settings_support_report_bug_chooser_title` and
`settings_support_check_updates_title`); `settings_debug_export_logs_title` / `_subtitle`
are gone and `settings_debug_export_logs_subject` stays. **No 🛑 build-breaking issues.**

**Round-1 fixes:**
- `kill_notice_body_other` (⚠️): applied, byte-identical to the suggestion (켜져 있던 PlayTranslate를
  휴대전화가 종료했습니다. …). The fronted object removes the misparse, the body now opens like
  `_body_stopped` (켜져 있던 PlayTranslate가 중지되었습니다.), and body + " 지금은 다시 켜져 있습니다."
  still reads as one unit; the 를 after PlayTranslate is unchanged.
- `keep_running_empty` (💬): applied, byte-identical (문제가 계속되면 증상을 재현한 다음 버그를 신고해
  주세요.). 문제 no longer repeats, and 버그를 신고해 주세요 still names the 버그 신고 row beneath it.
- Nothing around the fixes broke: `keep_running_title` = `settings_support_keep_running_title`
  (아이콘이 사라질 때, re-measured at 173 dp of the 272 dp toolbar); the OPPO and vivo lines are
  byte-identical; `kill_notice_not_now` = `btn_not_now`; the hub summaries re-measure at 161 dp
  and 241 dp (tap clause 157 dp); the toast still wraps to 2 lines at 296 and 320 dp with the
  address whole at the start of line 2 (286 dp); `a11y_stuck_message_xiaomi` is unchanged from HEAD.

#### Findings (round 2)

No new findings.

#### Verdict (round 2)

**PASS** (0 ❌, 0 ⚠️, 0 💬). All 41 strings were read again as the four surfaces: the kill
notice with each body alone and with the restored sentence, the Support card in row order, the
Fix disappearing icon page as Samsung, Xiaomi, Huawei, OPPO and vivo users get it (including the
stuck-accessibility card and the empty label over the Report a bug row), and the email flow from
chooser to toast to the 로그 공유 sheet. Every particle contact point was checked again (를/가/의/에서
straight after PlayTranslate, 가 after Android and Xiaomi, 에 after the address, which needs no
combined form), along with 띄어쓰기, the “ ” quotes, the arrows, and the 합니다체 bodies under noun or
~기 titles. Samsung's labels were re-confirmed today on Samsung Korea's own page
(samsung.com/sec/support/galaxy-battery/optimization: 디바이스 케어 > 배터리 > 백그라운드 앱
사용 제한 > 자동 절전 예외 앱, 절전 상태 앱), and a Korean secondary guide uses the same names. A
Korean search for Xiaomi's on-device labels again found no source, so the reviewed
`a11y_stuck_message_xiaomi` wording stands. No round-1 decision is disputed.

## Follow-up review 2026-10-07, round 1 (15 keys after the Fix disappearing icon rewrite)

Mechanical layer: `python3 TOOLS/mech_check.py F2/keys2.txt ko` reports "checked 1 locales x
15 keys; problems: 0" (every key present; `<xliff:g>` spans, including both `app_name` and
`app_name2` in the Xiaomi autostart, OPPO and vivo lines, byte-identical to EN; `\n` count
matches in `a11y_stuck_message`; no unescaped `'`/`"`, stray tag, double space or edge
whitespace), and `python3 scripts/l10n_diff.py --locale app/src/main/res/values-ko/strings.xml`
reports `values-ko missing=0 orphan=0 modified=0`, "in sync". The file parses as XML; each of
the 15 names occurs exactly once, between the same neighbours as in English; the four removed
keys (`restricted_settings_title` / `_message`, `keep_running_huawei_close_after_lock_title` /
`_line`) are gone. The only non-Hangul, non-ASCII glyphs in the 15 are “ ” and ⋮; no NBSP or
zero-width character. **No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `AccessibilityHelp.withRestrictedSettingsStep` returns `message + "\n\n" + addendum` on API 33+
  and the message alone below it. Callers: `MainActivity.showAccessibilityDialog`
  (`accessibility_dialog_message`), `SettingsRenderer.showOverlayIconA11yAlert`
  (`overlay_icon_a11y_required_message`), `AccessibilityAlert` (the three
  `a11y_required_*_message` in the OverlayAlert card, button 접근성 설정 열기) and
  `KeepRunningActivity.lineOf` (`keep_running_accessibility_line`, not while the service is
  stuck). `SettingsRenderer.showA11yStuckAlert` adds no addendum: `a11y_stuck_message`, plus
  `"\n\n" + a11y_stuck_message_xiaomi` when `Build.MANUFACTURER` is Xiaomi.
- `KeepRunningItems.ids`: Xiaomi gets autostart, its own battery card (title + the new line) and
  the lock card (no action), never the generic battery card; Huawei, OPPO, vivo and Samsung get
  one card each, then the generic battery card while the exemption is missing (its tap opens
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, AOSP's 앱이 항상 백그라운드에서 실행되도록
  허용하시겠습니까? dialog), accessibility (API 30+) and the tile (API 33+).
- `KeepRunningActivity.openFirst`: when no candidate screen launches, a `LENGTH_LONG` toast with
  `keep_running_screen_unavailable`, then `startActivity(appDetails)`. So the toast is read on
  top of App info, right after the tap on a card.

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| keep_running_oppo_auto_launch_line, keep_running_vivo_autostart_line (one fix: the second sentence, byte-identical in both values) | ⚠️ | `허용하지 않으면 휴대전화가 백그라운드에서 <xliff:g id="app_name2" example="PlayTranslate">PlayTranslate</xliff:g>를 중지하거나 다시 시작하지 못하게 할 수 있습니다.` | `허용하지 않으면 휴대전화가 백그라운드에서 <xliff:g id="app_name2" example="PlayTranslate">PlayTranslate</xliff:g>를 중지하거나, 앱이 다시 시작하지 못하게 할 수 있습니다.` | Scope garden path. "X를 A하거나 B하지 못하게 하다" is the everyday pattern for "keep from doing A or B" (앱을 삭제하거나 수정하지 못하게 하다), so the default parse is that the phone may keep PlayTranslate from being stopped or restarted. For the stop half that is the opposite of EN, and the reader reaches EN's "may stop it, or keep it from starting again" only on a second pass. With 앱이 the second clause gets its own subject, so 중지하거나 can only coordinate at the top (the phone stops it, or keeps the app from restarting); the comma marks the same boundary. Using 앱이 for PlayTranslate is the translator's own device in the battery line (PlayTranslate를 종료해도 앱이 스스로 다시 시작할 수 있습니다). Replace this sentence in both values; the first sentences stay. Both spans stay; mechanical rules re-checked on the full suggested values. |
| keep_running_xiaomi_autostart_line | 💬 | `설정에서 자동 시작을 검색하여 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>에 허용하세요. 허용하지 않으면 <xliff:g id="app_name2" example="PlayTranslate">PlayTranslate</xliff:g>가 종료된 뒤 Xiaomi가 다시 시작하지 못하게 할 수 있습니다.` | `설정에서 자동 시작을 검색하여 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>에 허용하세요. 허용하지 않으면 <xliff:g id="app_name2" example="PlayTranslate">PlayTranslate</xliff:g>가 종료된 뒤 Xiaomi가 앱을 다시 시작하지 못하게 할 수 있습니다.` | The causee is dropped, and its only antecedent is the subject of the subordinate clause (PlayTranslate가 종료된 뒤), so Xiaomi가 sits directly before 다시 시작하지. That locally invites the "phone restarts" reading the translator kept out of the battery line with 앱이. The causative settles it at 할 수 있습니다, so this is polish. 앱을 names what does not restart and matches the OPPO and vivo fix above. Both spans stay. |

### Clean areas — checked, no findings

**Particles.** After the fixed name PlayTranslate (vowel-final): 의 in the addendum, both stuck
messages, the Xiaomi battery line and the Huawei line; 가 in the battery title and the Xiaomi
autostart line's second span; 를 in the battery, tile and Samsung lines and the OPPO and vivo
second spans; 에 (no alternation) in the Xiaomi, OPPO and vivo first spans; none where the name
modifies a noun (PlayTranslate 카드를). Also right: Android가, Xiaomi가 (샤오미), Samsung은
(삼성), “제한된 설정 허용”을 and “백그라운드에서 실행”을 (consonant-final), “자동 관리”를
(vowel-final), 제한 없음으로 / 제한 없음을, 배터리 항목(…)을 (the particle agrees with 항목,
before the parenthesis), 자동 시작을, 자동 실행을, 앱 실행을, 배터리 사용량을, 절전 상태로,
자동 절전 예외 앱에.

**Register and title forms.** Bodies are 합니다체 with requests in ~하세요, as on the rest of the
page. The two changed titles end in verbal nouns (허용, 설정), the file's title convention. The
battery title is AOSP's `high_power_prompt_title` with 앱이 → PlayTranslate가 and the question
ending dropped, so the card names the dialog its tap opens, word for word.

**CHANGED keys: only what English moved.** Compared with the reviewed first-pass values
(`PKT1/packet-ko.md`; HEAD for the two stuck messages):
- `a11y_stuck_message` lost exactly the cause sentence (보통 시스템이 … 발생합니다.) and is
  otherwise byte-identical.
- `a11y_stuck_message_xiaomi` changed 사용 설정하고 → 허용하고 (enable → allow), 배터리 세이버를 →
  배터리 사용량을 (Battery saver → its battery use) and 중지합니다 → 중지할 수 있습니다
  (will → may), and nothing else.
- `keep_running_tile_line` lost 스와이프와 탭 한 번이면 and keeps the rest.
- `keep_running_xiaomi_lock_line` keeps its first sentence and replaces the memory-cleanup
  sentence.
- `keep_running_huawei_app_launch_line` keeps the three quoted switches and adds the search
  step and “자동 관리”.
- `keep_running_xiaomi_battery_title` swaps only the entry noun.
- The rewritten lines (battery, Xiaomi autostart, OPPO, vivo, Samsung) carry every clause of
  their new English: where, what to do, and the hedged effect (할 수 있습니다 for "may").

**Surfaces read as sets.**
- The addendum after each host. `accessibility_dialog_message` and
  `overlay_icon_a11y_required_message` end on the 설정 → 접근성 → 다운로드된 앱 → PlayTranslate →
  사용 설정 path, so 스위치 is the switch that path ends on. After the three
  `a11y_required_*_message` (OverlayAlert card), the switch has no antecedent in the message,
  exactly as in English, and the 접근성 설정 열기 button under it supplies one. After
  `keep_running_accessibility_line` it closes the 접근성 모드 사용 card, whose tap opens
  accessibility settings. The steps read in order (한 번 탭한 다음, … 열고, … 탭한 후, …
  선택하세요), with 다음 / 후 varying the two "then"s. It is not appended to the stuck alert,
  which matches the code.
- Each card, title then line:
  - 자동 시작 허용, then 설정에서 자동 시작을 검색하여….
  - 배터리 사용량을 제한 없음으로 설정, then 배터리 항목(…)을 열고 제한 없음을 선택하세요
    ("battery use" in the title, "battery entry" in the line, as in EN).
  - 최근 앱에서 PlayTranslate 잠그기, then the gesture.
  - 앱 실행을 수동으로 관리, then the 앱 실행 search and “자동 관리” off.
  - 자동 실행 및 백그라운드 활동 허용, then the 자동 실행 search and 백그라운드 활동.
  - 자동 시작 및 백그라운드 전력 사용 허용, then the 자동 시작 search and 백그라운드 고전력
    사용 (EN's own pair, "background power use" / "high background power use").
  - 자동 절전 예외 앱에 추가, then the Samsung path.
  - The battery title, then 허용하면…: 허용하면 picks up the title's 허용, and 앱이 keeps 다시
    시작 on the app, not the phone.
  - 빠른 설정 타일 추가, then 어느 화면에서든….
- The stuck alert: 접근성을 다시 시작해야 합니다, the message, then on Xiaomi the paragraph.
  Its two settings now repeat the two Xiaomi card titles (자동 시작 … 허용, 배터리 사용량을 제한
  없음으로 설정), and its 도 (자동 시작도) ties it to the re-toggle step above.

**Toast.** Measured with `TOOLS/Wrap` (14 sp, Noto Sans CJK KR). It takes 2 lines at 296 dp,
breaking at the sentence boundary (269 dp, then 266 dp), and 2 lines at 320 dp; it is 538 dp in
all. The tool breaks Korean only at spaces, while Android may also break between syllables, so
this is the worst case. The wording uses 해당 화면 for "that screen" and 이 항목 for "the
setting". 이 항목 is read right after the tap that raised the toast, so it points back at the
card the user just tapped. App info has opened under the toast, but nothing on it is singled
out for 이 to point at. 해당 항목 would be the strictly anaphoric choice but repeats the first
sentence's 해당. Passed.

**Two-span lines.** The Xiaomi, OPPO and vivo lines carry both spans: the first as the
recipient (PlayTranslate에 허용…), the second in the consequence sentence (PlayTranslate가
종료된 뒤 / PlayTranslate를 중지하거나). Repeating the name across two sentences reads naturally
in Korean. Only the consequence clauses are in the findings.

**Labels.**
- AOSP ko: 앱 정보 (`application_info_label`) and “제한된 설정 허용”
  (`app_restricted_settings_lockscreen_title`), quoted as the reviewed
  `restricted_settings_message` quoted it. The "Restricted setting" dialog is described, not
  named, as in EN. The battery title mirrors `high_power_prompt_title`, and 제한 없음 matches
  `manager_battery_usage_unrestricted_title` (as Xiaomi's option it is unverified). The ⋮ menu
  keeps the reviewed 점 3개(⋮): the glyph identifies the icon whatever a help page calls it
  (Google's ko help says 더보기), and AOSP shows no text label on that button, so there is
  nothing to mismatch.
- Samsung (official; re-verified on samsung.com/sec/support/galaxy-battery/optimization in both
  first-pass rounds, not re-fetched): 배터리, 백그라운드 앱 사용 제한 and 자동 절전 예외 앱, and
  절전 상태 for "sleep" (절전 상태 앱). All four match.
- Xiaomi (sold in Korea; no official Korean page readable). AdGuard's Korean KB
  (adguard.com/kb/ko/adguard-for-android/solving-problems/background-work, crowd-translated,
  secondary, read today) prints 자동 시작 (MIUI 12) and 백그라운드 자동 시작 (MIUI 13+/HyperOS,
  설정 → 앱 → 권한), 제한 없음, and 잠금 아이콘을 탭합니다. So the search word 자동 시작 matches
  either form as a substring, and 제한 없음 and the lock step agree.
- One open Xiaomi point, not a finding. For the per-app entry, AdGuard prints 배터리 절약
  (MIUI 13+/HyperOS and MIUI 12), with 앱 배터리 세이버 only in an older all-devices section.
  Together with the yellowit.co.kr article the first pass cited, two secondary sources now lean
  to 배터리 절약 for the first name in `keep_running_xiaomi_battery_line`'s parenthesis. Neither
  is official, and the KB disagrees with itself. The line also says 배터리 항목 and lists 배터리,
  so a user finds the entry under either name. No change is asked, but a Korean-UI Xiaomi or an
  official page should settle this first. 전원 for HyperOS 3's "Power" is the usual Korean
  rendering of a "Power" menu (전원 옵션, 전원 버튼); also unverified. Two Korean searches found
  no on-device source.
- Huawei, OPPO, vivo (not sold in Korea; plain-Korean renderings, consistent with the first
  pass): 앱 실행 (the title and the search word agree), “자동 관리”, “자동 실행”, “보조 실행”,
  “백그라운드에서 실행”, 자동 실행 (OPPO), 백그라운드 활동, 자동 시작 (vivo) and 백그라운드
  고전력 사용.
- Search words are unquoted, as in EN (자동 시작, 앱 실행, 자동 실행), and each repeats its card
  title's noun, so it reads as the term to type. “자동 관리” is quoted like the three switches in
  its sentence; unquoted, 자동 관리를 끈 다음 would blur into the prose.

**Terms.**
- 종료 for "close" (the battery line; the Xiaomi line's 종료된) and 중지 for "stop" (OPPO, vivo,
  both stuck messages): the split the first pass set.
- 휴대전화 for "phone" in all 5 places (4 keys; known issue 3), and 기기 where EN says "device"
  (Xiaomi 기기에서는, 기기의 접근성 설정).
- 허용 for every "allow".
- autostart → 자동 시작; auto-launch → 자동 실행 (the OPPO search word and the Huawei switch
  alike); launch → 실행 (앱 실행, 보조 실행).
- 앱 설정 for "app settings" and 앱의 시스템 설정 for "the app's system settings", as EN
  distinguishes them; 최근 앱 for Recents (AOSP's ko label).

**Typography.** 띄어쓰기 checked on all 15 (한 번, 탭한 다음, 탭한 후, 점 3개(⋮), 열 수 없습니다,
종료된 뒤, 끈 다음, 며칠 동안, 할 수 있는). 끌어내리세요 is one word (끌어내리다). Parentheses
attach to the preceding word (항목(…), 3개(⋮)). No NBSP, zero-width character or em dash.

### Verdict (round 1)

1 ⚠️ (one fix in two keys), 1 💬, 0 ❌. To apply: `keep_running_oppo_auto_launch_line` and
`keep_running_vivo_autostart_line` (the shared second sentence, ⚠️), then
`keep_running_xiaomi_autostart_line` (💬). The other 12 keys pass. One open label question, not a
finding: Xiaomi's per-app battery entry, 배터리 세이버 or 배터리 절약 (secondary sources only).

### Follow-up round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 TOOLS/mech_check.py F2/keys2.txt ko` reports "checked 1 locales
x 15 keys; problems: 0", and `python3 scripts/l10n_diff.py --locale
app/src/main/res/values-ko/strings.xml` reports `values-ko missing=0 orphan=0 modified=0`, "in
sync". The file parses as XML; each of the 15 names occurs exactly once; the four removed keys
(`restricted_settings_title` / `_message`, `keep_running_huawei_close_after_lock_title` / `_line`)
are still gone. The only non-Hangul, non-ASCII glyphs in the 15 are “ ” (the addendum, the Huawei
line) and ⋮ (the addendum). **No 🛑 build-breaking issues.**

**Round-1 fixes:**
- `keep_running_oppo_auto_launch_line`, `keep_running_vivo_autostart_line` (⚠️, one fix in two keys):
  applied, byte-identical to the suggestion in both (…PlayTranslate를 중지하거나, 앱이 다시 시작하지
  못하게 할 수 있습니다.). A character diff against the first applied values (`F2/frag/ko.tsv`) shows only
  the inserted ", 앱이" in each. The first sentences are untouched, and the two second sentences are still
  byte-identical. Now that the second clause has its own subject, 중지하거나 can only coordinate at the top
  level: the phone may stop PlayTranslate, or keep the app from starting again, as EN says.
- `keep_running_xiaomi_autostart_line` (💬): applied, byte-identical (…Xiaomi가 앱을 다시 시작하지 못하게
  할 수 있습니다.). The diff shows only the inserted 앱을, the causee of the usual "keep X from" pattern
  (X를 ~지 못하게 하다). It takes 을 where OPPO and vivo take 이, and each is right: this line has no
  coordination for a subject to disambiguate.
- Nothing around the fixes broke. Both spans stay in all three lines, each followed by its right particle
  (에 on the first; 가 or 를 on the second). The other 12 keys are byte-identical to the values round 1
  reviewed. No other ko key moved: `git diff HEAD` shows only the first pass's keys and these 15, and none of
  `F2/keys-extra-f2.txt` changed in ko. Each of the 15 still sits between its English neighbours. The titles
  above the three lines are unchanged (자동 시작 허용, 자동 실행 및 백그라운드 활동 허용, 자동 시작 및
  백그라운드 전력 사용 허용), and 허용하지 않으면 still picks up their 허용. The toast is unchanged and
  re-measures (`TOOLS/Wrap`, 14 sp) at 2 lines at 296 dp, broken at the sentence boundary (269 and 266 dp),
  and 2 lines at 320 dp; it is 538 dp in all.

#### Findings (round 2)

No new findings.

#### Verdict (round 2)

**PASS** (0 ❌, 0 ⚠️, 0 💬). All 15 strings were read again as sets, against the English and its comments:
- The addendum after each of its five hosts. After `accessibility_dialog_message` and
  `overlay_icon_a11y_required_message`, its 스위치 is the 사용 설정 switch their path ends on. After the three
  `a11y_required_*_message` and `keep_running_accessibility_line`, the switch is on the screen that the
  접근성 설정 열기 button or the card opens, as in EN. The steps read in order (한 번 탭한 다음, … 열고,
  … 탭한 후, … 선택하세요).
- Each card, title then line, as Xiaomi, Huawei, OPPO, vivo and Samsung users get them, then the generic
  battery, accessibility and tile cards.
- The stuck alert with and without the Xiaomi paragraph, whose two settings still repeat the two Xiaomi card
  titles.
- The toast, read over App info right after the tap, where 해당 화면 and 이 항목 point back at the card just
  tapped.

Particles: after PlayTranslate (vowel-final), 의, 가, 를 and 에 are right in the 13 keys that carry the name,
with none before 카드. Also right: Android가, Xiaomi가 (샤오미), Samsung은 (삼성), 앱이 / 앱을, “제한된 설정
허용”을, “자동 관리”를, “백그라운드에서 실행”을, 제한 없음으로 / 제한 없음을, 항목(…)을 and 절전 상태로.
Register: 합니다체 statements with ~하세요 steps throughout, and the two changed titles end in 허용 and 설정.
띄어쓰기 re-checked (한 번, 탭한 다음, 열 수 없습니다, 종료된 뒤, 못하게 할 수 있습니다, 며칠 동안).

Labels: AOSP ko 앱 정보, 제한된 설정 허용, 제한 없음 and the `high_power_prompt_title` wording match.
Samsung's official 배터리, 백그라운드 앱 사용 제한, 자동 절전 예외 앱 and 절전 상태 also match; they were not
re-fetched, since two earlier rounds read Samsung Korea's page. Huawei, OPPO and vivo stay plain-Korean
renderings, and each search word repeats its card title's noun.

Xiaomi is still unverified (known issue 2). Two Korean searches today found no Xiaomi Korea page; the only
Korean hit was the yellowit.co.kr article round 1 cited. mi.com/kr's copy of the Background autostart FAQ
(KA-507608) returned 403. So round 1's open question on the per-app battery entry (배터리 세이버 or 배터리
절약) stays open. It is still a label question, not a finding: the line's 배터리 항목, with 배터리 among its
alternatives, leads to the entry under either name.

No round-1 decision is disputed. Samsung은 and Xiaomi가 in Latin letters follow the file's spelling of brand
names (Android, Google Play); 삼성 / 샤오미 would make these two the only transliterated brands.
