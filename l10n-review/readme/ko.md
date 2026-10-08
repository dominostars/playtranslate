# Korean (ko) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] ko -> readme/README.ko.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Optional: Online Translation Backends | ⚠️ | "오프라인일 때는 ML Kit 번역으로 대체합니다." | "온라인 번역을 사용할 수 없을 때는 오프라인에서 작동하는 ML Kit 번역으로 대체합니다." | "오프라인일 때는" says ML Kit only takes over when the device has no connection. The app falls back whenever online translation is unavailable: `tr_service_offline_footer` is "오프라인 번역은 온라인 번역을 사용할 수 없을 때 대체로 사용됩니다". The suggestion keeps the preceding "…를 사용하고,". |
| Features > Offline | 💬 | "필요하면 오프라인 번역 모델도 추가할 수 있습니다." | "필요하면 오프라인 번역 모델을 다운로드해 사용할 수도 있습니다." | The app's section is "오프라인 모델 다운로드" (`lang_section_offline_models_title`). "추가" does not tell the reader the models are a download. |
| How to Install, step 4 | 💬 | "처음 실행하면 온보딩 안내에 따라 필요한 권한을 허용하세요" | "처음 실행하면 화면의 안내에 따라 필요한 권한을 허용하세요" | "온보딩" is UX jargon. The app never uses the word (no hits in values-ko), and a general reader knows "안내". |
| Can't enable accessibility?, last line | 💬 | "이제 PlayTranslate의 접근성을 사용 설정할 수 있습니다." | "이제 접근성 설정에서 PlayTranslate를 사용 설정할 수 있습니다." | "PlayTranslate의 접근성" reads as "PlayTranslate's accessibility". The app's own phrasing for this step is `status_accessibility_needed` "먼저 접근성 설정에서 PlayTranslate를 사용 설정하세요". |
| Support | 💬 | "Ko-fi에서 PlayTranslate를 후원할 수 있습니다: https://ko-fi.com/playtranslate" | "[Ko-fi](https://ko-fi.com/playtranslate)에서 PlayTranslate를 후원할 수 있습니다." | A colon after a sentence-final 합니다 is English punctuation. Folding the URL into the link keeps the sentence Korean. Do not write "Ko-fi(https://…)에서": GitHub's autolink would swallow ")에서", and the checker skips a bare URL after "(". The suggested form passes the checker (verified on a scratch copy). |
| Supported Languages > Game languages table | 💬 | "\| 중국어 (간체) \|" and "\| 중국어 (번체) \|" | "\| 중국어(간체) \|" and "\| 중국어(번체) \|" | These are the only parentheses in the file with a space before them. Everywhere else they attach (게임 언어(…), 기능(…), 어디서나(Anki 포함)), and Android's Korean display names (CLDR "{0}({1})") are 중국어(간체) / 중국어(번체). Native-name and code columns are unaffected. |
| Video link | 💬 | "[Persona 3 Reload에서 PlayTranslate를 사용하는 모습]" | "[페르소나 3 리로드에서 PlayTranslate를 사용하는 모습]" | The game shipped in Korea, with Korean text, as 페르소나 3 리로드, and Korean players know it by that title. The line stays a single link, so the checker's video rule still holds. |
| Optional: headings | 💬 | "## 선택 사항: 온라인 번역 서비스" and "## 선택 사항: Anki 플래시카드" | "## 온라인 번역 서비스(선택 사항)" and "## Anki 플래시카드(선택 사항)" | The "선택 사항:" prefix copies the English "Optional:" label. A Korean heading more often carries it as a trailing parenthetical, and the app itself writes "(선택 사항 — …)" in `crash_email_body`. Heading levels are unchanged. Optional: the prefix form is understandable. |
| Optional: Online Translation Backends > Custom | 💬 | "그 밖의 OpenAI 호환 엔드포인트(“사용자 지정 URL”에 사용할 기본 URL을 입력)" | "그 밖의 OpenAI 호환 엔드포인트(“사용자 지정 URL”에 사용할 백엔드 URL을 입력)" | "기본 URL" is the developer-docs rendering of "base URL", but a general reader takes it as "default URL". The field's own placeholder is `llm_backend_base_url_custom_hint` "백엔드 URL을 입력하세요". “사용자 지정 URL” itself is right: it matches `llm_backend_base_url_label`, and **사용자 지정** matches `llm_backend_preset_custom`. |

