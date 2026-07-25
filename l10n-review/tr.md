# Turkish (values-tr) localization review

Mechanical layer verified programmatically: all string/plurals names present, no extras; every `%n$s`/`%d` placeholder present; all `<xliff:g>` inner contents byte-identical to EN; `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match; every literal apostrophe is `\'`-escaped (all 16 brand-suffix sites); no raw `"` in text. **No 🛑 build-breaking issues.**

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| hymt_legal_message | ❌ | "Kabul Et düğmesine dokunarak" | "Kabul Ediyorum düğmesine dokunarak" | In-text button reference must match the actual `hymt_legal_agree` label ("Kabul Ediyorum — Hunyuan\'ı Etkinleştir"). Same failure as 5 of 6 other languages. |
| hymt_legal_message | ❌ | "ikamet etmiyor ya da bulunmuyorsunuz" | "ikamet etmiyor ve bulunmuyorsunuz" | Two negated verbs joined by "ya da" = "not residing OR not located" — logically weaker than EN's "not (residing or located)". The attestation must negate both conjuncts; "ve" fixes it. |
| hymt_legal_message | ⚠️ | "AB, BK veya Güney Kore" | "AB, Birleşik Krallık veya Güney Kore" | "BK" is not an established Turkish abbreviation for the UK (unlike "AB" for the EU); in a legal attestation, spell it out. |
| onboarding_notif_body | ❌ | "uyarı, ses veya başlık göndermez" | "uyarı, ses veya banner bildirimi göndermez" | "başlık" = "title/heading" — mistranslation of "banners" (pop-up notifications); users will not understand what is being promised. |
| crash_dialog_message | ⚠️ | "Bir yığın izi, son uygulama günlükleri ve … metinleri içerir." | "Rapor; bir yığın izini, son uygulama günlüklerini ve … metinleri içerir." | Subjectless sentence garden-paths: sentence-initial nominative "Bir yığın izi" reads as the subject ("a stack trace contains the logs and texts…"). Mixed case-marking on the conjuncts compounds it. |
| settings_header_ocr | ⚠️ | "Görüntüden metne (OCR)" | "Metin tanıma (OCR)" | "Image-to-text" calque; "metin tanıma" is the standard Turkish term and matches `status_ocr` ("Metin tanınıyor…"). |
| qwen_mnn_metered_warning_title / _message, qwen35_2b_mnn_metered_warning_title / _message, gemma_e2b_mnn_metered_warning_title / _message, hymt_metered_warning_title / _message | ⚠️ | "Ölçülü ağda indirilsin mi?" / "Bu ağ ölçülü olarak işaretlenmiş." | "Sayaçlı ağda indirilsin mi?" / "Bu ağ sayaçlı olarak işaretlenmiş." | Android's Turkish UI uses "sayaçlı" for metered (Data Saver / Wi-Fi "Sayaçlı"); "ölçülü" primarily means "moderate/restrained" and doesn't match the system toggle the user knows. 8 strings, one fix. |
| anki_sort_field_empty | ⚠️ | "yinelenen kaydı reddetme hatalarına neden olur" | "gönderim sırasında kartın yinelenen (kopya) olarak reddedilmesine neden olur" | "duplicate rejection errors" calque — parses as "errors of rejecting the duplicate record"; restructure around what actually happens. |
| pack_upgrade_mandatory_message | ⚠️ | "veya başka bir dil seçmek için silin" | "veya başka bir dil seçmek için paketi silin" | "silin" has no object; nearest noun is "yüklü sürüm". Add "paketi" so the delete target is unambiguous. |
| accessibility_dialog_message, overlay_icon_a11y_required_message | ⚠️ | "Erişilebilirlik → Yüklü uygulamalar" | "Erişilebilirlik → İndirilen uygulamalar" | Faithful to the EN source ("Installed apps", known upstream drift), but stock Android Turkish labels the accessibility app-list section "İndirilen uygulamalar" — users follow this path literally. |
| settings_anki_get_app_summary | ⚠️ | "ücretsiz indir" | "ücretsiz indirin" | Sen-register in a sentence-style digest; the sister row `anki_settings_get_ankidroid_title` says "ücretsiz edin" (siz) for the same content. |
| settings_anki_grant_summary | ⚠️ | "AnkiDroid kullanmak için izin ver" | "AnkiDroid kullanmak için izin verin" | Same register slip as above; all other digest/subtitle prose uses siz. |
| translate_button_prefix_translate, translate_button_prefix_reload | ⚠️ | "Çevir" / "Yenile" | "Çevir:" / "Yenile:" | Code composes prefix + space + region name → "Çevir Tam ekran" is verb-object inversion in Turkish. A trailing colon ("Çevir: Tam ekran") reads naturally without code changes. |
| tr_service_offline_footer | ⚠️ | "çok yavaş ve yorucu olabilir" | "çok yavaş olabilir ve cihazı zorlayabilir" | "taxing" means resource-heavy on the device; "yorucu" means tiring for a person. |
| anki_content_part_of_speech, anki_content_part_of_speech_desc | ⚠️ | "Söz türü" / "söz türü etiketi" | "Sözcük türü" / "sözcük türü etiketi" | "Sözcük türü" is the standard Turkish grammar term for part of speech; the file otherwise consistently uses "sözcük". |
| dialog_hotkey_setup_countdown | 💬 | "Basılı tutun 1.4" | "Basılı tutun: %1$s" | Verb-first then a bare number reads like a stray digit; a colon makes the countdown read as a value. |
| crash_dialog_discard | 💬 | "Sil" | "Raporu Sil" | Bare "Sil" next to "Gönder"/"Sonra" doesn't say what gets deleted; naming the report removes the destructive ambiguity. (It correctly does not read as "Cancel".) |
| settings_anki_digest | 💬 | "Deste %1$s · Kart türü %2$s" | "Deste: %1$s · Kart türü: %2$s" | `anki_deck_label_format` already uses "Deste: %1$s"; colons also read better with arbitrary deck names. |
| bergamot_warmup_downloading_multi | 💬 | "Çevrimdışı model indiriliyor %1$d/%2$d…" | "Çevrimdışı model %1$d/%2$d indiriliyor…" | The count reads tacked-on after the verb; "model 1/2 indiriliyor" is the natural order (placeholders are positional, reordering is safe). |
| anki_content_words_table_desc | 💬 | "her sözcüğün; okunuşları ve tanımlarıyla birlikte" | "her sözcüğün, okunuşları ve tanımlarıyla birlikte" | Semicolon splits the genitive from its head noun; comma (or nothing) is correct. |

