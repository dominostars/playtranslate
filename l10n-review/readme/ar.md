# Arabic (ar) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] ar -> readme/README.ar.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Features > Capture regions | ⚠️ | "اقصر الالتقاط على مربع الحوار، أو الترجمة المصاحبة، أو أي منطقة مخصصة تحددها" | "اقصر الالتقاط على مربع الحوار، أو النص المعروض أسفل الشاشة، أو أي منطقة مخصصة تحددها" | الترجمة المصاحبة is the standard formal term for subtitles, but in a README where الترجمة means the app's output, «capture the accompanying translation» invites the misreading that you capture translated text. The subtitles you capture are the game's own source-language text. If the maintainer wants to keep the term, a gloss also works: «أو الترجمة المصاحبة (النص أسفل الشاشة)». |
| Optional: Online Translation Backends (the 9-item list) | ⚠️ | "- **DeepL**: باقة مجانية متاحة على" | "- ‏**DeepL**: باقة مجانية متاحة على" | Conditional: check the rendered page first. As far as I know (not verifiable offline), GitHub stamps `dir="auto"` on each `<ul>`/`<ol>`/`<p>`/heading, so a block takes its direction from its first strong character, not from the `<div dir="rtl">` wrapper. This is the only list whose first character is Latin (D of DeepL), so the whole list, including the Arabic-led «مخصص» item, would lay out LTR: bullets on the left, and «احصل على المفتاح من ‹url›، ثم اختر النموذج…» split into segments in left-to-right order. The suggested text adds an invisible U+200F RIGHT-TO-LEFT MARK straight after `- `. One mark on the first item flips the list. Adding it to all 8 Latin-led items (DeepL to Claude) also covers per-`<li>` `dir=auto`. Bold parsing is unaffected. If the list already renders right-aligned, drop this row. |
| Features > Offline | 💬 | "ويمكنك اختياريًا تنزيل النماذج دون اتصال للترجمة" | "ويمكنك أيضًا تنزيل نماذج اختيارية للترجمة دون اتصال" | «تنزيل النماذج دون اتصال للترجمة» leaves للترجمة dangling and lets دون اتصال attach to تنزيل («download … without a connection»). «نماذج … للترجمة دون اتصال» says «models for offline translation», and «اختيارية» keeps the English «optional» as a property of the models instead of stacking it on يمكنك. The README is not pointing at the `lang_section_offline_models_title` heading here, so the wording doesn't need to match it exactly. |
| Features > Hotkeys | 💬 | "وهو مثالي لأجهزة الألعاب المحمولة المزوّدة بأزرار مخصصة" | "وهذا مثالي لأجهزة الألعاب المحمولة المزوّدة بأزرار مخصصة" | وهو binds to the nearest masculine noun, «زرًا فعليًا», so it reads as «a physical button, which is ideal for handhelds with dedicated buttons». وهذا refers to the whole setup, as the English «great for handhelds» does. |
| Features > Yomitan integration | 💬 | "تندمج قواميس Yomitan بسلاسة في التطبيق بكل ما فيها: النبرة الصوتية" | "تندمج قواميس Yomitan بسلاسة في التطبيق، بما في ذلك النبرة الصوتية" | «بكل ما فيها:» («with everything in them:») presents the four items as everything a Yomitan dictionary carries. The English says «including». The rest of the sentence reads correctly after the swap. |
| Features > Text-to-speech | 💬 | "استمع إلى النص منطوقًا بصوت عالٍ" | "استمع إلى النص مقروءًا بصوت عالٍ" | The Arabic is correct as it stands. «مقروءًا بصوت عالٍ» matches the app's own read-aloud wording (`header_action_read_aloud` «قراءة بصوت عالٍ», `cd_read_original_aloud`). |
| How to Install, step 2 | 💬 | "واختر التطبيق، ثم فعّل **السماح من هذا المصدر**" | "واختر المتصفح أو مدير الملفات، ثم فعّل **السماح من هذا المصدر**" | Everywhere else in this README, التطبيق means PlayTranslate («يترجم التطبيق», «داخل التطبيق», «يعتمد عليها التطبيق»), so «اختر التطبيق» reads as «pick PlayTranslate», which is not installed yet and is not in that list. The English «the app» is just as vague, but in Arabic the clash is sharper. |
| Won't install? (closing sentence) | 💬 | "ثم أعِد تفعيل Play Protect بعد ذلك ليواصل فحص تطبيقاتك الأخرى" | "ثم أعِد تفعيل Play Protect ليواصل فحص تطبيقاتك الأخرى" | ثم already means «afterward», so «ثم … بعد ذلك» says it twice. The English has the same redundancy («then … afterward»), but in Arabic it reads as padding. |
| Supported Languages > Translation languages heading | 💬 | "### لغات الترجمة (يُترجَم إليها من أجلك)" | "### لغات الترجمة (يترجم إليها التطبيق)" | «من أجلك» is a calque of «for you» and makes the passive heading stiff. The sibling heading «(تُقرأ من الشاشة)» and the intro's «(اللغات التي تظهر لك بها الترجمة)» are both natural. The checker locks only the heading level, not its text. |

