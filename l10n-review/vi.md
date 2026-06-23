# Vietnamese (values-vi) localization review

Mechanical layer is clean: all placeholders, `<xliff:g>` contents, `<b>` markup, `\n`, `\{ \}`, `&lt;img&gt;`, `&amp;`, and `\"` escapes are intact; no unescaped apostrophes; plurals use `other` only; brand names untranslated. No 🛑 findings.

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| accessibility_dialog_message (term used app-wide: accessibility_service_description, accessibility_dialog_title/_open, status_accessibility_needed, tile_subtitle_a11y_required, btn_open_a11y_settings, overlay_icon_a11y_required_title/_message, a11y_required_alert_title, a11y_required_displays/hotkey_message, enhanced_auto_translate_subtitle_off, restricted_settings_message) | ❌ | "Trợ năng" / "Cài đặt → Trợ năng → Ứng dụng đã cài đặt" | "Hỗ trợ tiếp cận" / "Cài đặt → Hỗ trợ tiếp cận → …" | "Trợ năng" is Apple/iOS's term. Google Android's Vietnamese UI and help docs use "Hỗ trợ tiếp cận" — users following the nav path won't find a "Trợ năng" entry in Settings. Term is at least internally consistent, so it's one global substitution. (Side note: Android's accessibility list section is "Ứng dụng đã tải xuống"; the EN source says "Installed apps", so that mismatch is upstream, not the translator's.) |
| hymt_legal_message | ❌ | "(1) Bạn hiện không cư trú hoặc không ở trong EU…" | "(1) Bạn hiện không cư trú hay đang ở trong EU, Vương quốc Anh hoặc Hàn Quốc." | Double negative "không … hoặc không …" parses as ¬A ∨ ¬B — satisfied if only one is true — weaker than the English warranty "not residing or located" = ¬(A ∨ B). One negation scoping both verbs restores the legal force. Rest of the block is faithful: §5(b) kept, country list complete, "xác nhận và cam đoan" carries "affirm and warrant". |
| onboarding_a11y_title, mp_overlay_permission_title (+ both message bodies, onboarding_a11y_body) | ⚠ | "Hiển thị trên ứng dụng khác" | "Hiển thị trên các ứng dụng khác" | Android's system permission page is named "Hiển thị trên các ứng dụng khác" (with "các"); these dialogs name the setting the user must find. |
| settings_capture_display_footer | ⚠ | "Màn hình mà bạn đang xem PlayTranslate sẽ được bỏ qua." | "Màn hình bạn đang dùng để xem PlayTranslate sẽ được bỏ qua." | Relative clause dropped the "on": current literally reads "the screen that you are viewing PlayTranslate". |
| word_detail_tatoeba_attribution | ⚠ | "Câu từ Tatoeba" | "Câu trích từ Tatoeba" | "câu từ" is itself a word ("wording"), inviting a misparse before reaching "Tatoeba". |
| anki_sort_field_empty | ⚠ | "sẽ gây lỗi từ chối trùng lặp khi gửi" | "sẽ khiến thẻ bị từ chối do trùng lặp khi gửi" | "lỗi từ chối trùng lặp" is a garbled compound; the error is the card being rejected as a duplicate. |
| tts_no_engine_row_subtitle | ⚠ | "công cụ đọc giọng nói" | "công cụ chuyển văn bản thành giọng nói" | Nonstandard coinage ("voice-reading tool"); fix also aligns with the row title and section header terminology. |
| settings_ocr_delete_shared_msg | ⚠ | "nó sẽ tải lại trong lần tiếp theo bạn chuyển sang một trong số đó" | "mô hình sẽ được tải lại vào lần tới bạn chuyển sang một trong các ngôn ngữ đó" | Missing passive "được"; "một trong số đó" has a vague referent. Lead-in "cũng được dùng bởi các ngôn ngữ này" is also a "bởi"-passive calque — "Các ngôn ngữ này cũng dùng %1$s" is more natural. |
| crash_dialog_discard | 💬 | "Hủy bỏ" | "Xóa báo cáo" | Sits next to "Hủy"-style buttons elsewhere; users may read it as plain Cancel, but it permanently deletes the crash report. |
| pack_upgrade_progress_format_with_bytes | 💬 | "%2$s trên %3$s" | "%2$s / %3$s" | Every other byte-progress string (bergamot, qwen, gemma, hymt, install_downloading_with_bytes) uses "/"; lone outlier. |
| word_detail_group_hanzi | 💬 | "Hanzi" | "Chữ Hán" | Vietnamese learners of Chinese near-universally say "chữ Hán"; "Hanzi" is opaque in VN. ("Kanji" is fine — established loanword among JP learners.) |
| overlay_icon_gesture_drag | 💬 | "<b>Kéo</b> trên từ" | "<b>Kéo</b> qua các từ" | "kéo trên từ" is a calque of "drag over words". |
| pt_accent_teal | 💬 | "Mòng két" | "Xanh mòng két" | Bare "Mòng két" is the duck; the color name needs "Xanh". |
| note_mlkit_service_unavailable, settings_cell_translation_services | 💬 | "Dịch vụ dịch" | "Dịch vụ dịch thuật" | Avoids the "dịch dịch" stutter; "dịch thuật" is the standard noun. |
| qwen_mnn_status_verifying (same in qwen35_2b/gemma_e2b/hymt) | 💬 | "Đang xác minh bản tải…" | "Đang xác minh tệp đã tải…" | "bản tải" is a non-word; at least consistent across all four rows. |
| crash_dialog_message | 💬 | "vừa nhận dạng (OCR) hoặc tra cứu gần đây" | "nhận dạng (OCR) hoặc tra cứu gần đây" | "vừa" and "gần đây" both mean "recently" — doubled. |
| live_mode_auto_with_hint | 💬 | "Tự động %1$s" → "Tự động Furigana" | "%1$s tự động" → "Furigana tự động" | Vietnamese modifier order; moving the whole xliff block is permitted. |
| hymt_legal_message | 💬 | "Liên minh Châu Âu" | "Liên minh châu Âu" | Standard orthography: "châu" lowercase in "châu Âu". |

