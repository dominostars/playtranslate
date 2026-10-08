# Turkish (tr) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] tr -> readme/README.tr.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Features > Dual Screen & Split Screen | 💬 | "Android'in bölünmüş ekranında, pencerede çalışan oyunların yanında da kullanılabilir" | "Android'in bölünmüş ekran modunda, pencere modundaki oyunların yanında da kullanılabilir" | The least natural clause in the file. “bölünmüş ekranında” (Android's split screen, possessive) is correct but stiff; Turkish names the feature “bölünmüş ekran (modu)”. For "windowed games" Turkish players say “pencere modu(ndaki)”; “pencerede çalışan” is a paraphrase. |
| Supported Languages (intro) | 💬 | "**26 oyun dilinden** (ekrandan okuyabildiği metinler)" | "**26 oyun dilinden** (ekrandan okuyabildiği metinlerin dili)" | The gloss puts “metinler” (texts) next to a language. The second gloss, “(size gösterilen dil)”, names a language, so the two would match. The English is just as loose, so this is optional. |
| Optional: Online Translation Backends > OpenAI, Gemini, DeepSeek, Mistral, Groq, OpenRouter, Claude (7 bullets, one fix) | 💬 | "[platform.openai.com](https://platform.openai.com/api-keys); modeli uygulama içinden seçin" | "[platform.openai.com](https://platform.openai.com/api-keys) (modeli uygulama içinden seçin)" | These are the semicolons that replaced the English em dashes. They are readable but slightly mechanical: in Turkish a semicolon joins clauses, and a bare link label is not a clause. A parenthetical is how a Turkish README would add this note. In each of the 7 bullets, replace `; modeli uygulama içinden seçin` with ` (modeli uygulama içinden seçin)`. The wording itself is right: "at runtime" means inside the app. |
| Optional: Online Translation Backends > Custom | 💬 | "OpenAI uyumlu başka herhangi bir uç nokta; Özel URL alanına sunucunuzun URL'sini girin" | "OpenAI uyumlu başka herhangi bir uç nokta (**Özel URL** alanına sunucunuzun URL'sini girin)" | Same punctuation fix as the row above. Bold the field name the way the README bolds the other UI labels. The words already match the app: “Özel” = `llm_backend_preset_custom`, “Özel URL” = `llm_backend_base_url_label`, and “Sunucunuzun URL'sini girin” = `llm_backend_base_url_custom_hint`. |

No ❌ and no ⚠️. The four rows above are optional polish.

## Clean areas (checked, no findings)

