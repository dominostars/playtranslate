# Vietnamese (vi) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] vi -> readme/README.vi.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Can't enable accessibility? (intro) | ⚠️ | "nút bật/tắt trong Cài đặt có thể bị mờ đi hoặc hiện thông báo “Cài đặt bị hạn chế”" | "nút bật/tắt trong Cài đặt có thể bị mờ đi hoặc hiện thông báo “Chế độ cài đặt bị hạn chế”" | From memory, AOSP Settings (vi) titles the dialog "Chế độ cài đặt bị hạn chế". The bare "Cài đặt bị hạn chế" also reads as "installation restricted", which is easy to misread in a section about sideloaded installs. Confirm on a vi device first. The app's `restricted_settings_*` strings use the current wording, so change them in the same pass. |
| Can't enable accessibility? step 3 | ⚠️ | "Nhấn vào **Cho phép cài đặt bị hạn chế**" | "Nhấn vào **Cho phép chế độ cài đặt bị hạn chế**" | From memory, the ⋮ menu item in AOSP Settings (vi) is "Cho phép chế độ cài đặt bị hạn chế". The current text can be read as "allow restricted installation". It matches the label the app quotes in `restricted_settings_message` today, so that string (and `restricted_settings_title`, "Cho phép Cài đặt bị hạn chế", which also capitalises "Cài đặt" mid-phrase) needs the same change. |
| How to Install, step 2 | 💬 | "hãy cho phép trình duyệt hoặc trình quản lý tệp cài đặt ứng dụng không rõ nguồn gốc: mở **Cài đặt →" | "hãy cho phép trình duyệt hoặc trình quản lý tệp cài ứng dụng không rõ nguồn gốc: mở **Cài đặt →" | One clause has three "cài đặt" in two senses: install (verb), Settings (app) and the "Install unknown apps" label. The short verb "cài", which the file already uses in "cài từ bên ngoài Cửa hàng Play", leaves "Cài đặt" for the Settings app and the Android label. |
| How to Install, step 3 | 💬 | "Mở tệp APK và nhấn “Cài đặt”" | "Mở tệp APK rồi nhấn nút **Cài đặt**" | The label is correct: the package installer's Install button is "Cài đặt". But step 2 just used **Cài đặt** for the Settings app, and "nút" makes clear this is the installer's button. Bold also matches every other tappable label in the file; the file uses quotes for messages, not buttons. |
| Won't install? (closing line) | 💬 | "Cài đặt tệp APK, sau đó bật lại Play Protect để tính năng này tiếp tục quét các ứng dụng khác của bạn." | "Cài tệp APK xong, hãy bật lại Play Protect để tính năng này tiếp tục quét các ứng dụng khác của bạn." | At the start of a sentence, right after a list of Play Store taps, "Cài đặt" first reads as the Settings app. "cài … xong, hãy" also sequences the two steps more naturally. |
| Optional: Anki Flashcards | 💬 | "Hãy cài đặt [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki), sau đó" | "Hãy cài [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki), sau đó" | The same sentence ends with "trong phần Cài đặt" (Settings), so one sentence uses "cài đặt" in both senses. The short verb keeps them apart. |
| Optional: Online Translation Backends | 💬 | "còn ML Kit là bản dịch ngoại tuyến được dùng dự phòng khi bản dịch trực tuyến không khả dụng" | "còn ML Kit là công cụ dịch ngoại tuyến được dùng dự phòng khi bản dịch trực tuyến không khả dụng" | The meaning is right ("used whenever online translation is unavailable") and the wording mirrors `tr_service_offline_footer`. But ML Kit is a translation engine, not a translation ("bản dịch"). Only the noun changes. |
| Optional: Online Translation Backends | 💬 | "Bạn có thể thêm bao nhiêu dịch vụ tùy thích: mỗi dịch vụ là một mục riêng trong danh sách" | "Bạn có thể thêm bao nhiêu dịch vụ tùy thích. Mỗi dịch vụ là một mục riêng trong danh sách" | The sentence has two colons: this one and the one that introduces the list at its end. A period splits it cleanly. |
| Features > Text-to-speech | 💬 | "Nghe đọc to văn bản." | "Nghe văn bản được đọc to." | "Nghe đọc to văn bản" is telegraphic. The passive reads more naturally. |

