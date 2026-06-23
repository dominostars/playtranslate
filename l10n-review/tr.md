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