All suggested replacements were applied together to a scratch copy, and `readme_l10n_check.py`'s `check()` still returns no errors and no warnings. Each "current" string occurs exactly once in the file.

## Clean areas (checked, no findings)

**Register.** Bodies are polite 합니다체 throughout (번역합니다, 표시됩니다, 있습니다), and steps use the polite imperative ~하세요 / ~해 주세요. There is no 반말, and 당신 never appears: "the language shown to you" becomes "사용자에게 보여 주는 언어". The two FAQ headings, "설치가 안 되나요?" and "접근성을 켤 수 없나요?", use the polite ~나요 question. That is the idiomatic Korean help-page heading, not a slip into informal speech, so I did not flag it.

**Terminology vs strings.xml.** Every feature and setting name matches the app:
- 번역 서비스 (`settings_cell_translation_services`, in "설정 → 번역 서비스")
- 단축키 (`settings_cell_hotkeys`)
- 텍스트 음성 변환 (`settings_cell_tts`; "텍스트 음성 변환 오디오" also matches `anki_content_word_audio_desc`)
- 텍스트 기록 / 기록 (`history_toggle_title` 텍스트 기록 유지, `settings_cell_history` 기록), with the app's verb 캡처한 문장
- 자동 번역 모드 (`live_mode_auto_translate_label` 자동 번역)
- 후리가나 and 병음 (`header_action_furigana`, `hint_label_pinyin_lower`)
- 캡처 영역 (`menu_capture_region`)
- 카메라 (`settings_cell_camera`, and "카메라를 비추면" echoes `camera_permission_rationale` "카메라로 비춘 텍스트")
- 사전 정의 / 사전 검색 (`settings_cell_dictionary` 사전)
- Anki 플래시카드 (`settings_cell_anki`)
- 게임 오디오 (`audio_source_game_name`)
- 제한된 설정 허용 (`restricted_settings_title`, `restricted_settings_message`)
- 단어 검색 (`onboarding_a11y_row_lookup_title`)
- 원탭 (`onboarding_welcome_learn_body`)
- 고저 악센트 (`yomitan_category_pitch_accent`)
- 대상 단어 and 단어 목록 (`anki_content_*`)
- 카드 유형 (`anki_card_type_row_label`)
- 무료 요금제 (`service_account_required_free`)

"단어 위에 올리면 사전 정의가 바로 표시됩니다" mirrors the lens's gesture-binding label `icon_action_lookup_words` "단어 위에 올려 정의 보기".

**Android / Play Store wording.** All of these match what Android and the Play Store show in Korean:
- 출처를 알 수 없는 앱 설치
- the installer's “설치” button
- “앱이 설치되지 않았습니다”
- Google Play 프로텍트
- Play 스토어
- Play 프로텍트 (menu)
- Play 프로텍트로 앱 검사
- “제한된 설정” (dialog)
- 제한된 설정 허용
- the ⋮ menu
- 설정 → 앱 → PlayTranslate

"사용하는 파일 관리자나 브라우저를 허용하세요" fits the per-app toggle (이 출처 허용) without naming it, as the English doesn't. The "설정 → 보안" path follows the English source, so it is not a translation issue.

**Accuracy.** Nothing is dropped or added:
- the 13 feature bullets, the 4 install steps, the 5 Play Protect steps, the 4 restricted-settings steps and the 9 service entries are all present
- the counts 26 / 59 are intact
- the claims match the English

Small, faithful clarifications are fine:
- "PlayTranslate에 AnkiDroid 접근 권한을 허용하면" makes explicit which access is meant
- "번역을 실시간으로 읽을 수 있고" spells out what the camera reads live
- "make requests" → "기능을 요청" is a reasonable narrowing in context

**Links and tables.**
- Every link is absolute and resolves: releases, APK, Discord twice, video, Lingva, the eight key pages, AnkiDroid, Ko-fi.
- Link texts are natural ("여기를 눌러 최신 릴리스를 다운로드하세요").
- Both tables translate the header cells as 언어 / 원어 표기 / 코드, and their native-name and code columns are byte-identical to English.
- Korean language names follow CLDR/Android: 튀르키예어, 카탈로니아어, 아이티어, 조지아어, 타갈로그어, 에스페란토어.
- The pointer section names credits for libraries, models and language data plus the GPL 3.0 license, and links `#credits` and `LICENSE`, as designed.