## Clean areas (checked, no findings)

**Register.** The translation addresses the reader as polite **bạn** throughout, with "hãy" imperatives. It never slips into "quý vị", bare commands or casual particles. Diacritics are complete, and I saw no stripped ASCII words or merged syllables in any prose line, heading or table cell.

**Terminology vs `values-vi/strings.xml`.** Every name matches the app:
- Dịch vụ dịch thuật (`settings_cell_translation_services`)
- Phím tắt (`settings_cell_hotkeys`)
- Chuyển văn bản thành giọng nói (`settings_cell_tts`)
- Lịch sử văn bản (`history_toggle_title` "Giữ lịch sử văn bản"; `settings_cell_history` "Lịch sử")
- "các câu đã chụp" (`settings_cell_history_summary_*`, the established capture verb)
- Chế độ tự động dịch (`live_mode_auto_translate_label` "Tự động dịch")
- furigana / pinyin (`header_action_furigana`, `hint_label_pinyin_lower`)
- Vùng chụp (`menu_capture_region`)
- máy ảnh (`settings_cell_camera`); "chụp ảnh tĩnh" also matches `camera_shutter_cd`
- Từ điển (`settings_cell_dictionary`)
- Thẻ ghi nhớ Anki (`settings_cell_anki`)
- Âm thanh trò chơi (`audio_source_game_name`)
- mô hình (dịch) ngoại tuyến (`lang_section_offline_models_title`)
- Loại thẻ (`anki_card_type_row_label`)
- Trọng âm cao độ and tần suất (`yomitan_category_*`)
- Tùy chỉnh, Khóa API and the “URL tùy chỉnh” field (`llm_backend_preset_custom`, `llm_backend_api_key_label`, `llm_backend_base_url_label`)
- Tra cứu từ (`onboarding_a11y_row_lookup_title`)
- bộ thẻ (deck)
- Hỗ trợ tiếp cận (never Trợ năng), which is also the framework's own `accessibility_binding_label`

The translation adds the field name in the Custom row, which is accurate, since that is the field the user types the base URL into. "Internet" is capitalised as in the framework's vi strings.

**Android and Play Store wording.**
- Install step 2's path, **Cài đặt → Ứng dụng → Quyền truy cập đặc biệt của ứng dụng → Cài đặt ứng dụng không rõ nguồn gốc**, and the switch **Cho phép từ nguồn này** match AOSP Settings (vi) from memory.
- "Cửa hàng Play" is confirmed on disk: framework `app_streaming_blocked_title_for_playstore_dialog` is "Cửa hàng Play không có sẵn".
- "Google Play Protect", "Play Protect" and **Quét ứng dụng bằng Play Protect** match the Play Store's vi labels from memory.
- “Ứng dụng chưa được cài đặt” matches the package installer's "App not installed." from memory.
- “Đã phát hiện ứng dụng độc hại” is byte-identical to framework `harmful_app_warning_title` on disk (android-36 `values-vi`).
- **Cài đặt → Ứng dụng → PlayTranslate**, menu **⋮** and "Xác thực khi được yêu cầu" are fine.

The only Android-label mismatch is the restricted-settings pair above.

**Accuracy.** Nothing is dropped or softened. Counts (26 / 59), all 13 feature bullets, the four install steps, the five Play Protect steps and the four restricted-settings steps are intact. The intro's "59 ngôn ngữ dịch" for English "59 user languages" follows the README's own later term "translation languages", which is a legitimate harmonisation. "pick a model at runtime" became "chọn mô hình ngay trong ứng dụng", which is correct. The ML Kit sentence says ML Kit is used as the fallback whenever online translation is unavailable, mirroring the vi `tr_service_offline_footer` almost word for word. The noun nit is in the table.

