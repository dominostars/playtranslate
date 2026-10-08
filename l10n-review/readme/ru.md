# Russian (ru) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] ru -> readme/README.ru.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Optional: Online Translation Backends | ⚠️ | "а запасным вариантом без сети служит офлайн-перевод ML Kit." | "а когда онлайн-перевод недоступен, запасным вариантом служит офлайн-перевод ML Kit." | «запасным вариантом без сети» reads as "the fallback for when there is no network", i.e. only when the device is offline. The app uses ML Kit whenever online translation is unavailable: `tr_service_offline_footer` is «Офлайн-переводы используются как запасной вариант, когда онлайн-переводы недоступны». The suggestion follows that wording. |
| Features > Anki export | 💬 | "Тип карточки выбирается вручную, а для популярных колод есть готовые пресеты." | "Можно выбрать тип карточки, а для популярных колод есть готовые пресеты." | «вручную» is not in the English. Next to "presets" it suggests a manual chore, when the point is that you can pick your own card type. «готовые пресеты» itself is fine (see appendix). |
| Features > Camera translation | 💬 | "или остановите кадр, чтобы нажимать на слова и смотреть их значения." | "или сделайте снимок, чтобы нажимать на слова и смотреть их значения." | The camera's freeze button is «Сделать снимок» (`camera_shutter_cd`), and the app calls the frozen frame a snapshot throughout. «остановите кадр» is understandable but is not the app's word. |
| Features > Text-to-speech | 💬 | "текст можно прослушать вслух. Голос по умолчанию меняется в настройках." | "приложение может прочитать текст вслух. Голос по умолчанию можно сменить в настройках." | «прослушать вслух» is a slight collocation clash, since «вслух» goes with reading or speaking, not with listening. «меняется в настройках» reads like a description of a setting that changes by itself; «можно сменить» is the natural way to say "you can change". |
| Features > Furigana/Pinyin Mode | 💬 | "подсказки к чтению показываются над иероглифами в реальном времени." | "над иероглифами в реальном времени показывается их чтение." | «подсказки к чтению» can be read as "tips for reading". «чтение» as the reading of a character is unambiguous, and «иероглифы» rightly covers both kanji and hanzi. |
| Features > Offline | 💬 | "- **Офлайн**: распознавание текста (OCR)" | "- **Работа офлайн**: распознавание текста (OCR)" | A bare «Офлайн» as a feature name reads clipped, because the word is an adverb in Russian. Every other bullet name is a noun phrase. |
| Features > Dual Screen & Split Screen | 💬 | "а также в режиме разделения экрана Android рядом с игрой в оконном режиме." | "а также в режиме разделения экрана Android рядом с игрой, запущенной в окне." | The sentence says «режим» twice and «экран» three times. «запущенной в окне» says "windowed game" without repeating «режиме». |
| How to Install, step 4 | 💬 | "При первом запуске пройдите шаги начальной настройки, чтобы выдать необходимые разрешения" | "При первом запуске пройдите начальную настройку и предоставьте необходимые разрешения" | The app always says «предоставить разрешение» (`btn_grant_permission`, `anki_permission_rationale_message`, `settings_anki_grant_summary`). «выдать» is developer slang. «пройдите шаги начальной настройки» is also wordy. |
| Can't enable accessibility? | 💬 | "переключатель в настройках может быть серым и неактивным или выдавать сообщение об ограниченной настройке." | "переключатель в настройках может оказаться серым и неактивным, или при нажатии на него появится сообщение об ограниченной настройке." | A switch does not «выдавать сообщение»: the dialog appears when the user taps it. Describing the dialog rather than quoting it is acceptable (see appendix). |
| Intro | 💬 | "а заодно помогает учить языки." | "и помогает учить языки." | «а заодно» ("and while it's at it") makes language learning a side benefit. The English names it as one of the app's two purposes ("translation and language learning"). |
| Supported Languages (intro sentence) | 💬 | "(это текст, который приложение считывает с экрана)" | "(на них написан текст, который приложение считывает с экрана)" | «это текст» equates the languages with the text. The English is loose here too ("the text it can read"), but in Russian the mismatch is visible. |
| Supported Languages > Translation languages heading | 💬 | "### Языки перевода (на них вы читаете перевод)" | "### Языки перевода (на них приложение переводит для вас)" | Says «перевод» twice in one short heading. The suggestion is also closer to the English "translated for you". The checker does not lock heading text. |