## Clean areas (checked, no findings)

**Register.** The whole file is formal MSA with no dialect. It addresses the reader in the masculine singular throughout: imperatives افتح، اضغط، ثبّت، عطّل، فعّل، اتبع، أكمل، مرّر، وجّه, plus يمكنك / لك / تطبيقاتك / مجموعاتك. There is no number or gender mixing. «انقر» is used for the GitHub links (clicked) and «اضغط» for on-device taps, which is the app's own verb.

**Terminology vs strings.xml.** Every feature or setting name the README uses matches the app:
- خدمات الترجمة, in «الإعدادات ← خدمات الترجمة» (`settings_cell_translation_services`)
- الاختصارات (`settings_cell_hotkeys`), and «اضغط مطولاً» (`hotkey_show_translations_title`)
- تحويل النص إلى كلام (`settings_cell_tts`), and «صوت تحويل النص إلى كلام» (`anki_content_word_audio_desc`)
- الترجمة التلقائية (`live_mode_auto_translate_label`). Dropping «وضع» from «Auto Translation Mode» matches the app's label.
- فوريغانا / بينيين (`header_action_furigana`, `hint_label_pinyin_lower`), with the definite article as in `onboarding_welcome_learn_body`
- مناطق الالتقاط (`menu_capture_region`, `nav_regions`), and مربع الحوار (`hint_region_name`)
- الكاميرا and «وجّه الكاميرا نحو النص» (`settings_cell_camera`, `settings_cell_camera_summary`), and «التقط لقطة» (`camera_shutter_cd` «التقاط لقطة»)
- البحث عن الكلمات (`onboarding_a11y_row_lookup_title`), بضغطة واحدة (`onboarding_welcome_learn_body`), «مع تغيّر الحوار» and «دون اتصال بالإنترنت» (`onboarding_welcome_play_body`)
- بطاقات Anki (`settings_cell_anki`), لقطة الشاشة (`anki_group_screenshot`), قائمة الكلمات (`anki_content_words_table`), the كلمة هدف wording (`anki_content_flag_targeted_sentence_desc`), نوع البطاقة (`anki_card_type_row_label`), مجموعة for deck (parameters doc)
- صوت اللعبة / تسجيل صوت اللعبة (`audio_source_game_name`, `anki_game_audio_row_title`)
- النبرة الصوتية and التكرار (`yomitan_category_pitch_accent`, `yomitan_category_frequency`), and «بسلاسة» (`yomitan_page_description`)
- سجل النصوص, الملتقَطة and معطّل (`history_toggle_title`, `history_toggle_subtitle`, `history_empty_off`)
- «السماح بالإعدادات المقيَّدة», «أعلى اليسار», «المصادقة» and «أذونات إمكانية الوصول» (`restricted_settings_title`, `restricted_settings_message`)
- مخصص and «عنوان URL الخاص بخادمك» (`llm_backend_preset_custom`, `llm_backend_base_url_custom_hint`), so there is no literal «base URL»
- «النماذج دون اتصال» (`lang_section_offline_models_title`), and العائمة (the app's own adjective for floating, `settings_show_overlay_icon` «الأيقونة العائمة»)

«المعاينة بالضغط المطوّل» in the accessibility section is better Arabic than the app's «معاينة الضغط المطوّل» (`settings_overlay_mode_subtitle`). It names no setting here, so the difference does not matter. A bare «دون اتصال» as the bullet label is acceptable.

**The ML Kit sentence.** «وتُستخدم ترجمة ML Kit دون اتصال كحل بديل عند عدم توفّر الترجمة عبر الإنترنت» means «used whenever online translation is unavailable», not «when there is no network». It follows `tr_service_offline_footer` («تُستخدم الترجمات دون اتصال كحل بديل عند عدم توفّر الترجمات عبر الإنترنت») almost word for word.

**Android and Play Store steps.** Step 2's path «الإعدادات ← التطبيقات ← الوصول الخاص للتطبيقات ← تثبيت التطبيقات غير المعروفة» and the switch «السماح من هذا المصدر» match my recollection of AOSP ar `special_access` / `install_other_apps` / `external_source_switch_title`. I could not verify them on disk: the SDK's framework `values-ar` does not carry Settings strings, and no system image is installed (see appendix). «تطبيق ضار» matches the framework's `harmful_app_warning_title` «تم العثور على تطبيق ضار», which I verified in `platforms/android-36/data/res/values-ar`. «لم يتم تثبيت التطبيق» and the «تثبيت» button are the PackageInstaller wording as I know it.

The Play Store labels are right. The launcher label is «متجر Play». Play Protect and Google Play Protect stay Latin, as Google keeps them in Arabic. The toggle is «فحص التطبيقات باستخدام Play Protect», and «رمز الملف الشخصي» follows Google's Arabic help phrasing. The restricted-settings steps reuse the app's matched strings.

**Accuracy.** All 13 feature bullets and all 9 backend bullets are present. The step counts are intact (4 / 5 / 4), and each block keeps its closing sentence. The Anki bullet keeps all six card contents, plus game audio and card-type presets. The Yomitan bullet keeps all four items, «(حتى في Anki)», and the future-integration line.

The additions only clarify:
- «شاهد … مع لعبة» in the video link
- «احصل على المفتاح من …» in the backend bullets, whose links do point at key pages
- «واقرأ ترجمته» in the camera bullet
- «من خارج المتجر» for «sideloaded», which is a good localization

None of them changes a capability claim.

**Links and tables.** The checker confirms that every link is absolute and byte-identical, and that the pointer section carries only `#credits` and `LICENSE`. Link text is natural («انقر هنا لتنزيل…», «خادم Discord», «ملف README باللغة الإنجليزية»). I diffed both tables against the pre-generated `tables/ar.md`. All 89 lines are identical except the two header rows, which read اللغة / الاسم الأصلي / الرمز. The pointer section names libraries, models, linguistic data and GPL 3.0.

**Typography.** The quotes are « », and every question uses ؟. Arabic commas ، are used throughout (27), and the prose has no Latin comma, semicolon or question mark. There are no em dashes and no straight quotes. The bold-label colon has no space before it and one after.

There is no tatweel and no bidi control character in the file, and no trailing or double spaces. Tanween follows the app's convention: ـًا in general (تلقائيًا، فورًا), and ـلاً after lam (أولاً، تكاملاً، مطولاً, as in the app's قليلاً، طويلاً). The numbers agree: «26 لغة», «59 لغة» (singular tamyiz for 11 to 99), and the dual «الجدولان مرتّبان» and «الشاشتين».