Clean areas (checked, no findings): all three `<plurals>` (anlam/karakter/sonuç — singular noun after numeral in both `one` and `other`, correct Turkish); bottom-bar labels ("Ayarlar" 7, "Bölgeler" 8, "Otomatik" 8, "Duraklat" 8) and "Yakalama\nBölgesi" — comparable to EN lengths, low truncation risk; i/İ casing ("İndir", "İptal", "İzin Ver", "İPUCU", "SESLERİ", "ÇİZ" all correct, no dotless-I errors found by scan or read); hotlist items `live_mode_auto_with_hint` ("Otomatik Furigana" — correct order), `status_idle`/`status_hold_hint` (button names anchored by "düğmesine", no garden path), `label_region_drag_hint` ("tüm kutuyu taşımak için" correctly scoped to the middle-drag only), `settings_capture_interval_hint` ("En az 1 saniye" — correct), `anki_permission_rationale_message`/`anki_settings_grant_access_subtitle` (no brand adjacency; "Devam Et" matches `btn_continue` exactly), `backend_cooldown_*` ("Yeniden deneme saati: 15:42" — natural colon construction), Quick Settings terminology ("Hızlı Ayarlar", "kutucuk" — matches Android TR), `btn_clear` ("Temizle" — correct, not "Sil"), `restricted_settings_message` ("Kısıtlı ayarlara izin ver" matches the Android 13 TR label), `overlay_hide_controls_message` (quoted "Şimdilik Gizle"/"Kapat" exactly match their button strings).

## Suffix coverage appendix

Every site where a runtime placeholder meets Turkish grammar — all use a head noun, postposition, or colon, so no suffix ever attaches directly to a placeholder; vowel harmony cannot break:

- status_no_text — %1$s + "metni"; %2$s + "içinde" ✓
- lang_setup_requires_64bit_msg — %1$s + "metnini" ✓
- anki_section_description — %1$s + "dilinde" ✓
- lang_section_offline_models_subtitle — %1$s → %2$s + "için" ✓
- anki_field_mapping_title — %1$s + "öğesini" ✓
- anki_sort_field_empty — %1$s + "alanına" ✓ (separate finding is about a later clause)
- anki_content_source_pick_title — %1$s + "alanını" ✓
- custom_region_edit_title — %1$s + "öğesini" ✓
- settings_ocr_delete_cd / _msg — %1$s + "modelini" ✓; settings_ocr_delete_title — %1$s + separate word "silinsin mi" ✓; settings_ocr_delete_shared_msg / _downloading_title — no suffix ✓
- llm_backend_get_key_title_fmt — %1$s + "API anahtarı" ✓
- llm_backend_invalid_key_alert_message_fmt — %1$s + verb phrase; %2$s + "adresinden" ✓
- llm_low_memory_message — %1$s + "çalışmak için"; %2$s/%3$s + "boş bellek"/"boş" ✓
- qwen_mnn / qwen35_2b / gemma_e2b / hymt disable_message — %1$s + "boyutundaki" ✓; metered_warning_message — %1$s + "boyutundadır" ✓; status lines — %1$s + "bellek", %2$s + "disk alanı", "(diskte %1$s)" ✓
- tts_language_unsupported_with_engine_message — %1$s + comma clause; %2$s + "dilini" ✓
- tts_language_unsupported_unknown_engine_message — %1$s + "dilini" ✓
- tts_voices_section_header — %1$s + "SESLERİ" (suffix on SESLER, not the placeholder) ✓
- tts_voice_region_numbered / tts_voice_numbered — bare juxtaposition ✓
- target_pack_migration_title / _message — %1$s + "tanımları"/"tanım paketi"; %2$s + "dilinde" ✓
- overlay_turn_off_title — %1$s + "kapatılsın mı" (separate word) ✓; overlay_turn_off_message — %1$s + "uygulamasından" ✓; overlay_hide_controls_title/_message — %1$s + "oyun ekranı kontrolleri"/"uygulamasını" ✓
- notif_text, status_accessibility_needed, quick_tile_add_row_subtitle, crash_dialog_message, settings_capture_display_footer, anki_permission_rationale_message, anki_settings_grant_access_subtitle, anki_not_installed_message, anki_models_unavailable, a11y_required_*, mp_overlay_permission_message — app/brand + "uygulaması…" head noun ✓
- pack_upgrade_progress_format(_with_bytes), install/bergamot/OCR download lines — placeholder + "indiriliyor" or "/" ✓
- tr_service_status_quota_fmt / _with_reset_fmt — "karakter kullanıldı"; "· sıfırlanma: %2$s" colon ✓
- backend_cooldown_status_fmt — "%1$s · %2$s %3$s" with colon-bearing connectors ✓
- word_detail_not_found — %1$s + "için" ✓; word_anki_deck_badge_cd — colon ✓; word_anki_in_decks — "%1$d Anki destesi" ✓
- status_error / settings_debug_export_logs_failed — colon ✓; settings_footer_version / crash_email_subject — "v" prefix ✓
- hotkey_show_hint_title / _dialog_title, live_mode_auto_with_hint, translate_button_subtitle_* — placeholder + bare verb phrase or "yerine" postposition ✓
- Fixed-text suffixes after xliff brand blocks (harmony decidable, all correct, all `\'`-escaped): Anki\'ye ×4, Anki\'de, AnkiDroid\'e ×3, AnkiDroid\'i ×3, Hunyuan\'ı, GitHub\'da, Discord\'a, PlayTranslate\'i, Google Play\'den ×2, TTS\'yi, Migaku\'nun ✓

## Verdicts

- Register consistency: good — siz throughout prose, platform-conventional short imperatives on buttons; two digest slips (`settings_anki_get_app_summary`, `settings_anki_grant_summary`).
- Terminology consistency: good — deste/kart/bilgi kartı/dil paketi/kısayol tuşu/yer paylaşımı/sözcük all consistent; fix "ölçülü ağ", "Görüntüden metne", "söz türü".
- Android-settings wording: mostly matches (Erişilebilirlik, "Diğer uygulamaların üzerinde göster", "Kısıtlı ayarlara izin ver", Hızlı Ayarlar/kutucuk); misses on metered ("sayaçlı") and the a11y nav-path section name.
- Vowel harmony at placeholders: clean — head-noun strategy applied at every site; zero direct-suffix-on-placeholder cases.
- i/İ casing: clean.
- Plurals: correct (singular noun in both categories).
- Truncation risk: low; no changes needed.
- Legal text: structure, §5(b), and country list preserved, but two ❌ fixes required (button-name mismatch, "ya da"→"ve" negation scope) plus the "BK" spelling-out.
- Overall: **fix-then-ship** — three ❌ items (two in the legal attestation) and the metered/OCR-header terminology before release; the rest are polish.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR sets; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| yomitan_auto_update_subtitle | ⚠️ | "Bu sözlüğün yeni sürümlerini otomatik olarak indir ve yükle" | "Bu sözlüğün yeni sürümlerini otomatik olarak indirir ve yükler" | Bare **sen**-imperative ("indir ve yükle") in a toggle-caption — both a register slip (file uses siz, e.g. `quick_tile_add_row_subtitle` "açıp kapatın", and `settings_anki_get_app_summary` was corrected to "indirin") and a mood mismatch: EN is a *descriptive caption* of what the switch does, not a command. Sibling toggle subtitles describe behavior in 3rd person (`settings_overlay_mode_subtitle` "…gösterileceği", `settings_vertical_grow_subtitle` "…genişletir"); the descriptive "indirir ve yükler" matches that pattern and sidesteps the imperative entirely. |