## Clean areas (checked, no findings)

**Register.** The file uses formal lowercase вы throughout: «вы впервые откроете», «выбранную вами», «ваши колоды», «URL вашего сервера». Every imperative is the -те form (захватите, наведите, назначьте, откройте, отключите, добавляйте). There is no ты, твой or capitalised Вы/Ваш anywhere.

**Terminology vs strings.xml.** All of these match the app's words:
- Сервисы перевода (`settings_cell_translation_services`, in «Настройки → Сервисы перевода»), Горячие клавиши, Синтез речи (also `audio_source_tts_name`)
- Автоперевод (`live_mode_auto_translate_label`, `settings_header_auto_translate`)
- фуригана, and пиньинь with the app's own declension (`icon_action_swap_pinyin` «пиньинем», so the genitive «пиньиня» is consistent)
- Области захвата (`menu_capture_region`), and «Диалоговое окно» (the app's own `hint_region_name` example)
- Карточки Anki (`settings_cell_anki`), Звук игры (`audio_source_game_name`), Снимок экрана (`anki_content_picture`), список слов and целевое слово (the Anki content strings)
- История текста (`history_toggle_title` «Хранить историю текста», `history_empty_off`), with the app's capture verb («захваченных предложений»)
- поиск слов and «в одно касание» (`onboarding_welcome_learn_body`)
- «Скачать офлайн-модели» wording for the offline models (`lang_section_offline_models_title`)
- тональное ударение and «органично встраиваются» (`yomitan_page_description`)
- Языки игры (`lang_translate_from` «Язык игры»), Распознавание текста (OCR), Специальные возможности, «разрешение на специальные возможности», «при удержании ... предпросмотра» (`settings_overlay_mode_subtitle`)
- Свой (`llm_backend_preset_custom`), and «укажите URL вашего сервера», which mirrors `llm_backend_base_url_custom_hint` «Введите URL вашего сервера» and avoids a literal "base URL". `llm_backend_base_url_label` «Свой URL» is consistent with it.

«Языки перевода» is not the picker's label («Ваш язык»), but the English README's "translation languages" is not that label either, so the README is not naming a setting there.

**Android and Play Store steps.** Install step 2 has «Настройки → Приложения → Специальный доступ → Установка неизвестных приложений» and the per-app switch «Разрешить установку из этого источника» (AOSP `special_access` / `install_other_apps` / `external_source_switch_title`). The installer button is «Установить», and the failure text «Приложение не установлено» is Android's own wording.

The Play Store labels are Google Play (the app's current Russian label, not the old «Play Маркет»), Google Play Защита, Play Защита and «Проверять приложения с помощью Play Защиты». The case forms are right: «включите Play Защиту», «с помощью Play Защиты». The restricted-settings steps match `restricted_settings_title` and `restricted_settings_message`: «Разрешить ограниченные настройки», the ⋮ menu «в правом верхнем углу», and «аутентификацию».

**Accuracy.** All 13 feature bullets and all 9 backend bullets are present. The step counts are intact: 4 install steps, 5 Play Protect steps, 4 restricted-settings steps, and the closing sentence of each block. The Anki bullet keeps all six card contents plus game audio and presets, and the Yomitan bullet keeps all four items, "incl. Anki" and the future-integration line. Two renderings localise well: "pick a model at runtime" becomes «модель выбирается прямо в приложении», and "sideloaded" becomes «установленные не из Google Play».

**Links and tables.** The checker confirms that every link is absolute and that the URLs match. Link text is natural («Нажмите здесь, чтобы скачать…», «серверу Discord», «англоязычном README»). The video line keeps the Latin title Persona 3 Reload, which has no official Russian release title.

Both tables are byte-identical to the pre-generated `tables/ru.md` rows, all 87 of them. Only the header cells changed, and they read Язык / Самоназвание / Код («Самоназвание» is the right word for an endonym). The pointer section names libraries, models, linguistic data and GPL 3.0, and links only #credits and LICENSE.

**Typography and grammar.** The quotes are « » (no straight quotes outside URLs). After the bold feature name comes a colon and then lowercase text. The compounds are hyphenated (Android-устройств, API-ключ, APK-файл, офлайн-модели, онлайн-сервисы). ё is used consistently in the prose (нём, выдаёт, шестерёнки, объединённые, удаётся). Numerals agree («26 языков», «59 языков», с + genitive, на + accusative).

The cases around brands also hold up:
- the indeclinable brands sit in correct frames («в AnkiDroid», «на Ko-fi», «к нашему серверу Discord»)
- «с API, совместимым с OpenAI, —» closes the participial phrase with a comma before the dash
- «например Ayn Thor» correctly has no comma after «например» at the head of the clarifying phrase

There are no trailing spaces or double spaces.

## Russian-specific appendix

- **«плавающая лупа»:** keep it. «Лупа» is the established Russian UI word for an on-screen magnifier (Windows «Экранная лупа», iOS «Лупа»). «плавающая» matches the app's «плавающий значок». The alternatives are worse: «линза» is an optics calque and «увеличительное стекло» is long. The app has no string that names the lens, so nothing conflicts.
- **«готовые пресеты»:** keep it. «Пресет» is the normal word in Russian gaming and creative-software writing, and «готовые пресеты» is a standard collocation, not a pleonasm a reader would notice. «готовые шаблоны» would collide with Anki's card templates, which the app calls «шаблон» in `anki_content_flag_*_desc`. «предустановки» is stiff. The only issue in that sentence is «вручную» (findings table).
- **"Restricted setting" dialog:** describing it («сообщение об ограниченной настройке») instead of quoting it is acceptable. The wording echoes the «Разрешить ограниченные настройки» item the reader taps next. I could not check AOSP's Russian title for that dialog (no web tools), and quoting a guessed label would be worse than describing it. OEM skins also vary.
- **"Special app access":** «Специальный доступ» is stock Android. Samsung One UI shows a different path (it puts «Установка неизвестных приложений» under «Особые права доступа»). Android also offers to take the reader to the right screen, as step 2 says, so this needs no change.
- **Pre-generated table rows (not a README fix):** «Китайский (упрощенная)» / «Китайский (традиционная)» come from CLDR and are left as the brief instructs. The feminine adjective has no head noun, and «упрощенная» lacks the ё the prose uses. If they are ever changed, the change belongs in the generator and the app's picker, not in this file alone (for example «Китайский (упрощённое письмо)»).

## Disposition (2026-10-06)

Applied all 12: the ⚠️ (ML Kit fallback wording from `tr_service_offline_footer`) and the 11 💬
as suggested. Install step 2 had been rewritten in English (Apps → Special app access path) and
re-translated by the translator before this review, so the reviewed step is the current one.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] ru -> readme/README.ru.md`, no warnings; also PASS with `--require-header`). **No 🛑 issues.**

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Can't enable accessibility? (intro paragraph) | 💬 | "а при нажатии на него появляется сообщение «Настройки с ограниченным доступом»." | "а при нажатии на переключатель появляется сообщение «Настройки с ограниченным доступом»." | «на него» has three grammatical antecedents (переключатель, приложения, APK-файла). The intended one is the farthest, and «APK-файла» sits right before the pronoun. The sense resolves it (a switch is what you tap), so this is a nit. Naming the noun costs one repeat in the sentence. Moving «для любого приложения, установленного из скачанного APK-файла,» up to follow «Начиная с Android 13» also fixes it, without the repeat. |

Clean areas: The four steps match the English in order, with nothing added or dropped. «Этот пункт появляется только после шага 1» keeps the condition, and "(top right)" and the closing sentence went with the English. «система» stands in for the English's second "Android" and still says who greys the switch out. Every Android label matches AOSP 16 QPR2 ru byte for byte: Настройки, Специальные возможности, Приложения, «О приложении», Разрешить доступ к настройкам and «Настройки с ограниченным доступом». They also match the app's reviewed `a11y_restricted_settings_addendum` («Начиная с Android 13», «неактивен», «страницу «О приложении»») and the «Настройки → Специальные возможности» path in `overlay_icon_a11y_required_message`. The old «Разрешить ограниченные настройки» is gone from the file. Quoted labels stay nominative in apposition, and the return trip uses the inflected «в раздел специальных возможностей», as the app's running text does. Formal вы is used throughout, quotes are « » with the period outside, bold sits on the same four spans as the English (both paths, ⋮ and the menu item), and items 1 to 4 end in periods like the English.

### Disposition (2026-10-08)

Applied the 1 💬 («при нажатии на переключатель»).