**Typography.**
- Quotes are “ ” throughout, with no straight quotes, em dashes or 「」.
- The English em dashes in the service list became parentheticals, which suits Korean.
- 띄어쓰기 is correct, including auxiliary verbs (보여 주는, 읽어 들이는, 켜 두세요, 막아 두기, 소리 내어 읽어 줍니다).
- Feature bullets end with periods and step lists don't, consistently within each list.

## Korean-specific appendix

**Particle audit (the known Korean risk).** Every particle that directly follows Latin text, a number or a link was checked against its Korean pronunciation. All of them are correct:
- PlayTranslate는 / 를 / 에 / 의, and PlayTranslate**를 (플레이트랜슬레이트, vowel)
- [Lingva](…)를 (링바)
- [AnkiDroid](…)를 and AnkiDroid에
- OCR과 (오시알, final ㄹ)
- APK를 (에이피케이)
- URL을 (유알엘, final ㄹ) and “사용자 지정 URL”에
- README의
- Ko-fi에서
- Reload에서
- [Discord 서버](…)에
- [크레딧](…)과 (final ㅅ)
- **Google Play 프로텍트**가, **Play 스토어**를, **프로필 아이콘**을, **톱니바퀴 아이콘**을, **제한된 설정 허용**을
- 26개와 / 59개를, and the bolded **게임 언어 26개**(…)를 / **번역 언어 59개**(…)로, where the particle correctly agrees with 개 across the parenthetical
- “앱이 설치되지 않았습니다”나

There are no variable placeholders in a README, so the 을(를) combined forms do not apply.

**Coinages asked about.**
- **플로팅 돋보기** (floating lens): keep. The code calls it `MagnifierLens` and draws it as a magnifier, so 돋보기 is accurate. 플로팅 is the app's established word (`settings_show_overlay_icon` 플로팅 아이콘). A literal 렌즈 would be opaque to Korean readers.
- **빈도 표시** (frequency chips): keep. "칩" would be a Material-design calque that general readers don't know. 빈도 표시 says what the reader sees, and it sits well next to the app's 빈도 (`yomitan_category_frequency`). If the author wants the "badge" sense, 빈도 배지 is the only alternative worth considering, and it is not clearly better.
- **프리셋** (preset): keep. It is the standard Korean gaming and app loanword. The app has no Korean term of its own to match: no 프리셋 in values-ko, and the card-type picker sections are 기본값 / 카드 유형. The usual alternative, 사전 설정, would collide with 사전 (dictionary) in an app where 사전 appears everywhere.

**Heading 온라인 번역 서비스 for "Online Translation Backends".** This is correct. The settings page is 번역 서비스 (`settings_cell_translation_services`), and the app's own row for adding one of these is "온라인 번역 서비스 추가" (`tr_service_add_online`). Its footer also says "온라인 번역 서비스는 캡처된 텍스트를 받습니다" (`tr_service_order_footer`). Rendering "backends" as 백엔드 would have broken the glossary's "Translation service, one noun" rule.

**Parentheticals such as (모델은 앱에서 선택).** The attached form `[platform.openai.com](…)(모델은 앱에서 선택)` follows Korean orthography, which puts no space before an opening parenthesis. It matches the rest of the file (기능(…), 어디서나(Anki 포함), 엔드포인트(…)) and the app's own style (`service_account_required_free` "계정 필요(무료 요금제 있음)"). On GitHub the blue link text ends visibly before the parenthesis, so it does not read as part of the domain, and I left it unflagged. The wording itself, "(모델은 앱에서 선택)" for "pick a model at runtime" and "(무료 요금제 제공)" for DeepL's free tier, is natural and matches the app's 무료 요금제. The only spacing outlier is 중국어 (간체) / (번체), listed above.

**App-side note (out of scope for this README).** The README's "대화창" (dialogue box) is the word Korean gamers use. The app's region-name example `hint_region_name` is "예: 대화 상자", which in Korean UI usually means a software dialog box. The README is right not to copy it. That app string may deserve a look in the next strings review.

