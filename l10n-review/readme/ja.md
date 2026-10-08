# Japanese (ja) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] ja -> readme/README.ja.md`, no warnings). **No 🛑 issues.**

Reviewed against `readme/README.ja.md` as of 2026-10-06 16:10. The file changed on disk partway through the review: the two Chinese rows of the game-language table went from half-width `中国語 (簡体字)` / `中国語 (繁体字)` to full-width `中国語（簡体字）` / `中国語（繁体字）`. The new form is correct, and the checker passed again afterwards.

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Optional: Online Translation Backends | ⚠️ | "オフライン時の代替としてML Kitを使います。" | "オンライン翻訳を利用できないときは、オフラインで動作するML Kitで代替します。" | "オフライン時" says ML Kit only takes over when the device has no connection. The app uses it whenever online translation is unavailable: `tr_service_offline_footer` is 「オフライン翻訳は、オンライン翻訳が利用できないときの代替として使用されます」. The suggestion follows that wording and keeps the preceding 「…を使い、」. |
| Intro; Supported Languages | 💬 | "ゲームの言語は 26 種類、翻訳先の言語は 59 種類に対応しています！" and "**26 種類のゲームの言語**" and "**59 種類の翻訳先の言語**" | "ゲームの言語は26種類、翻訳先の言語は59種類に対応しています！" and "**26種類のゲームの言語**" and "**59種類の翻訳先の言語**" | Only these numerals get half-width spaces. Every other half-width run in the file sits flush against the Japanese (Androidアプリ, 2画面, 3点メニュー, GPL 3.0ライセンス), and the app writes counts flush too (`%d件の辞書`). The checker accepts the flush form; its own comment cites `26種類`. Keep the spaces in Google Play プロテクト and Play ストア, which are Google's official names. |
| Features > Offline | 💬 | "必要に応じて、オフライン翻訳モデルも追加できます。" | "必要に応じて、オフライン翻訳モデルをダウンロードして使うこともできます。" | The app's section is 「オフラインモデルをダウンロード」 (`lang_section_offline_models_title`). "追加" is vaguer and does not tell the reader that the models are a download. |
| Video link | 💬 | "[Persona 3 ReloadでPlayTranslateを使っている様子]" | "[『ペルソナ３ リロード』でPlayTranslateを使っている様子]" | Japanese players know the game by its Japanese release title (Atlus writes it with a full-width ３), and 『』 marks a work title. The line stays a single link, so the checker's video rule still holds. |
| Optional: headings | 💬 | "## オプション：オンライン翻訳サービス" and "## オプション：Ankiフラッシュカード" | "## オンライン翻訳サービス（任意）" and "## Ankiフラッシュカード（任意）" | The "オプション：" prefix copies the English "Optional:". Japanese docs usually mark an optional section with a （任意） suffix, and the app itself writes 「（任意：…）」 in `crash_email_body`. Heading levels are unchanged, so the checker still passes. |

## Clean areas (checked, no findings)

**Register.** Polite です/ます throughout, with no plain-form sentences. あなた never appears. "The language shown to you" becomes 「翻訳結果を表示する言語」, and "translated for you" becomes 「翻訳して表示する言語」. The headings are noun phrases: 「インストールできない場合」, 「ユーザー補助をオンにできない場合」, 「クレジットとライセンス」. Honorifics stay light (ご要望, ご参加ください).