## Clean areas (delta)
Suffix-on-placeholder — clean at every contact point; the two highest-risk Turkish sites are correct:
- `yomitan_importing_progress` — "%2$d dosyadan %1$d. içe aktarılıyor…": the ablative case suffix ("-dan") attaches to the **fixed head noun** "dosya", not to %2$d; the "." after %1$d is an ordinal marker valid for any value. EN deliberately omits the noun; TR adds "dosya" (more natural than a bare ordinal in Turkish) — placeholder reorder is allowed and harmony cannot break. ✓
- `yomitan_import_summary_count` (one/other) — "%2$d sözlükten %1$d tanesi içe aktarıldı.": ablative "-ten" on fixed "sözlük", partitive "tanesi" on %1$d; no suffix on either placeholder. ✓
- `yomitan_import_summary_more` (one/other) — "+%1$d tane daha": classifier "tane" follows the count as a separate word; no suffix glued on. ✓
- Summary list lines (`_duplicates` "Zaten içe aktarılmış:", `_invalid` "Okunamadı:", `_no_space` "Yeterli alan yok:", `_failed` "Başarısız:") all use the **colon construction** before the `%1$s` file-name list — never a possessive/case suffix on the placeholder. ✓
- `anki_content_*_desc` brand refs use the colon-free **head-noun "alanı için"** after each `<xliff:g>` brand span; the brand suffixes themselves attach to fixed names and are all `\'`-escaped: Lapis\'in ×3, JPMN\'in ×3, Migaku\'nun ×1 (neighbor). ✓

i/İ casing — clean; no dotless-I errors (scan + read). Sentence-initial dotted-İ correct in "İçe aktarma tamamlandı", "İçe aktarılamadı", "Sözlük içe aktarılıyor"; mid-word "içe" stays dotted-lowercase. None pre-uppercased (the app uppercases labels at runtime), so labels like "Gelişmiş", "Özel URL", "Otomatik güncelle", "Metin okuma", "Ses" carry correct lowercase spelling. ✓

Terminology reuse — consistent with the file and the parameters: **sıklık** (frequency, matching `anki_content_frequency` "Sıklık yıldızları"), **perde vurgusu** (pitch accent, matching `yomitan_category_pitch_accent`), **sözlük** (dictionary), **içe aktar** (import, matching the whole Yomitan block), **alan(ı)** (field, matching `anki_content_source_pick_title`/`anki_sort_field_empty`), **metin okuma** (TTS — `audio_source_tts_name` "Metin okuma" matches `settings_header_text_to_speech`/`settings_cell_tts` 3×), **Ses** (`audio_source_picker_title` matches `anki_group_audio`/`tts_voice_picker_title`), **Sonuç yok** (`audio_no_results` matches `lang_search_no_results`/`dictionary_status_no_results`), **Yükleniyor…** (`audio_loading` matches `settings_ocr_installing`), **Gelişmiş** (standard Android "Advanced"). ✓

Register — siz throughout the new prose: `llm_backend_base_url_invalid` "https:// kullanın…" (siz-imperative), `anki_content_frequency_stylized_desc` "…kullanın" (siz). Toggle/label titles use the noun/infinitive form per convention ("Otomatik güncelle", "İçe aktarma tamamlandı"). Sole exception is the `yomitan_auto_update_subtitle` slip above. ✓

Plurals — both `<plurals>` correct: `yomitan_import_summary_count` keeps the singular noun "sözlük" after the numeral in **both** one/other (correct Turkish, not a copied EN split); `yomitan_import_summary_more` keeps singular "tane …daha" in both. ✓

Short-label truncation — low risk: "Ses", "Gelişmiş", "Özel URL", "Metin okuma", "Sonuç yok", "Yükleniyor…", "Yüklenemedi", "Bilinmeyen dosya" are all comparable to or shorter than typical row/label space; the only multi-word title ("İçe aktarma tamamlandı") is an alert title with room. ✓

`Example:` rule — `anki_content_pitch_position_desc` correctly leaves "Örnek: 0,2" as-is (sample not localized); the quoted field names ("PitchPosition", "PAOverride", "Frequency", "FrequenciesStylized", "FreqSort", "FrequencySort") are kept verbatim inside curly typographic quotes. ✓

Delta verdict: **ship after the one ⚠️** — `yomitan_auto_update_subtitle` (sen→descriptive). No ❌, no 🛑. The two Turkish-critical mechanics (no suffix on a bare placeholder; i/İ casing) are clean across all 29 keys.

---

## Delta review — 2026-07-14 sync

Scope: the 174 delta keys (170 new + 4 changed English) — game-audio recording & trim editor, translation History, Advanced LLM prompt editor, in-app updater, translation-service cells, single-app capture, the 38 `misc_*` dictionary chips.

Mechanical layer re-verified programmatically across all 174 keys: every key present, no extras; every `%1$s`/`%2$s`/`%d` present and matching EN; every `<xliff:g>` span byte-identical to EN (inner content, `id`, `example`); `\n` preserved in `floating_menu_capture_screen` ("Ekranı\nYakala"); every bare keyword literal (`{text}` `{strings}` `{N}` `{source}` `{source_code}` `{target}` `{target_code}`) byte-identical Latin and **unsuffixed**; every apostrophe `\'`-escaped (`Anki\'ye`, `GitHub\'dan` ×4, `URL\'sini`), no raw `'`; `<plurals>` = one/other. The `&amp;` absent from `update_dialog_download` is correct — TR uses "ve", not "&". **No 🛑 build-breaking issues. No ❌.**