## Disposition (2026-10-06)

Applied the ⚠️ (ML Kit fallback wording from `tr_service_offline_footer`) and seven 💬:
다운로드해 사용할 수도, 화면의 안내, 접근성 설정에서 PlayTranslate를, the Ko-fi link form,
중국어(간체)/(번체) (also fixed in `scripts/readme_lang_tables.java` for ko), the (선택 사항) heading
suffix, and 백엔드 URL. Not applied: the video link text keeps "Persona 3 Reload" in Latin, as the
translation brief lists it with the brand names and GitHub's video player replaces the link text on
render. Install step 2 was rewritten in English after this review (Apps → Special app access path)
and re-translated separately.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] ko -> readme/README.ko.md`, no warnings; the same with `--require-header`). **No 🛑 issues.**

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Can't enable accessibility? > step 2 | 💬 | "앱 정보 페이지를 여세요: **설정 → 앱 → PlayTranslate**" | "앱 정보 페이지(**설정 → 앱 → PlayTranslate**)를 여세요." | A colon after the sentence-final 여세요 is the English punctuation the 2026-10-06 review took off the Ko-fi line (after 합니다); this is now the file's only instance. The step also has no closing period, where the English and steps 1, 3 and 4 of this list have one. 를 agrees with 페이지 across the parenthesis, as in "**게임 언어 26개**(…)를", and the parenthesis attaches without a space, as everywhere in the file. 앱 정보 페이지 stays, matching the app's addendum. The checker passes on a scratch copy with this text. |

Clean areas: Accurate against the English: four steps in order with every clause, including 한 번 탭, closing the message and the "only after step 1" condition (이 항목은 1단계를 마친 뒤에만 표시됩니다); the intro keeps Android 13 이상, the downloaded APK and the tap that shows the message; the closing sentence and 오른쪽 상단의 are gone as in English, and nothing is added. Labels byte-match AOSP android16-qpr2 ko on disk: 설정 (`settings_label`), 접근성 (`accessibility_settings`), 앱 (`apps_dashboard_title`), 앱 정보 (`application_info_label`), “제한된 설정” (`blocked_by_restricted_settings_title`) and 제한된 설정 허용 (`app_restricted_settings_lockscreen_title`); step 4's 접근성 설정 is AOSP `accessibility_settings_title` and the app's own name for that screen (`accessibility_dialog_open` 접근성 설정 열기). Nothing contradicts the app: `a11y_restricted_settings_addendum` has the same 회색으로 표시, 한 번 탭, 앱 정보 페이지 and 선택하세요, and 설정 → 접근성 starts the path in `overlay_icon_a11y_required_message` and `accessibility_dialog_message`. The bare **⋮** 메뉴 follows the English and the README's reviewed earlier wording; the addendum's 점 3개(⋮) names the same unlabeled button. Particles are all right: PlayTranslate를 (vowel-final), APK로, 메시지가 / 메시지를, 메뉴를, 허용을, 1단계를, 설정으로, 요청이. Sentence endings match the rest of the README: 합니다체 for statements (표시됩니다) and ~하세요 for every instruction, as in the install and Play Protect lists. Markdown: bold on the two paths, ⋮ and the menu item only, as in English; numbering 1 to 4. Typography: “ ” quotes, 띄어쓰기 (한 번, 마친 뒤에만, 인증한 다음), no space before a parenthesis. The intro's 회색으로 비활성화되며 (kept from the reviewed earlier wording) and step 1's 회색으로 표시된 describe the same switch and read naturally together.

Out of scope (app strings, not this README): values-ko `accessibility_dialog_message` and `overlay_icon_a11y_required_message` print 설정 → 접근성 → 다운로드된 앱, while AOSP android16-qpr2 ko `user_installed_services_category_title` is 다운로드한 앱 (values-ja's ダウンロードしたアプリ matches its AOSP label). The first app-string review proposed 다운로드된 앱 as stock Android's label; older Android releases were not checked here.

### Disposition (2026-10-08)

Applied the 1 💬 (step 2 restructured with the path in parentheses). The out-of-scope app-string note (다운로드된 앱 vs AOSP 다운로드한 앱) is reported to the developer, not changed here.