## Arabic RTL appendix

**Wrapper.** The file opens with `<div dir="rtl">` and a blank line, and closes with a blank line and `</div>`, which the checker confirms. In CommonMark the opening `<div>` is an HTML block (type 6) that ends at the first blank line, so everything between the tags is parsed as ordinary Markdown. The headings, all five lists, both tables, the bold labels and the links therefore render normally. GitHub's sanitizer keeps the `dir` attribute.

**Per-block direction.** As far as I know, GitHub renders `<p>`, headings and `<ul>`/`<ol>` with `dir="auto"`. That means each block's direction comes from its first strong character, not from the wrapper. Tables carry no `dir`, so they inherit `rtl`: the Language column sits on the right and Code on the left.

I checked the first strong character of every block. All of them start with Arabic except two:
- the H1 «PlayTranslate», which will be LTR and left-aligned. It is harmless for a brand title, so leave it.
- the backends list, which is the ⚠️ row above.

No paragraph starts with a Latin token. A quick confirmation, without opening a browser, is to diff the output of GitHub's own Markdown endpoint (e.g. `gh api /markdown` on the file) for `dir="auto"` on the `<ul>`.

**Arrows and mirrored positions.** All 6 arrows are ← (3 in install step 2, 2 in the restricted-settings path, 1 in «الإعدادات ← خدمات الترجمة»), and there is no →. In each path the arrow points toward the next item in reading order, including before the Latin «PlayTranslate» at the end of the restricted-settings path.

