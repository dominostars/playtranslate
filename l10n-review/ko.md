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