Sections checked and clean: onboarding, word detail, Anki review sheet and all content-source/flag descriptions (Example: samples correctly left untranslated, `\{\{furigana:\}\}` and `&lt;img&gt;` intact), all four MNN model families (fully parallel and consistent), low-memory gate, cooldown lines ("Thử lại lúc" for times vs "Thử lại vào" for dates is exactly right), TTS picker, hotkeys, region picker, debug section, toasts.

## Verdicts

- **Register consistency:** pass — polite "bạn" throughout, "Hãy" imperatives in prompts, no register drift.
- **Terminology consistency:** pass — deck=bộ thẻ, card=thẻ, loại thẻ; gói ngôn ngữ; phím tắt; lớp phủ; chụp màn hình; tải xuống/xóa; mạng có đo lượng dữ liệu — all uniform; only the "trên"-vs-"/" byte-progress outlier.
- **Android-settings wording:** needs work — Accessibility must become "Hỗ trợ tiếp cận" (app-wide) and overlay title needs "các"; Quick Settings ("Cài đặt nhanh", "ô") and metered wording match the system.
- **Diacritics:** pass — full sweep found no missing or wrong tone/vowel diacritics and no syllable-spacing errors.
- **Grammar around placeholders:** pass — all composed strings read naturally with real values ("Cần bộ nhớ 4 GB", "3 bộ thẻ Anki", "Dịch Toàn màn hình", "GIỌNG TIẾNG NHẬT"); classifiers natural.
- **Truncation risk:** pass — "Tự động / Tạm dừng / Cài đặt / Vùng" and "Vùng\nchụp" are all short.
- **Legal text:** structurally faithful (§5(b), EU/UK/South Korea list, "xác nhận và cam đoan") but clause (1)'s double negative weakens the attestation — must fix before ship.
- **Overall:** fix-then-ship — two ❌ items (accessibility term swap, legal clause (1)) plus the ⚠ polish; everything else is a solid, internally consistent native-quality translation.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR set — both `yomitan_import_summary_count` and `yomitan_import_summary_more` collapsed to Vietnamese's single `other`; `processDebugResources` BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
None. All 29 keys are natural, correct, and terminology-consistent. Nothing rises even to 💬.