**Links and tables.** The checker passes. The link text is natural ("Nhấn vào đây để tải bản phát hành mới nhất", "máy chủ Discord"). Both tables have translated header cells (Ngôn ngữ / Tên bản địa / Mã) and the same rows in the same order, with the native-name and code columns untouched. The pointer section "Ghi công và giấy phép" links the English README's Credits anchor and the LICENSE file. It keeps the word "Credits" in its link text, so the reader can find that heading.

**Typography.** The file uses “ ” curly quotes for quoted messages, with no space before colons, and the arrow paths are spaced. The trailing-period pattern in the feature list mirrors the English source.

## Vietnamese appendix

**The "Cài đặt" ambiguity map.** Vietnamese uses one word for Settings (the app) and install (the verb). Every occurrence in the file:

| line | text | sense | verdict |
|---|---|---|---|
| 27, 34 | Cách cài đặt / Không cài đặt được? | install | clear, since headings carry no Settings context |
| 30 | "…trình quản lý tệp cài đặt ứng dụng…" | install (verb) | 💬, use "cài" (row above) |
| 30 | **Cài đặt →** … | Settings | correct, bold path |
| 30 | **…→ Cài đặt ứng dụng không rõ nguồn gốc** | Android's "Install unknown apps" label | correct, keep verbatim |
| 31 | “Cài đặt” (button) | installer's Install button | label correct; 💬 add "nút" and bold |
| 36 | “Ứng dụng chưa được cài đặt” | install | Android's label, keep |
| 44 | "Cài đặt tệp APK, …" | install | 💬, use "Cài … xong" |
| 48 | "nút bật/tắt trong Cài đặt" | Settings | correct |
| 48, 52 | “Cài đặt bị hạn chế” / **Cho phép cài đặt bị hạn chế** | Android's "restricted setting(s)" | ⚠️ AOSP uses "chế độ cài đặt", which also removes the install reading |
| 50 | **Cài đặt → Ứng dụng → PlayTranslate** | Settings | correct |
| 164 | **Cài đặt → Dịch vụ dịch thuật** | app Settings | correct |
| 178 | "Hãy cài đặt [AnkiDroid] … trong phần Cài đặt" | install, then Settings | 💬, use "cài" for the first |

**Restricted settings: sources.** The app's `restricted_settings_title` is "Cho phép Cài đặt bị hạn chế". Its `restricted_settings_message` quotes the menu item as "Cho phép cài đặt bị hạn chế", and the README copied that faithfully. My recollection of the AOSP Settings vi strings is:
- `blocked_by_restricted_settings_title`: "Chế độ cài đặt bị hạn chế"
- `app_restricted_settings_lockscreen_title`: "Cho phép chế độ cài đặt bị hạn chế"

This follows Google's usual vi convention of "chế độ cài đặt" for settings items as opposed to the Settings app. These Settings-app strings are not in the framework res on disk, so this is from memory. Confirm on a vi-locale Pixel/AOSP device before editing. If it is confirmed, the README rows and the two app strings should move together. OEM skins (Samsung, Xiaomi) may word it differently. Since the README names the AOSP menu path, the AOSP label is the one to match.

**"kính lúp nổi" (floating lens): keep.** The app never names the lens in its UI (`icon_action_lookup_words` is just "Đưa lên một từ để xem định nghĩa"), so this is the README's own coinage:
- "kính lúp" (magnifier) is the everyday Vietnamese word for the circular magnifying tool, and it fits a lens you drag over text.
- "nổi" (floating) matches the app's established "biểu tượng nổi" (floating icon).
- The alternatives are worse: "ống kính" is a camera lens, and "thấu kính" is an optics-textbook term.
- The verb "Đưa … lên" matches `icon_action_lookup_words`.