- **Register:** polite siz throughout. Imperatives: tıklayın, katılın, çevirin, sınırlayın, dinleyin, kaydedin, okuyun, bakın, izleyin, seçin, açın, verin, dokunun, kapatın, yükleyin, doğrulayın, girin. Possessives: cihazınızda, tarayıcınızın, dosya yöneticinizin, simgenize, kimliğinizi, kameranızı, destelerinize, sunucunuzun, dokunmanıza, tuttuğunuz. There is no sen form and no infinitive-style imperative.
- **Terminology vs `values-tr/strings.xml`:** every feature and settings name matches the app.
  - Çeviri hizmetleri (`settings_cell_translation_services`): used in the settings path and in the H2.
  - Kısayol tuşları (`settings_cell_hotkeys`); Metin okuma (`settings_cell_tts`, `audio_source_tts_name`); Metin geçmişi (`history_toggle_title`, `history_empty_off`); Yakalanan cümlelerin kaydı (`settings_cell_history_summary_*`, so "captured" is the app's “yakala”).
  - Otomatik Çeviri modu: `live_mode_auto_translate_label` is “Otomatik Çeviri”, and `hotkey_auto_translation_title` already says “Otomatik Çeviri modunu”. The capital Ç in the feature label is consistent with the app. Lowercase “otomatik çeviri” is right for generic running text (`settings_header_auto_translate`), and the README uses the capital only for the mode's name.
  - Furigana / pinyin (`header_action_furigana`, `hint_label_pinyin_lower`); Yakalama bölgeleri (`menu_capture_region` “Yakalama Bölgesi”, `nav_regions` “Bölgeler”); Diyalog kutusu (`hint_region_name`).
  - Metin tanıma (OCR) (`settings_header_ocr`, verbatim); çevrimdışı modelleri indirmek (`lang_section_offline_models_title` “Çevrimdışı modelleri indir”).
  - Sözcük arama (`onboarding_welcome_learn_body`, `onboarding_a11y_row_lookup_title`): the app uses sözcük everywhere and never kelime. Tek dokunuşla (`onboarding_welcome_learn_body`). “Kayan” for the floating lens matches the app's “kayan simge” (`icon_gestures_no_menu_message`, `overlay_icon_a11y_required_message`). The app has no string that names the lens, so “büyüteç” has nothing to match.
  - Anki: Anki Bilgi Kartları (`settings_cell_anki`, used as the H2); deste (glossary); Kart türü (`anki_card_type_row_label`); Sözcük listesi (`anki_content_words_table`); hedef sözcük (`anki_content_flag_targeted_sentence_desc`); Ekran görüntüsü (`anki_group_screenshot`); Oyun sesini kaydet (`anki_game_audio_row_title`, `audio_source_game_name` “Oyun sesi”); orijinal metin (`section_original`, `cd_read_original_aloud`). “Hazır ayarlar” for presets has no app string to match, and it correctly avoids “şablon”, which the app uses for Anki templates.
  - Yomitan: perde vurgusu and sıklık (`anki_content_pitch_position`, `anki_content_frequency*`); “sorunsuzca bütünleşir” echoes `yomitan_page_description`.
  - Kamera: “kameranızı … metne doğrultup” = `settings_cell_camera_summary`, and “anlık görüntü alıp” = `camera_shutter_cd` “Anlık görüntü al”. That is the app's own word for "freeze a frame".
  - API anahtarı (`llm_backend_api_key_label`); Erişilebilirlik izni, capitalized mid-sentence as the app does (`accessibility_dialog_message`, `a11y_required_hotkey_message`); “… yolunu izleyin” for a settings path, the app's own construction (`accessibility_dialog_message`).
- **ML Kit sentence:** “çevrimiçi çeviri kullanılamadığında ise çevrimdışı yedek olarak ML Kit devreye girer” means "used whenever online translation is unavailable", not "when the device is offline". It mirrors `tr_service_offline_footer` (“çevrimiçi çeviriler kullanılamadığında yedek olarak kullanılır”). Correct.
- **Android / Play Store wording:**
  - From disk: “Zararlı uygulama tespit edildi” is byte-identical to the framework's `harmful_app_warning_title` (android-36 `values-tr`). Stating the full title, where the English quotes only the fragment "harmful app", is the better choice for a reader who has to recognize the dialog.
  - From memory (nothing on disk to diff, no device pass), these match stock AOSP/Play Turkish: Ayarlar → Uygulamalar → Özel uygulama erişimi → Bilinmeyen uygulamaları yükle; the switch “Bu kaynaktan izin ver”; the installer's “Yükle” button and its “Uygulama yüklenmedi” failure text; Play Store → profil simgesi → Play Protect → dişli simgesi → “Uygulamaları Play Protect ile tara”.
  - The restricted-setting labels match the app's reviewed strings (`restricted_settings_title`, and the quoted “Kısıtlı ayarlara izin ver” in `restricted_settings_message`). See the appendix for the one open AOSP question.
  - Glossing "sideloaded" as “Play Store dışından yüklenen” reads well and is used the same way in both troubleshooting sections.
- **Accuracy:** all present, in order, nothing dropped or added: 13 feature bullets, 4 install steps, 5 Play Protect steps, 4 restricted-settings steps, 9 backend bullets. Small clarifications that stay true to the app:
  - “isterseniz çevrimdışı çeviri modellerini de indirebilirsiniz” for "optional offline models": they are downloads.
  - “modeli uygulama içinden seçin” for "at runtime".
  - “59 çeviri dili” in the intro where the English says "user languages". This matches the body's "translation languages", and the picker's “Diliniz” can't take a numeral.
  - “Bu bütünleşme ileride daha da derinleşecek” is a fair rendering of "Look for deeper integration in the future".
- **Tables and links:**
  - Header cells are translated (Dil / Yerel adı / Kod), and so are both `###` headings and the intro sentence.
  - Every Turkish language name in both tables is byte-identical to the JDK's `Locale.getDisplayName(tr)` (CLDR) output, checked for all 26 + 59 codes: Çince (Basitleştirilmiş) / (Geleneksel), Svahili dili, Marathi dili, Telugu dili, Kannada dili, Tagalogca, Haiti Kreyolu, Galiçyaca, Afrikaanca, Maltaca and so on. That is what the app's picker shows. The native-name and code columns are untouched (the checker verifies this).
  - The release links are absolute. Link texts read naturally: “En son sürümü indirmek için buraya tıklayın”, “Discord sunucumuza”, “İngilizce README dosyasında”. The video line is on its own line.
  - The pointer section “Teşekkürler ve lisans” names libraries, models and language data, links `#credits` and the GPL 3.0 LICENSE, as designed.
  - Cosmetic, raw source only: the CJK/Indic native-name cells are padded unevenly. GitHub renders the table the same either way.
- **Typography:**
  - Turkish “ ” quotes on all 3 quoted UI messages; no straight double quotes; no em or en dashes anywhere.
  - Colons sit right after bold labels. Text after a label is capitalized when a sentence follows (all feature bullets) and lowercase for fragments (“ücretsiz plan için …”, “OpenAI uyumlu …”), consistently.
  - The H2s capitalize the word after “İsteğe bağlı:”, the usual heading style. TDK's lowercase-after-colon rule is for running text, so no row.
  - Lists keep the English period pattern (the feature bullets on lines 22, 24 and 25 end with a period, as in English; steps and backend bullets don't).
  - Dotted capital İ is correct throughout (İngilizce, İsteğe, İstendiğinde, İki, İsveççe, İbranice, İzlandaca, İrlandaca, İtalyanca).
  - Singular nouns after numerals (26 oyun dilini, 59 çeviri dilini).

## Turkish-specific appendix

### Suffix coverage (every brand, code, URL and bold-label contact point)

| contact point | form | verdict |
|---|---|---|
| Persona 3 Reload + locative | Reload'da | ✓ last vowel o → -da; final d is voiced, so no -ta |
| Android + genitive | Android'in | ✓ the dominant Turkish usage (the word is said with a final -id); the app has no precedent either way |
| Anki + dative | Anki'ye | ✓ = app `history_action_anki` “Anki\'ye ekle” |
| AnkiDroid + dative (×2, Features and Anki section) | AnkiDroid'e | ✓ = app `AnkiDroid</xliff:g>\'e` (2 strings) |
| Play Protect + accusative | Play Protect'i | ✓ said “protekt”, e → -i |
| PlayTranslate + accusative / genitive | PlayTranslate'i, PlayTranslate'in | ✓ = app `PlayTranslate\'i` |
| URL + possessive + accusative | URL'sini | ✓ = app `llm_backend_base_url_custom_hint` “URL\'sini” |
| furigana + accusative | furiganayı | ✓ common noun, so no apostrophe |
| büyüteç + accusative | büyüteci | ✓ ç → c softening |
| Ayarlar + ablative / locative | Ayarlardan, Ayarlardaki | ✓ no apostrophe, as in the app (`note_mlkit_account_issue` “Ayarlardan”) |
| Bold labels and links | **Play Store** uygulamasını, **Play Protect** seçeneğine, **Bu kaynaktan izin ver** seçeneğini, **Uygulamaları Play Protect ile tara** seçeneğini, **Kısıtlı ayarlara izin ver** seçeneğine, **⋮** menüsüne, **Ayarlar → …** yolunu / sayfasını / bölümünde, [AnkiDroid](…) uygulamasını, [GPL 3.0](…) lisansı, Ko-fi üzerinden, Yükle düğmesine | ✓ every suffix sits on a Turkish head noun, never on the Latin label |
| APK | APK dosyasını (×3), APK dosyalarını (×1) | ✓ head-noun construction throughout |
| Suffixes inside bold | **profil simgenize**, **dişli simgesine**, **26 oyun dilinden**, **59 çeviri diline** | ✓ Turkish words, harmony correct |

### Open item, not a finding: “Kısıtlı” vs AOSP's restricted-setting wording

The README (“Kısıtlı ayar” message, step 3 “Kısıtlı ayarlara izin ver”) matches the app's reviewed `restricted_settings_title` / `restricted_settings_message`, so the README and the app agree. Which word AOSP Settings uses in Turkish can't be checked here: the Settings app's strings are not on disk, and the framework file uses both forms (“Arka plan verileri kısıtlı”, but “kısıtlanmış” in the CLIR and battery-saver strings). From memory, which is unverified, Google's Turkish Settings leans to “kısıtlanmış” (“Kısıtlanmış profil”, the battery-usage option “Kısıtlanmış”). The dialog may therefore read “Kısıtlanmış ayar” and the ⋮ item “Kısıtlanmış ayarlara izin ver”. Either way the reader would recognize the item, because the root is the same. Change nothing unless a Turkish-locale Android 13+ device shows “Kısıtlanmış”. If it does, change the README (the accessibility paragraph and step 3) and both app strings together, so that they stay identical.

### Naturalness overall

This reads like a Turkish technical writer's README. Sentences are rebuilt in Turkish order (“Engeli kaldırmak için:”, “Böyle bir durumda taramayı geçici olarak kapatın:”, “Oyun sesini bile kaydedip karta ekleyebilirsiniz!”). Glosses go to the reader (“Play Store dışından yüklenen”, “ekrandaki adımları izleyerek”), and the app's own nouns are reused even for small things (anlık görüntü, Özel URL, yolunu izleyin). The only spots that feel translated are the four 💬 rows. Verdict: ship as is, or after the optional polish. Nothing blocks.

## Disposition (2026-10-06)

Applied all 4 💬 (bölünmüş ekran modunda / pencere modundaki, metinlerin dili, parenthetical
model notes ×7, **Özel URL** parenthetical). "Kısıtlı" stays, matching the app's own strings;
the AOSP wording question is left for a device check.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS with one warning, `warning 38 bold spans, English has 37` (the same with `--require-header`). The warning predates this delta and does not come from this section. At HEAD the counts were 37 and 36, and the extra span is **Özel URL** in the Custom backend bullet, which the 2026-10-06 review added on purpose. This section has 4 bold spans in both files. **No 🛑 issues.**

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Can't enable accessibility? (intro paragraph) | 💬 | "Android 13 ve sonraki sürümlerde Android, indirilen bir APK dosyasından yüklenen her uygulamanın Erişilebilirlik anahtarını gri gösterir;" | "Android 13 ve sonraki sürümlerde sistem, indirilen bir APK dosyasından yüklenen her uygulamanın Erişilebilirlik anahtarını gri gösterir;" | The second «Android» comes five words after the first. The English repeats it too, but in Turkish the two sit close enough to read as a stumble. «sistem» keeps the agent, which is the point of the rewrite (Android itself greys the switch out, not an OEM), and the subject comma stays. The Russian translation made the same choice. Optional. |

Clean areas: The four steps match the English in order, with nothing added or dropped. «Bu seçenek yalnızca 1. adımdan sonra görünür» keeps the condition, «İstenirse» is "if prompted", and «Sağ üstteki» and the closing sentence went with the English. Every Android label matches AOSP 16 QPR2 tr byte for byte: Ayarlar, Erişilebilirlik, Uygulamalar, Uygulama bilgileri, “Kısıtlanmış ayar” and Kısıtlanmış ayarlara izin verme. They also match the app's reviewed `a11y_restricted_settings_addendum` and the path in `overlay_icon_a11y_required_message`. This closes the 2026-10-06 open item on «Kısıtlı» vs «Kısıtlanmış»: no «Kısıtlı» is left in the file. The menu label ends in «izin verme», which on its own reads as "don't allow". Here it is bold and followed by «seçeneğini seçin», so it reads as the item's name, the same reasoning that kept the quotes in the app string. Every suffix sits on a Turkish head noun (bölümünde, uygulamasını, mesajını, sayfasını, menüsüne, seçeneğini, 1. adımdan), never on a label or on PlayTranslate. Polite siz is used throughout, quotes are “ ”, bold sits on the same four spans as the English, items 1 to 4 end in periods like the English, and «anahtar» (switch) matches the app.

### Disposition (2026-10-08)

Applied the 1 💬 («sürümlerde sistem,»).
