# Arabic (values-ar) localization review

Reviewed all 1181 lines of `values-ar/strings.xml` against the full English source. **Mechanical rules: no violations found** — all placeholders present and inside intact `<xliff:g>` blocks, `<b>`/`\n`/`\{\{furigana:\}\}`/`&lt;img&gt;` preserved, no unescaped apostrophes (the file uses «» and Arabic prose with none), brand names all in Latin script, plural quantity names valid. No 🛑 findings.

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `enhanced_auto_translate_subtitle_off` | ⚠ | تتطلب الوصول إلى إمكانية الوصول. | تتطلب إذن إمكانية الوصول. | "access to accessibility" doubles وصول; the `a11y_required_*` strings already use إذن إمكانية الوصول — align. |
| `tts_language_unsupported_unknown_engine_message` | ⚠ | محرك تحويل النص إلى كلام النشط لا يدعم | المحرك النشط لتحويل النص إلى كلام لا يدعم | Adjective النشط can't hang off the end of that idafa chain; reads broken. The with-engine variant (line above it) is fine. |
| `live_mode_pause_auto_label` | ⚠ | إيقاف التلقائي | إيقاف التلقائي مؤقتًا | Reads as "turn off auto". Bottom bar correctly uses إيقاف مؤقت for Pause; bare إيقاف is already the Turn Off label (`floating_icon_close_label_turn_off`, `capture_lifecycle_stop`). |
| `qwen_mnn_metered_warning_message`, `qwen35_2b_…`, `gemma_e2b_…`, `hymt_metered_warning_message` | ⚠ | هذه الشبكة محددة كمحدودة الاستخدام. المتابعة؟ | هذه الشبكة مصنَّفة كشبكة محدودة الاستخدام. هل تريد المتابعة؟ | محددة كمحدودة is an ugly jingle and المتابعة؟ is telegraphic for a dialog body. Same fix in all four. The agreed term شبكة محدودة الاستخدام itself is used consistently. |
| `legacy_engines_removed_message` | ⚠ | مترجِماتك القديمة دون اتصال | مترجِماتك القديمة للترجمة دون اتصال | دون اتصال dangles ("removed while offline" reading). |
| `hymt_legal_message` | ⚠ | بالضغط على موافق … أنت لا تقيم أو تتواجد | بالضغط على «أوافق» … لا تقيم ولا تتواجد | The button is أوافق — تفعيل Hunyuan, not موافق — in an attestation the referenced label should match exactly. Negation should distribute with ولا. Optionally تؤكد وتضمن ما يلي: before the list. Substance is faithful: §5(b) kept, EU/UK/South Korea enumerated both times, تؤكد وتضمن preserves "affirm and warrant". |
| `onboarding_a11y_title`, `mp_overlay_permission_title` (+ bodies) | ⚠ | العرض فوق التطبيقات الأخرى | verify vs. system | AOSP/Google Settings labels this special access الظهور فوق التطبيقات الأخرى on the devices I know; if the device string differs, users won't find the toggle. Needs on-device confirmation before changing (OEMs vary). |
| `deprecated_badge_label` | ⚠ | متوقف | مهمل | متوقف says "stopped/not running"; the model still works, it's retired. مهمل is the established rendering of "deprecated". |
| `notif_title`, `update_dialog_message`, `tts_language_unsupported_with_engine_message`, `deepl_settings_about` | ⚠ | e.g. PlayTranslate نشط | prefix RLM (‏) or reword verb-first | All four start with a Latin token, so a firstStrong text view will lay the whole line out LTR (wrong alignment, trailing-punctuation drift). `llm_backend_invalid_key_alert_message_fmt` shows the right pattern (رفض %1$s…). Verify in-app first. |
| `anki_send_failed_title` | 💬 | تعذّر إضافة البطاقة | تعذّرت إضافة البطاقة | Masculine verb with feminine إضافة is permissible but the file itself uses تعذّرت الترجمة and تمت الإضافة — polish for consistency. |
| `anki_deck_not_selected_subtitle` | 💬 | غير محدد | غير محددة | Refers to المجموعة (fem.) on the deck row. |
| `word_anki_in_decks` | 💬 | %1$d مجموعات Anki | مجموعات Anki: %1$d (or make it `<plurals>` upstream) | Count is ≥2 by definition: "2 مجموعات" should be مجموعتان, "11 مجموعات" should be singular. A fixed string can't be right for all counts. |
| `settings_capture_displays_count` | 💬 | %1$d شاشات | عدد الشاشات: %1$d (or `<plurals>`) | Shown for 2+, and 2 is the dominant case — "2 شاشات" misses the dual شاشتان. Telegraphic digit+plural is tolerated in Arabic UI, hence nit. |
| `deepl_settings_about` | 💬 | DeepL هو خدمة ترجمة | DeepL هي خدمة ترجمة | Copula should agree with the feminine predicate noun خدمة. |
| `settings_header_ocr` | 💬 | الصورة إلى نص (OCR) | تحويل الصورة إلى نص (OCR) | Bare calque of "Image-to-text"; the masdar reads naturally as a header. |
| `update_dialog_ask_again_later` | 💬 | السؤال لاحقًا | اسألني لاحقًا | Current form is stiff for a button. |
| `status_hold_hint` | 💬 | على المناطق أو تلقائي | على «المناطق» أو «تلقائي» | These are button names; without quotes "اضغط مطولاً على المناطق" reads as "long-press on the regions" generically. |
| `overlay_hide_controls_message` | 💬 | «الإيقاف» يعطّلها | «إيقاف» يعطّلها | Quoted button label must match the actual button (`floating_icon_close_label_turn_off` = إيقاف, no article). «الإخفاء الآن» matches its button correctly. |
| `live_mode_auto_with_hint` | 💬 | تلقائي %1$s | %1$s تلقائيًا | Composed "تلقائي فوريغانا" puts the modifier first — un-Arabic word order for a mode label. |

