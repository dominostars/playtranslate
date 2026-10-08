# Thai (th) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] th -> readme/README.th.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| How to Install, step 2 | ⚠️ | "เลือกแอปที่ใช้ดาวน์โหลดไฟล์ APK แล้วเปิด **อนุญาตจากแหล่งที่มานี้**" | "เลือกแอปที่จะใช้เปิดไฟล์ APK แล้วเปิด **อนุญาตจากแหล่งที่มานี้**" | English says only "pick the app" (the browser or file manager). The permission belongs to the app that opens the APK; "the app used to download the APK" sends a file-manager user to the browser instead |
| Intro paragraph | 💬 | "รองรับภาษาของเกม 26 ภาษา และแปลเป็นภาษาของคุณได้ 59 ภาษา!" | "รองรับภาษาของเกม 26 ภาษา และภาษาของคุณ 59 ภาษา!" | "translate into your language, 59 languages" pairs a singular "your language" with a count; the parallel form reuses the app's own pair `lang_translate_from` ภาษาของเกม / `lang_translate_to` ภาษาของคุณ |
| Features > Camera translation | 💬 | "เล็งกล้องไปที่ข้อความรอบตัวเพื่ออ่านคำแปลแบบสด" | "เล็งกล้องไปที่ข้อความรอบตัวเพื่ออ่านคำแปลแบบเรียลไทม์" | แบบสด collocates with broadcasting (ถ่ายทอดสด); the README already says แบบเรียลไทม์ for "real-time" in the intro and in Furigana/Pinyin Mode |
| Features > Hotkeys | 💬 | "ตั้งค่าปุ่มบนตัวเครื่องให้กดค้างเพื่อแสดงคำแปลหรือฟุริงานะ" | "ตั้งค่าปุ่มฮาร์ดแวร์ให้กดค้างเพื่อแสดงคำแปลหรือฟุริงานะ" | "physical key" (vs on-screen) also covers controller and keyboard keys, which the hotkeys support; ปุ่มบนตัวเครื่อง narrows it to buttons built into the device. Optional, since handhelds are the case the sentence goes on to name |
| Won't install? (closing line) | 💬 | "ติดตั้งไฟล์ APK ให้เสร็จ แล้วเปิด Play Protect อีกครั้งเพื่อให้สแกนแอปอื่นๆ ต่อไป" | "ติดตั้งไฟล์ APK ให้เสร็จ แล้วเปิด **สแกนแอปด้วย Play Protect** อีกครั้งเพื่อให้สแกนแอปอื่นๆ ต่อไป" | เปิด right after step 1's "เปิด **Play Store**" can read as "open Play Protect" (the page); naming the toggle from step 5 makes it unambiguous "turn back on" |
| Optional: Online Translation Backends | 💬 | "จะใช้ ML Kit ซึ่งแปลแบบออฟไลน์เป็นทางเลือกสำรอง" | "จะใช้ ML Kit แปลแบบออฟไลน์เป็นทางเลือกสำรอง" | Meaning is already correct; the ซึ่ง relative clause is a little stiff, and the serial-verb form reads more naturally |

## Clean areas (checked, no findings)

**Register.** Neutral-polite throughout: no ครับ/ค่ะ or other particles, คุณ used sparingly (10 times) where a pronoun is wanted, โปรด for requests, imperatives in steps. Consistent with `values-th`.

