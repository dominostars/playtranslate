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

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; six-way plural CLDR sets; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| `anki_content_pitch_position_desc`, `anki_content_frequency_values_desc`, `anki_content_frequency_stylized_desc`, `anki_content_frequency_harmonic_desc` | ⚠️ | …“PitchPosition” … “Frequency” … “FreqSort” … (English curly quotes) | …«PitchPosition» … «Frequency» … «FreqSort» … | One recurring fix across all four new desc strings. The English field **names** are correctly kept verbatim — but the *quote glyphs* around them are a localization choice the file already settled: the adjacent, already-reviewed flag descs render English template-field names in Arabic guillemets (`«Is Vocabulary Card»` 553, `«IsSentenceCard»` 556, `«IsTargetedSentenceCard»` 559). The new strings instead keep the source's curly `“ ”`, so two sibling groups of `anki_content_*_desc` (lines 539–548 vs 553–562) quote field names differently. File-wide the convention is `« »` (21 pairs) and these 6 `“ ”` are the only deviation. Switch to `« »`; do **not** touch the field-name text itself. |
| `anki_content_frequency_harmonic_desc` | ⚠️ | …المتوسط التوافقي ل**ترددات** الكلمة في القواميس … الأكثر **تكرارًا** | …المتوسط التوافقي ل**تكرارات** الكلمة في القواميس … الأكثر **تكرارًا** | Term drift: "frequency" is rendered **تكرار** everywhere else in the file (`anki_content_frequency`, `anki_content_frequency_values_desc`, `yomitan_category_frequency`, and twice in this very string: `حسب التكرار`, `الأكثر تكرارًا`), but the genitive plural here switches to **تردد/ترددات** (the acoustic/signal sense). Both are technically valid for a harmonic mean of frequencies, but the one-term-one-translation rule wants تكرار. Internal switch inside a single string makes it the clearest case. |
| `anki_content_frequency_stylized_desc` | 💬 | …مُخرَجة بالتنسيق المنسَّق الخاص بـ JPMN… | …مُخرَجة بتنسيق JPMN المُنمَّق الخاص به… (or drop المنسَّق) | Root-echo jingle: التنسيق + المنسَّق share ن-س-ق back-to-back ("the formatted format"). English is plain "JPMN's own styled format". Polish only. |

## Clean areas (delta)
Scrutinized and passed:

- **The six plural forms — the headline risk — are correct in both `<plurals>`, and crucially the explicit digit is kept in every category** (verified programmatically: all six `yomitan_import_summary_count` items carry both `%1$d` and `%2$d`; all six `yomitan_import_summary_more` items carry `%1$d`). The digit-less `قاموس واحد`/`قاموسان` convention the brief warns against is **not** used — every form is built around the shown numeral.
  - `yomitan_import_summary_count` (agrees with the **total** `%2$d`): the counted noun (تمييز) is correctly inflected per category — `قاموس` (zero/one), dual **قاموسين** (two), plural-genitive **قواميس** (few, 3–10), singular-accusative-with-tanwīn **قاموسًا** (many, 11–99 — the tanwīn alif is written correctly, a frequent MT miss), singular **قاموس** (other, 100+). All six right.
  - `yomitan_import_summary_more` ("+N more", noun elided): `آخر` (one) / `آخران` (dual, two) / `أخرى` (zero, few, many, other). Each form is grammatically valid agreement for the implied masculine singular ملف/اسم ("more files/names" per the EN comment); few/many/other correctly collapse to the fem-sg `أخرى`. Internally consistent; no defect.
- **Terminology reuse is strong.** النبرة الصوتية (pitch accent) matches `yomitan_category_pitch_accent` / `yomitan_page_description`; التكرار (frequency) matches `anki_content_frequency` & friends; الكلمة المميَّزة (highlighted word) matches every sibling `anki_content_*_desc` verbatim; تحويل النص إلى كلام (`audio_source_tts_name`) matches the file-wide TTS term; تنزيل…وتثبيت (`yomitan_auto_update_subtitle`) matches the download (`lang_download`=تنزيل) and install (`settings_ocr_installing`=التثبيت) verbs; جارٍ التحميل…/تعذّر التحميل (`audio_loading`/`audio_error_loading`) match the loading/`تعذّر` error families; لا توجد نتائج (`audio_no_results`) is byte-identical to `lang_search_no_results` and `dictionary_status_no_results`; الصوت (`audio_source_picker_title`) matches `tts_voice_picker_title`/`anki_group_audio`.
- **RTL / mixed-script.** No string in the set starts with a Latin token **except `audio_source_commons_name`** (= "Wikimedia Commons", an untranslated brand with no Arabic around it — firstStrong laying it out LTR is correct/harmless). `llm_backend_base_url_invalid` opens with Arabic (استخدم), so the line base direction is RTL and `https://` / `http://` / `(LAN)` ride as embedded LTR runs — the standard, correct approach. Confirmed file-wide there are **zero** bidi control chars (U+200E/200F/202A-E = 0), so the file's settled convention is to lean on the platform UBA with no manual RLM; the new strings follow it. The prior section's "prefix RLM" suggestion was never adopted anywhere, so I'm not introducing a finding that fights 1181 lines of precedent — flagging RTL render as **in-app verification** instead (see below). The `الخاص بـ JPMN`/`بـ http://` glue-to-Latin pattern matches the established lines 134/268/545.
- **The `Example:` / field-name rule is honored.** `مثال: 0,2` and `مثال: ★★★` keep the sample as-is; the six English field names are left untranslated (only their *quote glyphs* are flagged above, never the names).
- **Short-label truncation: low.** `audio_source_picker_title`=الصوت (one short word for "Audio"), `audio_no_results`, `audio_error_loading` are all tight. `llm_backend_advanced_header`="Advanced" → خيارات متقدمة expands to two words for an ADVANCED-card header (not a tiny bottom-bar label); reads naturally and matches the file's tendency to expand bare headers — acceptable, eyeball on a narrow card if convenient. `yomitan_auto_update_label`=تحديث تلقائي and `llm_backend_base_url_label`=عنوان URL مخصص are normal row labels.
- **Naturalness/register:** uniform formal MSA, no calques. `llm_backend_base_url_invalid` reads idiomatically ("…لا يُسمح بـ http:// إلا لعنوان محلي أو ضمن الشبكة المحلية (LAN)") and helpfully glosses LAN. `yomitan_importing_progress` (جارٍ استيراد N من M…) and the summary titles (اكتمل الاستيراد / تعذّر الاستيراد) are natural. No `؟` needed — none of the 29 is a question.

## Needs in-app RTL verification (delta)
- `llm_backend_base_url_invalid` — the densest mixed run in the set: Arabic + `https://` + `—` + `http://` + `(LAN)` on one line. Confirm the two URL schemes and the parenthesized LAN sit in reading order and the em-dash doesn't drift.
- `anki_content_*_desc` (the four) — quoted English field name immediately followed by `في` + a Latin brand span (`“PitchPosition” في Lapis`); confirm the quote glyph + brand cluster renders RTL-correct (and re-check after the `«»` fix).
- `yomitan_import_summary_*` lines with a trailing `%1$s` file-name list (مستورَدة بالفعل: …, تعذّرت قراءتها: …) — confirm the Latin file-names appear after the Arabic label when read RTL.

---

# Delta review — 2026-07-14 sync (174 keys)

