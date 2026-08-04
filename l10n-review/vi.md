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

---

# Delta review — 2026-07-14 sync (174 keys)
Scope: the 170 newly-translated + 4 changed-English keys (History screen, Advanced LLM prompt editor, in-app updater, game-audio trim, translation-service page, MangaOCR toggle, stream-scope prompt, floating-menu panel, the 38 `misc_*` register tags).

**Mechanical layer verified programmatically across all 174 keys:** every `%n$s`/`%d` placeholder present and matching; every `<xliff:g>` span byte-identical to EN (inner content, `id`, `example`); the bare literal keywords `{text} {source} {source_code} {target} {target_code} {context} {N} {strings}` reproduced byte-for-byte in Latin in running prose (`llm_prompt_fatal_missing_text/_strings`, `llm_prompt_advisory_missing_source/_target/_count`); `\n` preserved (1 in `floating_menu_capture_screen`); `<b>`/`&lt;&gt;&amp;`/`\{ \}` counts match; no unescaped `'` or `"`; `name=` untouched; `settings_yomitan_count_summary` collapsed to Vietnamese's single CLDR `other` with **no invented `one`**; file is NFC-normalized with no stray combining marks; `:app:processDebugResources` BUILD SUCCESSFUL. **No 🛑.**

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| llm_prompt_advisory_foreign_token | ❌ | "Câu lệnh này không điền giá trị cho {text}, nên **nó** sẽ được gửi đi nguyên văn." | "Câu lệnh này không điền giá trị cho {text}, nên **từ khóa này** sẽ được gửi đi nguyên văn." | The whole job of this line is to say *which* thing goes to the model as literal text — the keyword. But "nó" has two candidate antecedents ("Câu lệnh này" and "{text}"), and subject-continuity makes the **wrong** one (the prompt) the preferred parse: "so *it* [the prompt] will be sent verbatim." Naming the referent kills the ambiguity, and "từ khóa" is already the file's word for keyword (`llm_prompt_keywords_header` = "Từ khóa"). |
| llm_prompt_kw_count_desc | ⚠️ | "Số cụm từ trong **lô dịch**" | "Số cụm từ trong **loạt dịch**" | Glossary: one English term → one translation. "Batch" is rendered two ways inside the same feature — `llm_prompt_row_batch_title` = "Câu lệnh **hàng loạt**", this key = "**lô** dịch". These are the file's only two "batch" strings and they disagree; the legend sits directly under the editor whose title says "hàng loạt". |
| floating_menu_capture_screen | ⚠️ | "Chụp\nmàn hình" | "Chụp\nmàn hình" (non-breaking space) | Vietnamese is **the only one of 12 locales** whose line 2 is two words. `FloatingIconMenu.fitLabel()` shrinks the label until the widest *unbreakable run* fits `labelColumnPx` (66dp) — but `BreakIterator` splits "màn hình" into two runs, so the guard never measures the string that actually has to fit line 2 as a unit. At default scale it fits (~47dp of 66dp at 11sp) and renders fine; at fontScale ≳1.4 "màn hình" overflows, wraps to a 3rd line, and `maxLines=2` (no ellipsize) silently **drops "hình"**. A NBSP makes it one run, so `fitLabel` shrinks it correctly. Zero visual change at default scale. |
| misc_historical | ⚠️ | "Lịch sử" | "Từ lịch sử" | Bare noun = "History" — reads as a *topic/domain* label (the same italic " · " line also carries pass-through domain tags), not as the usage label "the word denotes a thing of the past". "Từ lịch sử" reuses the file's own "Từ X" pattern (Từ cổ, Từ mới, Từ nam/nữ giới) and marks it as a word class. |
| misc_childrens | ⚠️ | "Trẻ em" | "Từ trẻ em" | Same bare-noun problem ("Children"), and it's the odd one out among the speaker-group tags: `misc_female_speech` = "**Từ** nữ giới", `misc_male_speech` = "**Từ** nam giới". "Từ trẻ em" restores the pattern. |
| misc_slur | 💬 | "Lăng mạ" | "Từ lăng mạ" | "Lăng mạ" names the *act* of reviling; a slur is a *word class*. The "Từ X" prefix also widens the gap from its neighbour `misc_offensive` = "Xúc phạm". (Cluster is already distinguishable — this is polish.) |
| misc_dated | 💬 | "Lỗi thời" | "Từ cũ" | Vietnamese lexicography's obsolescence pair is **cổ** (archaic) / **cũ** (dated), and `misc_archaic` already uses "Từ cổ". "Lỗi thời" ("outmoded") leans toward *obsolete*, crowding `misc_obsolete`. Trade-off: "Từ cổ"/"Từ cũ" differ by one diacritic, so keep only if that reads cleanly at 12.5sp italic. |
| misc_informal, misc_obsolete | 💬 | "Không trang trọng", "Không còn dùng" | (keep — see note) | The only two labels phrased as English **glosses** ("not formal", "no longer used") rather than Vietnamese register terms, and the two longest in the set. But there is no better short native term: "thân mật" is taken by `misc_familiar`, and "suồng sã" is pejorative. Recommend **keeping** — accuracy beats brevity here (they wrap, they do not truncate). Logged so the next reviewer doesn't re-litigate. |
| misc_humble | 💬 | "Khiêm nhường" | "Khiêm ngữ" | Its cluster sibling is "Kính ngữ" (sonkeigo); "Khiêm nhường" drops the ‑ngữ marker and reads as the character trait "modest" rather than a speech register. Optional — the current form is understood. |
| update_dialog_download | 💬 | "Tải và cài đặt" | "Tải xuống và cài đặt" | Objectless download verb: the file's standalone form is "Tải xuống" (`lang_download`, 20 hits). Bare "Tải" is fine *with* an object ("Tải Google TTS", "Tải mô hình") but this one has none. Not wrong, just the lone objectless "Tải". |
| llm_prompt_discard_title, llm_prompt_discard_confirm | 💬 | "Bỏ **các** thay đổi?" / "Bỏ thay đổi" | "Bỏ thay đổi?" / "Bỏ thay đổi" | Title and its own confirm button disagree on "các". Drop it from the title so the button echoes it exactly. |
| update_unknown_sources_button | 💬 | "Mở cài đặt" | "Mở Cài đặt" | The file already splits 4–2 on this: "Mở **C**ài đặt" (`btn_open_overlay_settings`, `btn_open_app_settings`, `btn_open_a11y_settings`, `accessibility_dialog_open`) vs "Mở **c**ài đặt" (`mp_overlay_permission_button`, `tts_no_engine_open_settings`). Cài đặt is the Settings *app*; side with the majority. Pre-existing split, so low priority. |
| llm_prompt_warning_title | 💬 | "Hãy kiểm tra câu lệnh này" | "Kiểm tra lại câu lệnh này" | "Hãy" is an exhortation particle — verbose for a bold dialog **title**, and its sibling title `llm_prompt_invalid_title` ("Không thể lưu câu lệnh này") is a plain statement. |