**The two Turkish-critical mechanics are clean across all 174 keys** — see the Suffix coverage appendix below. This is why nothing reached ❌.

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| llm_prompt_row_translation_subtitle, llm_prompt_row_batch_subtitle | ⚠️ | "…çevresini saran **metin**." / "…kullandığı **metin**." | "…çevresini saran **istem**." / "…kullandığı **istem**." | **Binding-glossary violation.** The glossary requires *one noun across all `llm_prompt_*`* for "prompt". The file uses **istem** in the other 8 (`Sistem istemi`, `Çeviri istemi`, `Toplu istem`, `İstem boş olamaz`, `Bu istem çok uzun`…) and drops to **metin** ("text") in exactly the two strings that *explain what a prompt row is* — so the row titled "Çeviri istemi" is glossed as a "metin", and the user never learns the two are the same object. TR is the outlier: de/fr/es/ru all keep one terminological noun in these subtitles (Anfrage / requête / petición / Запрос). "istem" is the only choice that satisfies the glossary (which also forbids "request" → so **not** "istek"). |
| llm_prompt_fatal_missing_text, llm_prompt_fatal_missing_strings | ⚠️ | "…içermelidir — **o olmadan çevrilecek ifade** modele hiç gönderilmez." | "…içermelidir — **aksi takdirde** çevrilecek ifade modele hiç gönderilmez." | Garden path. `olmadan` is a converb and readily attaches to the following participle, so "o olmadan çevrilecek ifade" first parses as *"the phrase that will be translated **without it**"* — the opposite of a warning. The intended reading needs "o olmadan" to be a sentence adverbial. `aksi takdirde` ("otherwise") can only be sentence-level, so the ambiguity disappears; the em dash is kept. (Same class as the `crash_dialog_message` garden path in the base review.) 2 strings, one fix. |
| llm_prompt_discard_title, llm_prompt_discard_confirm | ⚠️ | "Değişiklikler **sil**insin mi?" / "Değişiklikleri **sil**" | "Değişiklikler **at**ılsın mı?" / "Değişiklikleri **at**" | **sil** is the app's committed *Delete* verb — `history_action_delete` "Sil", `pack_upgrade_button_delete` "Sil", `settings_ocr_disable_delete` "Modeli sil", `cd_delete_region`, `cd_yomitan_delete`. Nothing is *deleted* here: an unsaved edit buffer is abandoned. Reusing "sil" both collides with the glossary's Remove/Delete pair and misstates the action ("silmek" implies something persisted is destroyed). "atmak" is the idiomatic Turkish discard verb and is unused elsewhere in the file. (Avoid "Vazgeç" — it sits next to `btn_cancel` "İptal" and the two would read as the same button.) |
| tr_service_status_invalid_key | ⚠️ | "Geçersiz API **A**nahtarı" | "Geçersiz API **a**nahtarı" | Title-cases a common noun in a body-text status line. Turkish does not title-case, and the committed file already writes it lowercase everywhere in prose: `llm_backend_invalid_key_alert_title` "API anahtarı doğrulanamadı", `llm_backend_get_key_title_fmt` "…API anahtarı edinin", `llm_model_picker_fetch_failed` "API anahtarını kontrol edin", and its own delta sibling `tr_service_remove_message` "…API anahtarını siler". (Title Case is reserved in this file for *field labels* — `llm_backend_api_key_label`, `deepl_api_key_field_label` — which this is not.) Its four sibling status lines are all sentence case. |
| add_online_service_title | ⚠️ | "Çeviri **H**izmeti **E**kle" | "Çeviri hizmeti ekle" | The row `tr_service_add_online` ("Çevrimiçi çeviri hizmeti ekle") *opens the screen* whose toolbar this is — the same phrase, two capitalizations, on two surfaces the user sees back-to-back. The file's toolbar/picker titles are sentence case (`ocr_picker_title` "OCR aracını seçin", `pack_upgrade_progress_title` "Dil paketleri güncelleniyor", `lang_section_offline_models_title` "Çevrimdışı modelleri indir"); Title Case is the button/dialog family (`btn_continue`, `pack_upgrade_button_now`). Align the toolbar to the row. |
| game_audio_trim_duration | ⚠️ | "%1$s sn **seçildi** · %2$s sn **kaydedildi**" | "%1$s sn **seçili** · %2$s sn **kayıtlı**" | A status readout, not an event. `-di` announces something that *just happened* ("2,4 seconds got selected"), but this line renders continuously above the waveform **and** as the sentence-audio row title in the card editor. EN's "selected"/"recorded" are stative participles; Turkish's stative equivalents are `seçili` / `kayıtlı`. Also 4 chars shorter, which helps the row title. ("sn" itself is correct — matches the committed `settings_capture_interval_seconds_suffix`.) |
| game_audio_trim_no_audio | ⚠️ | "Ses yok" | "Ses olmadan devam et" | The only one of the trim editor's four buttons that doesn't read as an action — and **"X yok" is this file's status-line idiom**: `tr_service_status_no_internet` "İnternet yok", `tr_service_status_no_usage_today` "Bugün kullanım yok", `audio_no_results` "Sonuç yok". Beside "Seçimi kullan" / "Seçimi oynat" / "Bunun yerine metin okuma kullan", "Ses yok" reads as *"there is no sound"* (i.e. playback is broken), not as *"send the card silent"*. Avoid "Ses ekleme"/"Ses kullanma" — the negative imperative `-ma` is homographic with the verbal noun and stays ambiguous. |
| **(code, not strings)** `GameAudioTrimActivity.kt:207`, `SentenceAnkiContentFragment.kt:294` | ⚠️ | `String.format(Locale.US, "%.1f", seconds)` | `Locale.getDefault()` | Hard-codes a **period** decimal separator into the `game_audio_trim_duration` seconds value, so Turkish renders "2**.**4 sn seçildi" where it must read "2**,**4". The app's own Turkish text already uses comma decimals (`anki_content_pitch_position_desc` "Örnek: 0,2"). Cannot be fixed in `values-tr`. Affects every comma-decimal locale (tr, de, fr, es, pt-BR, ru, ar, vi) — flagging here because the value is only observable through this string. |
| misc_endearing | 💬 | "Sevecen" | "Sevgi sözü" | "Sevecen" describes a *person's disposition* (an affectionate person); the chip labels what a **word** does. "Sevgi sözü" (term of endearment) reads as a lexicographic label. |
| misc_polite | 💬 | "Kibar" | "Nezaket dili" | Breaks the parallelism of its own cluster — `misc_honorific` "Saygı dili" and `misc_humble` "Tevazu dili" are both `X dili`; "Kibar" is a bare adjective. The three render side by side on one word (JA sonkeigo/kenjougo/teineigo), where the shared shape is what makes them read as a set. |
| misc_idiomatic | 💬 | "Deyimsel" | "Deyim" | **Deyim** is the actual TDK label and is free — no `pos_*` collision (`pos_expression` "İfade", `pos_phrase` "Öbek", `pos_proverb` "Atasözü"). Also 3 chars shorter on a width-constrained chip. "Deyimsel" is a coined adjective. |
| misc_dated | 💬 | "Modası geçmiş" | "Demode" (only if truncation bites) | Largest EN→TR growth in the chip family (13 chars vs "Dated" 5) and the longest non-`yojijukugo` chip. "Modası geçmiş" is the more precise label, so keep it unless the chips actually clip — noted for the truncation ledger, not as a defect. |
| game_audio_trim_title | 💬 | "Oyun Sesini Kırp" | "Oyun sesini kırp" | Same toolbar-title casing question as `add_online_service_title`, but with no conflicting sibling to force it. Defensible either way; listed for consistency if the casing fix is applied. |

### Clean areas (delta)