Scope: the 170 new + 4 changed keys (History screen, Advanced LLM prompt editor, in-app updater, game-audio trim editor, single-app capture, OCR picker, the 38 `misc_*` dictionary chips). Mechanical layer re-verified programmatically over the 174: placeholder parity 174/174; every `<xliff:g>` inner content + `id` + `example` byte-identical to EN; the six bare `{text}` `{source}` `{source_code}` `{target}` `{target_code}` `{N}` `{strings}` literals intact and Latin; `\n` preserved in `floating_menu_capture_screen`; no raw `'`/`"`; `<plurals>` carries all six Arabic CLDR categories with no dupes/extras; zero bidi control chars (matches the file's settled no-RLM convention); `:app:processDebugResources` BUILD SUCCESSFUL. **No 🛑.**

The `&amp;` count differs in `update_dialog_download` (EN `Download &amp; install` → AR `تنزيل وتثبيت`) — that is the ampersand correctly becoming the conjunction **و**, not a broken escape. Not a finding.

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `hotkey_auto_hint_title` | ❌ | اضغط لبدء/إيقاف \<hint\> **تلقائيًا** | اضغط لبدء/إيقاف **الوضع التلقائي لـ**\<hint\> | **Meaning inversion.** Renders "اضغط لبدء/إيقاف فوريغانا تلقائيًا" = "press to start/stop furigana **automatically**". تلقائيًا is a free accusative adverb, so inside a verbal sentence it binds to the nearest verbal element — بدء/إيقاف — and the auto-ness lands on the *starting and stopping*. But the hotkey is the **manual** trigger for the **automatic** mode. The sibling row proves the right pattern: `hotkey_auto_translation_title` = "اضغط لبدء/إيقاف **الترجمة التلقائية**" — an *adjective* bound to its noun, which survives embedding. Two rows, same section, same slot, one broken. الوضع التلقائي is the file's own established term (`settings_hide_overlays_during_auto_mode`, `settings_overlay_mode_subtitle`) and is **gender-safe**: the placeholder attaches via لـ, so nothing has to agree with a runtime فوريغانا vs بينيين (an adjective التلقائي/التلقائية would have to guess). Alt, if the exact live-mode label must be preserved: «\<hint\> تلقائيًا» in guillemets — the quotes bound the label so the adverb cannot escape, matching the file's convention of quoting UI labels (`status_hold_hint`, `overlay_hide_controls_message`). **Do NOT also "fix" `hotkey_auto_hint_dialog_title`** — see Clean areas. |
| `ocr_source_label` | ⚠️ | تم التعرف على النص بواسطة %1$s | **تمت القراءة بواسطة** %1$s | Glossary **hard constraint**: this must mirror the *structure* of the committed `translation_source_label` = "**تمت الترجمة بواسطة** %1$s" (line 278), the way ja swaps 翻訳→読み取り and ru swaps Перевод→Распознано. The delta instead translated the English afresh: تمت→تم, and the masdar replaced by a verb phrase (التعرف على النص). The two attribution lines render one under the other on the same result card (source line, translation line), and the new one is ~70% longer (5 words vs 3) on a muted single-line label. تمت القراءة restores the [تمت + masdar + بواسطة] frame and matches ja 読み取り ("reading") exactly. |
| `tr_service_status_usage_today_fmt` | ⚠️ | اليوم: %1$s **رمز** | **التوكنات اليوم:** %1$s | **Two defects, one line.** (a) *Term collision*: the same delta uses **رمز** for *language code* — `llm_prompt_kw_source_code_desc` ("رمز لغة المصدر، مثل ja") and `llm_prompt_kw_target_code_desc`. رمز appears nowhere else in the file, so all three uses are new and one word now carries two terms of art inside the same LLM feature. "اليوم: 12,345 رمز" reads as "12,345 **codes**". (b) *Number agreement*: 12,345 ends in 45 → 11–99 band → tamyīz manṣūb → **رمزًا**, not رمز. Token counts are nearly always in that band, so the **dominant** case is ungrammatical. The suggested label-then-value form fixes both at once: it dodges number agreement entirely (the pattern this report already recommended for `settings_capture_displays_count`) and توكن is the standard Arabic rendering of the LLM sense. Minimal alt, fixing only the collision: "اليوم: %1$s توكن". |
| `misc_colloquial` (+ `misc_slang`) | ⚠️ | عامّي / دارجة | **محكي** / دارجة | **Informality cluster collapses.** The glossary requires these four to stay distinguishable — they render side by side on one word. But **العامية and الدارجة are the two standard Arabic synonyms for the identical thing** (colloquial/vernacular Arabic; the Mashriq says عامية, the Maghreb says دارجة). A reader sees the same tag twice. Keep **دارجة** for slang — it anchors `misc_internet_slang` = دارجة الإنترنت and `misc_manga_slang` = دارجة المانغا, a coherent system worth preserving — and move *colloquial* off it. محكي ("spoken", as in العربية المحكية) is the Arabic-linguistics term for everyday spoken language and is unmistakably distinct from دارجة. Cluster then reads محكي / غير رسمي / … / دارجة. |
| `misc_familiar` | ⚠️ | حميمي | **ودّي** | حميمي is *emotional* intimacy ("intimate, close friend"). The JMdict `fam` tag is a **register** — casual speech used with intimates — not an emotional quality. It also collides in feel with `misc_endearing` = **تحبّبي** (affectionate), which sits in the same chip row. ودّي reads as a tone/register and stays distinct from both تحبّبي and غير رسمي. |
| `update_error_retry` | ⚠️ | حاول مرة أخرى | **إعادة المحاولة** | This is a **button**, and it is the only imperative among them. Every other delta button is a masdar: تجاهل، إزالة، حذف، نسخ، تنزيل وتثبيت، فتح الإعدادات، الحفظ على أي حال، استخدام التحديد، تشغيل التحديد، حذف النموذج، الاحتفاظ بالنموذج، عرض ملاحظات الإصدار. And the file **already ships the noun**: `backend_cooldown_retry_at`/`_on` = "**إعادة المحاولة**" (lines 1516/1518). إعادة المحاولة is also Android's own Arabic for Try again / Retry, so it matches what the user sees system-wide. Note the imperative is *correct* where it already appears — in body prose (`update_error_incomplete`, `update_error_verification`, `anki_send_failed_message`, `yomitan_*`). Only the button is wrong; do not touch the prose. |
| `floating_menu_capture_screen` ↔ `floating_menu_btn_capture_region` | ⚠️ | التقاط\nالشاشة (new) vs **منطقة\nالالتقاط** (existing, line 1045) | keep the new one; align the existing → **التقاط\nالمنطقة** | These are the **two states of one button** (per the EN comment: the label shown "while the region is full screen"). EN keeps one shape — [Capture]\n[object] — for both. Arabic flips the word order between them and shares nothing. Worse, the *existing* label reads as a **noun** ("the capture region") where EN means an **action** ("capture the region"). The new delta string has the right shape; the old one should follow it. The fix lands on the existing string — flagged here because the brief puts delta-vs-existing drift in scope. |
| `game_audio_trim_use_tts` | ⚠️ | استخدام تحويل النص إلى كلام بدلاً منه | **تحويل النص إلى كلام بدلاً منه** | **Truncation.** 37 chars on a *secondary text button* in the trim editor, sitting beside `game_audio_trim_no_audio` = "بدون صوت" (8). EN is "Use TTS instead" (15) — the blow-up comes from correctly expanding the TTS initialism to the file's established تحويل النص إلى كلام (**keep that**; it matches `audio_source_tts_name`). Dropping استخدام recovers ~7 chars and loses nothing: on a button the action is implied. |
| `settings_debug_log_trace` | 💬 | تسجيل تتبُّع سجل الترجمة | تسجيل تتبُّع **لسجل** الترجمة | Four-noun idafa chain (تسجيل → تتبُّع → سجل → الترجمة) with a س-ج-ل root echo (*tasjīl … sijill*). Grammatical, but hard to parse at a glance. Debug-only row, hence 💬. |
| `update_error_no_space` | 💬 | لا توجد مساحة **حرة** كافية… | لا توجد **مساحة كافية**… | حرّ is the wrong sense of "free" (at liberty, not vacant); the vacancy sense is خالية/فارغة, or just drop the adjective. The file's own dominant term for this state is "مساحة **غير كافية**" (`yomitan_no_space_title`, `yomitan_import_summary_no_space`) and مساحة تخزين elsewhere. Only 💬 because `yomitan_no_space_message` already ships "المساحة الحرة" — so this is a file-wide polish item, not delta drift. |
| `history_delete_confirm_title` | 💬 | حذف هذا **المُدخَل**؟ | حذف هذا **السطر**؟ | The surrounding Arabic history strings call these items **سطر**: `history_clear_confirm_message` ("كل **سطر** محفوظ"), `history_empty_none` ("تظهر **الأسطر**"). EN mixes "entry"/"line" too, so the delta is faithful — but السطر reads more naturally and matches the dialog body, which shows the line itself. |
| `settings_ocr_use_manga_subtitle` | 💬 | …**وهو** مكمّل لأداة OCR المحددة… | …**MangaOCR** مكمّل لأداة OCR المحددة… | The brand name is dropped from EN's third sentence ("MangaOCR is a supplement to the selected OCR"). The antecedent is recoverable from the toggle title directly above, so this is safe — noting only that the name is lost where EN repeats it deliberately. |
| `misc_yojijukugo` | 💬 | تعبير رباعي الأحرف | (keep) | Correct call — the glossary says describe it where no native term exists, and Arabic has none. Flagging only as the **longest chip in the set** (18 chars, vs `pos_noun` = اسم at 3): eyeball it in the chip row before shipping. |

## Clean areas (delta)

**The six-way plural is correct in every category — the headline risk, and it passed.** `settings_yomitan_count_summary` carries all six Arabic CLDR categories, and each is grammatically right *for its own count range*, read with a real number:
- **zero** `تم استيراد 0 قاموس` ✓ — digit + singular, matching the four existing AR plurals.
- **one** `تم استيراد قاموس واحد` ✓ — digit-less, exactly as [`l10n-language-parameters.md`](../docs/l10n-language-parameters.md) sanctions for Arabic.
- **two** `تم استيراد قاموسين` ✓ — and this is the subtle one. **قاموسين, not قاموسان**: استيراد is a masdar and the dual is its مضاف إليه, so the **genitive** dual is required. (Contrast the committed `word_detail_senses_count`, where two = **معنيان** — nominative, because there the noun is a standalone label. Both are right in their own frames; the translator got the frame right.)
- **few** (3–10) `تم استيراد 3 قواميس` ✓ — plural genitive; قواميس is a diptote (صيغة منتهى الجموع) so it takes fatḥa with no tanwīn, which is invisible unvocalized.
- **many** (11–99) `تم استيراد 11 قاموسًا` ✓ — singular accusative tamyīz, **and the tanwīn alif is written** (قاموسًا). This is the exact form the brief asked me to verify against the `word_detail_chars_count` precedent (حرفًا), and it matches.
- **other** (100+) `تم استيراد 100 قاموس` ✓ — singular genitive.

The `example=` attributes on the four new xliff:g spans (zero=0, few=3, many=11, other=100) diverge from EN — but EN only *has* one/other, so there is nothing to copy for the other four, and these values match the file-wide convention already set by `word_detail_senses_count`, `word_detail_chars_count`, `lang_search_match_count` and `dictionary_entries_count`. Not a violation.

**RTL / bidi.** Only two delta strings open with a non-Arabic run, and **both are safe**:
- `hotkey_auto_hint_dialog_title` = `%1$s تلقائيًا` — the placeholder is **not Latin at runtime**: `hint_label_furigana_lower` / `_pinyin_lower` are localized to **فوريغانا / بينيين** in this very file (lines 1036–1037), so the line opens Arabic and the base direction is RTL. It is also **byte-identical to the committed, previously-reviewed `live_mode_auto_with_hint`** (line 988) — which is itself the form *this report's own 2026-06-23 round recommended and got applied*. Correct and consistent; **leave it alone.**
- `service_llm_badge` = `LLM` — a bare initialism with no Arabic around it; firstStrong laying it LTR is right, and it matches the `audio_source_commons_name` precedent.

Everything else opens Arabic. I traced the trickiest mixed runs through the UBA by hand and they resolve correctly: `{N}:` in `llm_prompt_advisory_missing_count` (the curly braces are Bidi_Mirrored, and the mirroring + reordering cancel so the reader sees `{N}` with the colon correctly on its left); `إزالة OpenAI؟` in `tr_service_remove_title_fmt` (؟ is U+061F, Bidi class **AL** — strong RTL — so it lands left of the brand, correctly); `المفتاح ••••4f2a` in `tr_service_key_tail_fmt` (the bullets stay right of the hex tail, i.e. *before* it in reading order). Zero bidi control chars, consistent with the file's 1181-line no-RLM precedent.

**The three-way Remove / Delete / Clear split is exactly right** — the thing the glossary most expects to drift. Services are **أزيلَ**: `tr_service_remove_confirm` = إزالة, `tr_service_delete_cd` = إزالة الخدمة. Entries and models are **حُذِفَ**: `history_action_delete` = حذف, `settings_ocr_disable_delete` = حذف النموذج. All history is **مُسِحَ**: `history_clear_menu` = مسح السجل, `history_clear_confirm_title` = مسح كل السجل؟. And `tr_service_remove_message` keeps both verbs apart within one sentence ("**إزالة** الخدمة من القائمة و**حذف** مفتاح API") exactly as EN does.

**Number-noun agreement around the other placeholders.** `settings_ocr_disable_manga_msg` isolates the size in parentheses — "(68 MB)" — so agreement never arises, and the alternative-question أم (not أو) is the correct particle. `update_error_no_space` puts the Arabic word first inside the parens ("المطلوب 230 MB"), making it a nominal sentence and embedding the Latin run safely. `game_audio_trim_duration` abbreviates seconds to **ث**, which looks like drift (the file spells out ثانية / ثانيتين elsewhere) but is in fact a **deliberate dodge**: spelled out, one fixed string would have to produce both "2.4 ثانية" and "147 ثانيةً" — impossible. The abbreviation doesn't inflect. Correct call; noting only that it is the file's first use of it.

**Terminology.** Checked each glossary term against what the **committed** file already uses, not against English: **موجّه** (prompt) is used in all 14 `llm_prompt_*` strings with no drift to طلب/استعلام — and where EN itself says "*request*" in the row subtitles, AR correctly says **الطلب**, preserving EN's own distinction. **الكلمات المفتاحية** (keyword). **المزوّد** (Provider) matches the committed `tr_service_order_footer` ("لكل **مزوّد**"). **خدمة ترجمة** matches the committed page title `settings_cell_translation_services` = خدمات الترجمة. **السجل / سجل النصوص** (History) is one noun throughout. **اقتصاص** (Trim) + **التحديد** (selection) are consistent across all six `game_audio_trim_*`. **صوت اللعبة** (Game audio) reads as a noun phrase in both the pill and the section header. **LLM** stays Latin in all four places. **التراكبات** (`floating_menu_panel_overlays`) matches `settings_overlay_mode_subtitle` and `settings_hide_overlays_during_auto_mode` verbatim. **الترجمة المباشرة** (live translation) vs **الوضع المباشر** (live mode) preserves EN's own distinction. The **"captured"** verb is the established **الملتقَط/الملتقَطة** family in all three places the glossary names (`settings_cell_history_summary_on/off`, `history_toggle_subtitle`) plus `error_single_app_not_fullscreen` — no second verb introduced. The **لـ / بـ glue-with-a-space** before a Latin run (`لـ LLM`, `لـ PlayTranslate`) matches the committed pattern at lines 134/260/268/390/392/417/600.

**Grammar sampled with real values.** `anki_game_audio_permission_denied` — "يبقى تسجيل صوت اللعبة **معطّلًا**" is accusative as the خبر of يبقى, with the tanwīn alif written. `service_account_required` / `_free` / `service_no_account_required` — "يتطلب **حسابًا**" / "لا يتطلب **حسابًا**", accusative, correct and parallel across the trio. `error_single_app_not_fullscreen` — "**ستُستأنف**", feminine, agreeing with الترجمة.

**The other three `misc_*` clusters hold.** *Offensiveness*: تحقيري / مسيء / بذيء / شتيمة — four genuinely distinct words (disparaging / offensive / obscene / insult); شتيمة is a noun where the other three are adjectives, but the family already mixes forms in both languages (لغة الأطفال، تعبير نسائي، محاكاة صوتية are nominal, mirroring EN's "Children's", "Internet slang", "Four-character compound"). *Obsolescence*: مهجور / بائد / قديم الطراز / تاريخي — distinct; مهجور is the classical lexicographic label for an archaic word, and بائد ("extinct") carries the stronger "no longer exists at all" of *obsolete*. *Honorifics*: تعظيمي / تواضعي / مهذب — cleanly map to sonkeigo / kenjougo / teineigo. `misc_kana_only` / `misc_kanji_only` keep kana/kanji as loanwords in Arabic script (كانا / كانجي), consistent with the file's فوريغانا / بينيين. Register and form match the committed `pos_*` siblings (bare classical terms: اسم، فعل، صفة) — nouns there, adjectives here, which is right, since EN does the same.

**Deliberate decisions honored.** `stream_kind_share_one_app` = مشاركة تطبيق واحد and `_entire_screen` = مشاركة الشاشة بأكملها match AOSP SystemUI's Arabic, and `stream_kind_prompt_message` **re-uses both labels byte-for-byte** inside its body — the cross-reference is intact. `llm_prompt_kw_source_desc` / `_target_desc` correctly keep **Japanese** / **English** in Latin. `llm_status_low_memory_badge` untouched.

## Needs in-app RTL verification (delta)

- `tr_service_key_tail_fmt` — "المفتاح ••••4f2a": the bullets are neutrals and the tail is a digit+letter run whose leading digit resolves to AN (Arabic Number) after an AL. Confirm the tail sits *left* of the bullets and the bullets don't jump.
- `llm_prompt_advisory_missing_*` / `_fatal_missing_*` — the `{token}` literals with mirrored curly braces. They should read `{N}` / `{source}` with punctuation on the left; confirm no brace flips visually.
- `game_audio_trim_duration` — "تم تحديد 2.4 ث · تم تسجيل 147 ث": two number runs and a middle dot separator on one line, above the waveform *and* as a card-editor row title.
- `settings_ocr_disable_manga_msg` — paren mirroring around "(68 MB)".
- Chip row width: `misc_yojijukugo` (تعبير رباعي الأحرف), `misc_internet_slang`, `misc_manga_slang`, `misc_onomatopoeia` — the four longest.
- `game_audio_trim_use_tts` on the trim editor's secondary button row (see finding).
- `probe_initializing` = جارٍ التهيئة… in its ~1.5 s chip (consistent with the file's جارٍ + masdar progress pattern, but it is a small chip).

## Verdicts (delta)

- Mechanical: **pass** — 0 🛑; resources build.
- Plurals: **pass** — all six categories, each correct for its range, including the accusative tanwīn on `many` and the genitive dual on `two`.
- RTL / bidi: **pass** — no unsafe Latin-first string; the one that looked unsafe resolves to Arabic at runtime.
- Terminology: **pass with one collision** — رمز doing double duty as *code* and *token*.
- `misc_*` clusters: **fix two** — عامّي/دارجة collapse, and حميمي's wrong sense.
- Register: **pass with one slip** — the `update_error_retry` imperative among masdar buttons.
- Grammar around placeholders: **pass with one inversion** — `hotkey_auto_hint_title`.
- Overall: **fix-then-ship** — 1 ❌, 7 ⚠️, 5 💬; no build-breakers.

---

## Delta review round 2 — 2026-07-14

Fresh, independent re-derivation of all 174 delta keys against EN, with the round-1
corrections in place. **Primary target: regressions introduced by the fixes.**

Mechanical layer re-verified programmatically over the 174: placeholder parity
174/174; every `<xliff:g>` span (inner text, `id`, `example`) byte-identical to EN;
the eight bare `{text} {strings} {source} {source_code} {target} {target_code} {N}
{context}` literals intact and Latin; `\n` preserved in `floating_menu_capture_screen`;
no unescaped `'`; `<plurals>` carries exactly the six Arabic CLDR categories with a
`%d` in every category that needs one; **zero** bidi control chars (matches the file's
1181-line no-RLM convention); all **38 `misc_*` labels byte-distinct** (`renderMisc`
calls `.distinct()`, so a duplicate would silently collapse — none does), none
contains the `" · "` join separator, and none collides with a `pos_*` label;
`:app:processDebugResources` **BUILD SUCCESSFUL**. **No 🛑.**

Of the 13 strings round 1 changed, **11 are correct and 2 regressed.** Both regressions
are below, plus one thing round 1 missed.

## Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `hotkey_auto_hint_title` | ❌ | اضغط لبدء/إيقاف الوضع التلقائي **لـ** ⟦%1$s⟧ | اضغط لبدء/إيقاف **«**⟦%1$s⟧ **تلقائيًا»** | **Round-1 regression — two defects.** **(a) The `لـ ` glue is orthographically broken here.** `لـ` is lam + **TATWEEL (U+0640)** + space: a device that exists *only* to carry an Arabic prefix particle onto a **non-joining Latin** token. All 17 other tatweels in the file prove it — every one precedes `PlayTranslate`, `DeepL`, `JPMN`, `LLM`, `http://`, `§5(b)`, or a `%1$s` that is a brand (`llm_backend_get_key_title_fmt`→"OpenAI", `overlay_hide_controls_title`→app name). But **this `%1$s` is Arabic at runtime**: `HotkeysSettingsActivity.kt:116` passes `hintLabel` = `overlay_mode_option_furigana`/`_pinyin` = **فوريغانا / بينيين** (values-ar 1077/1079; the other candidate, `hint_label_*_lower` at 1036/1037, is Arabic too). So the row renders **«الوضع التلقائي لـ فوريغانا»** — a lam with a dangling connector stroke, a space, then the word. Correct Arabic prefixes the lam directly (لفوريغانا). Round 1 justified the لـ by citing "the committed pattern at lines 134/260/268" — but that is the *Latin* pattern; it mis-transferred. **(b) It breaks the mode's cross-reference.** EN names this mode identically in all three places it appears — `hotkey_auto_hint_title` ("Tap to start/stop **Auto {Furigana}**"), `hotkey_auto_hint_dialog_title` ("**Auto {Furigana}**") and the committed floating-menu `live_mode_auto_with_hint` ("**Auto {Furigana}**"). AR now has **three** names: الوضع التلقائي لـ فوريغانا on the row vs **فوريغانا تلقائيًا** in the dialog *and* the menu. The suggested form is **round 1's own stated alternative** and fixes both at once: no tatweel; the quoted span is **byte-identical** to `hotkey_auto_hint_dialog_title` and `live_mode_auto_with_hint`, restoring the 3-way cross-reference; and the guillemets bound the label so `تلقائيًا` still cannot escape and bind to `بدء/إيقاف` — which was round 1's original (correct) objection. Guillemets are the file's own convention for quoting UI labels (27 pairs; `status_hold_hint` quotes two Arabic labels exactly this way) and are Bidi_Mirrored, so they mirror correctly in the RTL paragraph. |
| `game_audio_trim_use_tts` | ⚠️ | تحويل النص إلى كلام بدلاً منه (29) | **استخدام تحويل النص إلى كلام** (27) | **Round-1 regression — it cut the wrong word.** Round 1 dropped `استخدام` for width. But `game_audio_trim_save` — **the button beside it in the same row** (`activity_game_audio_trim.xml` 87–114: `btnTrimUseTts`, `btnTrimNoAudio`, Space, `btnTrimSave`) — is **استخدام التحديد**. EN deliberately parallels the pair: "Use TTS instead" / "Use selection". AR now keeps the verb on the *short* button and strips it from the *long* one, so a bare noun phrase ("text-to-speech instead of it") sits next to a proper action noun. `بدلاً منه` is the droppable half anyway: **منه** ("of **it**") has no on-screen antecedent — and when the sibling button reads `بدون صوت` there is no audio for it to refer to. The suggestion restores the استخدام X parallel, keeps the file's established TTS term (تحويل النص إلى كلام = `audio_source_tts_name` — do **not** shorten to Latin "TTS"; pt-BR's "Usar TTS" is a measured locale-specific exception), and is **2 chars shorter than the current text**, so it is not a width regression. The row is tight either way (29+8+15 Arabic chars across three `wrap_content` buttons) — eyeball on device regardless. |
| `cd_change_source_language`, `cd_change_target_language`, `llm_prompt_kw_source_code_desc`, `llm_prompt_kw_target_code_desc` | ⚠️ | تغيير **لغة المصدر** / تغيير **اللغة الهدف**  ·  رمز **لغة المصدر** / رمز **اللغة الهدف** | **تغيير اللغة المصدر** / تغيير اللغة الهدف  ·  **رمز اللغة المصدر** / رمز اللغة الهدف | **Round 1 missed this.** One English pattern → two Arabic constructions, and they are **paired**: source uses iḍāfa (bare **لغة** المصدر), target uses apposition (definite **اللغة** الهدف). The two `cd_change_*` are the contentDescriptions of the two **adjacent** language chips (TalkBack reads them back to back); the two `kw_*_code_desc` are two **adjacent** rows of the same 8-row keyword legend. EN is perfectly symmetric in both pairs. Worse, "target language" appears in two forms inside that one legend: `llm_prompt_kw_target_desc` = "الاسم الإنجليزي **للغة** الهدف" vs `llm_prompt_kw_target_code_desc` = "رمز **اللغة** الهدف". The committed file already settled the form — **apposition**: `anki_content_sentence_translation_desc` = "إلى **اللغة الهدف** التي اخترتها", `anki_content_sentence` = "**الجملة المصدر**". So align the two *source* strings to it; the two target strings already match. Both constructions are grammatical — this is a one-term-one-translation fix (reviewing rule 3), not a grammar fix. **`llm_prompt_kw_source_desc` / `_target_desc` need no change**: لـ+لغة and لـ+اللغة both surface as **للغة**, so they are already spelled compatibly with either standard. |
| `misc_familiar` | 💬 | ودّي | (keep) | Round 1's حميمي→ودّي is sound and I concur: it is byte-distinct from `misc_endearing` (تحبّبي) and `misc_informal` (غير رسمي), and it reads as a tone rather than emotional intimacy, which was the point. Noting only that modern ودّي is overwhelmingly "amicable/friendly" (مباراة ودية، علاقات ودية) — a *disposition* — where JMdict `fam` is a *register*. No single-word Arabic register label lands closer without re-colliding with تحبّبي, so **keep** unless a native reviewer prefers حميم. |

## Round-1 fixes re-derived and confirmed (no regression)

The other 11 all hold up under fresh derivation:

- **`ocr_source_label`** = تمت القراءة بواسطة ⟦%1$s⟧ ✓ — now mirrors the committed `translation_source_label` (line 278) = "تمت الترجمة بواسطة ⟦%1$s⟧" frame-for-frame ([تمت + masdar + بواسطة]). The two attribution lines stack on the same result card; they now read as a set. Matches ja's 翻訳→読み取り swap.
- **`tr_service_status_usage_today_fmt`** = التوكنات اليوم: ⟦%1$s⟧ ✓ — **both** defects gone. Verified programmatically: **توكن now appears exactly once in the whole file**, and رمز is left to mean *code* in `llm_prompt_kw_source_code_desc`/`_target_code_desc` — collision cleared. And the label-then-value form dodges the tamyīz problem entirely: the old "%1$s رمز" would have needed **رمزًا** for 12,345 (ends 45 → 11–99 band), i.e. the dominant case was ungrammatical. Now no number-noun agreement arises at all. توكن is a transliteration rather than an Academy term, but the Academy-ish alternative (الرموز المميّزة) re-collides with رمز — which is exactly what the fix was for. Keep.
- **`update_error_retry`** = إعادة المحاولة ✓ — byte-matches the committed `backend_cooldown_retry_at`/`_on` (1516/1518) and Android's own Arabic for Retry. The imperative/masdar split is now **systematic**, not accidental: `حاول مرة أخرى` survives in exactly the 9 *body-prose* strings (`update_error_incomplete`, `_verification`, `anki_send_failed_message`, `yomitan_*`, …) and **zero** buttons; every delta button is a masdar (تجاهل، إزالة، حذف، نسخ، تنزيل وتثبيت، فتح الإعدادات، الحفظ على أي حال، استخدام التحديد، تشغيل التحديد، حذف النموذج، الاحتفاظ بالنموذج، عرض ملاحظات الإصدار، إعادة تعيين). Correctly left the prose alone.
- **`error_capture_blocked_secure`** = التطبيق الملتقَط يحظر… ✓ — and this is better than EN, which says "**this** app blocks screen capture" (ambiguous: could read as PlayTranslate itself). AR disambiguates to *the captured app*, matching the string's own comment and its sibling `error_single_app_not_fullscreen`. The الملتقَط/الملتقَطة family is now used in all 6 places it belongs and nowhere else.
- **`history_delete_confirm_title`** = حذف هذا السطر؟ ✓ — and **مُدخَل is now fully gone from the History surface** (verified file-wide: the only surviving مدخل is `anki_content_expression` = "الكلمة / المدخل", the dictionary-*headword* sense — a different domain, no collision). سطر is now the single noun across `history_clear_confirm_message`, `history_empty_none`, and this dialog.
- **`update_error_no_space`** = لا توجد مساحة كافية… ✓ — حرّ (at liberty) correctly dropped; now aligned with `yomitan_no_space_title` (مساحة غير كافية).
- **`settings_debug_log_trace`** = تسجيل تتبُّع لسجل الترجمة ✓ — the لـ breaks the 4-noun iḍāfa chain and it parses cleanly now. (Note this لـ is a *plain* lam on an Arabic word — **no tatweel** — which is exactly what `hotkey_auto_hint_title` should have done. The two fixes were applied inconsistently.)
- **`misc_colloquial`** = محكي, **`misc_slang`** = دارجة ✓ — distinct, and the دارجة / دارجة الإنترنت / دارجة المانغا system is coherent and worth having preserved. Honest caveat: دارجة most literally means *colloquial*, so it is doing "slang" duty; but Arabic has no crisp native slang≠colloquial split, the two labels are unmistakably different words, and محكي (cf. العربية المحكية) reads correctly as colloquial and pairs neatly against `misc_literary` = أدبي. Works.
- **`misc_female_speech` / `misc_male_speech`** = لغة النساء / لغة الرجال ✓ — a shift from "term" to "language", but sanctioned by the upstream JMdict tags themselves ("female **term or language**") and by the Japanese label they render (女性語, literally "women's language"). It also builds a coherent trio with `misc_childrens` = لغة الأطفال. Distinct, correct width. Fine.

## Clean areas (round 2)

**The six-way plural, re-read at every count band with a real number.** `settings_yomitan_count_summary` is correct in all six, and the two subtle ones are genuinely right:
- **two** = `تم استيراد قاموسين` — **قاموسين, not قاموسان**. استيراد is a masdar and the dual is its مضاف إليه → **genitive** dual (-ayn). Contrast the committed `word_detail_senses_count` where two = **معنيان** (nominative) because there the noun is a standalone label. Different frames, both right.
- **many** (11–99) = `تم استيراد 11 قاموسًا` — singular accusative tamyīz **with the tanwīn alif written**. This is the form MT most often misses.
- **few** (3–10) = `3 قواميس` (plural genitive; قواميس is a diptote, so no tanwīn — invisible unvocalized) · **other** (100+) = `100 قاموس` (singular genitive) · **one** = digit-less `قاموس واحد` · **zero** = `0 قاموس` (and per the EN comment the cell only renders once dictionaries exist, so zero never fires).

**Number-noun agreement with real values dropped in, across the whole delta.** Every contact point dodges or satisfies agreement: `game_audio_trim_duration` abbreviates to **ث** (one fixed string cannot produce both "2.4 ثانية" and "147 ثانيةً" — the abbreviation doesn't inflect; correct call); `settings_ocr_disable_manga_msg` isolates the size in parens; `update_error_no_space` fronts the Arabic ("المطلوب 230 MB"); `tr_service_status_usage_today_fmt` now sidesteps it entirely (above). And the **بضع/بضعة polarity is correct in both places it occurs** — `anki_game_audio_row_subtitle` = "آخر **بضع** دقائق" (دقيقة fem → بضع, no ة) and `settings_llm_context_subtitle` = "آخر **بضعة** أسطر" (سطر masc → بضعة, with ة). That is the classic Arabic gender-polarity trap and it is right both times.

**RTL / bidi at the Latin-token seams — the brief's priority.** Programmatic sweep: exactly **two** delta strings open with a non-Arabic run, and both are safe — `hotkey_auto_hint_dialog_title` (`⟦%1$s⟧ تلقائيًا`, where `%1$s` is **فوريغانا/بينيين**, Arabic at runtime, so the paragraph is RTL — round 1's call confirmed by tracing the code) and `service_llm_badge` (bare `LLM`, no Arabic around it; firstStrong LTR is right, matching the `audio_source_commons_name` precedent). Everything else opens Arabic — including `ocr_picker_message`, which deliberately fronts **تقنية** so the line doesn't start on `OCR`. Zero bidi control chars, consistent with the file's no-RLM precedent. The three tatweel seams in the delta that *are* correct (`settings_header_advanced_llm` → `LLM`, `update_unknown_sources_message` and `update_error_wrong_package` → `PlayTranslate`) all precede genuine Latin.

**Cross-references intact.** `stream_kind_prompt_message` re-uses **both** choice-button labels byte-for-byte inside its body (مشاركة تطبيق واحد / مشاركة الشاشة بأكملها) — the AOSP-verbatim wording is preserved on both sides. `settings_ocr_use_manga_subtitle`'s "لا يُنصح به **للترجمة التلقائية**" matches `hotkey_auto_translation_dialog_title` = الترجمة التلقائية. `history_action_anki` and `cd_add_to_anki` are identical by design.

**Terminology.** Re-checked against the *committed* file rather than English: **موجّه** (prompt, 14 strings, no drift to طلب/استعلام — and where EN itself says "*request*" in the row subtitles, AR correctly says **الطلب**, preserving EN's own distinction); **المزوّد**; **الكلمات المفتاحية**; **السجل / سجل النصوص**; **اقتصاص + التحديد**; **التراكبات**; **الملتقَط**; **إمكانية الوصول**. The three-way **إزالة (service) / حذف (entry, model) / مسح (all history)** split holds, and `tr_service_remove_message` still keeps إزالة and حذف apart inside one sentence exactly as EN does.

**Settled decisions honored** (checked, not re-litigated): AOSP share-button wording; `llm_prompt_kw_source_desc`/`_target_desc` keeping "Japanese"/"English" in Latin; `llm_status_low_memory_badge`'s dash; the `misc_*` tags not truncating (they wrap in one `TextView`), so no label was shortened for width.

## Needs in-app RTL verification (round 2)

Unchanged from round 1, plus one new item:
- **`misc_*` chip row is mixed-script by design.** `renderMiscText` joins with `" · "` and `MiscVocabulary.isPassthrough` **passes domain/gazetteer tokens through raw in English**, so an Arabic row can render `محكي · مجازي · medicine`. Neutral separators between an Arabic run and a Latin run — worth one eyeball. Not a string defect (it's the render contract), so not filed.
- `tr_service_key_tail_fmt` — "المفتاح ••••4f2a": bullets are neutrals, and the tail's leading digit resolves to AN after an AL run while `f2a` is L. Densest seam in the delta.
- `game_audio_trim_duration` — two number runs + a middle dot on one line.
- `llm_prompt_advisory_*` / `_fatal_missing_*` — the `{token}` literals; curly braces are Bidi_Mirrored.
- `settings_ocr_disable_manga_msg` / `update_error_no_space` — paren mirroring around "(68 MB)" / "(230 MB)".
- The trim editor's three-button row (see `game_audio_trim_use_tts`).

## Out of delta — informational only, not a finding, do not edit

The committed **`lang_section_offline_models_subtitle`** (line 260) carries the *identical* defect to the `hotkey_auto_hint_title` ❌: `"…دون اتصال لـ ⟦%1$s⟧ ← ⟦%2$s⟧…"`, where `%1$s`/`%2$s` come from `Language.displayName()` → `getDisplayLanguage(Locale.getDefault())`, which returns **Arabic** on an Arabic device ("اليابانية"). So it too renders a dangling `لـ ` before an Arabic word. Flagged only to establish that this is a **real defect class**, not a house convention — the file has no intentional precedent for tatweel-before-Arabic, which is why the delta must not add a second instance.

## Verdicts (round 2)

- Mechanical: **pass** — 0 🛑; resources build.
- Regressions from round 1: **2 of 13 fixes regressed** — `hotkey_auto_hint_title` (❌) and `game_audio_trim_use_tts` (⚠️). The other 11 are correct.
- Plurals: **pass** — six categories, each right for its range, including the genitive dual and the accusative tanwīn.
- Number-noun agreement: **pass** — including both بضع/بضعة polarity calls.
- RTL / bidi: **pass, with one orthography break** — no unsafe Latin-first string; the break is the `لـ ` tatweel above.
- `misc_*` (38 labels): **pass** — all byte-distinct (so `.distinct()` collapses nothing), all four clusters separable after the edits.
- Terminology: **fix one** — the source/target language iḍāfa-vs-apposition split (4 keys, 2 edits).
- Overall: **fix-then-ship** — 1 ❌, 2 ⚠️, 1 💬; no build-breakers.

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
quotes; `<plurals>` categories exactly zero/one/two/few/many/other. `./gradlew :app:processDebugResources`
is green. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | was | now | why |
|---|---|---|---|---|
| `settings_ocr_note_mlkit` | ⚠️ | "سريع حتى في الشاشات المزدحمة بالنص" | "لا يتباطأ حتى مع كثرة النص على الشاشة" | The English comment forbids reusing the literal Fast tier label; the first pass reused «سريع», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

All four new `<plurals>` carry the full six categories, with `one` and `two` digit-less as the file requires — سطر واحد / سطران, قاموس واحد / قاموسان — and few/many agreeing (أسطر / سطرًا; قواميس / قاموسًا). The dual is used where Arabic wants it: أداتا الكاميرا واستيراد الملفات in `settings_ocr_delete_camera_import_note`, قاموسان كانا محدّثين in `yomitan_collection_skipped_count`. ← (not →) separates the settings path in `slow_ocr_prompt_message`, matching `overlay_icon_a11y_required_message`. `update_none_message` reorders its two `<xliff:g>` spans so the sentence opens on الإصدار rather than a Latin token. `camera_no_text_hint` uses a comma where English uses an em dash. محرك (engine) stays distinct from أداة (tool) and نموذج (model) — all three meet in one sentence. مجموعة القواميس for the Yomitan collection is spelled out so it cannot be read as مجموعة, which the file already uses for a deck. `settings_support_check_updates_title_available` byte-matches `update_dialog_title` (تحديث متاح). Mixed Arabic + Latin brand + digits appear in `ocr_label_paddle_*`, `image_import_page_chip` and `update_none_message`; all are bidi-isolated by surrounding Arabic or by the numeric run itself. Formal MSA throughout. **RTL render on device still wants a human eye** — the mechanical layer cannot see mirroring.

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

**PASS.** One ⚠️ found and fixed, no ❌. RTL layout remains the one thing this pass cannot certify from source.

## Delta review 2026-08-04 (8 keys: one-tap card toasts, first-field guard, hide-translations toggle, waveform zoom hint)

Scope: `card_words_in_sentence`, `anki_added_sentence_success`, `anki_added_word_success`,
`game_audio_zoom_hint`, `anki_first_field_unmapped`, `anki_first_field_empty`,
`history_hide_translations_toggle_title`, `history_hide_translations_toggle_subtitle`.
Reviewed against the EN source and its translator comments; report only, no edits made.

Mechanical layer verified programmatically over the delta: every `<xliff:g>` span
byte-identical to EN (inner `%1$s`, `id` and `example` all unchanged, none split or
re-indexed); placeholder multisets identical to EN; `<b>`, `\n`, `\{ \}`,
`&lt;/&gt;/&amp;` counts match; no unescaped `'` or `"`; no duplicate `name`s and no
keys absent from EN; the brand `Anki` untranslated in all four strings carrying it.
`« »` remains the file's only quote pair (31 / 31, zero `“ ”`, zero `\"`), so the two
first-field strings convert EN's curly quotes correctly. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `anki_first_field_unmapped`, `anki_first_field_empty` | ⚠️ | «…من تحديد **الملاحظة**» / «…لتحديد **الملاحظة**» | **الملحوظة** | These two strings are the file's first and only use of Anki's *note* entity, and the entire reason EN introduces it (the file otherwise says بطاقة throughout) is to hand the reader a word to go find inside AnkiDroid. AnkiDroid's own Arabic calls that entity **الملحوظة** — 50 hits on the ملحوظ- lemma across the ten `values-ar` files sampled (إضافة ملحوظة, تحرير الملحوظة, نوع الملحوظات, حذف الملحوظات المحددة, لا يولِّد نوع الملحوظة الحالية أي بطاقات) — and reserves **ملاحظة** for the discourse marker «ملاحظة:» ("Note:") that opens its warning dialogs. So the chosen word is the one AnkiDroid uses for a *remark*, and تحديد الملاحظة reads to an AnkiDroid user as "identify the remark". Counter-consideration to weigh before applying: this file already diverges from AnkiDroid's Arabic on *deck* (مجموعة here, رزمة there) and that passed full review — but deck is a concept PlayTranslate renders in its own UI, whereas note exists in these strings only to be recognized in AnkiDroid's. |
| `anki_first_field_unmapped`, `anki_first_field_empty` | 💬 | «من **تحديد** الملاحظة» | «من **التعرّف على** الملحوظة» | عيّن and حدّد are thesaurus synonyms, so «عيّن قيمة … ليتمكن Anki من تحديد …» spends both on two different acts inside one eight-word sentence, and تحديد الملاحظة can momentarily parse as *specifying* the note — the act the imperative just asked for — rather than *telling one note from another*, which is what Anki's first-field checksum actually does. التعرّف على carries the recognize sense cleanly. **تمييز is not available**: `anki_words_helper` already spends it on "highlight" (اضغط على كلمة لتمييزها في البطاقة). Stacks with the row above; apply to both strings or neither, so "identify" stays one verb. |

### Clean areas (delta) — checked, no findings

**`card_words_in_sentence`** is not the calque it looks like. الكلمات في الجملة reuses the
file's own header template: `anki_group_words_count` ("Words on card") is already
الكلمات في البطاقة, so the card-back header and the review-sheet group header now read
as a matched pair. The tighter iḍāfa كلمات الجملة would be better isolated Arabic and
worse here, because it would break that pair — the locale file wins over the general
preference.

**`anki_added_sentence_success` / `anki_added_word_success`.** تمت agrees with the
feminine إضافة, and the frame matches the sibling `anki_added_no_audio`
(تمت الإضافة إلى Anki) and `anki_adding_in_progress` (جارٍ الإضافة إلى Anki), so the
one-tap toasts read as one family. The genitive heads بطاقة الجملة / بطاقة الكلمة reuse
`anki_mode_sentence` / `anki_mode_word` verbatim (جملة / كلمة), which is what makes the
toast do its job — EN's comment says the toast is where the silently-applied mode
becomes visible, and the contrasting word lands in the same slot in both strings. The
definite iḍāfa was checked for the competing "the card *of this* sentence" reading; both
readings leave the user with the right idea of what was created, so it is not a finding.

**`game_audio_zoom_hint`.** قرّب إصبعيك أو باعد بينهما is the standard Arabic
pinch instruction, not a rendering of the English verb "pinch" — باعَدَ takes بين, so
باعد بينهما is the more correct MSA government, not a wordier باعدهما. مقدار أكبر أو أقل
is the idiomatic أكبر/أقل pairing (as in بدرجة أكبر أو أقل), not a mismatched comparative
needing أصغر. On length: 58 characters sits mid-pack against the peers already shipped
(de 82, ru 68, tr 67, th 46, en 32), and the caption's `TextView` in
`app/src/main/res/layout/anki_game_audio_panel.xml` is `match_parent` × `wrap_content` at
11sp with **no `maxLines` and no `ellipsize`**, so the worst case is a second line inside
a 24dp-padded panel — never a clip. Length read from the layout, not guessed.

**`anki_first_field_unmapped` against the two-line toast clamp.** 53 Arabic characters
plus the field name, against EN's 50 plus the same name — no headroom was spent, and the
Arabic needs the same two lines the English already needs. The added head noun **للحقل**
before «%1$s» is load-bearing rather than padding: it gives the Latin field name an
Arabic anchor so the clause never runs preposition-straight-into-Latin, and it tells the
reader the quoted token is a field name. It also cannot be dropped for brevity without
reintroducing that bidi seam. «…» matches `anki_content_source_pick_title`, which already
quotes a field name this way, and عيّن matches that string's تعيين, so EN's "map" stays
one Arabic verb across the mapping flow (`anki_card_type_edit_mapping_row_label`
تعديل تعيين الحقول, `anki_card_type_basic_no_mapping` تعيين حقول). كوّن in
`anki_field_mapping_unconfigured` renders a *different* EN verb ("Configure") and is not
an inconsistency.

**`anki_first_field_empty`** mirrors EN's own card/note split faithfully
(بطاقة → الملاحظة → بطاقة), which is the right call: EN deliberately keeps both words,
and flattening them to بطاقة would delete the distinction the alert exists to explain.
The implied subject of يجب أن يحتوي is الحقل الأول (masculine) — agreement holds. Full
alert, so length is not a constraint.

**History block.** The toggle title إخفاء الترجمات is a maṣdar, matching every sibling in
the block (حفظ صور الالتقاط, الاحتفاظ بسجل النصوص) rather than an imperative; الترجمات is
the plural the app already uses (`onboarding_a11y_row_translate_sub`,
`overlay_icon_gesture_hold`). The subtitle reuses the two settled History terms instead
of inventing: **النص الملتقَط** joins الجمل الملتقَطة / التطبيق الملتقَط / النص الملتقَط
(`history_toggle_subtitle`, `settings_cell_history_summary_on|off`,
`error_single_app_not_fullscreen`, `tr_service_order_footer`), honouring the
"captured is the app's established verb" constraint; and **سطر** is the same row noun as
`history_delete_confirm_title`, `history_clear_confirm_message`, `history_empty_none` and
the `history_line_count` plurals. EN's row / line / entry all collapse into one Arabic
word here, which is correct — on screen they are one thing. ترجمته agrees with masculine
سطر. The indefinite object in اضغط على سطر follows the reviewed precedent in
`anki_words_helper` (اضغط على كلمة), and اضغط على is the file's single tap verb
(`status_idle`, `overlay_icon_gesture_hold`).

**Diacritics.** الملتقَط carries the same disambiguating fatḥa the file already uses
everywhere (passive ملتقَط, not active ملتقِط); عيّن and قرّب carry only the shadda. No
new tashkīl habit was introduced by this delta.

### RTL render notes (delta)

1. **No string opens on a Latin token.** Four of the eight embed Latin runs and all four
   keep them off the sentence head: `anki_first_field_unmapped` opens on عيّن,
   `anki_first_field_empty` on الحقل (EN opens on the quoted field name — the translator
   prepended the head noun for exactly this reason), and both `anki_added_*` open on تمت
   with `Anki` last. `anki_first_field_empty`'s second sentence flips to VSO —
   يستخدم Anki الحقل الأول — where EN is "Anki uses the first field…"; that flip is what
   keeps the brand out of the head position. The convention holds across the delta.

2. **Guillemets around an LTR field name.** In «`%1$s`» with a Latin value, both marks are
   bidi-neutral sitting between an Arabic (R) and a Latin (L) strong run, so UBA rule N2
   resolves them to the paragraph level (RTL); both are `Bidi_Mirrored`, so U+00AB renders
   at the **right** edge of the quoted run and U+00BB at the left — marks facing outward,
   the Arabic convention — with the Latin name laid out LTR inside them. Correct by
   construction, but this is the one case in the delta that wants a device eyeball with a
   real AnkiDroid field name, and with an Arabic-named field if the user has one. The
   trailing periods in both first-field strings are paragraph-final neutrals and will park
   at the far **left** of the last line; that is right, and looks wrong to an LTR reviewer.

3. **`card_words_in_sentence` is not rendered by Android.** It is baked into the Anki card
   back as `<div class="gl-section">` (`app/src/main/java/com/playtranslate/ui/PtNoteBuilder.kt:138`,
   fed from `AnkiSendPipeline.kt:192`). That class carries
   `letter-spacing:0.12em; text-transform:uppercase`
   (`app/src/main/java/com/playtranslate/ui/PtCardTemplates.kt:131-132`, mirrored in
   `app/src/main/java/com/playtranslate/ui/AnkiHtmlStylers.kt:163-164`), and its container
   `.pt-words` is `text-align:left` with no `dir` or `lang` anywhere on the emitted
   document. Two consequences on an Arabic UI: `text-transform:uppercase` is a **no-op**
   on Arabic script, so the header loses the size/weight contrast the Latin version buys
   from caps; and WebView applies `letter-spacing` **between the glyphs of a cursive
   script**, so الكلمات في الجملة renders with gaps opened inside each word. Both are
   code-side, in a template outside this review's scope — flagged, not fixed, and *not*
   a reason to shorten or change the translation. Note a `:lang(ar)` / `[dir=rtl]`
   override could not hook it as the template stands: nothing emits a `lang` or `dir`, so
   any fix has to be decided at build time (or `letter-spacing` dropped from
   `.gl-section` / `.gl-pos` unconditionally).

### Verdict (delta)

**PASS with one ⚠️.** Report only — `values-ar/strings.xml` was not edited. Mechanical
layer clean. One ⚠️ (الملاحظة → الملحوظة, spanning `anki_first_field_unmapped` and
`anki_first_field_empty`) and one 💬 stacking on the same two keys; the other six keys are
clean, and three of them are clean specifically because they reused an in-file precedent
rather than translating the English afresh. RTL geometry still wants a device pass — the
guillemet-around-field-name case in the two first-field strings is the thing to look at.

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
| yomitan_update_repair_message | ❌ | «…مرة أخرى **ليعمل** مع هذا الإصدار…» | «…مرة أخرى **لتعمل** مع هذا الإصدار…» | Agreement break. The subject of the purpose clause is بيانات — a non-human sound feminine plural, which takes **feminine singular** verb agreement (تعمل), not masculine يعمل. The masculine form silently re-points the clause at القاموس, which is not what EN says: "The **data** … needs to be downloaded again **to work** with this version of the app." |
| settings_debug_angle_gate | 💬 | «عتبة الزاوية الكلاسيكية (10°)» | «العتبة الكلاسيكية للزاوية (10°)» | Attachment ambiguity: عتبة and الزاوية are both feminine, so الكلاسيكية can read as modifying either — "classic angle" rather than "classic threshold". Breaking the iḍāfa with لـ pins the adjective to العتبة, which is the intended reading (the *threshold* is the legacy one, per the EN comment). The same attachment fix was applied in es / fr / pt-BR this round.

### Clean areas (delta) — checked, no findings

**The device gate mirrors its siblings rather than the English.** `bergamot_device_unsupported`
«غير مدعوم على هذا الجهاز» is `llm_hardware_unsupported_arm64` minus its parenthetical, and
sits exactly parallel to `bergamot_pair_unsupported` «غير مدعوم للزوج اللغوي الحالي» — the
line it outranks. No fresh translation was invented for a sentence the file already owned.

**Update vocabulary reused from the app's own updater.** `yomitan_update_available_title`
«تحديث متاح» and `yomitan_update_check_failed_title` «تعذّر التحقق من التحديثات» are
byte-identical to the existing `update_dialog_title` / `update_check_failed_title`;
`yomitan_update_check_failed_message` closes with `yomitan_download_error_message`'s tail
(«تحقق من اتصالك وحاول مرة أخرى»); `yomitan_update_scan_active_message` closes with
`anki_models_unavailable`'s «حاول مرة أخرى بعد قليل». One flow, one vocabulary.

**No sentence opens with a Latin token.** Every string carrying the dictionary-name
placeholder opens in Arabic, per the RTL rule: «يمكن تحديث «%1$s»…»,
«يلزم تنزيل بيانات «%1$s»…», «القاموس «%1$s» على أحدث إصدار»,
«أصبح القاموس «%1$s» على أحدث إصدار». The head noun القاموس doubles as the agreement
anchor, so an arbitrary title (Jitendex.org, «JMnedict [2026-08-13]») cannot destabilise
the sentence. Guillemets follow `yomitan_duplicate_message` and `yomitan_delete_title`.

**Passive register is internally consistent.** `yomitan_update_skipped_title` «لم يُطبَّق التحديث»
and its `_message`'s «لم يُثبَّت» use the same vocalised مبني للمجهول rather than mixing in
«لم يتم» periphrasis inside one alert.

**Progress title matches the app's progress idiom.** «جارٍ التحقق من التحديثات» extends
`update_progress_verifying` «جارٍ التحقق…» and `yomitan_downloading_title` «جارٍ تنزيل القاموس».

**Terminology.** قاموس (dictionary), التعريفات (definitions), إصدار (version), and
لغة المصدر for "Source Language" — lifted from `llm_prompt_kw_source_desc`'s «للغة المصدر»
rather than invented. MSA formal register; the imperative أوقفه addresses the user as the
rest of the file does. Title Case in the EN alert titles is not replicated (Arabic has no
case), and matches the Yomitan family's own convention anyway (`yomitan_io_error_title`
"Import Failed", `yomitan_downloading_title` "Downloading Dictionary").

### Verdict

**PASS after fixes.** One ❌ — a real agreement break, now corrected — and one 💬. The
delta's hardest spot, an arbitrary Latin-script dictionary title dropped into four Arabic
sentences, is handled by the القاموس head-noun construction and holds for any title.
RTL rendering of the four mixed Arabic + Latin + digit strings still wants a device pass.

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

Three ⚠️, all applied.

| name | severity | current (was) | applied | note |
|---|---|---|---|---|
| anki_card_too_large_prompt | ⚠️ | «بتعريفات نصية عادية» | «تعريفاتها بالنص العادي» | "plain text" is already owned by the file: `yomitan_styling_subtitle` ends «لاستخدام النص العادي دائمًا». The adjectival «نصية عادية» was a fresh coinage for a term that had a committed translation two rows away. The replacement is a nominal relative clause on an indefinite antecedent (no الذي — correct), so it also stops the sentence stacking three adjectives on بطاقة. |
| anki_card_too_large_simplify | ⚠️ | «حفظ نسخة مبسّطة» | «حفظ بطاقة مبسّطة» | The button introduced a second noun for the object — نسخة (copy) — where the app's word throughout is بطاقة, and where the prompt body one line above already says «حفظ بطاقة مبسّطة». Now the button byte-matches the action its own body offers, which is what the translation contract asks for. |
| settings_debug_short_text_routing | ⚠️ | «توجيه النصوص القصيرة دون اتصال» | «توجيه دون اتصال للنصوص القصيرة» | The postposed «دون اتصال» landed on النصوص القصيرة, so the row read "routing of short texts that are offline" rather than "offline routing of short texts". This is the exact multi-locale trap recorded in `l10n-updating-locales.md` after "Classic angle threshold" (es/fr/pt-BR/ar simultaneously) — same shape, two nouns and one modifier, and Arabic fell for it again. Moving the modifier next to توجيه fixes the attachment. |

### Clean areas (delta) — checked, no findings

**The failure body stands alone.** «هذه البطاقة أكبر من أن يقبلها AnkiDroid» names its own
subject, so it survives being shown titleless as a Toast, and reads correctly under
`anki_send_failed_title` «تعذّرت إضافة البطاقة» in the dialog path.

**Elative construction, not a calque.** Both "too big to…" sentences use أكبر من أن +
subjunctive («أكبر من أن تُرسَل», «أكبر من أن يقبلها») rather than a literal كبير جدًا لـ.
The title keeps كبيرة جدًا, which is the idiomatic short form for a heading and matches the
definite-subject/indefinite-predicate shape of `anki_send_failed_title`.

**No sentence opens on a Latin token**, per the ar parameters: the prompt opens تعريفات,
the failure هذه, the toast تمت, the mmap row فرض. AnkiDroid and Anki sit mid-sentence in
all four, and mmap / LLM are trailing inside the debug row.

**Terminology.** بطاقة (card), التعريفات (definitions), القاموس (dictionary),
«على الجهاز» for on-device — the last taken from `llm_prompt_advisory_too_long`'s
«النماذج العاملة على الجهاز» rather than coined. «تبسيط / مبسّطة» is one root across all
four oversize strings.

**Toast shape.** «تمت الإضافة إلى Anki (…)» is byte-identical in its opening to
`anki_added_no_audio` / `_no_screenshot`, so the simplified toast joins that family
instead of starting a second one.

### Verdict

**PASS after fixes.** Three ⚠️, all corrected; no ❌ in the Arabic itself. RTL rendering of
the two strings that mix Arabic with Latin brand names, and of the mmap row's trailing
`mmap` + `LLM` inside parentheses, still wants a device pass.

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

**Siblings, one term swapped.** «تبديل بينيين المضمّن» mirrors «تبديل فوريغانا المضمّنة»,
with masculine agreement for بينيين (a writing system, نظام).

**The ⋯.** «مزيد من الإجراءات» parallels `capture_sliver_expand_hint` «اضغط لمزيد من الخيارات»
(مزيد من + plural), with الإجراءات for actions.

**Row names and RTL.** «قراءة بصوت عالٍ» and «تعديل النص» are the verbal nouns of
`cd_read_original_aloud` and `cd_edit_original`. The row's icon sits at the start (the
right under RTL), before the label; no delta string starts with a Latin token.

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
| settings_overlay_min_text_warning | 💬 | …بشكل متكرر أكثر… | …بوتيرة أعلى… | Clumsy comparative. |
| translation_error_auth … translation_error_unreachable (11 strings) | 💬 (decision) | «خدمة %1$s: …» | — | EN leads with the service name. A Latin first strong character makes the pill's TextView resolve an LTR paragraph (FIRSTSTRONG), left-aligned inside an RTL row; خدمة before the name keeps the paragraph RTL and still shows the name exactly. Kept. |

### Clean areas (delta) — checked, no findings

**Gestures and actions.** Gesture names are verbal nouns (السحب / الضغط المطوّل / الضغط),
matching the file's اسحب / اضغط مطولاً / اضغط and its existing noun «الضغط المطوّل»
(`settings_overlay_mode_subtitle`); actions are masdar like the file's other rows.

**Agreement and quotes.** لإبقائها agrees with القائمة, لا تستجيب with خدمة, لاستبعادها with
الكلمة. « » as in `overlay_hide_controls_message`; the alert's «فتح القائمة السريعة»
byte-matches the action.

**Terms.** Reused: تغيير لغة اللعبة, التقاط الشاشة, بدء/إيقاف الترجمة التلقائية, تعذّرت الترجمة,
مفتاح API غير صالح, الحصة, الرصيد, تجريبي., تجاهل (`llm_prompt_discard_title`), التراكب.

**RTL.** «(HTTP 404)» at the end of an Arabic line resolves correctly under UBA bracket
pairing (both brackets take the Arabic context). The one-line pill «الترجمة الآن من
اليابانية» shapes correctly through Canvas.drawText. A device pass should still confirm
the pills and the gesture cell.

### Verdict (round 1)

1 💬 to apply; 1 💬 recorded as a decision.

### Round 2 (2026-09-29), after applying round 1

Round-1 fix present: «…بوتيرة أعلى…». Mechanical layer re-run after the fixes: 0 problems; analyzer `missing=0 orphan=0 modified=0`; `:app:processDebugResources` BUILD SUCCESSFUL. Every screen of the delta was re-read in full, not only the changed keys.

| name | severity | current | suggested | note |
|---|---|---|---|---|
| translation_error_discard_card_message | 💬 | …وسيتعيّن عليك إنشاؤها من جديد من التقاط جديد. | …وسيتعيّن عليك إنشاؤها مرة أخرى من التقاط جديد. | جديد twice (من جديد … جديد). New in round 2; applied at the user's request. |

**Verdict (round 2):** **PASS.** The one 💬 was applied at the user's request; mechanical layer, analyzer and `:app:processDebugResources` re-run clean after it.

## Delta review 2026-10-07 (41 keys + 2 orphans: bug-report email, Support rows, kill notice, Fix disappearing icon page)

Mechanical layer verified: `mech_check.py keys.txt ar` reports `checked 1 locales x 41 keys;
problems: 0` (all 41 present; every `<xliff:g>` span byte-identical to EN; `%1$s` parity;
`\n` count 4 = 4 in the email body; no `<b>`, `\{ \}` or entities in this delta; no unescaped
`'`/`"`, stray tags, double spaces or edge spaces). `l10n_diff.py --locale
app/src/main/res/values-ar/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync";
re-run with `--english-base` (the English as of the last sync) it is still `modified=0`, so
`settings_debug_export_logs_subject` changed only its comment. The two orphans
`settings_debug_export_logs_title` / `_subtitle` are gone (0 hits); the file parses as XML and
has no duplicate `name=`. **No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `MainActivity.maybeShowKillNotice` builds the body as one of the three `kill_notice_body_*`,
  plus `' '` + `kill_notice_restored` only when a floating icon is actually on screen.
  `OverlayAlert` draws the title (17 sp bold) and body (13 sp) with `Gravity.CENTER` and no
  explicit `textDirection`, so each paragraph takes its direction from its first strong
  character. Every one of the 41 strings starts with an Arabic letter (bidi class AL, checked
  by script), so each one resolves RTL in the alert, the toast, the toolbar and the rows.
- `KeepRunningActivity`: the Xiaomi battery card reuses `keep_running_battery_line`. When the
  service is enabled but unbound, `a11y_stuck_title` and the restart line replace the
  accessibility card's title and line. That card opens `ACTION_ACCESSIBILITY_SETTINGS` itself,
  and the Huawei close-after-lock card opens `ACTION_POWER_USAGE_SUMMARY` (the Battery screen).
  The Report a bug row under the cards binds the same two strings as `SettingsRenderer`
  (~1190-1203), whose hub summary is `singleLine` + `ellipsize=end`.
- `BugReport.email` fills `%1$s` with `BuildConfig.VERSION_NAME`, a String, so the version
  keeps Latin digits ("3.3.0") under ar. `LogExporter.emailFiles` shows
  `email_no_app_fallback` (`LENGTH_LONG`, `SUPPORT_EMAIL`) and then `shareFiles` with the
  same subject under the `share_chooser_share_logs` chooser.

### Findings (delta, round 1)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| keep_running_huawei_close_after_lock_line | ⚠️ | `يوجد في إعدادات البطارية ← المزيد من إعدادات البطارية. وبدونه يُغلَق تطبيق <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> كلما قُفلت الشاشة.` | `يوجد في الإعدادات ← البطارية ← إعدادات إضافية. وإلا فسيُغلَق تطبيق <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> كلما قُفلت الشاشة.` | This value has two fixes. (1) **Label (official source):** Huawei's Arabic copy of the article whose English says "More battery settings" (consumer.huawei.com/sa, `ar-eg00428704`, read today) gives the path الإعدادات > البطارية > «إعدادات إضافية». «المزيد من إعدادات البطارية» is the research file's GUESS cell. The card lands on البطارية, so «إعدادات إضافية» is the next word the user scans for. (2) **«وبدونه»:** its pronoun most naturally takes the subject of «يوجد», which is the option itself. The line then reads "without the close-apps option, PlayTranslate closes", which reverses the cause. Only the other cards' «بدون ذلك» pattern rescues the intended "without turning it off". EN's "Otherwise" is «وإلا» (with فـ before the future, as جواب الشرط requires). |
| kill_notice_restored | 💬 | `وقد عاد إلى العمل الآن.` | `وقد عاد التطبيق إلى العمل الآن.` | The agreement is right: masculine «عاد» can only mean the app (تطبيق), never the feminine «الأيقونة» of the sentence before it. Its antecedent, though, is two or three sentences back. After `_body_other` the sentence just before is «تفعل بعض الهواتف ذلك…». After `_body_stopped` the nearest masculine agent is «مدير البطارية أو الذاكرة». In both cases the reader stops to ask what came back. Naming the subject reads cleanly after all three bodies. |
| keep_running_xiaomi_lock_line, keep_running_tile_line | 💬 | lock: `افتح التطبيقات الحديثة، واضغط مطولاً على بطاقة <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> ثم انقر على القفل. عمليات تنظيف الذاكرة تتخطى التطبيقات المقفلة.`<br>tile: `بتمريرة ونقرة فقط، يمكنك تشغيل <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> مجددًا من أي شاشة.` | lock: `افتح التطبيقات الحديثة، واضغط مطولاً على بطاقة <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> ثم اضغط على القفل. عمليات تنظيف الذاكرة تتخطى التطبيقات المقفلة.`<br>tile: `بتمريرة وضغطة فقط، يمكنك تشغيل <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> مجددًا من أي شاشة.` | One English term, one verb. The file's word for tap is اضغط / «الضغط» (`icon_gesture_tap`, `status_idle`, `anki_words_helper_hide`, `history_hide_translations_toggle_subtitle`), paired with «الضغط المطوّل» for hold, across 33 strings. These two are the file's only uses of the root نقر. This is a consistency fix only: انقر is standard Arabic for tap (Samsung's own Arabic page writes «انقر على»). |
| keep_running_oppo_auto_launch_line, keep_running_vivo_autostart_line | 💬 | `بدون ذلك، يغلق الهاتف تطبيق <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> بعد وقت قصير من مغادرته.` | `بدون ذلك، يغلق الهاتف تطبيق <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> بعد وقت قصير من مغادرتك له.` | A motion masdar takes its pronoun as the subject first, so «مغادرته» reads as "its departure" (the app's, or the phone's). The user, who does the leaving in EN ("after you leave it"), drops out. «مغادرتك له» puts the user back. Apply the change to both keys so they stay byte-identical. |
| settings_support_report_bug_email_body | 💬 | `ماذا حدث؟\n\n(صف ما كنت تفعله وما المشكلة التي حدثت.)\n\nمعلومات الجهاز والسجلات الأخيرة مرفقة.` | `ماذا حدث؟\n\n(صف ما كنت تفعله وما المشكلة التي واجهتها.)\n\nمعلومات الجهاز والسجلات الأخيرة مرفقة.` | حدث appears twice in two lines («ماذا حدث؟» / «التي حدثت»). «التي واجهتها» ("that you ran into") is the idiomatic way to say "what went wrong" from the user's side. Optional, since the user edits the body before sending. |

### Clean areas (delta) — checked, no findings

**RTL and mixed runs.** No string starts with a Latin token. The title and the restart line put the verb first («أغلق Android تطبيق…», «أوقف Android الخدمة…»), the same order as the reviewed `a11y_stuck_message`. Xiaomi appears as «نظام Xiaomi».

- **Email address and toast.** The address forms one LTR run, because @ and . sit between Latin letters. The toast wraps to 2 lines at both 296 and 320 dp, with the whole address at the start of line 2 (wrap tool re-run: «…أرسل الملفات إلى» at 267 dp | «support@playtranslate.com بطريقة أخرى.» at 244 dp).
- **Email subject.** In «تقرير خطأ في PlayTranslate – v3.3.0», the spaces and the en dash between the two Latin runs resolve L (UBA N1), and the digits after «v» become L (W7). «PlayTranslate – v3.3.0» therefore renders as one block to the left of the Arabic, the same shape as the reviewed `crash_email_subject`.
- **Punctuation.** A sentence-final period after a Latin name (`keep_running_huawei_app_launch_line`) takes the paragraph direction and lands at the left end. «» and () are mirrored pairs between Arabic letters. ← is not mirrored, so it points along the RTL reading order, as in `overlay_icon_a11y_required_message`. The delta has no Latin `, ? ; :`, no tatweel, bidi controls or NBSP, and its one question ends in ؟.
- **Device pass.** One on an ar device should still confirm the toast wrap and the centered alert.

**Kill notice, read as a set.** The VSO title and the three bodies read naturally. «إن لم تكن أنت من أوقفه، فربما أوقفه مدير البطارية أو الذاكرة في هاتفك» keeps the open attribution of the English. Agreement holds throughout:

- masculine for تطبيق PlayTranslate (كان، أوقفه، عاد);
- feminine for الأيقونة (اختفت) and المشكلة (إليها);
- «تفعل بعض الهواتف» takes the feminine singular of a non-human plural.

«قيد التشغيل» for "on" matches `onboarding_notif_row_status_sub`. «لتوفير البطارية» matches `a11y_stuck_message`. The buttons are «عرض الخيارات» and «ليس الآن», the second byte-identical to `btn_not_now`.

**Support card, five rows in order.** The two new titles are verbal nouns like their neighbours (التحقق من التحديثات، الانضمام إلى…، دعم PlayTranslate). The subtitles are imperatives like the Discord and donate subtitles.

- «امنع هاتفك من إغلاق PlayTranslate» measures 179 dp and is whole even at 216 dp.
- The bug-report subtitle measures 265 dp against 256 dp for English, which is within noise. Its first sentence is 151 dp, so the tap action is whole at both 216 and 249 dp, and only the hold hint clips, as it does in English.
- Dropping "instead" is the right trade at this width: after the email sentence, «للمشاركة» already reads as the alternative. «بالبريد» is clear in a phone row and saves the width «بالبريد الإلكتروني» would cost.

**Fix disappearing icon page.** The toolbar title is byte-identical to the Support row and measures 171 of 272 dp. «إصلاح» + symptom (as in إصلاح بطء الهاتف) is the everyday Arabic heading pattern, and the intro reads naturally.

- **Empty label.** In «إذا استمرت المشكلة، فكرّر ما أدى إليها ثم أبلغ عن خطأ», the فـ correctly opens an imperative جواب الشرط. «أبلغ عن خطأ» is the row's «الإبلاغ عن خطأ» as a verb, so the label points at the row below it in the row's own words.
- **Battery cards.** The shared battery line reads correctly under both «ضبط البطارية على «الاستخدام بلا قيود»» and «ضبط موفّر البطارية على «بدون قيود»». The generic title drops "usage" to avoid «استخدام… «الاستخدام…»». Nothing is lost: the quoted value is AOSP's exact per-app option, and the card opens Android's own per-app dialog.
- **Accessibility line.** The present tense of «يعمل تطبيق PlayTranslate بأولوية…» reads as the effect of the mode named in the card title, which is how Arabic setting descriptions are written.
- **Card titles** are verbal nouns throughout.
- **One term, one word.** «عمليات تنظيف الذاكرة» appears twice, «التشغيل التلقائي» covers autostart and auto-launch on all four ROMs, and «الأيقونة العائمة», «اضغط مطولاً», «السجلات» and «مربع الإعدادات السريعة» are each reused from their precedents.

**Platform and OEM labels.**

- **AOSP.** «الاستخدام بلا قيود» is exactly AOSP's `manager_battery_usage_unrestricted_title`.
- **Samsung (official).** Samsung UAE's Arabic page, re-read today, matches all five labels: «العناية بالجهاز»، «البطارية»، «حدود استخدام الخلفية»، «عدم وضع تطبيقات في سكون تلقائي مطلقاً» (tanween on the alif, as Samsung writes it)، «التطبيقات الساكنة».
- **Huawei.** «تشغيل التطبيقات» and the three switches «التشغيل التلقائي» / «التشغيل الثانوي» / «التشغيل في الخلفية» match the official cells. «يدويًا» describes manual mode instead of quoting an unverified dialog title.
- **Xiaomi.** «التشغيل التلقائي» / «موفّر البطارية» / «بدون قيود» have no Arabic source: they are UNVERIFIED in oem-labels.md, and my two searches found no Xiaomi page in Arabic. They do repeat the reviewed `a11y_stuck_message_xiaomi` word for word, so the app says one thing.
- **OPPO and vivo.** Every Arabic cell is a GUESS. The titles describe the switches without quoting them, which is the honest choice.
- **Recents.** «التطبيقات الحديثة» was not changed. Recents is a button or gesture, not a labelled screen; AOSP's accessibility action calls it «التطبيقات المستخدمة مؤخرًا», and Xiaomi's Arabic label is unverified.

**Accessibility term.** AOSP names the Settings entry «أدوات تسهيل الاستخدام». The app's pre-existing إمكانية الوصول does not matter in this delta, for three reasons:

- the card opens `ACTION_ACCESSIBILITY_SETTINGS` itself;
- «وضع إمكانية الوصول» is the app's own name for the mode;
- the restart line reuses the two sentences of `a11y_stuck_message`.

**Email flow.** «إرسال تقرير التعطل» and «إرسال تقرير الخطأ» follow `crash_dialog_message`, and the subject mirrors `crash_email_subject`. «السجلات الأخيرة» matches «سجلات التطبيق الأخيرة». The toast's «بطريقة أخرى» leads naturally into the «مشاركة السجلات» sheet that opens next.

**Web checks (5 of 8).** Huawei SA's Arabic `ar-eg00428704` and Samsung UAE's Arabic battery page were read through the fetch tool's summary, which quoted the Arabic labels. Three searches for Xiaomi and OPPO Arabic labels found no Xiaomi or OPPO page in Arabic.

### Verdict (round 1)

**1 ⚠️, 4 💬** (5 rows covering 7 keys). No ❌, no 🛑.

1. Apply the ⚠️: `keep_running_huawei_close_after_lock_line` (the official «إعدادات إضافية» label, and «وإلا» in place of the ambiguous «وبدونه»).
2. Recommended 💬: `kill_notice_restored` (name «التطبيق» as the subject); the file's tap verb in `keep_running_xiaomi_lock_line` and `keep_running_tile_line`; «مغادرتك له» in the OPPO and vivo lines (both keys); `settings_support_report_bug_email_body` (optional).

The other 34 keys pass as they are.

### Round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 PKT/../tools/mech_check.py PKT/../keys.txt ar` reports `checked 1 locales x 41 keys; problems: 0` (all 41 present; every `<xliff:g>` span byte-identical to EN; `%1$s` parity; `\n` 4 = 4 in the email body; no unescaped `'`/`"`, stray tags, double spaces or edge spaces). `python3 scripts/l10n_diff.py --locale app/src/main/res/values-ar/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync". The file parses as XML with no duplicate `name=`; the two orphans `settings_debug_export_logs_title` / `_subtitle` have 0 hits and `settings_debug_export_logs_subject` is still present. `git diff` of `values-ar/strings.xml` holds only the 41 keys, the two banners and the two orphan deletions, so no string outside the delta was touched.
**No 🛑 build-breaking issues.**

**Round-1 fixes:** each value below was compared byte for byte with the round-1 "suggested" cell.
- `keep_running_huawei_close_after_lock_line` (⚠️): **applied** as suggested. I re-checked the label today: Huawei SA's Arabic page `ar-eg00428704` prints «الإعدادات > البطارية > إعدادات إضافية», with «استمرار الاتصال عند انطفاء شاشة الجهاز» under it. «وإلا فسيُغلَق» is grammatical (فـ before سـ in the جواب الشرط).
- `kill_notice_restored` (💬): **applied**. It follows each of the three bodies after the code's one ASCII space, and masculine «عاد» agrees with «التطبيق».
- `keep_running_xiaomi_lock_line`, `keep_running_tile_line` (💬): **applied**. The root نقر now has 0 uses in the file. «بتمريرة وضغطة» matches «بضغطة واحدة» in `onboarding_welcome_learn_body`.
- `keep_running_oppo_auto_launch_line`, `keep_running_vivo_autostart_line` (💬): **applied**. The two are still byte-identical.
- `settings_support_report_bug_email_body` (💬): **applied**. It still has 4 `\n`.

No fix touched a view that clips, so the packet's widths stand: toolbar title 171 of 272 dp, the two hub summaries 179 and 265 dp, toast 2 lines with the address whole at the start of line 2. `keep_running_title` and `settings_support_keep_running_title` are still byte-identical, and so are `kill_notice_not_now` and `btn_not_now`.

#### Findings (round 2)

No new findings.

**Fresh read: checked, no finding.**
- **Kill notice.** I read the title, then each body + " " + restored, then the two buttons. Agreement holds:
  - masculine for تطبيق PlayTranslate (كان، أوقفه، عاد);
  - feminine for الأيقونة (اختفت);
  - feminine singular for the non-human plural (تفعل بعض الهواتف).

  In `_body_memory`, «فأغلق تطبيق» reads correctly whether taken as active (the phone closed it) or passive.
- **Support card.** The five rows read as a set: verbal-noun titles and imperative subtitles. The bug-report subtitle's tap action (151 dp) is whole at 216 dp.
- **Fix disappearing icon page.** I read the page in each ROM's card order. On Xiaomi, two adjacent lines open with «بدون ذلك،», as the English does with "Without this,". In the empty label, «فكرّر ما أدى إليها» ("repeat what led to it") is a natural way to say "reproduce the issue".
- **Email flow.** The chooser titles follow `crash_dialog_message` («تقرير التعطل») and the subject mirrors `crash_email_subject`.
- **RTL.**
  - Rendered with the real values, all 41 start with an Arabic letter (bidi class AL).
  - None has bidi controls, NBSP, zero-width characters or tatweel.
  - The only punctuation used is ( ) . @ « » ، ؟ – ←.
  - «PlayTranslate – v3.3.0» and the address each form one LTR run. «أغلق Android تطبيق PlayTranslate» keeps its two Latin runs separate.
- **Platform labels.**
  - «الاستخدام بلا قيود» is exactly `manager_battery_usage_unrestricted_title` in the android16-qpr2 Settings ar file.
  - Samsung and Huawei are official, as in round 1.
  - Xiaomi stays UNVERIFIED: one more Arabic search today found no Xiaomi page in Arabic. Its labels still repeat `a11y_stuck_message_xiaomi` word for word.
- **Style, no change needed.** The toast says «لم يُعثر على», while the file's five other not-found strings say «لم يتم العثور على». Both are correct MSA, and the shorter form suits a toast capped at two lines.
- **Web checks.** I used 2 of 6: the Huawei SA fetch above and one search for Xiaomi in Arabic.

#### Verdict (round 2)

**PASS.** All five round-1 rows (7 keys) landed exactly as suggested. There are no new findings (0 ❌, 0 ⚠️, 0 💬) and no 🛑. Two items are not blocking, both carried over from round 1: a device pass on an ar device to confirm the toast wrap and the centered alert, and the Xiaomi Arabic labels, which no source confirms. The Huawei close-after-lock card itself is the known English-source issue (packet-common, "Already known" 1).

## Follow-up review 2026-10-07, round 1 (15 keys after the Fix disappearing icon rewrite)

Mechanical layer: `python3 TOOLS/mech_check.py F2/keys2.txt ar` reports `checked 1 locales x 15 keys; problems: 0` (all 15 present; every `<xliff:g>` span byte-identical to EN, including both `app_name` / `app_name2` spans in the Xiaomi autostart, OPPO and vivo lines; `%1$s` parity; `\n` 2 = 2 in `a11y_stuck_message`; no unescaped `'`/`"`, stray tags, double spaces or edge spaces). `python3 scripts/l10n_diff.py --locale app/src/main/res/values-ar/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync". The file parses as XML with no duplicate `name=`, and the four keys removed from English (`restricted_settings_title`, `restricted_settings_message`, `keep_running_huawei_close_after_lock_title` / `_line`) have 0 hits. Against `HEAD`, the only string-level differences are the 42 keys the first pass and this delta added, the two existing keys this delta changed (`a11y_stuck_message`, `a11y_stuck_message_xiaomi`), and four deletions (`restricted_settings_title` / `_message` and the first pass's two orphans). All 15 keys sit in English order.
**No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `AccessibilityHelp.withRestrictedSettingsStep` appends the addendum on API 33+ as `message + "\n\n" + step`:
  - in system AlertDialogs after `accessibility_dialog_message` (`MainActivity`) and `overlay_icon_a11y_required_message` (`SettingsRenderer`);
  - in the OverlayAlert after the three `a11y_required_*` (`AccessibilityAlert`: 13 sp, centered, scrolling, 280 dp wide);
  - in the accessibility card's 13 sp line.

  The stuck alert never carries it; on Xiaomi it gets `"\n\n"` + `a11y_stuck_message_xiaomi`. No view sets `textDirection`, so each paragraph takes the direction of its first strong character. All 15 values start with an Arabic letter (bidi AL, checked by script), so every paragraph, the appended ones included, lays out RTL.
- `KeepRunningActivity.openFirst` tries the card's own screens. The Xiaomi battery card goes straight to the per-app battery-saver activity, and the generic battery card to Android's "Let app always run in background?" dialog. When nothing launches, it shows `keep_running_screen_unavailable` as a `LENGTH_LONG` toast and opens App info. Card titles (`Text.PT.RowTitle`, 15 sp medium) and lines (`Text.PT.RowSubtitle`, 13 sp) have no `maxLines`, so the toast is the only width-bound string in the delta.

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|

No findings.

### Clean areas — checked, no findings

**CHANGED keys: nothing lost, nothing added.** Each was compared with its reviewed value: `HEAD` for the two stuck-alert keys, and the first pass's final values for the rest.
- `a11y_stuck_message` is the `HEAD` value minus exactly the cause sentence («يحدث هذا عادةً بعد أن يوقف النظام التطبيق إجباريًا لتوفير البطارية.»), checked byte for byte.
- `a11y_stuck_message_xiaomi` makes the English's three moves and no others:
  - «فعّل أيضًا التشغيل التلقائي» → «اسمح أيضًا بالتشغيل التلقائي»;
  - «واضبط موفّر البطارية» → «واضبط استخدامه للبطارية»;
  - «سيستمر» → «قد يستمر».
- `keep_running_tile_line` drops «بتمريرة وضغطة فقط،» and keeps the rest byte-identical. Under the tile title, «يمكنك تشغيل PlayTranslate مجددًا من أي شاشة.» reads as the card's benefit, which is how Arabic card lines are written.
- `keep_running_xiaomi_lock_line` keeps its first sentence byte-identical, round-1 «اضغط» fix included, and replaces only the memory-cleanup claim.
- `keep_running_xiaomi_battery_title` swaps «موفّر البطارية» for «استخدام البطارية», just as the English swaps the entry's name for "battery use".
- `keep_running_huawei_app_launch_line` keeps the reviewed switch list word for word and adds the search and Manage-automatically steps in front of it.
- The other six are new sentences: the generic battery title and line, and the Xiaomi autostart, OPPO, vivo and Samsung lines. Each says what the new English says. The hedges are «قد», no effect claim is stronger than the English, and Xiaomi stays «نظام Xiaomi».

**Read as sets.**
- **Addendum.** It reads as a closing paragraph after all five messages and after the accessibility card's line.
  - After the two path messages, «مفتاح التبديل» picks up the path's last step, «تفعيل».
  - After the three OverlayAlert messages, it is the permission's switch, as "the switch" is in English.
  - «مفتاح التبديل» is AOSP's own Arabic for a switch (`stylus_handwriting_summary`).
  - In the hotkey alert it follows «المفاتيح التي تضغط عليها» (keyboard keys), so «مفتاح» may read as a key for a moment. «ظهر … باللون الرمادي» settles that at once.
- **Stuck alert.** Title, body and Xiaomi paragraph read in order, and «أيضًا» ties the Xiaomi steps to the re-toggle. The paragraph's two steps repeat the two Xiaomi card titles («السماح بالتشغيل التلقائي», «ضبط استخدام البطارية على «بدون قيود»»). Its «في إعدادات النظام الخاصة بالتطبيق» matches the Xiaomi battery line.
- **Cards.** I read every title + line in each ROM's order. The terms hold across the page:
  - «العودة إلى العمل» in four lines, as in `kill_notice_restored`;
  - «بدون ذلك،» for "Without it/these";
  - «في الإعدادات، ابحث عن «…»» in all four search lines;
  - «الإصدار / الإصدارات» for versions in the two Xiaomi lines.

  The three two-span lines carry both spans. The second mention opens its own sentence («بدون ذلك، قد … تطبيق PlayTranslate …»), so the repetition reads naturally.
- **Toast, then App info.** «الشاشة المطلوبة» cannot be taken for the App info page («معلومات التطبيق») that opens under it. «هذا الإعداد» points back to the card just tapped.

**Toast measured** (wrap tool, 14 sp, Noto Naskh Arabic UI).
- It is 497 dp in all and takes 2 lines at both widths. At 296 dp the lines are «…ابحث عن هذا» (287 dp) and «الإعداد في إعدادات هاتفك بدلًا من ذلك.» (206 dp).
- It stays at 2 lines at 296 dp with a font up to about 15% wider (wrap at 257 dp) and needs 3 from about 21% wider (245 dp).
- English needs 3 lines at 296 dp (known issue 1).

**Grammar and agreement.**
- Masculine for تطبيق PlayTranslate: له، نفسه، استخدامه، يغلقه، يمنعه.
- «به» refers to التشغيل التلقائي.
- Feminine for الخدمة (تشغيلها، شغّلها) and for Samsung as a company (تُدخل، تحدّ).
- Feminine singular for non-human plurals (التطبيقات … يمكنها؛ إعدادات … الخاصة).
- In the addendum, فـ opens the imperative جواب الشرط («فاضغط»). The battery line's present-tense جواب («يتيح») correctly needs none.

**RTL and punctuation.**
- No bidi controls, NBSP, zero-width characters or tatweel.
- The only punctuation is . ، « » ( ) ⋮. There is no question, so no ؟ is needed, and there are no arrow paths, so no ← is needed.
- «Android 13» is one LTR run (EN after L, W7), as in `requires_android_11_message`.
- ⋮ is a neutral (ON) between Arabic letters, so it lands in reading order.
- The Arabic comma after PlayTranslate or Xiaomi is CS and takes the paragraph direction.

**Register and terms.**
- Formal MSA throughout.
- The tap verb is اضغط / اضغط مطولاً; نقر has 0 uses.
- «Android 13 أو أحدث» follows «Android 11 أو أحدث» in `requires_android_11_message`.
- Tanween placement is mixed, but that predates this delta. «مطولاً» (the file's precedent) and Samsung's «مطلقاً» put it on the alif; «بدلًا» and «دائمًا» put it before. The file already mixes the two, 94 to 23.

**Platform labels** (AOSP android16-qpr2 Settings, `ar` file read locally).
- «معلومات التطبيق» is `application_info_label`.
- «السماح بالإعدادات المحظورة» is `app_restricted_settings_lockscreen_title`. AOSP's restricted-settings strings use المحظورة, so the deleted string's «المقيَّدة» is rightly gone.
- The dialog the greyed switch shows is «الإعداد محظور». It shares the root, so the steps read as one flow.
- The battery title repeats AOSP's dialog, «هل أردت السماح بتشغيل التطبيق في الخلفية دائمًا؟», whose body says «السماح بتشغيل تطبيق "…" في الخلفية دائمًا».
- «رمز المزيد ⋮»: the translator's source is Google's Arabic help, which my search did not surface. The phrase is idiomatic, and the glyph itself is printed.

**OEM labels.**
- **Huawei** (official, `ar-eg00414587`): the search word «تشغيل التطبيقات» and «إدارة تلقائية»، «التشغيل التلقائي»، «التشغيل الثانوي»، «التشغيل في الخلفية» all match.
- **Samsung** (official, Samsung UAE Arabic): «البطارية»، «حدود استخدام الخلفية»، «عدم وضع تطبيقات في سكون تلقائي مطلقاً» all match.
- **vivo and OPPO** (unverified; new secondary support). Huawei's Arabic copies of the two articles the English comments cite print:
  - vivo (`ar-eg15850064`): «iManager > التشغيل التلقائي» and «الإعدادات > البطارية > استهلاك الطاقة المرتفع في الخلفية»;
  - OPPO (`ar-eg15850071`): «مدير الهاتف > أذونات الخصوصية > تطبيقات بدء التشغيل التلقائي» and «الإعدادات > البطارية > إدارة البطارية للتطبيقات».

  So the shared search word «التشغيل التلقائي» is vivo's label and part of OPPO's. The vivo line's unquoted «استهلاك مرتفع للطاقة في الخلفية» shares every content word with vivo's entry.
- **Xiaomi** (unverified; one secondary source disagrees). Huawei's Arabic copy of `en-us15850067` (`ar-eg15850067`, Xiaomi on Android 9) prints «تشغيل تلقائي» without the article, «أداة توفير طاقة البطارية», «لا توجد قيود» and «المهام الأخيرة». The English comments cite the same page for "Autostart" and "Battery saver".
  - No change is suggested. The page is Huawei's rendering of another OEM's screen, from Android 9, and Huawei's own vivo copy writes «التشغيل التلقائي».
  - The article does matter for search: neither «التشغيل التلقائي» nor «تشغيل تلقائي» contains the other.
  - The two option labels would still be recognizable from «بدون قيود» and «موفّر البطارية».

**Out of scope, informational (not counted).** The two path messages the addendum follows go through «… ← التطبيقات التي تم تنزيلها ← …». AOSP's Arabic title for that sub-list is «التطبيقات التي نزّلتها» (`user_installed_services_category_title`), and AOSP names the Settings entry «أدوات تسهيل الاستخدام», as round 1 of the 2026-10-07 delta noted. The sub-list is still recognizable; this is for a later full pass.

**Web checks: 6 of 6.**
- Three Huawei SA Arabic pages: `ar-eg15850067`, `-64` and `-71`.
- One Kaspersky ar-AE page, a landing page with no content.
- Two searches, for Arabic Xiaomi labels and for Google's «رمز المزيد». Neither found a usable source.

### Verdict (round 1)

**PASS.** 0 ❌, 0 ⚠️, 0 💬, no 🛑; all 15 keys can stay as they are. Two items are not blocking:
1. A device pass on an ar device still owes the toast wrap and the centered alerts (carried over).
2. On a Xiaomi set to Arabic, check that a Settings search for «التشغيل التلقائي» finds the autostart switch. The only Arabic Xiaomi text found (Huawei-authored, Android 9) writes «تشغيل تلقائي» without the article, which a literal search would miss.

### Follow-up round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 TOOLS/mech_check.py F2/keys2.txt ar` reports `checked 1 locales x 15 keys; problems: 0` (all 15 present; every `<xliff:g>` span byte-identical to EN, both `app_name` / `app_name2` spans included; `%1$s` parity; `\n` 2 = 2 in `a11y_stuck_message`; no unescaped `'`/`"`, stray tags, double spaces or edge spaces). `python3 scripts/l10n_diff.py --locale app/src/main/res/values-ar/strings.xml` reports `missing=0 orphan=0 modified=0`, "in sync". The file parses as XML with 1047 names and no duplicate; the four keys removed from English and the first pass's two orphans have 0 hits; the 15 keys sit in English order. Against `HEAD` the string-level changes are still exactly the 42 added keys, the two changed stuck-alert keys and the four deletions. With the spans filled in, all 15 start with an Arabic letter (bidi AL) and contain no bidi controls, NBSP, zero-width characters or tatweel.
**No 🛑 build-breaking issues.**

**Round-1 fixes:** none to apply. Round 1 reported no findings (0 ❌, 0 ⚠️, 0 💬). `values-ar/strings.xml` was last written at 00:14:48, before the round-1 report, and all 15 values are byte-identical to the translator's source (`F2/frag/ar.src`) and to the regenerated packet, so the file still holds exactly what round 1 passed. Round 1 did not cover the first pass's 41 keys for ar (that pass closed with its own round-2 PASS above).

#### Findings (round 2)

No new findings.

**Fresh read: checked, no finding.**
- **Addendum after each message.** I read it composed (`message + "\n\n" + addendum`) after `accessibility_dialog_message`, `overlay_icon_a11y_required_message`, the three `a11y_required_*` and `keep_running_accessibility_line`. It reads as its own closing paragraph each time; فـ opens the imperative جواب and every later step stays inside it.
  - AOSP, re-read in the local android16-qpr2 `ar` file: «معلومات التطبيق» is `application_info_label`, «السماح بالإعدادات المحظورة» is `app_restricted_settings_lockscreen_title`, and the greyed switch's dialog «الإعداد محظور» shares المحظور.
  - After `a11y_required_displays_message` («يتطلب تبديل الشاشات…»), «مفتاح التبديل» echoes تبديل. It is the set term for a toggle, and «ظهر … باللون الرمادي» fixes the sense, as with the hotkey alert's «المفاتيح» in round 1.
- **Stuck alert.** The body is `HEAD` minus exactly the cause sentence (re-checked by script). In the Xiaomi paragraph, «استخدامه» takes تطبيق PlayTranslate, and its two steps repeat the two Xiaomi card titles.
- **Cards, title + line, in each ROM's order** (Xiaomi, Huawei, OPPO, vivo, Samsung, then the generic battery, accessibility and tile cards). Titles are verbal nouns; lines are imperatives or benefit statements.
  - Agreement: masculine for تطبيق PlayTranslate (له، نفسه، استخدامه، يغلقه، يمنعه); feminine for Samsung (تُدخل، تحدّ) and for the non-human plural (يمكنها). «بضعة أيام» has the right number polarity.
  - The battery line's present-tense جواب after إذا needs no فـ.
- **Labels and search words.**
  - The generic battery title repeats AOSP's dialog «هل أردت السماح بتشغيل التطبيق في الخلفية دائمًا؟» (`high_power_prompt_title`).
  - Huawei's five labels match the official Arabic page recorded in round 1. «تشغيل التطبيقات» is the label itself, so a Settings search finds it.
  - Samsung's labels are official (Samsung UAE Arabic, as recorded in round 1).
  - «التشغيل التلقائي» is vivo's label and a contiguous part of OPPO's «تطبيقات بدء التشغيل التلقائي» (both from Huawei-authored copies), so both searches match.
  - Xiaomi stays unverified (known issue 2).
- **Toast.** Re-measured with the wrap tool (14 sp, Noto Naskh Arabic UI): 497 dp, 2 lines at both 296 and 320 dp.
- **RTL and punctuation.**
  - All 15 start with an Arabic letter, and «Android 13» is one LTR run.
  - ⋮ and the Arabic comma after a Latin name take the paragraph direction, and the Xiaomi battery line's parentheses are mirrored pairs between Arabic text.
  - No string asks a question or gives a path, so none needs ؟ or ←.
  - The tap verb is اضغط throughout; the root نقر has 0 uses in the file.
- **Web checks: 3 of 4.** Two searches for Xiaomi's Arabic autostart label, and one fetch of the Kaspersky ar-AE page they returned, which is the same support landing page round 1 found. Neither found a Xiaomi label in Arabic, so round 1's device check stands.

#### Verdict (round 2)

**PASS.** Round 1 had no findings, so there was nothing to apply, and the file still holds the values round 1 passed. There are no new findings (0 ❌, 0 ⚠️, 0 💬) and no 🛑. Two items carry over from round 1, neither blocking:
1. A device pass on an ar device for the toast wrap and the centered alerts.
2. On a Xiaomi set to Arabic, check that a Settings search for «التشغيل التلقائي» finds the autostart switch. The only Arabic Xiaomi text found (Huawei-authored, Android 9) writes «تشغيل تلقائي», and neither phrase contains the other.