What was checked, key by key:
- **Anki pitch/frequency (8):** `anki_content_pitch_position` "Vị trí trọng âm cao độ" reuses the same compound as `yomitan_category_pitch_accent`; `_desc` correctly uses "giáng" for the downstep and keeps the "PitchPosition"/"PAOverride" field names + the `0,2` example verbatim. The four `frequency_*` keys reuse "tần suất" uniformly ("Danh sách tần suất", "Số sắp xếp theo tần suất"); `_harmonic_desc` renders harmonic mean as "trung bình điều hòa" (correct) with a natural "(càng thấp = càng thường gặp)" gloss; ★/"Frequency"/"FrequenciesStylized"/"FreqSort"/"FrequencySort" field names all left as-is per the Example:/quoted-field rule.
- **OpenAI base URL (3):** `llm_backend_advanced_header` "Nâng cao" matches existing "nâng cao" usage; `llm_backend_base_url_label` "URL tùy chỉnh" has correct adjective-after-noun order; `llm_backend_base_url_invalid` preserves `https://`/`http://` and the em-dash, and renders "local or LAN address" idiomatically as "địa chỉ cục bộ hoặc trong mạng LAN".
- **Yomitan import + auto-update:** all summary lines reuse the file's established verbs/terms ("Đã nhập rồi" = `yomitan_duplicate_title`; "Không thể đọc" = the verb in `yomitan_invalid_message`; "Không đủ dung lượng" = `yomitan_no_space_title`); `từ điển`/`tệp` classifiers natural; `yomitan_auto_update_subtitle` faithful with consistent "tải xuống"/"cài đặt".
- **Audio picker (6):** `audio_source_tts_name` "Chuyển văn bản thành giọng nói" matches `settings_header_text_to_speech` / `settings_cell_tts` exactly (the old "công cụ đọc giọng nói" coinage the full review flagged is now gone — `tts_no_engine_*` reads "công cụ chuyển văn bản thành giọng nói"); `audio_no_results` = `lang_search_no_results`; `audio_source_picker_title` "Âm thanh" = `anki_group_audio`; `audio_loading`/`audio_error_loading` follow the standard "Đang tải…" / "Không thể tải" pattern.

## Clean areas (delta)
- **Diacritics / tone marks:** full sweep of all 29 strings — every syllable carries its proper tone/vowel diacritic (trọng âm cao độ, giáng, điều hòa, tần suất, tệp, dung lượng); no stripped-ASCII syllable, no wrong-tone syllable.
- **Syllable spacing:** each syllable space-separated; no merged/run-together syllables in any new string.
- **"of"/count vs byte-progress separator:** correctly disambiguated — item counts use "X trên Y" (`yomitan_importing_progress` "Đang nhập 2 trên 5", `yomitan_import_summary_count` "4 trên 6 từ điển"), while byte progress everywhere uses "/". This is the natural Vietnamese split ("2 out of 5" vs "12 MB / 84 MB"), not the lone outlier the prior review noted (which has since been fixed — `pack_upgrade_progress_format_with_bytes` now uses "/").
- **Classifiers in collapsed plurals:** `yomitan_import_summary_count` keeps "từ điển"; `yomitan_import_summary_more` "+%1$d tệp nữa" adds the natural "tệp" classifier (the elided items are file names) — both read correctly in the single `other` form.
- **Terminology reuse:** tần suất, trọng âm cao độ, từ điển, nhập (import), tải xuống/cài đặt, âm thanh, and Chuyển văn bản thành giọng nói are all consistent with the rest of the file and with each other.
- **Register:** polite **bạn**-level throughout (no second-person needed in these mostly-label strings; "Hãy …" imperatives where present in neighbors are consistent); no register drift.
- **Short-label truncation:** "Nâng cao", "URL tùy chỉnh", "Âm thanh", "Đang tải…", "Không thể tải", "Tự động cập nhật", "Tệp không xác định" are all short — no truncation risk.
- **Example:/quoted-field rule:** honored — Anki `*_desc` output samples (`0,2`, `★`, `★★★`) and Lapis/JPMN field names left untranslated.