Clean areas (checked, no findings): register is uniform formal MSA with no dialect; ؟ used on every question and ← on every nav/direction arrow with no stray `?`/`→`; the "top-left" mirroring in `restricted_settings_message` is the right call for RTL (Android Settings mirrors) and it is the only screen-position string in the file, so it is consistently done; agreed terms إمكانية الوصول / مجموعة (deck) / بطاقة (card) / حزمة اللغة / اختصار (hotkey) / تحويل النص إلى كلام / التقاط الشاشة / تنزيل / حذف are each used consistently; Quick Settings tile matches Android Arabic (مربع الإعدادات السريعة); the Japanese "Example:" samples (聞く, ★★★, noun, Word Audio field names) are correctly left unlocalized; the CC BY 2.0 FR license string is untouched; number+noun agreement after large placeholders (500,000 حرف، 5 نجوم، ثانيتين) is correct.

## Plurals coverage appendix

All three `<plurals>` blocks in the file were checked category-by-category. The bare-noun `one`/`two` forms without a digit are correct, idiomatic Arabic (the digit-less dual/singular is exactly what CLDR-style Arabic UI should do).

- `word_detail_senses_count` ✓ — zero `%d معنى` ok; one معنى واحد ✓; two معنيان ✓ (correct dual); few `%d معانٍ` ✓ (3–10 broken plural, genitive); many `%d معنى` ✓ (11–99 singular accusative); other `%d معنى` ✓ (100+/fractions singular).
- `word_detail_chars_count` ✓ — one حرف واحد ✓; two حرفان ✓; few `%d أحرف` ✓ (plural of paucity, ideal for 3–10); many `%d حرفًا` ✓ (correctly written with tanwīn alif); other `%d حرف` ✓.
- `lang_search_match_count` ✓ — one نتيجة واحدة ✓ (fem. agreement); two نتيجتان ✓; few `%d نتائج` ✓; many/other `%d نتيجة` ✓.

(Related non-plurals count strings `word_anki_in_decks` and `settings_capture_displays_count` have agreement limitations — see their finding rows.)

## Needs in-app RTL verification

- The four Latin-first strings above (`notif_title` in the notification shade especially) — does firstStrong flip them LTR?
- `hymt_legal_message` — the «§5(b)» cluster (ON + digit + Latin) is the highest-risk mixed run in the file; confirm it doesn't render as "(b)5§".
- Byte-progress pairs with only `/` between two number runs: `bergamot_status_downloading`, `bergamot_warmup_downloading`, `bergamot_warmup_downloading_multi`, `tr_service_status_quota_fmt` — confirm so-far appears before total when read RTL.
- Nav-path arrow chains crossing the Latin PlayTranslate token: `accessibility_dialog_message`, `overlay_icon_a11y_required_message`.
- `anki_card_type_row_empty` افتراضي (PlayTranslate) — paren mirroring around a trailing Latin run.
- `capture_display_row_label` — Arabic label + spaced dash + Latin display name.
- `word_detail_numbered_definition` when definitions are English (Latin-script target).
- Truncation at 8–9sp: `live_mode_pause_label` إيقاف مؤقت (two words where English is "Pause"), `floating_menu_btn_capture_region` two-line منطقة\nالالتقاط, and the مفعّل/معطّل state badges (`capture_lifecycle_state_on/off`) in their pill.
- System-wording matches on a real Arabic-locale device: "Display over other apps" title, metered-network phrasing, and that the ⋮ button does sit top-left on the mirrored App-Info page.

## Verdicts

- Register consistency: **pass** — uniform formal MSA, no dialect or register mixing.
- Terminology consistency: **pass with one fix** — only the Pause/إيقاف split (`live_mode_pause_auto_label`).
- Android-settings wording: **mostly pass** — accessibility/Quick Settings/restricted-settings match; overlay-permission title needs device verification.
- Plurals: **pass** — all 3 blocks, all 6 categories each, grammatically correct including the digit-less one/two forms.
- Grammar around placeholders: **pass with minor fixes** — thoughtful restructuring overall (حجم X هو Y, رفض %1$s…); a handful of agreement/idafa slips flagged.
- RTL/mixed-direction: **good with 4 at-risk strings** — arrows, ؟, and top-left mirroring all deliberately and consistently handled; Latin-first strings need RLM or verification.
- Truncation risk: **low** — two candidates to eyeball on device.
- Legal text: **faithful** — §5(b), region list, and affirm-and-warrant force intact; fix the موافق/أوافق button-label mismatch.
- Overall: **fix-then-ship** — no build-breakers, no mistranslations of substance; apply the ⚠ rows and run the RTL verification pass.