**Suffix-on-placeholder — clean at all 30 contact points, zero exceptions** (see appendix). This is the finding I went looking hardest for and did not find: **no case/possessive suffix is glued to a variable placeholder anywhere in the delta.** Every `%1$s` / `%2$s` / `%d` is followed by a space and a head noun (`%1$s uygulamasını`, `%1$s modunu`, `%1$s güncellemesi`, `%d sözlük`, `%1$s sn`, `%1$s token`), a postposition (`%1$s tarafından`), a separate verb (`%1$s kaldırılsın mı`, `%1$s göstermek için`), or punctuation (`İndirme boyutu: %1$s`, `(%1$s)`). Vowel harmony cannot break for any runtime value.

**Brand suffixes on fixed spans — harmonized and escaped.** `Anki\'ye` (last vowel *i* → front → `-e`; vowel-final → buffer *y*) ✓. `GitHub\'dan` ×4 (pronounced final vowel *a* → back → `-dan`; voiced final *b* → `-dan`, not `-tan`) ✓ — the standard form in Turkish tech writing. `URL\'sini` (abbreviation takes its suffix behind an apostrophe per TDK; `sunucunuzun URL'si` + acc. `-ni`) ✓. The head-noun compounds correctly take **no** apostrophe: `PlayTranslate güncellemesi`, `PlayTranslate uygulamasının / uygulamasına` ✓ (TDK: the compound marker on the second element is not an inflectional suffix).

**`settings_yomitan_count_summary`** — correct. one/other (Turkish CLDR set), and the noun stays **singular after the numeral in both** ("1 sözlük içe aktarıldı" / "3 sözlük içe aktarıldı"). The two forms being *identical* is the correct Turkish outcome, not a copy-paste — and it matches the committed `yomitan_import_summary_count` precedent. ✓

**The 38 `misc_*` chips — the strongest part of this delta.** All four near-synonym clusters are internally distinguishable, with four (three) separate lexemes each:
- offensiveness: **Aşağılayıcı** / **Kırıcı** / **Kaba** / **Hakaret** ✓
- obsolescence: **Arkaik** / **Eskimiş** / **Modası geçmiş** / **Tarihsel** ✓
- informality: **Konuşma dili** / **Teklifsiz** / **Senli benli** / **Argo** ✓
- honorifics: **Saygı dili** / **Tevazu dili** / **Kibar** ✓