**Terminology vs strings.xml.** Every feature or setting name matches the app: บริการแปล (`settings_cell_translation_services`, also in the path **การตั้งค่า → บริการแปล** and the section heading), ปุ่มลัด (`settings_cell_hotkeys`), การอ่านออกเสียงข้อความ (`settings_cell_tts`), ประวัติข้อความ (matches `history_toggle_title` เก็บประวัติข้อความ and `history_empty_off`; the sentence เก็บประโยคที่จับภาพไว้ reuses the app's captured verb จับภาพ), โหมดแปลอัตโนมัติ (`live_mode_auto_translate_label` แปลอัตโนมัติ), ฟุริงานะ / พินอิน (`header_action_furigana`, `hint_label_pinyin_lower`), พื้นที่จับภาพ (`menu_capture_region`), พจนานุกรม, กล้อง, แฟลชการ์ด Anki (`settings_cell_anki`, used verbatim in the heading), เสียงเกม (`audio_source_game_name`), โมเดลออฟไลน์ (`lang_section_offline_models_title`), คำจำกัดความ (`icon_action_lookup_words`), การเน้นระดับเสียง (`yomitan_category_pitch_accent`), ประเภทการ์ด (`anki_card_type_row_label`), เสียงอ่านออกเสียง for the TTS audio on cards (`anki_content_word_audio_desc`), ภาพนิ่ง for the frozen camera frame (`camera_shutter_cd` ถ่ายภาพนิ่ง), สำรับ for deck, แตะเดียว for one-tap (`onboarding_welcome_learn_body`), ใบอนุญาต for licence (`hymt_legal_message`). The Custom backend line names the preset กำหนดเอง (`llm_backend_preset_custom`) and the field “URL ที่กำหนดเอง” (`llm_backend_base_url_label`) exactly. Hold-to-preview กดค้างเพื่อแสดงคำแปล matches `hotkey_show_translations_title`.

**Android and Play Store wording.** Install step 2: การตั้งค่า → แอป → สิทธิ์เข้าถึงพิเศษของแอป → ติดตั้งแอปที่ไม่รู้จัก and the switch อนุญาตจากแหล่งที่มานี้ match AOSP Settings' Thai labels (from memory; not in the framework file). Step 3's “ติดตั้ง” is the package installer's button (from memory). “ตรวจพบแอปที่เป็นอันตราย” is byte-identical to the framework's `harmful_app_warning_title` (verified on disk, android-36 values-th). “ไม่ได้ติดตั้งแอป” is the package installer's "App not installed." (from memory). Play Store steps: Play Store, ไอคอนโปรไฟล์, Play Protect, ไอคอนรูปเฟือง, สแกนแอปด้วย Play Protect (from memory) are right. Restricted settings: “การตั้งค่าที่ถูกจำกัด” (dialog title, from memory) and **อนุญาตการตั้งค่าที่ถูกจำกัด** match the app's `restricted_settings_title` exactly, and step 2 "แตะปุ่มจุดสามจุด (**⋮**) ที่มุมขวาบน" mirrors `restricted_settings_message`. การช่วยเหลือพิเศษ matches the framework's accessibility strings. แยกหน้าจอ is Android's split-screen term.

**Accuracy.** All 13 features, the 4 install steps, the 5 Play Protect steps and the 4 restricted-settings steps are present in order; counts 26 / 59 intact; nothing softened. Small additions are faithful clarifications: "จากมากไปน้อย" on the sort order (true), "มีแผนฟรี สมัครได้ที่" for "free tier at", "รับคีย์ได้ที่ … และเลือกโมเดลได้ในแอป" for "pick a model at runtime". The ML Kit sentence says ML Kit is used เมื่อการแปลออนไลน์ไม่พร้อมใช้งาน, which mirrors `tr_service_offline_footer` (ใช้เป็นทางเลือกสำรองเมื่อการแปลออนไลน์ไม่พร้อมใช้งาน) as required. The Anki line's "ให้สิทธิ์เข้าถึง AnkiDroid ในการตั้งค่าของ PlayTranslate" is the correct reading of the English.

**Specific choices asked about.** แว่นขยายแบบลอย for "floating lens" is the best available: the English comments call it the magnifier lens, เลนส์ alone reads as a camera lens, and the app has no competing term. ปุ่มเสริม for "dedicated buttons" is right: on handhelds these are the spare/extra buttons a user can give to the app, and the obvious alternative ปุ่มเฉพาะ is a calque. The noun-phrase headings กรณีติดตั้งไม่ได้ / กรณีเปิดการช่วยเหลือพิเศษไม่ได้ are the natural Thai troubleshooting form; a bare "ติดตั้งไม่ได้?" would be chattier than the rest of the page. (ไม่บังคับ) is Android's standard Thai rendering of "(optional)" and the app uses it (`crash_email_body`). ภาษาของคำแปล for "translation languages" is accurate, natural, and pairs cleanly with ภาษาของเกม; see the appendix on the app's ภาษาของคุณ.

**Links and tables.** Release link uses the absolute URL (correct for a file under `readme/`); Discord, video, backend and Ko-fi links intact. Both table headings are translated (ภาษาของเกม (อ่านจากหน้าจอ), ภาษาของคำแปล (แปลให้คุณอ่าน)), header cells ภาษา / ชื่อในภาษานั้น / รหัส are translated, native-name and code columns untouched (checker confirms), and the Thai language names follow CLDR as Android displays them (บังกลา, คุชราต, มราฐี, เฮติครีโอล, etc.). The pointer section says credits for libraries, models and linguistic data plus the GPL 3.0 licence live in the English README, and links both #credits and LICENSE.

**Typography.** “ ” quotes throughout, no ASCII quotes, no em dashes. A scripted scan found no Thai character touching a Latin letter or digit without a space, no double spaces, no space before `:` `!` `)`, and no sentence-final periods in Thai prose. Spaces mark clause and list-item boundaries correctly (e.g. the Anki field list), `→` is spaced in all three paths, numbers are spaced (26 ภาษา, 59 ภาษา). ๆ is written tight (อื่นๆ), which matches all 8 uses in `values-th` (0 spaced). The two "!" mirror the English and the app's own `onboarding_welcome_title`.

## Thai-specific appendix

**Spacing at links.** "โปรดเข้าร่วม[เซิร์ฟเวอร์ Discord](…)" (twice) has no space before the link. That is correct: the link text starts with Thai and is the object of เข้าร่วม inside one verb phrase, so a space there would mark a false phrase boundary; the link styling already separates it visually. Every link that starts with Latin text is space-bordered.

**ภาษา compounds.** The README never puts a placeholder after ภาษา, but its compounds are all written tight (ภาษาของเกม, ภาษาของคำแปล, ภาษาอังกฤษ), consistent with the `values-th` rule for Thai-language names; "README ภาษาอังกฤษ" is spaced at the Latin boundary as it should be.

**ภาษาของคำแปล vs the app's ภาษาของคุณ.** The app labels the target picker ภาษาของคุณ (`lang_translate_to`, "Your Language"), while the English README says "translation languages". The Thai README follows the English in the Supported Languages section and the table heading, which is right for a section that explains both sides, and its parenthetical (ภาษาที่แสดงให้คุณอ่าน) bridges to the app's wording. The intro line is the one place that speaks of "user languages", which is why the 💬 above suggests the app's ภาษาของคุณ there.

**Labels from memory.** สิทธิ์เข้าถึงพิเศษของแอป, ติดตั้งแอปที่ไม่รู้จัก, อนุญาตจากแหล่งที่มานี้, การตั้งค่าที่ถูกจำกัด, ไม่ได้ติดตั้งแอป and สแกนแอปด้วย Play Protect are the AOSP / Pixel and Play Store Thai labels as I recall them; none of them is in the framework file. Some OEM skins (e.g. Samsung One UI) word "Special app access" differently, which the README cannot cover and the English does not either.

## Disposition (2026-10-06)

Applied all 6: the ⚠️ (the app that opens the APK, not the one that downloaded it) and the 5 💬
as suggested.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] th -> readme/README.th.md`, one warning: `38 bold spans, English has 37`; same with `--require-header`). The extra span is “สแกนแอปด้วย Play Protect” in the "Won't install?" closing line, added by the 2026-10-06 disposition, so it comes from outside this section. This section has the same 4 bold spans as the English. **No 🛑 issues.**

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Can't enable accessibility? > intro | 💬 | "ใน Android 13 ขึ้นไป Android จะทำให้สวิตช์การช่วยเหลือพิเศษเป็นสีเทา" | "ใน Android 13 ขึ้นไป ระบบจะทำให้สวิตช์การช่วยเหลือพิเศษเป็นสีเทา" | Thai has no comma to close the time phrase. The spaces around a Latin word are there anyway, so they don't mark a boundary, and “Android 13 ขึ้นไป Android” first reads as one noun phrase. Before ระบบ, the space is between two Thai words, which only happens at a phrase boundary. Android stays the agent, named just before, and ระบบ is the section's own word in step 4 (“หากระบบขอ”). |

Clean areas: Nothing is added, dropped or softened. Every intro fact and all four steps are there in the English order. The "only after step 1" condition is kept (“ตัวเลือกนี้จะปรากฏหลังจากทำขั้นตอนที่ 1 แล้วเท่านั้น”), and “หากระบบขอ” keeps "if prompted". Android labels match AOSP Android 16 exactly: การตั้งค่า, การช่วยเหลือพิเศษ, แอป, ข้อมูลแอป for the English "AppInfo", “การตั้งค่าที่จำกัด” twice, and อนุญาตการตั้งค่าที่จำกัด (`settings_label`, `accessibility_settings`, `apps_dashboard_title`, `application_info_label`, `blocked_by_restricted_settings_title`, `app_restricted_settings_lockscreen_title`). The app's reviewed `a11y_restricted_settings_addendum` and `overlay_icon_a11y_required_message` use the same words. OEM skins' Thai labels were not checked. The register is neutral-polite with no particles, and แตะ, เลือก and เปิด are used as in the rest of the file. Only “ ” quotes are used, with no sentence periods. Every seam between Thai and Latin, digits or symbols (Android 13, APK, PlayTranslate, ⋮, →, ขั้นตอนที่ 1) has a space. Otherwise spaces fall only at phrase boundaries and around quotes, with no double spaces. Bold is on the same four labels as the English, and the list is numbered 1 to 4.

Outside this delta (not reviewed, for the record): AOSP Android 16's `external_source_switch_title` is “อนุญาตการติดตั้งจากแหล่งที่มานี้”, while install step 2 bolds “อนุญาตจากแหล่งที่มานี้”. The 2026-10-06 review could only go from memory. `special_access` (สิทธิ์เข้าถึงพิเศษของแอป) matches.

### Disposition (2026-10-08)

Applied the 1 💬 (ระบบ for the second Android). The out-of-scope install-step label note is reported to the developer.