**"Trợ giúp và ủng hộ" (Support): keep.** The English heading covers both meanings: getting help on Discord and supporting the project on Ko-fi. "Trợ giúp và ủng hộ" names both. A bare "Hỗ trợ" would lose the Ko-fi sense and echo "Hỗ trợ tiếp cận" from the section just above.

**Language names.** The app's language picker uses `Locale.getDisplayLanguage`, so it shows CLDR names. I ran the CLDR vi display names (JDK 25) for all 26 + 59 codes, and every Language-column cell matches them. That includes the less obvious "Tiếng Italy", "Tiếng Bangla", "Tiếng Haiti", "Tiếng Galician", "Tiếng Quốc Tế Ngữ" and "Tiếng Hà Lan (Nam Phi)" for Afrikaans. The last one is CLDR's own vi name, not a translator invention, so a reader sees the same name as in the app. "Tiếng Trung (Phồn thể)" is CLDR's zh-Hant display name.

## Disposition (2026-10-06)

Applied the 7 💬 as suggested. The two ⚠️ (“Chế độ cài đặt bị hạn chế” / **Cho phép chế độ cài đặt
bị hạn chế**) are NOT applied: both are from memory, and the README deliberately quotes the app's
own reviewed `restricted_settings_*` strings, so the README and the app would have to change
together after a Vietnamese device confirms Android's label. Recorded as a device-check item.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] vi -> readme/README.vi.md`, `warning 38 bold spans, English has 37`). The extra span is install step 3's **Cài đặt** (the installer's Install button, bolded in the 2026-10-06 disposition), outside this section; this section has 4 bold spans, as in English. **No 🛑 issues.**

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Can't enable accessibility? (intro) | 💬 | "và khi nhấn vào nút này sẽ hiện thông báo “Chế độ cài đặt bị hạn chế”." | "và khi bạn nhấn vào nút này, thông báo “Chế độ cài đặt bị hạn chế” sẽ hiện ra." | After "Android làm mờ …, và" the clause has no subject, so who taps is left to the reader and the sentence reads like speech. Naming "bạn" (the file's register) and making the message the subject reads as written prose. Optional. |

Clean areas: The four steps follow the English order and content, "Mục này chỉ xuất hiện sau bước 1" keeps the only-after-step-1 condition, and the old closing sentence is gone, as in English. Every Android label is byte-identical to AOSP Android 16 Settings (vi) on disk: Cài đặt, Hỗ trợ tiếp cận, Ứng dụng, Thông tin ứng dụng (Android's label, not the English's "AppInfo"), “Chế độ cài đặt bị hạn chế” (`blocked_by_restricted_settings_title`) and **Cho phép các chế độ cài đặt bị hạn chế** (`app_restricted_settings_lockscreen_title`, with "các", which the 2026-10-06 from-memory suggestion lacked). The app's `a11y_restricted_settings_addendum` uses the same words (nút chuyển, bị mờ, trang Thông tin ứng dụng, menu ⋮, the menu label), and the path agrees with "Cài đặt → Hỗ trợ tiếp cận → …" in `overlay_icon_a11y_required_message` and `accessibility_dialog_message`. That settles the 2026-10-06 device-check item from AOSP source: "Cài đặt bị hạn chế" (readable as "installation restricted") is gone from both the README and the app. Register is bạn, with bare imperatives in the steps as in the file's other lists; Hỗ trợ tiếp cận throughout (never Trợ năng), capitalised as elsewhere; diacritics complete, text NFC. Bold sits on the same four labels as English, numbering 1 to 4, curly quotes, spaced arrows, no dashes.

### Disposition (2026-10-08)

Applied the 1 💬 ("khi bạn nhấn vào nút này, thông báo … sẽ hiện ra").