**Terminology vs strings.xml.** All of these match the app's words:
- 翻訳サービス (`settings_cell_translation_services`, used in 「設定 → 翻訳サービス」)
- ホットキー
- テキスト読み上げ
- テキスト履歴 (the app's toggle is 「テキスト履歴を保存」, and its screen is 履歴)
- 自動翻訳モード (from `live_mode_auto_translate_label` 自動翻訳)
- ふりがな and ピンイン
- キャプチャ範囲 (`menu_capture_region`)
- セリフ枠 and カスタム範囲 (the app's own `hint_region_name` and `label_add_custom_region`)
- Ankiフラッシュカード
- ゲーム音声
- カードタイプ, 単語リスト and 対象の単語 (the Anki content strings)
- 静止画を撮影 (`camera_shutter_cd`) and 「カメラを向ける」 (`settings_cell_camera_summary`)
- 単語検索 and 辞書検索
- 高低アクセント and 「シームレスに組み込み」 (`yomitan_page_description`)
- ゲームの言語 (`lang_translate_from`)
- 「カスタムURL」 (`llm_backend_base_url_label`) and カスタム (`llm_backend_preset_custom`)

The README's 翻訳先の言語 is not the picker's label (使用する言語), but neither is the English README's "translation languages", so the README is not naming that setting. Not a finding.

**Android and Play Store steps.** Step 2 of the install has 不明なアプリのインストール and 「この提供元のアプリを許可」. The restricted-settings steps match `restricted_settings_title` and `restricted_settings_message`: 「制限付き設定」, 制限付き設定を許可, and 右上の3点メニュー（⋮）. The installer's 「インストール」 and the failure text 「アプリはインストールされていません」 are Android's own wording. Grayed-out toggles are 「グレー表示」. "Sideloaded" becomes 「ストア外から入手した」, which reads naturally.

**Accuracy.** Every step count is intact: 4 install steps, 5 Play Protect steps, 4 restricted-settings steps. Nothing is dropped. Two additions are accurate and match the app: the per-app toggle named in install step 2, and 「カスタムURL」 on the Custom backend line.

**Links and tables.** Every link is absolute, and the checker confirms they match the English. The bare Ko-fi URL sits at the end of its line. That is the safe form: GFM autolinking would swallow a following full-width 「）」 or Japanese text into the link.

Both tables have translated header cells (言語 / 現地語表記 / コード) and untouched native-name and code columns. The language names follow the standard Japanese names (CLDR): カタロニア語, ジョージア語, ペルシア語, ギリシャ語, ハイチ・クレオール語, 中国語（簡体字）/（繁体字）.

The pointer section names libraries, models, language data and GPL 3.0, and links #credits and LICENSE.

**Typography.** Every list item uses a full-width ：, and all the punctuation is full-width (、。！（）「」). UI labels are in 「」 or bold, following the English bolding (no bold-count warning). There are no Western quotes, no em dashes, and no trailing spaces. The only half-width parentheses are markdown link syntax.

## Japanese-specific appendix

- **Play Store and Play Protect labels:**
  - Google Play プロテクト, Play ストア and Play プロテクト all match Google's Japanese forms, including the space after "Play".
  - The toggle 「Play プロテクトでアプリをスキャン」 matches my knowledge of Google's Japanese help and Play Store UI. I could not check it against a device or the live help page (no web tools).
  - 「プロフィールアイコン」 and 「歯車アイコン」 describe icons that have no visible label, so the lack of a space (Google's help writes "プロフィール アイコン") does not matter.
- **"Install unknown apps" path:**
  - 不明なアプリのインストール is AOSP's Japanese label, and 「この提供元のアプリを許可」 is the per-app switch.
  - The switch wording matches the app's own `update_unknown_sources_message` (「…の「この提供元のアプリを許可」をオンにしてください」) character for character. A reader who has followed the README will recognize the screen the in-app updater sends them to.
  - A source-side note, not a translation finding: the path 設定 → セキュリティ → … copies the English README. On Pixel and stock Android 8+, the screen is under 設定 → アプリ → 特別なアプリアクセス → 不明なアプリのインストール. If the English path is ever made more precise, carry the change over.
- **Spacing around numerals and Latin:** one inconsistency, the 26 / 59 row above. Brand names and Latin terms are consistently flush (Androidアプリ, Discordサーバー, Ayn Thorなど, ML Kitを, Ko-fiで), apart from Google's two spaced official names.
- **「フローティングのルーペ」 (floating lens): keep it.**
  - ルーペ is the right noun for a draggable magnifier.
  - 拡大鏡 would sound like the OS's own magnification feature, and 虫眼鏡 names an icon more than a tool.
  - The app never names the lens in its UI. The README's verb かざす matches the app's own gesture text, 「単語にかざして定義を表示」 (`icon_action_lookup_words`).
  - The 「フローティングの〜」 pattern also appears in `mp_overlay_permission_message`. 「フローティングルーペ」 would be equally fine, but not better.
- **「頻度ラベル」 (frequency chips): acceptable, no change needed.**
  - The app has no Japanese term for the chips. Its Yomitan category is just 頻度, and the Anki fields use 頻度リスト.
  - 頻度タグ would be an equivalent alternative, close to how the app names the 品詞タグ.
  - チップ, a literal rendering of "chips", would read as UI jargon, so ラベル is the better choice.
- **「オンライン翻訳サービス」 for "Online Translation Backends": correct.**
  - The body links 「設定 → 翻訳サービス」, which matches `settings_cell_translation_services` exactly.
  - The heading phrase is the app's own: the services page's add button is 「オンライン翻訳サービスを追加」 (`tr_service_add_online`), and its footer says 「オンライン翻訳サービスにはキャプチャしたテキストが送信されます」 (`tr_service_order_footer`).
  - "Backends" correctly becomes サービス, the noun the parameters glossary fixes for this page.

## Disposition (2026-10-06)

Applied the ⚠️ (ML Kit fallback wording from `tr_service_offline_footer`) and three 💬: flush
numerals (26種類 / 59種類), ダウンロードして使う for the offline models, and the （任意） heading
suffix on both optional sections. Not applied: the video link text keeps "Persona 3 Reload"
in Latin, as the translation brief lists it with the brand names and GitHub's video player
replaces the link text on render. The table-parentheses change the reviewer saw land mid-review
came from the zh-CN review and is now in `scripts/readme_lang_tables.java` for every ja/zh locale.
The source-side finding (the "Settings → Security" path for Install unknown apps is the legacy
location; stock Android has it under Apps → Special app access) is reported to the maintainer,
not changed here.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] ja -> readme/README.ja.md`, no warnings; the same with `--require-header`). **No 🛑 issues.**

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Can't enable accessibility? > step 2 | 💬 | "アプリ情報のページを開きます：**設定 → アプリ → PlayTranslate**" | "アプリ情報の画面（**設定 → アプリ → PlayTranslate**）を開きます。" | The only numbered step in the README with no closing 。, where the English ends it with a period. After the colon the path trails a finished sentence, English-style; in parentheses it sits next to the screen it names, and the step ends on its verb like steps 1, 3 and 4. 画面 rather than ページ: step 4 of this list says 「ユーザー補助の画面」 and install step 2 「この設定画面」, values-ja uses ページ only for pages of an imported file (「前のページ」), and the addendum's 「アプリ情報」を開き names no noun, so nothing contradicts. アプリ情報 itself stays (AOSP `application_info_label`). The bold is valid inside （） for the same reason as 3点メニュー（**⋮**）, and the checker passes on a scratch copy with this text. |

Clean areas: Accurate against the English: four steps in order with every clause, including 「一度タップ」, closing the message, and the "only after step 1" condition (「この項目は手順1の後にだけ表示されます」); the intro keeps Android 13以降, the downloaded APK and the tap that shows the message; the closing sentence and 「右上の」 are gone as in English, and nothing is added. Labels byte-match AOSP android16-qpr2 ja on disk: 設定 (`settings_label`), ユーザー補助 (`accessibility_settings`), アプリ (`apps_dashboard_title`), アプリ情報 (`application_info_label`), 「制限付き設定」 (`blocked_by_restricted_settings_title`) and 制限付き設定を許可 (`app_restricted_settings_lockscreen_title`). Nothing contradicts the app: `a11y_restricted_settings_addendum` has the same グレー表示, 一度タップ, 3点メニュー（⋮） and 選択, and 設定 → ユーザー補助 and オンにする are the start and end of the path in `overlay_icon_a11y_required_message` and `accessibility_dialog_message`. Register: ます-form steps like the install and Play Protect lists, 手順 as in the section's own intro, no あなた. Markdown: bold on the two paths, ⋮ and the menu item only, as in English; numbering 1 to 4. Typography: all punctuation full-width (、。「」（）：); the only half-width runs are Android 13, digits flush against the Japanese (手順1, 3点), the spaced arrows as elsewhere in the file, and ⋮ (U+22EE, as in English). 「APKファイル」, where the rest of the file says APK, reads naturally and needs no change.

### Disposition (2026-10-08)

Applied the 1 💬 (step 2 restructured with the path in parentheses).
