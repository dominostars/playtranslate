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