They are genuine **TDK dictionary labels**, not glosses of English — *Argo, Kaba, Mecaz, Örtmece, Yansıma, Teklifsiz, Eskimiş, Şaka yollu, Alay yollu, Çocuk dili* are all real TDK register marks. `misc_kana_only`/`misc_kanji_only` keep **kana**/**kanji** as loanwords per the glossary ✓. `misc_yojijukugo` correctly **describes** it ("Dört karakterli deyim") rather than romanizing ✓. Circumflexes are right where TDK wants them (**Resmî**, **Edebî**) — a careful-translator tell. Register and brevity match the committed `pos_*` family (single-word Turkish grammatical terms, sentence-cap); the noun/adjective mix mirrors TDK's own labels. Casing is uniformly sentence-case across all 38.

**Hard constraints, both verified against the committed file:**
- `ocr_source_label` — **mirrors `translation_source_label` exactly**. Committed: `%1$s tarafından çevrildi`. Delta: `%1$s tarafından tanındı` ✓ — identical structure, and `tanı-` ties to the app's OCR term "metin tanıma".
- **"Captured" is one verb** — `yakala-` throughout: `settings_cell_history_summary_*` "Yakalanan cümlelerin kaydı", `history_toggle_subtitle` "Yakalanan cümleleri", `history_empty_off` "…yakaladığı cümleleri", `audio_source_game_enable_hint` "…yakalamak için", `error_capture_blocked_secure` "ekran yakalamayı", matching the committed `floating_menu_capture_screen` / "Yakalama Bölgesi". No second verb introduced ✓.

**Terminology** — consistent with the committed file and the glossary: **istem** (prompt, 8/10 — see finding), **anahtar sözcük** (keyword), **Sağlayıcı** (Provider), **çeviri hizmeti** (matching the committed page title `settings_cell_translation_services` "Çeviri hizmetleri"), **kaldır/sil/temizle** correctly kept apart for Remove/Delete/Clear, **Geçmiş** (History, one noun), **kırp** + **seçim** (Trim + selection), **Oyun sesi** (reads as a noun phrase on both the pill and the section header), **LLM** kept as the Latin initialism, **güncelleme** (update) vs the committed pack **güncelleme**… — and critically, **sayaçlı** for metered (`update_dialog_metered_note`), which matches the corrected committed value in all 8 `*_metered_warning_*` strings. No `ölçülü` drift survives. **yedek** for fallback (`llm_status_low_memory_badge`) matches the committed `tr_service_offline_footer` "…yedek olarak kullanılır" ✓.

**Register** — siz throughout prose ("Tekrar deneyin", "açın", "izin verin", "girin", "gözden geçirin", "…oynadığınız"); buttons and toggle titles use the file's established bare imperative ("Kaldır", "Sıfırla", "Durdur", "Kopyala", "Tekrar dene", "Modeli sil", "Oyun sesini kaydet"); subtitles are descriptive 3rd person ("saklar", "kaydeder", "verir"). **No repeat of the `yomitan_auto_update_subtitle` sen-imperative slip** from the last delta ✓.

**i/İ casing — clean.** Sentence-initial dotted İ correct throughout (İzin, İndir, İndirme, İndirilen, İstem, İnternet argosu); dotless ı correct in Yalnızca, Başlatılıyor, Sıfırla, Kırıcı, Yansıma, Aşağılayıcı, Standart dışı, Modası geçmiş, Şaka yollu, Alay yollu. The one pre-uppercased string is correct ("UYARI:" ← uyarı, ı→I ✓), and the two runtime-uppercased headers spell correctly for the transform ("Gelişmiş LLM yapılandırması" → GELİŞMİŞ LLM YAPILANDIRMASI ✓, "Anahtar sözcükler" → ANAHTAR SÖZCÜKLER ✓). No dotless-I errors found by scan or read.

**Truncation** — low risk. `service_llm_badge` "LLM" (3) ✓; `probe_initializing` "Başlatılıyor…" (13 = EN's 13) ✓; `floating_menu_capture_screen` "Ekranı\nYakala" — `\n` intact, 6/6 balanced across two lines, and correctly inverted to object-then-verb for Turkish ✓. Longest `misc_*` chip is "Dört karakterli deyim" (21) vs EN's "Four-character compound" (22) ✓; only `misc_dated` grows materially (💬 above).

**`ör.`** is *not* a drift — the committed `hint_region_name` already uses "ör. Diyalog kutusu", and the full "Örnek:" form is reserved for the Anki `*_desc` strings that mirror EN's "Example:" ✓. Checked and dismissed.

**Naturalness** — no calques found in the new prose. `audio_source_game_ready` "Yakın zamanda oynadığınız oyundan" (avoids a stiff "son oyununuzdan"), `history_empty_none` "Satırlar çevrildikçe burada görünür" (`-dikçe` is exactly right for "as they translate"), `stream_kind_prompt_message` (correct `X için Y'den farklı çalışır` comparative), `settings_ocr_use_manga_subtitle` (semicolon improves on EN's comma splice), `update_error_signature` / `_downgrade` / `_no_space` all read natively.

**Verification note (not a finding):** `stream_kind_share_one_app` "Tek bir uygulamayı paylaş" / `stream_kind_share_entire_screen` "Tüm ekranı paylaş" are exempt per the review brief (AOSP SystemUI wording, deliberate) and are **not** flagged. Worth one device check nonetheless: Android 14's Turkish MediaProjection chooser may label the options "Tek bir uygulama" / "Ekranın tamamı" (noun phrases, no verb). If so, matching them exactly would strengthen the "name the option you just tapped" contract. I could not verify AOSP's `values-tr` from here, so no fix is proposed.

### Suffix coverage appendix (delta) — all 30 contact points

Every site in the 174 delta keys where a runtime value meets Turkish grammar. **Zero suffixes attached directly to a placeholder.**

*Head noun after the placeholder (harmony resolves on the fixed noun):*
- `floating_menu_panel_open_app` — %1$s + **uygulamasını** ✓
- `history_empty_off` — PlayTranslate + **uygulamasının** ✓
- `update_unknown_sources_message` — PlayTranslate + **uygulamasına** ✓
- `update_error_wrong_package` — PlayTranslate + **güncellemesi** ✓ (compound; correctly no apostrophe)
- `hotkey_auto_hint_title` — %1$s + **modunu** ✓ (parallel to `hotkey_auto_translation_title`)
- `game_audio_trim_duration` — %1$s + **sn**, %2$s + **sn** ✓
- `tr_service_status_usage_today_fmt` — %1$s + **token** ✓ (singular after a numeral — correct Turkish)
- `settings_yomitan_count_summary` — %d + **sözlük** in both one/other ✓

*Postposition:*
- `ocr_source_label` — %1$s + **tarafından** ✓ (mirrors `translation_source_label`)
- `llm_prompt_advisory_foreign_token` — %1$s + **bu istem tarafından** ✓ (nominative subject position)

*Separate word / verb phrase — no suffix:*
- `tr_service_remove_title_fmt` — %1$s + **kaldırılsın mı?** ✓
- `hotkey_show_hint_title` — %1$s + **göstermek için** ✓ (bare indefinite object; harmony cannot arise)
- `update_progress_title` — PlayTranslate + **güncelleniyor** ✓
- `update_error_no_space` — %1$s + **gerekiyor)** ✓
- `hotkey_auto_hint_dialog_title` — %1$s at end ✓ · `tr_service_key_tail_fmt` — %1$s at end ✓

*Colon / parentheses construction:*
- `update_dialog_size_note` — **İndirme boyutu:** %1$s ✓
- `settings_ocr_disable_manga_msg` — **(**%1$s**)** ✓

*Bare `{keyword}` literals in running prose — all followed by a space, never suffixed:*
- `llm_prompt_fatal_missing_text` **{text}** + içermelidir ✓ · `llm_prompt_fatal_missing_strings` **{strings}** + içermelidir ✓
- `llm_prompt_advisory_missing_count` **{N}** + eksik ✓ · `_missing_source` **{source}** / **{source_code}** + veya/eksik ✓ · `_missing_target` **{target}** / **{target_code}** + veya/eksik ✓

*Fixed-span brand suffixes (harmony decidable, all correct, all `\'`-escaped):*
- **Anki\'ye** (cd_add_to_anki, history_action_anki) ✓ · **GitHub\'dan** ×4 (update_error_incomplete / _signature / _verification / _wrong_package) ✓ · **URL\'sini** (llm_backend_base_url_custom_hint) ✓

### Delta verdict

**Ship after the ⚠️ set.** No 🛑, no ❌. The four Turkish-critical mechanics — suffix-on-placeholder, brand-suffix harmony, singular-noun-after-numeral plurals, i/İ casing — are clean across all 174 keys, and the `misc_*` chip family (the highest-risk group) is the best work in this delta: real TDK labels, four distinguishable clusters. The eight ⚠️ are one binding-glossary term drift (`istem` → `metin` in two subtitles), one garden-path (2 keys), one Delete/Discard verb collision (2 keys), two casing slips, two register/idiom slips in the trim editor, and one **code-side** decimal-separator bug that no locale file can fix.

---

## Delta review round 2 — 2026-07-14

Fresh independent re-derivation of the 174 delta keys after round 1's 15 corrections. Primary target: regressions introduced by the fixes.

Mechanical layer re-verified across all 174 keys: placeholder parity with EN (`%1$s`/`%2$s`/`%d`), `{keyword}` literal parity, `<xliff:g>` inner content + `id` + `example` byte-identical, every apostrophe `\'`-escaped, `\n` intact, plurals = one/other. **No 🛑.**

**Suffix audit — the primary job — PASSES.** All 20 placeholder/keyword contact points re-scanned mechanically (regex: placeholder immediately followed by a letter or apostrophe). **Zero hits.** None of the 15 edits glued a suffix to a variable. The two edits that touch a placeholder-bearing string are both safe: `game_audio_trim_duration` keeps the fixed head noun (`%1$s` + **sn** + `seçili`), and `llm_prompt_fatal_missing_text`/`_strings` keep `{text}`/`{strings}` followed by a space + `içermelidir` (bare indefinite object — the only suffix-safe construction, and correct Turkish SOV). Vowel harmony cannot break anywhere in the delta.

### Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| game_audio_trim_use_tts, game_audio_trim_no_audio | ❌ | "Bunun yerine metin okuma kullan" / "Ses olmadan devam et" | "TTS kullan" / "Ses olmasın" | **Round 1 pushed the trim editor's primary button off-screen.** `activity_game_audio_trim.xml:80-116` is a fixed **horizontal LinearLayout** — `[TextButton use_tts][4dp][TextButton no_audio][Space weight=1][Button save]` — with `wrap_content` buttons, **no weights, no ellipsize, no wrap, no scroll**. AOSP `LinearLayout.measureHorizontal` gives the buttons their full desired widths, the `Space` absorbs the negative delta and clamps to 0, and everything past the parent edge is simply **clipped**. Modelled at 14sp/411dp (Pixel-class): EN row = **372dp** (fits, 39dp slack). TR row = **564dp** → `game_audio_trim_save` ("Seçimi kullan", 131dp) starts at **x=421dp on a 411dp screen: 0 of 131dp visible.** The user cannot confirm a trim. Round 1 did not cause this alone — `use_tts` at 31 chars (2× EN) was already over budget (pre-round-1 row = 468dp, save button 86/131dp visible: **clipped but still tappable**) — but the `no_audio` expansion (7→20 chars, +96dp) took it from *degraded* to *unusable*. Round 1's semantic objection to "Ses yok" was sound (it is this file's status-line idiom: `tr_service_status_no_internet` "İnternet yok", `audio_no_results` "Sonuç yok"); the replacement just cost 13 characters on the one row in the entire delta that cannot afford them. **"Ses olmasın"** (11 ch) keeps the fix — the optative `-sın` marks a *choice*, not an existential status, so it cannot read as "playback is broken" — at 9 chars less. **"TTS kullan"** mirrors pt-BR's already-sanctioned width exception ("Usar TTS"), and the TTS initialism is already committed in this file (`tts_no_engine_get_google` "Google TTS\'yi Edin", `tts_no_engine_open_settings` "TTS ayarlarını aç"). Result: **353dp — fits even a 360dp phone, and is narrower than English.** |
| misc_yojijukugo | 💬 | "Dört karakterli deyim" | "Dört karakterli kalıp" | Round 1's `misc_idiomatic` "Deyimsel" → **"Deyim"** was right (real TDK label; independently re-verified free of any `pos_*` collision — `pos_expression` "İfade", `pos_phrase` "Öbek", `pos_proverb` "Atasözü", `pos_counter` "Sayma sözcüğü"), but it made `misc_idiomatic` a **substring of `misc_yojijukugo`**. The two tags co-occur on JMdict senses (a yojijukugo is routinely also `id`), and `renderMisc` joins with " · " → "**Deyim · Dört karakterli deyim**". `.distinct()` does not catch it (the strings differ), so the echo renders. EN has no echo because it says "compound", not "idiom". **kalıp** (set phrase) is the faithful rendering of "compound", is a real lexicographic term, and breaks the repetition. Cosmetic and rare — not a blocker. |
| llm_prompt_discard_message | 💬 | "Bu istemde yaptığınız **değişiklikler** kaydedilmedi." | "Bu istemde yaptığınız **düzenlemeler** kaydedilmedi." | After round 1's sil→at fix, the discard dialog reads title "Değişiklik**ler** atılsın mı?" / message "…değişiklik**ler** kaydedilmedi." / button "Değişiklik**leri** at" — **"değişiklik" three times in one small dialog.** EN deliberately varies: *changes* / *edits* / *Discard*. "düzenleme" is already this file's word for edit (`floating_menu_edit_region` "Bölgeyi düzenle"), so this both restores EN's variation and removes the echo. No width cost — `OverlayAlert` stacks its buttons vertically (`OverlayAlert.kt:204` `orientation = VERTICAL`), so "Değişiklikleri at" (17 ch) is safe on its own full-width button. |

### The 15 round-1 edits, re-derived from scratch

**14 of 15 are correct and integrate cleanly.** Verified individually:

- **`llm_prompt_row_translation_subtitle` / `_batch_subtitle` (metin → istem)** — correct, and it **resolved a collision rather than creating one.** "metin" is this file's word for *text*, and `llm_prompt_kw_text_desc` ("Çevrilecek metin" = EN "The text to translate") uses it that way. Before the fix, the same noun named both *prompt* and *text* inside one screen. Post-fix the glossary is airtight: **"istem" = prompt in 12/12 sites** (`Sistem istemi`, `Çeviri istemi`, `Toplu istem`, `İstem boş olamaz`, `İstem {text} içermelidir` ×2, `Bu istem çok uzun`, `Bu istem kaydedilemiyor`, `Bu istemi gözden geçirin`, `Bu istemde…`, `bu istem tarafından`, + the 2 fixed subtitles), **"metin" = text in 1/1**. Zero leaks. Both new subtitles are grammatical (`-an` and `-dığı` participles with correct genitive subjects).
- **`llm_prompt_fatal_missing_text` / `_strings` (→ aksi takdirde)** — correct. `aksi takdirde` can only be sentence-level, so the `o olmadan` converb garden path is gone. `{text}`/`{strings}` remain unsuffixed and sit in canonical SOV object position; the em dash is kept (locale-exempt).
- **`llm_prompt_discard_title` / `_confirm` (sil → at)** — correct, and **the `at-` / `kapat-` collision check passes.** Swept the whole file: `at-` (discard) appears at exactly two sites (lines 776, 780); `kapat-` (turn off) at ten (`overlay_turn_off_title`, `bergamot_disable_title`, `settings_ocr_disable_manga_title`, `menu_close`, `cd_close`, …). Distinct stems, distinct surface forms (**atılsın** vs **kapatılsın**), never co-resident on a screen. Better still, the fix *inherited* the file's confirm-title grammar: `X kapatılsın mı?` / `Bu kayıt silinsin mi?` / `Tüm geçmiş temizlensin mi?` / `%1$s kaldırılsın mı?` / **`Değişiklikler atılsın mı?`** — one shared "-Ilsın mı?" template signalling *confirm dialog*, five distinct verbs signalling five distinct actions. **temizle (Clear) / sil (Delete) / kaldır (Remove) / at (Discard) / kapat (Turn off) are now cleanly separated.** Plural inanimate subject + singular verb ("Değişiklikler atılsın") is correct Turkish. "Değişiklikleri at" correctly carries its object — bare "At" would read as the noun *horse*.
- **`tr_service_status_invalid_key` / `add_online_service_title` (casing)** — both correct. `tr_service_status_invalid_key` "Geçersiz API anahtarı" now matches all four sibling status lines (sentence case). `add_online_service_title` "Çeviri hizmeti ekle" is confirmed a **MaterialToolbar title** (`activity_add_online_service.xml:21`) opened by the row `tr_service_add_online` "Çevrimiçi çeviri hizmeti ekle" (`settings_row_add.xml:36`) — same phrase, now one casing, and TR drops "Çevrimiçi" in the toolbar exactly as EN drops "Online". No ellipsis risk (19 chars on a match_parent toolbar).
- **`game_audio_trim_title` (sentence case)** — correct, and the resulting register split is **principled, not a slip**: toolbar titles name a screen with a bare imperative ("Oyun sesini kırp", "Çeviri hizmeti ekle"), while dialog/picker titles prompt the user with siz ("OCR aracını seçin"). Both conventions are internally consistent.
- **`game_audio_trim_duration` (→ seçili / kayıtlı)** — correct. The stative participles are right for a continuously-rendered readout, and safe in **both** render sites: `tvTrimDuration` in the editor and the sentence-audio row title in the card editor (`SentenceAnkiContentFragment.kt:292`). `Text.PT.RowTitle` (`styles.xml:228`) sets **no `maxLines` and no `ellipsize`**, so it wraps — and the TR string is 30 chars, identical to EN's 30. No truncation. The `%1$s` head-noun **sn** is intact.
- **`error_capture_blocked_secure` (→ yakalanan uygulama)** — correct, and the **referent is right**: `ReconcilerLiveMode.kt:354-361` fires this only after a sustained all-black frame in the single-app capture path, i.e. the app *being captured* holds `FLAG_SECURE`. EN's "this app" is genuinely ambiguous; "yakalanan uygulama" resolves it and matches its sibling `error_single_app_not_fullscreen` ("yakalanan uygulama ekranı kaplamıyor") word for word. The "yakalanan … yakalamayı" root echo is the price of the committed capture glossary (`yakala-` is the one verb, per `floating_menu_capture_screen`); considered and **dismissed** — swapping to "ekran görüntüsü" to gain euphony would fracture the glossary. No change recommended.
- **`misc_polite` (→ Nezaket dili)** — correct and an improvement. The honorific triad is now parallel — **Saygı dili / Tevazu dili / Nezaket dili** — which is what makes the three read as a set when JA sonkeigo/kenjougo/teineigo land on one word. It joins a coherent 7-member "X dili" register frame (+ Konuşma / Çocuk / Kadın / Erkek dili); all seven are distinct and each genuinely names a speech register.
- **`misc_idiomatic` (→ Deyim)** — correct (see 💬 above for the one side effect).
- **`misc_endearing` (→ Sevgi sözcüğü)** — correct. Note this **deviates from round 1's written suggestion** ("Sevgi sözü"); the applied form is the more standard collocation and is equally free of collisions. No width cost (chips wrap). Accepted as-is.

### Clean areas (round 2)

**`misc_*` — all 38 labels mutually distinct, mechanically verified.** `renderMisc` (`MiscLabels.kt:31`) calls `.distinct()`, so an accidental duplicate would silently collapse two tags into one. None do. All six near-synonym clusters remain internally separable after round 1's three edits: offensiveness (Aşağılayıcı / Kırıcı / Kaba / Hakaret / Hassas), obsolescence (Arkaik / Eskimiş / Modası geçmiş / Tarihsel), informality (Konuşma dili / Teklifsiz / Senli benli / Argo / İnternet argosu / Manga argosu), honorifics (Saygı dili / Tevazu dili / Nezaket dili / Resmî), figurative (Deyim / Mecaz / Dört karakterli deyim / Örtmece), affect (Sevgi sözcüğü / Şaka yollu / Alay yollu / Çocuk dili). The only substring overlaps are `Argo` ⊂ `İnternet argosu` / `Manga argosu` — **intentional and correct** (EN does the same), since these are specializations of slang.

**Width sweep — the whole delta, not just the chips.** Every delta string referenced from a layout was checked against its container. `activity_game_audio_trim.xml`'s action bar is the **only** hard-clip site: everything else is a `match_parent` toolbar (ellipsizes, but 16-19 chars on a full-width bar), a wrapping `TextView`, an icon `FrameLayout`/`ImageView` carrying only a content description (`history_action_*`, `tr_service_delete_cd`, `cd_choose_ocr`), or the 3-char `service_llm_badge`. Round 1 checked the `misc_*` chips for truncation and declared "low risk" — that was right for the chips and wrong for the one row that matters.

**Plurals.** `settings_yomitan_count_summary` is the delta's only `<plurals>`: one/other both "%d sözlük içe aktarıldı". Read at every band (0 / 1 / 3), the singular noun after the numeral is correct Turkish; the two forms being identical is the right outcome, not a copy-paste. `settings_yomitan_empty_summary` covers the zero case separately.

**Register / i-İ casing / brand suffixes** — re-checked, unchanged and clean. siz throughout prose; bare imperatives on buttons and toggle titles; descriptive 3rd person on subtitles. No dotless-I errors. `Anki\'ye`, `GitHub\'dan` ×4, `URL\'sini` all harmonized and escaped; head-noun compounds (`PlayTranslate güncellemesi`, `PlayTranslate uygulamasının`) correctly take no apostrophe.

**Known code defects, not re-filed** (per brief): `SentenceAnkiContentFragment.kt:294` and `GameAudioTrimActivity.kt` format seconds with `Locale.US`, so `game_audio_trim_duration` renders "2**.**4 sn seçili" where Turkish requires "2**,**4". Unfixable in `values-tr`.

### Round-2 verdict

**FIX FIRST** — one ❌. The Turkish trim editor's confirm button ("Seçimi kullan") is laid out entirely off-screen; shortening `game_audio_trim_use_tts` → "TTS kullan" and `game_audio_trim_no_audio` → "Ses olmasın" brings the row to 353dp (narrower than English) and restores it. Everything else round 1 touched is correct: the suffix audit passes with zero hits, the `at-`/`kapat-` verbs do not collide, and all 38 `misc_*` labels remain distinct. The two 💬 are polish.

**Cross-locale escalation (not a TR finding).** The same row overflows a 411dp screen in **de (572dp), fr (524dp), ru (521dp), es (469dp)** as well as tr (564dp). Only EN (372dp) and pt-BR (336dp) fit — and pt-BR fits *because* it was measured and cut. The root cause is the layout, not the translations: a fixed horizontal `LinearLayout` of `wrap_content` buttons with no weight, ellipsize, wrap, or scroll will clip its last child in any locale that runs long. Shortening strings is a mitigation; giving the row a wrapping/`FlexboxLayout`/stacked-button treatment is the cure.

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
quotes; `<plurals>` categories exactly one/other. `./gradlew :app:processDebugResources`
is green. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | was | now | why |
|---|---|---|---|---|
| `settings_ocr_note_mlkit` | ⚠️ | "Metni yoğun ekranlarda bile hızlı" | "Metni yoğun ekranlarda bile yavaşlamaz" | The English comment forbids reusing the literal Fast tier label; the first pass reused «hızlı», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

**No suffix is attached to any placeholder** anywhere in the delta — vowel harmony is never guessed. `settings_ocr_footer_guidance` uses a free-standing head word (“`%1$s`” etiketli motor), `image_import_no_text` and `camera_snapshot_no_text` suffix the head noun instead (`%1$s` metni algılanmadı, mirroring the committed `status_no_text`), `settings_support_check_updates_subtitle` uses a colon frame, and `update_none_message` leaves both spans bare. i/ı are spelled for runtime uppercasing (`capture_show_on_screen` → EKRANDA GÖSTER carries no dotted-i hazard). Both plural categories take the singular noun after the numeral, which is correct Turkish, matching `yomitan_import_summary_count`. **motor** for engine matches Android's Turkish for a pluggable engine and stays clear of **araç** (tool) and **model** — all three meet in `settings_ocr_delete_camera_import_note`. anlık görüntü for the camera freeze-frame stays distinct from ekran görüntüsü (`anki_group_screenshot`). `hotkey_capture_screen_dialog_title` (Ekranı Yakala) matches `floating_menu_capture_screen`'s wording. siz-level imperatives throughout.

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
