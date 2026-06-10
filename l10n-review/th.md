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