«أعلى اليسار» appears 3 times: the Play Store profile icon, the Play Protect gear, and the App-info ⋮. This matches `restricted_settings_message`, and the Play Store mirrors its top app bar in RTL, so the avatar and gear really are on the left.

**Mixed-script seams.** I traced each seam with the Unicode bidi rules, assuming an RTL block:
- «خادم Discord.» at the end of the paragraph: the period sits between L and the paragraph end, so it resolves to the embedding direction and lands visually after «Discord», on the left. That is correct.
- «(حتى في Anki).»: the bracket pair contains strong R, so both brackets resolve R and mirror correctly. The classic failure, a parenthesis stuck to the Latin side, does not occur.
- «مثل Ayn Thor، أو»: the Arabic comma sits between L and R, so it takes R and stays after «Thor» in reading order.
- «Persona 3 Reload» and «GPL 3.0»: the digits follow a Latin letter, so they resolve to L and each run stays intact. «26» and «59» after Arabic resolve AN and display normally, including «و59».
- «ترخيص المشروع: [GPL 3.0]» and every «**Brand**: …» label: the colon between L and R resolves R, so it reads Brand, then colon, then Arabic.
- «Ko-fi»: the hyphen sits between two L characters and stays inside the run. The bare Ko-fi URL at the end of its paragraph sits at the left edge with no trailing punctuation to drift.
- «⋮» is neutral and renders inside the Arabic sentence.

**The terms the translator asked about.**
- الترجمة المصاحبة (subtitles): see the ⚠️ row.
- العدسة العائمة (floating lens): keep it. العائمة is the app's own word for floating («الأيقونة العائمة»), and the adjective stops a bare «العدسة» from being read as the camera lens in a README that also has a camera tool. «العدسة المكبّرة» would be the alternative, but the app has no on-screen label for the lens, so it would gain no consistency.
- تلميحات النطق (reading hints): keep it. It is better than «تلميحات القراءة», which reads as «reading tips». النطق is also the app's own word for pronunciation (`yomitan_category_pronunciation`).
- «Special app access» (الوصول الخاص للتطبيقات): this is my best recollection of the AOSP/Pixel label, but I could not confirm it offline. `~/Library/Android/sdk/platforms/android-36/data/res/values-ar` holds only framework strings, and `system-images/` is empty. A glance at any Arabic-locale Pixel or AOSP device would settle it.

  On Samsung One UI the entry is «الوصول الخاص» behind a ⋮ menu. The English path is just as AOSP-specific, and the step's last sentence (Android offers to open the screen when the APK is opened) covers OEM drift.

## Disposition (2026-10-06)

Applied all 9. The `dir="auto"` question is confirmed, not conditional: GitHub's renderer
(`gh api /markdown`, 2026-10-06) emits `<ul dir="auto">` inside the `<div dir="rtl">` wrapper and
keeps a U+200F mark in the text, so every block whose first strong character is Latin takes its
direction from that character. A right-to-left mark was therefore added at the start of every
such block (the 8 Latin-led backend bullets and any other Latin-led list item or paragraph),
not only the first one. The H1 title stays as is. The subtitles finding took the suggested
rewording «النص المعروض أسفل الشاشة».