## Truncation — measured, not guessed

All four surfaces the brief flagged were checked against the actual render code. Three cannot truncate:

- **`service_llm_badge`** = "LLM" — unchanged from English, `wrap_content` TextView in all three layouts. No risk.
- **`probe_initializing`** = "Đang khởi tạo…" — `StreamKindProbe.ProbeView.onMeasure()` **measures the localized string** and sizes the window to it (`SIZE_PX + labelW`). Self-fitting by construction; the code comment says as much.
- **The 38 `misc_*` tags** — **not chips.** `WordDefinitionsView.buildMiscRow()` builds a `MATCH_PARENT` / `WRAP_CONTENT` italic TextView at 12.5sp with **no `maxLines` and no ellipsize**, joined with " · " by `renderMiscText`. They **wrap, they never clip**. Length is therefore a line-economy question (a long label pushes the register line to a second line under a compact gloss), not a truncation one — which is why `misc_informal` / `misc_obsolete` above are a "keep", not a "shorten". Also verified: all 38 Vietnamese labels are **distinct**, so `renderMisc(...).distinct()` cannot silently swallow one of a pair.
- **`floating_menu_capture_screen`** is the only genuine risk — see the ⚠️ row above.

## Clean areas (checked, no findings)

- **`settings_yomitan_count_summary`** — `other` only, no invented `one`. "Đã nhập %d từ điển" reads correctly at every count (1, 2, 5, 11) and reuses the classifier-less "từ điển" the committed `yomitan_import_summary_count` already established.
- **Classifiers / grammar around placeholders** — every `<xliff:g>` sentence read with a real value dropped in: "Gỡ **OpenAI**?", "Giữ lại mô hình đã tải (**68 MB**) hay xóa nó…", "Không đủ dung lượng trống để tải bản cập nhật (cần **230 MB**)", "Dung lượng tải xuống: **128 MB**", "Hôm nay: **12.345** token" (invariant — correct, Vietnamese has no plural marking), "Đã chọn **2,4** giây · Đã ghi **147** giây", "Nhận dạng bởi **PaddleOCR**", "**Furigana** tự động", "Đang cập nhật **PlayTranslate**", "Khóa ••••**4f2a**". All natural; no missing or wrong classifier anywhere.
- **Terminology vs the committed file** — every glossary term checked against what is already shipped: **prompt** = "câu lệnh" in all 11 `llm_prompt_*` strings and *nowhere else* in the file (no collision with a CLI sense); **keyword** = "Từ khóa"; **Provider** = "Nhà cung cấp" (matches the committed `tr_service_order_footer`); **Translation service** = "dịch vụ dịch thuật" (matches the committed page title `settings_cell_translation_services` exactly — the earlier "Dịch vụ dịch" fix has landed); **Remove vs Delete** kept apart exactly as English does — services are *gỡ* (`tr_service_remove_*`, `tr_service_delete_cd`), entries/models are *xóa* (`history_action_delete`, `settings_ocr_disable_delete`), and `tr_service_remove_message` carries both in one sentence ("sẽ **gỡ** dịch vụ khỏi danh sách và **xóa** khóa API"); **Clear** = "Xóa toàn bộ lịch sử" (scope-distinct from "Xóa mục này?"); **History** = "Lịch sử" (`settings_cell_history` = `history_screen_title`); **Trim** = "Cắt" + "đoạn đã chọn" for the selection; **Game audio** = "Âm thanh trò chơi" (works as pill *and* section header); **LLM** kept as the initialism; **metered** = "…có đo lượng dữ liệu" (head noun correctly "kết nối", matching English's "metered connection"); **overlay** = "Lớp phủ"; **captured** = "chụp" throughout the History strings, reusing the established screen-capture verb.
- **`ocr_source_label`** — "Nhận dạng bởi %1$s" is a structural mirror of the committed `translation_source_label` "Dịch bởi %1$s", exactly as the glossary requires.
- **Register** — polite **bạn** throughout (`stream_kind_prompt_title`, `update_dialog_metered_note`, `llm_prompt_discard_message`, `audio_source_game_ready`, `llm_backend_base_url_custom_hint`, `llm_prompt_row_translation_subtitle`); no drift. **Sibling buttons are grammatically parallel** in every dialog: Giữ mô hình / Xóa mô hình; Dùng đoạn đã chọn / Phát đoạn đã chọn; Chia sẻ một ứng dụng / Chia sẻ toàn bộ màn hình; Vẫn lưu / Bỏ thay đổi / Đặt lại; Thử lại / Tải và cài đặt / Xem ghi chú phát hành / Mở cài đặt — all verb-first imperatives.
- **Mid-sentence capitals** (`hotkey_show_translations_title` "Giữ để hiện **B**ản dịch", `hotkey_auto_translation_title` "Nhấn để bật/tắt **T**ự động dịch") are **not** a finding: the committed `status_hold_hint` ("Nhấn giữ **V**ùng hoặc **T**ự động…") already establishes that on-screen button/section labels are capitalized when named in prose.
- **`hotkey_auto_hint_dialog_title`** = "%1$s tự động" — correct Vietnamese modifier order and byte-consistent with the committed `live_mode_auto_with_hint`, which now carries the same fix.
- **Diacritics** — full Unicode-aware sweep of all 174 strings: every ASCII-only token is a genuinely diacritic-free Vietnamese syllable (chia, cho, cung, dung, ghi, khi, sao, tra, trong, thanh, nhau, quanh, minh, bao, hay, nay, sang, sau…) or an allow-listed brand/technical term. **No stripped or mistyped diacritic; no merged syllables.** File is NFC.
- **Deliberate decisions honoured** — `stream_kind_share_one_app` / `_entire_screen` match Android VN's own consent wording ("Chia sẻ một ứng dụng" / "Chia sẻ toàn bộ màn hình"); `llm_prompt_kw_source_desc` / `_target_desc` correctly keep "Japanese" / "English" in Latin as the literal runtime expansions; `llm_status_low_memory_badge` left untouched, its em dash exempt.

## Verdict

**Ship after the ❌ and the four ⚠️.** This is a strong, natural, internally-consistent delta — the glossary was clearly worked from the committed file rather than from English, and the *gỡ*/*xóa* split, the `ocr_source_label` mirror, the `%1$s tự động` order and the `other`-only plural all landed correctly. The one substantive defect is the ambiguous pronoun in `llm_prompt_advisory_foreign_token`; the rest is polish plus one latent large-font clipping bug in the two-line capture button.

---

# Delta review round 2 — 2026-07-14
Fresh independent re-derivation of the 174 delta keys after round 1's 13 corrections. Primary target: regressions introduced by those fixes.

**Mechanical layer re-verified after the edits.** The one edit that could plausibly have broken the build or the string was `floating_menu_capture_screen`, so it was checked at the *compiled* level rather than by eye: `aapt2 compile` (build-tools 36.1.0) of `values-vi/strings.xml` emits the byte sequence `43 68 e1 bb a5 70 | 0a | 6d c3 a0 6e | c2 a0 | 68 c3 ac 6e 68` — i.e. `Chụp` + **LF** + `màn` + **U+00A0** + `hình`. The `\n` survived as a real newline, `&#160;` resolved to a genuine NBSP, the NBSP is **interior** (not leading/trailing), and it was neither downgraded to a plain space nor dropped by AAPT2's whitespace collapsing. All other placeholders, `<xliff:g>` spans, literal `{keyword}` tokens, `\{ \}`, `&lt;&gt;&amp;` and quote escapes are unchanged from round 1's verification. `settings_yomitan_count_summary` is still `other`-only. **No 🛑.**

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| game_audio_trim_use_tts, game_audio_trim_no_audio, game_audio_trim_save (3 keys, one fix) | ❌ | "Dùng TTS thay thế" / "Không có âm thanh" / "Dùng đoạn đã chọn" — **row = 487.8 dp** | "Dùng TTS" / "Bỏ âm thanh" / "Dùng đoạn này" — **row = 363.2 dp** | The trim editor's bottom action bar overflows every phone and clips the **primary confirm** off the right edge. Measured, not guessed — see "Trim-row width" below. The weighted `Space` in the row cannot rescue it (LinearLayout clamps a negative share to 0), and the row has no ellipsize, no `maxLines`, no scroll and no orientation lock. **Root cause is the layout, not the locale** — English itself needs 377.7 dp and already clips on a 360 dp phone. The shortening above is the locale-side mitigation: it buys back 124.6 dp and makes the row fit the 393–412 dp band that most phones sit in. It still cannot fit 360 dp, because nothing can. `"Dùng TTS"` also hits the 88 dp `minWidth` floor, so it costs nothing — exactly the width exception pt-BR already took with `"Usar TTS"`. (If the literal is preferred over `"Bỏ âm thanh"`, `"Không âm thanh"` gives a 387.0 dp row — still fits 393 dp+.) |
| error_capture_blocked_secure | ⚠️ | "Không đọc được gì — ứng dụng **đang được chụp chặn** việc **chụp** màn hình." | "Không đọc được gì — ứng dụng này chặn việc chụp màn hình." | **Round-1 regression.** The fix imported `ứng dụng đang được chụp` from its sibling `error_single_app_not_fullscreen` for consistency — a good instinct that misfires here. In the sibling the participle is followed by a **negator** (`… đang được chụp **không** hiển thị…`), which cleanly opens the predicate. Here it is followed by another **verb** (`chặn`), and Vietnamese has no relativiser: the reader parses `ứng dụng đang được chụp` as a complete clause ("the app is being captured"), then hits `chặn` with no subject and has to reanalyse. It also puts **chụp** twice in nine words. English says "**this** app", not "the captured app" — the referent is already established, so the participle buys nothing and costs a garden path. |
| llm_prompt_kw_count_desc | ⚠️ | "Số cụm từ trong **loạt dịch**" | "Số cụm từ trong **lô dịch**" (revert) | **Round-1 regression.** The consistency premise was false. "Batch" is not in the glossary's one-term-one-translation table, and the two sites are different parts of speech: `llm_prompt_row_batch_title` uses **hàng loạt**, a fixed *adverbial* compound ("en masse") that cannot be decomposed or nominalised, while this legend line needs a **noun** for "the batch". Forcing the adverb into the noun slot produced "loạt dịch", a coinage — bare `loạt` needs a determiner (`một loạt`, `hàng loạt`, `loạt này`), and `dịch` on its own is a live homonym (translate / epidemic), so "loạt dịch" briefly reads as "a wave of epidemics". `lô` is the standard Vietnamese computing noun for a batch (`xử lý theo lô`, `kích thước lô`), and the legend already sits under the "Câu lệnh hàng loạt" editor, so context does the disambiguating. If the glossary echo really is wanted, `"Số cụm từ trong một lần dịch hàng loạt"` keeps **hàng loạt** intact and is grammatical — but it is long for a legend line. |
| llm_prompt_advisory_missing_source | 💬 | "…mô hình sẽ không biết **nó** đang dịch từ ngôn ngữ nào." | "…mô hình sẽ không biết đang dịch từ ngôn ngữ nào." | Its sibling `llm_prompt_advisory_missing_target` — which renders in the **same dialog, directly adjacent** — has no `nó`: "mô hình sẽ không biết cần dịch sang ngôn ngữ nào." One line carries the redundant pronoun and the other doesn't. Dropping it makes the pair parallel. (Not ambiguous — `nó` can only be `mô hình` — just asymmetric. Missed by round 1, which was looking at `nó` in the neighbouring `foreign_token` line.) |
| misc_humble | 💬 | "Khiêm ngữ" | "Khiêm nhường ngữ" (optional) | Round 1's fix is *understood* — beside `misc_honorific` = "Kính ngữ" the parallel is transparent Sino-Vietnamese (kính/khiêm + ngữ) and it correctly marks a speech register rather than the character trait "Khiêm nhường" did. But "khiêm ngữ" is a coinage: the attested Vietnamese rendering of 謙譲語 is **khiêm nhường ngữ** (the standard set is tôn kính ngữ / khiêm nhường ngữ / thể lịch sự). Since these labels wrap rather than truncate, the longer attested form costs nothing. Keeping "Khiêm ngữ" is defensible; logged so it isn't re-litigated a third time. |
| misc_idiomatic + misc_yojijukugo | 💬 | "Thành ngữ" · "Thành ngữ bốn chữ" | (keep) | JMdict tags a four-character compound with **both** `id` and `yoji`, so these two co-render on the same sense as "Thành ngữ · Thành ngữ bốn chữ" — a stutter English doesn't have ("Idiomatic · Four-character compound"). They are distinct strings, so `.distinct()` does not collapse them and nothing is lost. "Thành ngữ bốn chữ" is the correct Vietnamese gloss and there is no shorter native term. **Keep** — logged only so the next reviewer doesn't "fix" it into a collision. |

## Trim-row width — measured

Asked for explicitly. Measured against the **device's own font**: `/system/fonts/Roboto-Regular.ttf` pulled from the connected handset (variable Roboto, upem 2048, `wght` 100–900), instanced at **wght = 500** — that is what `sans-serif-medium` resolves to, and `Widget.Material3.Button`'s `textAppearance` is `?attr/textAppearanceLabelLarge` = **14 sp, sans-serif-medium, letterSpacing 0.00714286 em**. Geometry from the Material 1.14 AAR: TextButton horizontal padding **12 + 12 dp** (`m3_btn_text_btn_padding_*`), filled Button **24 + 24 dp** (`m3_btn_padding_*`), `android:minWidth` **88 dp** inherited from `Base.Widget.AppCompat.Button`, horizontal insets 0. Kerning ignored (< 0.5 % on these strings).

| button | style | text | button width |
|---|---|---|---|
| `game_audio_trim_use_tts` | TextButton | "Dùng TTS thay thế" — 117.1 dp | **141.1 dp** |
| `game_audio_trim_no_audio` | TextButton (+4 dp margin) | "Không có âm thanh" — 123.0 dp | **147.0 dp** |
| `game_audio_trim_save` | filled M3 Button | "Dùng đoạn đã chọn" — 123.7 dp | **171.7 dp** |

**Row = 12 + 141.1 + 4 + 147.0 + 0 + 171.7 + 12 = 487.8 dp.**

The `<Space android:layout_width="0dp" android:layout_weight="1"/>` between `no_audio` and `save` does **not** absorb the overflow: `LinearLayout.measureHorizontal` gives a weighted child `share = childExtra * delta / weightSum`, which is *negative* here, and the spec is built with `Math.max(0, childWidth)` — so the Space collapses to 0 and stops. The three buttons keep their full measured widths (`btnTrimSave` is measured with `AT_MOST(screen − 24 dp)` because a weight was seen before it, so it takes its natural width and never wraps), the row's own width stays pinned at the screen width by the `EXACTLY` spec, and `layoutHorizontal` starts at `paddingLeft` (gravity is `center_vertical` only → horizontal START). **The overflow therefore falls off the right edge, taking the primary confirm with it.**

`game_audio_trim_save` is laid out at **x = 304.1 dp** and spans to 475.8 dp:

| screen | row overflow | `game_audio_trim_save` visible | reachable? |
|---|---|---|---|
| **360 dp** | **over by 127.8 dp** | **55.9 / 171.7 dp — 33 %** | **No, in any usable sense.** The label is centred at 328.1–451.8 dp, so only its first ~2 characters ("Dù…") are on screen. The 56 dp sliver is *technically* still touch-dispatchable — it lies inside the parent's bounds — but the user sees a filled button sheared in half with a truncated word and no confirm affordance. |
| **411 dp** | **over by 76.8 dp** | **106.9 / 171.7 dp — 62 %** | **Cut, though pressable.** Roughly "Dùng đoạn đ" is visible; the button's right edge and the end of the label are off-screen. |

Two aggravations: the activity has **no `screenOrientation` and no width-qualified layout**, and `GameAudioTrimActivity` pads `android.R.id.content` by the system-bar/cutout insets, so a landscape cutout subtracts further. Any `fontScale` above 1.0 also scales the text but not the paddings, widening the gap.

**Not a Vietnamese-only bug.** English is 377.7 dp and already overflows a 360 dp phone by 17.7 dp; pt-BR (337.4 dp) fits only because it took the "Usar TTS" exception. The three-button floor is 3 × 88 dp + 28 dp = 292 dp, which leaves ~68 dp of total text budget to reach 360 dp — so **no Vietnamese wording that stays intelligible can fit a 360 dp screen** (the most aggressive candidate, "Dùng TTS" / "Không âm thanh" / "Dùng đoạn", is still 360.9 dp). The row needs a layout fix — weights + `maxLines="1"` + `ellipsize` on the two text buttons, or a vertical stack, or the confirm on its own full-width row. The string shortening in the ❌ row above is the mitigation that makes it survive the common 393–412 dp band in the meantime.

## The NBSP edit — verified end to end

`floating_menu_capture_screen` = `Chụp\nmàn&#160;hình` is **correct and introduced no regression**. Four things had to hold and all four do:

1. **The `\n` survived.** Compiled bytes show a real `0x0A` between `Chụp` and `màn` (AAPT2 processes the `\n` escape into a newline *before* whitespace collapsing, so it is never eaten). The committed sibling `floating_menu_btn_capture_region` (`Vùng\nchụp`) compiles identically.
2. **The NBSP is interior, and that is load-bearing.** `FloatingIconMenu.unbreakableRuns()` does `text.substring(start, end).trim()`, and Kotlin's `String.trim()` uses `Char.isWhitespace()` = `Character.isWhitespace() || Character.isSpaceChar()` — U+00A0 is `Zs`, so `isSpaceChar` is **true** and a *leading or trailing* NBSP would have been silently stripped. Between `màn` and `hình` it is untouched.
3. **`BreakIterator` now sees one run.** U+00A0 is UAX #14 class **GL** (glue): no break before or after. `BreakIterator.getLineInstance(vi)` yields exactly `["Chụp", "màn hình"]`, so `fitLabel`'s `runs.maxOf { paint.measureText(it) }` finally measures the string that actually has to fit line 2 as a unit. That was the whole point of the edit and it lands.
4. **Zero visual change at default scale.** Measured in the label's real paint (Roboto **Bold** — `captureLabel` calls `setTypeface(null, Typeface.BOLD)`, which round 1's estimate did not account for): `màn hình` = 4.164 em → **45.8 dp at 11 sp**, against a 66 dp column (78 dp button − 2 × 6 dp side padding). Comfortable. `Chụp` = 25.7 dp.

**Residual (code, not locale — no action for the translator).** `fitLabel` shrinks 11 sp → 8.5 sp (`labelMinSp`), at which `màn hình` is 35.4 dp. The run therefore fits until **fontScale ≈ 1.86** (up from ≈ 1.44 without the NBSP — the edit buys a real ~0.4 of headroom). Android 14+ allows fontScale up to 2.0, and beyond ~1.86 the TextView can no longer break the glued run at a word boundary, so it makes a desperate mid-word break and `maxLines = 2` drops the tail ("Chụp / màn hì"). This is the `labelMinSp` floor, not the string: `Chụp màn hình` on one line is 74.6 dp at 11 sp and cannot fit 66 dp either, so two lines is forced and there is no shorter Vietnamese for "Capture screen". Lowering `labelMinSp` to ~8.0 sp would close the last of it.

## The `misc_*` set after round 1 — re-derived

Checked programmatically, not by eye. **All 38 labels are distinct strings**, so `renderMisc(...).distinct()` cannot silently swallow one of a pair. **No label contains `·`, `・`, `/` or `|`**, so none splits into two tags under `renderMiscText`'s `" · "` join. **No collision with the sibling `pos_*` family** (checked all 26) or with `yomitan_category_*`.

The four clusters the parameters doc requires to stay distinguishable all survive the edits, and each is now internally coherent:

- **offensiveness** — Miệt thị / Xúc phạm / Thô tục / **Từ lăng mạ**. Distinct, and the new `Từ` prefix on `misc_slur` widens the gap from `misc_offensive` = "Xúc phạm" without colliding with `misc_derogatory` = "Miệt thị" (which is what the *natural* word for "slur", `từ miệt thị`, would have done).
- **obsolescence** — **Từ cổ** / Không còn dùng / **Từ cũ** / **Từ lịch sử**. `cổ` (archaic) vs `cũ` (dated) is the authentic pair from Vietnamese lexicographic practice (Hoàng Phê's *Từ điển tiếng Việt* uses exactly these two labels), so round 1's `Lỗi thời` → `Từ cũ` is right and no longer crowds `misc_obsolete`. The two differ by base vowel *and* diacritic (u + tilde vs o + circumflex + hook), which reads cleanly even at 12.5 sp italic.
- **informality** — Khẩu ngữ / Không trang trọng / Thân mật / Tiếng lóng. Four distinct terms; round 1's decision to **keep** the two gloss-style labels stands (they wrap, they do not truncate).
- **honorifics** — Kính ngữ / Khiêm ngữ / Lịch sự. Distinct; see the 💬 above.

Nine labels now begin with **Từ** (cổ, cũ, mới, lịch sử, trẻ em, nam giới, nữ giới, lăng mạ, tượng thanh). That is a lot, but it is the correct Vietnamese lexicographic pattern (`từ cổ`, `từ lóng`, `từ địa phương`, `từ Hán Việt`), all nine are distinct, and the `Từ + noun` frame forces the "word" reading of `từ` over the preposition "from". `misc_childrens` = "Từ trẻ em" now matches `misc_male_speech`/`misc_female_speech` exactly as it should.

One cross-family duplicate exists and is **not** a defect: `misc_polite` = `inflection_polite` = "Lịch sự". They render in different rows through different joins (`" · "` vs `", "`), never in the same list, so `.distinct()` never sees them together — and "polite" genuinely is the right word for both a polite *word* and a polite *inflection*. `inflection_*` is committed and out of scope anyway.

## Clean areas (checked, no findings)

- **The other ten round-1 fixes are all correct and introduced nothing.** `llm_prompt_advisory_foreign_token`'s "từ khóa này" kills the wrong-antecedent parse and reuses the file's own word for keyword (`llm_prompt_keywords_header` = "Từ khóa"); `misc_historical`/`misc_childrens`/`misc_slur`/`misc_dated` are covered above; `llm_prompt_discard_title` "Bỏ thay đổi?" now echoes its own confirm button `llm_prompt_discard_confirm` "Bỏ thay đổi" exactly; `update_dialog_download` "Tải xuống và cài đặt" restores the file's standalone download verb (`lang_download`, 20 hits) and is safe on width — `OverlayAlert.addButton` stacks buttons in a **vertical** LinearLayout, one full-width row each; `update_unknown_sources_button` "Mở Cài đặt" moves to the majority (now 5 capital-C vs 1 lowercase, and the lone survivor `mp_overlay_permission_button` is committed/out of scope — `tts_no_engine_open_settings` = "Mở cài đặt TTS" is correctly lowercase, since that names TTS settings, not the Settings app); `llm_prompt_warning_title` "Kiểm tra lại câu lệnh này" drops the verbose exhortative `Hãy` and now parallels its sibling title `llm_prompt_invalid_title` "Không thể lưu câu lệnh này" as a plain statement.
- **`hotkey_auto_translation_dialog_title`** = "Tự động dịch" is **byte-identical** to the committed `live_mode_auto_translate_label` (line 1033) and `settings_header_auto_translate` (line 1301), and `enhanced_auto_translate_title` builds on it ("Tự động dịch nâng cao"). The apparent asymmetry with `hotkey_auto_hint_dialog_title` = "%1$s tự động" is not a defect — the hint label is a *noun* taking a post-posed modifier ("Furigana tự động"), the translate one is an *adverb + verb* ("automatically translate"). Both orders are correct Vietnamese and both match what is already shipped.
- **`game_audio_trim_duration`** — the other string in the trim editor with a width question. `tvTrimDuration` uses `Text.PT.RowTitle` (15 sp, sans-serif-medium) inside the 48 dp-inset body: "Đã chọn 2.4 giây · Đã ghi 147 giây" = **226.3 dp** against 264 dp available at 360 dp. Fits, and the style sets no `maxLines`/`ellipsize`, so it would wrap rather than clip at large font scales. `game_audio_trim_play` ("Phát đoạn đã chọn", OutlinedButton, centred, ~173 dp against 264 dp) also fits. Only the bottom action bar is broken.
- **Grammar around placeholders**, re-read with real values: "Gỡ **OpenAI**?", "Giữ lại mô hình đã tải (**68 MB**) hay xóa nó…", "Không đủ dung lượng trống để tải bản cập nhật (cần **230 MB**)", "Hôm nay: **12.345** token", "Khóa ••••**4f2a**", "Nhận dạng bởi **PaddleOCR**", "Nhấn để bật/tắt **Furigana** tự động", "Đang cập nhật **PlayTranslate**", "Đã nhập **3** từ điển". All natural; classifiers correct; no case/agreement contact points to break.
- **Register** — polite **bạn** throughout the delta, no drift; sibling buttons stay grammatically parallel in every dialog (Giữ mô hình / Xóa mô hình; Phát đoạn đã chọn / Dùng đoạn đã chọn; Chia sẻ một ứng dụng / Chia sẻ toàn bộ màn hình; Vẫn lưu / Bỏ thay đổi / Đặt lại).
- **Terminology** re-checked against the committed file after the edits: prompt = câu lệnh (11 keys, no collision elsewhere); keyword = Từ khóa; Provider = Nhà cung cấp; translation service = dịch vụ dịch thuật; the *gỡ* (remove a service) / *xóa* (delete an entry or model) split holds, including inside `tr_service_remove_message` which carries both; Clear = "Xóa toàn bộ lịch sử" vs delete-one = "Xóa mục này?"; captured = chụp; overlay = Lớp phủ; metered = kết nối có đo lượng dữ liệu; LLM kept as the initialism; `ocr_source_label` "Nhận dạng bởi %1$s" still mirrors the committed `translation_source_label` "Dịch bởi %1$s".
- **Settled decisions honoured** — AOSP share-button wording, the Latin "Japanese"/"English" in `llm_prompt_kw_source_desc`/`_target_desc`, `llm_status_low_memory_badge`'s dash, and the `misc_*` no-truncation rule were all left alone.
- **Known code defects, not locale bugs** (reported separately, mentioned once as required): `game_audio_trim_duration` will render "Đã chọn **2.4** giây" rather than "2,4" because `GameAudioTrimActivity` formats seconds with `Locale.US`; `update_dialog_size_note` / `update_error_no_space` will show "128 MB" rather than a localized unit because `humanSize()` hardcodes English units. The Vietnamese strings themselves are correct.

## Verdict

**FIX FIRST.** One ❌ — the trim editor's action row overflows every phone width and shears the primary confirm `game_audio_trim_save` off the right edge (33 % visible at 360 dp, 62 % at 411 dp); the layout is the root cause but Vietnamese is the locale that makes it unusable, and the three-string shortening above is needed regardless. Then the two ⚠️ round-1 regressions: `error_capture_blocked_secure` (garden path + "chụp…chụp" stutter) and `llm_prompt_kw_count_desc` ("loạt dịch" coinage, built on a consistency premise that does not hold across parts of speech). Everything else in the delta is right. The NBSP edit is **correct, verified at the compiled-byte level, and introduced no regression** — the `\n` survived, the NBSP is interior where Kotlin's `trim()` cannot reach it, and `fitLabel` now measures "màn hình" as the single unbreakable run it always was.

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
| `settings_ocr_note_mlkit` | ⚠️ | "Nhanh ngay cả với màn hình nhiều chữ" | "Vẫn mượt dù màn hình nhiều chữ" | The English comment forbids reusing the literal Fast tier label; the first pass reused «Nhanh», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

Diacritics complete and syllable spacing preserved on every new string. **Hỗ trợ tiếp cận** for accessibility across all three `a11y_stuck_*` strings — never Trợ năng. **Máy ảnh** was adopted for the camera tool and every camera permission string, matching Android's own Vietnamese for the CAMERA permission group, so `camera_permission_denied` points at a label the user can actually find in system settings. **trình nhận dạng** was chosen for *engine* precisely so **công cụ** stays free for *tool*: the two meet in `settings_ocr_delete_camera_import_note`, and reusing công cụ for both would have read as a tautology. It also echoes the file's existing «Nhận dạng bởi %1$s» (`ocr_source_label`). ảnh tĩnh for the camera freeze-frame stays distinct from ảnh chụp màn hình (`anki_group_screenshot`). bạn throughout. Plurals `other` only. `settings_support_check_updates_title_available` byte-matches `update_dialog_title` (Có bản cập nhật).

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

Scope: the eight keys added by `84d28c88` (card-mode memory: `anki_added_sentence_success`,
`anki_added_word_success`), `51536300` (first-field guard: `anki_first_field_unmapped`,
`anki_first_field_empty`, and the sentence-card back header `card_words_in_sentence`), the
in-card trim waveform caption (`game_audio_zoom_hint`), and the History display toggle
(`history_hide_translations_toggle_title` / `_subtitle`). Reviewed independently against the
English source and its per-string comments; the translations were not written by this reviewer.

Mechanical layer verified programmatically over the delta: all eight keys present and no extras
anywhere in the file; every `<xliff:g>` span (4 `brand_anki`, 2 `field_name`) byte-identical to EN
including `id` and `example`; placeholder multisets identical (`%1$s` x2, none elsewhere);
`<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` and `“ ”` counts match EN; no unescaped `'` or `"` in any
string body; `name="…"` untouched; no plurals in the delta. Text is NFC throughout with no
combining-mark sequences, no NBSP, no double spaces, and no merged or ASCII-stripped syllables.
`./gradlew :app:processDebugResources` is green. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `anki_added_word_success` | ⚠️ | "Đã thêm thẻ từ vào <xliff:g>Anki</xliff:g>" | "Đã thêm thẻ **từ vựng** vào <xliff:g>Anki</xliff:g>" | **thẻ từ** is a fixed Vietnamese collocation for a magnetic-stripe card (*khóa thẻ từ*), and **từ** is equally the preposition *from* — so "thẻ từ vào Anki" garden-paths as "the card **from** … into Anki" before context rescues it. This toast has exactly one job (EN comment: one-tap "routes by the remembered Sentence/Word default with no UI showing it, so the toast names the card shape created"), and it is read in a glance while the sheet is closing — the mode word has to land on first parse. **thẻ từ vựng** is what Vietnamese Anki users say, cannot be misparsed, and leaves its pair `anki_added_sentence_success` ("thẻ câu", unambiguous) untouched. If byte-linking to the toggle labels is preferred over naturalness, the alternative is "thẻ Từ" / "thẻ Câu", quoting `anki_mode_word` (Từ) and `anki_mode_sentence` (Câu) as mode names. |
| `game_audio_zoom_hint` | ⚠️ | "Chụm hai ngón để hiện thêm hoặc bớt âm thanh" | "Chụm hai ngón để xem nhiều hoặc ít âm thanh hơn" | The coordination breaks: **hiện** cannot govern **bớt**, so the second conjunct stands alone as **bớt âm thanh** — the everyday Vietnamese for *turn the sound down*. Under a waveform whose sibling row chip plays the clip, a volume reading is a live misreading of what the gesture does, not a theoretical one. The suggestion keeps EN's own noun and holds the current one-line footprint (46 vs 44 chars; the TextView is `wrap_content` with no `maxLines` at 11 sp inside the panel's 24 dp padding, so nothing clips either way — this is about the reading, not the width). Shorter, more Android-idiomatic alternative: "Chụm hai ngón để thu phóng dạng sóng" (**thu phóng** is Android's own Vietnamese for *zoom* and cannot mean volume); ja/ko/th all took this extent/range route. Separately, **chụm** names only the inward pinch while the gesture is bidirectional — matching EN's bare "Pinch" is defensible (es/fr/pt do the same) and is not counted as a defect here. |
| `anki_first_field_unmapped` | 💬 | "Hãy ánh xạ **một** giá trị cho “%1$s” để Anki **có thể** nhận diện ghi chú." | "Hãy ánh xạ giá trị cho “%1$s” để Anki nhận diện ghi chú." | Faithful and correct, but 1.29x EN on the one string whose comment says "Kept short: Android 12+ clamps toasts to two lines". **một** and **có thể** are article/modal calques Vietnamese does not need after **để**. Arithmetic: a system toast gives roughly 264 dp of text at 14 sp on a 360 dp screen, about 37 characters a line, so two lines is about 74 characters; EN's 48-character frame survives field names up to about 26 characters, the current Vietnamese 63-character frame only up to about 11, and the trim buys back roughly 11. Word order is already right — the imperative leads, so a clip costs the reason and not the instruction (better than ja/ko, which put the reason first). If clarity is judged worth the headroom instead, add the head noun the way th/zh/ko/pt did: "cho trường “%1$s”" (+7). |

### Clean areas (delta) — checked, no findings

**Diacritics and orthography** read character by character on all eight strings: Chụm / ngón / để / hiện / hoặc / bớt / âm; Hãy / ánh xạ / giá trị / có thể / nhận diện / ghi chú; đang trống / trường đầu tiên / mọi thẻ; Ẩn / Chỉ hiển thị / đã chụp / Nhấn / dòng / bản dịch. All correct, all NFC precomposed, syllables space-separated, nothing stripped.

**`card_words_in_sentence` = "Từ trong câu" is right, and the obvious objection does not hold.** The bare **Từ** invites the same *word*-vs-*from* ambiguity flagged in the toast above, and "Các từ trong câu" would kill it — but the committed `anki_group_words_count` is already **"Từ trên thẻ"** (EN "Words on card"), the identical *Từ + location phrase* frame, and breaking the parallel for one of the two would be worse than the residual ambiguity. Unlike the toast, **Từ** is here in head position, where the noun reading is the default and there is no competing fixed collocation. Render checked: the header goes through `.gl-section` (`font-size:0.55em; font-weight:500; letter-spacing:0.12em; text-transform:uppercase`) in `PtCardTemplates`/`AnkiHtmlStylers`, so the card shows "TỪ TRONG CÂU" — Chromium uppercases precomposed Vietnamese correctly and the string is NFC, so no tone mark is lost; at 12 characters it is shorter than EN's 17 even with the letter-spacing. It is baked at send time (`AnkiSendPipeline`), so no runtime locale drift.

**Toast pattern.** `anki_added_sentence_success` reuses the committed `anki_added_no_audio` frame exactly — **Đã thêm … vào Anki** — keeping the completive **Đã**, the `vào` complement and the brand span at the tail, so the three Anki success toasts read as one family. Both new toasts are shorter than EN (0.89x, 1.00x), so the two-line clamp is not in play for them.

**Anki terminology.** **ánh xạ** for *map* matches every committed sibling — `anki_content_source_pick_title` ("Ánh xạ \"%1$s\""), `anki_card_type_edit_mapping_row_label` ("Chỉnh sửa ánh xạ trường"), `anki_card_type_basic_no_mapping` ("không cần ánh xạ trường") — and the dialog it announces opens immediately after the toast, so the user meets the same verb twice in two seconds. **trường** = field is the file-wide term (12+ hits). The **Hãy** imperative is the file's toast register, not verbosity: `anki_field_mapping_unconfigured` ("Hãy cấu hình các trường…"), `anki_models_unavailable` ("Hãy thử lại…"), `audio_source_game_enable_hint` ("hãy bật…").

**ghi chú for Anki's *note* — correct, and consistent across both strings.** This is the one surface where AnkiDroid's own data model leaks through PlayTranslate's friendlier vocabulary, and English does the same thing (it says "card type" in `anki_card_type_*`, then "the note" here). **ghi chú** is AnkiDroid's own Vietnamese term, so the word the user meets in the error is the word AnkiDroid shows them — which is the point of naming the note at all. **loại thẻ** stays correct where EN says *card type*, and the two do not fight. Both first-field strings use **ghi chú** with the same verb **nhận diện**, so the pair reads as one story rather than two unrelated errors; **nhận diện** also stays clear of the file's OCR verb **nhận dạng** (`ocr_source_label` "Nhận dạng bởi %1$s"), which would otherwise have implied recognition of an image.

**`anki_first_field_empty` prepositions and pronoun resolution.** "đang trống **trên** thẻ này" / "trên mọi thẻ" is not a clash with `anki_words_helper`'s "trong thẻ / khỏi thẻ" — those encode membership, this encodes location on the card, and the file's own **"Từ trên thẻ"** (`anki_group_words_count`) is the precedent. The second sentence resolves English's dangling *it* ("so it needs a value") explicitly to **trường này**, which is an improvement on the source and removes the only ambiguity in the sentence. It renders in a full alert, so its 1.10x ratio costs nothing.

**History terminology.** English has now used three nouns for one thing — *line* (`history_empty_none`, `history_clear_confirm_message`, `history_line_count`), *entry* (`history_delete_confirm_title`), and now *row*. Vietnamese correctly collapses *row* into **dòng**, the unit the file already uses ("Các dòng sẽ xuất hiện…", "Mọi dòng đã lưu…", plural "%d dòng"), instead of minting a fourth word; **mục** stays reserved for the delete-one dialog ("Xóa mục này?") exactly as EN reserves *entry*. **văn bản đã chụp** is already the file's rendering of *captured text* (`tr_service_order_footer`), and sits comfortably beside "các câu đã chụp" (`history_toggle_subtitle`) since this string is about text, not sentences. **Ẩn bản dịch** is the exact antonym of the committed `hotkey_show_translations_dialog_title` ("Hiện bản dịch") and matches `translate_button_subtitle_hold_to_hide_translations`. The classifier **một dòng** and the "Nhấn vào một X để…" frame both match `anki_words_helper` ("Nhấn vào một từ để…"). Render: `TranslationHistoryActivity.bindHideTranslationsToggle` uses the standard `tvRowTitle` / `tvRowSubtitle` row; the subtitle is within one character of the shipped sibling `history_capture_image_toggle_subtitle` (64 vs 63), so no new wrap behaviour.

**Register and punctuation.** Polite **bạn**-level throughout, with the subject correctly dropped in all eight (no `bạn` is needed in any of them, and none was forced in). Sentence-final periods match EN string for string — present on both first-field strings and the History subtitle, absent on the toasts, the header and the caption. **“ ”** curly quotes per the Vietnamese parameter row and byte-matching EN in both first-field strings; the escaped straight `\"` in `anki_content_source_pick_title` is English's own inconsistency and out of scope here. Brand **Anki** untranslated inside all four spans.

### Verdict

**PASS with polish.** No 🛑, no ❌. Two ⚠️ worth fixing before the device pass — `anki_added_word_success` ("thẻ từ" reads as *magnetic card* / *card from*, and this toast exists solely to make the card shape legible at a glance) and `game_audio_zoom_hint` ("bớt âm thanh" reads as *turn the volume down* under a control that does not change volume) — plus one 💬 trim on `anki_first_field_unmapped` that buys back toast-clamp headroom the English comment explicitly budgets for. Everything else in the delta is correct, consistent with the committed file, and lands its render surface.
