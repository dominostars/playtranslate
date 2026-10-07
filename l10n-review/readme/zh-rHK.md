# Traditional Chinese, Hong Kong (zh-rHK) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] zh-rHK -> readme/README.zh-HK.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Features > 離線使用 | 💬 | "OCR 和詞典查詢無需連線上網，還可以另行下載離線翻譯模型" | "OCR 和詞典查詢無需網絡連線，還可以另行下載離線翻譯模型" | 連線上網 says "connect" and "go online" at once. The app's own phrase for an internet connection is 網絡連線 (`note_mlkit_no_internet` 無網絡連線, and "請檢查你的網絡連線" in five error strings), which is also the Hong Kong 網絡 rather than Taiwan's 網路. |
| Features > 雙螢幕與分割螢幕 | 💬 | "也能在 Android 分割螢幕模式下與以視窗模式執行的遊戲並排使用" | "也能在 Android 分割螢幕模式下，與在視窗中執行的遊戲並排使用" | 與以 stacks two prepositions and 模式 appears twice in one clause, so the sentence trips. "Windowed games" just means games running in a window. |
| Features > Yomitan 整合 | 💬 | "Yomitan 詞典可無縫整合，支援音高重音、詞頻標籤和漢字補充資訊" | "可無縫整合 Yomitan 詞典，支援音高重音、詞頻標籤和漢字補充資訊" | 詞典可無縫整合 leaves 整合 without an object, so it is unclear what the dictionaries are integrated into. The app's own `yomitan_page_description` has the app doing the integrating (PlayTranslate 便會將其無縫整合到應用程式的各處), and with that subject the rest of the sentence (支援…) reads naturally. |
| Supported Languages > both table headers (game table and translation table) | 💬 | "\| 語言 \| 本地名稱 \| 代碼 \|" | "\| 語言 \| 原文名稱 \| 代碼 \|" | This is the zh-CN 本地名称 with only the script converted. In Hong Kong, 本地 reads first as "local, i.e. Hong Kong" (本地新聞, 本地用戶), so 本地名稱 can look like "the Hong Kong name" over a column of Español and Deutsch. 原文名稱 says "the name in the language itself". The checker compares only rows, so editing the header cell is safe. The same fix applies at both places. |

## Clean areas (checked, no findings)

- **Accuracy and completeness.** All 13 feature bullets, the 4 install steps, the 5 Play Protect steps, the 4 restricted-settings steps and the 9 backends are present and in order, with nothing added, dropped or softened. "Off by default", "temporarily", "re-enable afterward", "(top right)" on both Play steps, the Ayn Thor example, "incl. Anki" and "pick a model at runtime" (可在應用程式內選擇模型) are all kept. 26 and 59 appear in both the intro and Supported Languages. The Anki bullet lists all six card contents (原文、譯文、詞語清單、目標詞語、朗讀音訊和截圖).
- **The ML Kit sentence.** 「線上翻譯不可用時，便以 ML Kit 作為離線後備方案」 says that ML Kit is used whenever online translation is unavailable. It follows `tr_service_offline_footer` (線上翻譯不可用時，便會使用離線翻譯作為後備方案) almost word for word, and 後備方案 is the app's term.
- **Register.** Casual 你 is used throughout (你的瀏覽器, 提示你, 你的其他應用程式, 你也可以, 顯示給你看, 你自己的後端 URL, 你的牌組), and 您 never appears. The tone is friendly and idiomatic (對話一變就自動翻譯, 記得重新開啟…, 甚至還能錄製遊戲音訊一併儲存！).
- **Terminology vs strings.xml.** These all match the app exactly:
  - 翻譯服務 = `settings_cell_translation_services`
  - 快捷鍵 = `settings_cell_hotkeys`
  - 文字轉語音 = `settings_cell_tts`
  - 自動翻譯 = `live_mode_auto_translate_label`
  - 振假名 = `header_action_furigana`, used at both occurrences, with no 假名注音 carried over from zh-CN
  - 拼音 = `hint_label_pinyin_lower`
  - 擷取區域 = `menu_capture_region`
  - 相機 = `settings_cell_camera`
  - 詞典 = `settings_cell_dictionary`
  - Anki 抽認卡 = `settings_cell_anki`, used as the H2
  - 遊戲音訊 = `audio_source_game_name`
  - 允許受限設定 and 點按右上角的三點（⋮）按鈕 = `restricted_settings_title` / `restricted_settings_message`
  - 離線翻譯模型 is consistent with `lang_section_offline_models_title` 下載離線模型
  - 文字歷史記錄 = `history_toggle_title` 保留文字歷史記錄, and 保留擷取到的句子 echoes `history_empty_off`
  - 拍攝快照 = `camera_shutter_cd`
  - 長按預覽 = `settings_overlay_mode_subtitle`
  - 詞語清單 = `anki_content_words_table`
  - 音高重音 = `yomitan_category_pitch_accent`
  - 自訂 = `llm_backend_preset_custom`
  - 後端 URL = `llm_backend_base_url_custom_hint`
  - 次序 = `tr_service_order_footer`
  - the rest also match the app: API 金鑰, 卡片類型, 牌組, 截圖, 身份驗證, 無障礙權限, 質素

  "Word" is 詞語 at all five places (詞語查詢, 任意詞語, 詞語清單, 目標詞語, 其中的詞語), and 單詞 and 單字 never appear. `nav_regions` (區域) is not used in the README, so there is nothing to compare.
- **現成設定 for presets.** This is the right call. 預設 means "default" in this app: it appears in eight strings, including `anki_card_type_section_default` and `tts_voice_default`, and the README itself uses 預設 for "default" four times (預設語音, 預設關閉, 預設不允許, 預設使用 Lingva). 範本 is also taken, by Anki templates (範本欄位). 內置 is the Hong Kong form, not Taiwan's 內建.
- **Links.** The checker confirms every URL is absolute and byte-identical to English. The link text reads naturally: 按此下載最新版本 (a Hong Kong idiom), Discord 伺服器, 鳴謝部分, LICENSE. The bare Ko-fi URL has a space on both sides, so GitHub autolinks it.
- **Tables.** I regenerated both tables with `scripts/readme_lang_tables.java zh-HK` (read-only) and diffed them: all 85 data rows are byte-identical, and only the header cells are translated. The Language column is JDK CLDR zh-HK, so it uses Hong Kong names such as 意大利文, 克羅地亞文, 格魯吉亞文 and 中文（簡體字）, not Taiwan's 義大利文, 克羅埃西亞文 and 喬治亞文. 代碼 is fine. The one header finding is above.
- **Pointer section.** 鳴謝與許可證 is accurate and natural. 鳴謝 is the usual Hong Kong word. 許可證 matches the app (`hymt_legal_message`: 該許可證…本許可證), and GNU 通用公共許可證 is the Hong Kong name for the GPL. The body points to the English Credits anchor for libraries, models and language data, and states GPL 3.0 with the LICENSE link.
- **Typography.**
  - Corner quotes 「」 are used for every quotation: 「安裝」, 「未安裝應用程式」, 「有害的應用程式」, 「受限設定」. There are no “ ” and no ASCII quotes.
  - A script confirmed Pangu spacing across all prose: no Han character touches a Latin letter or digit without a space, there is no space next to full-width punctuation, and there is no half-width punctuation next to Han.
  - Punctuation is full-width throughout: ：，。；（）？！. The full-width ／ in 振假名／拼音模式 is correct. The → separators keep the app's spaced style. There are no em dashes: the English dashes became ，.
  - Bullet endings follow the post-review zh-CN pattern: one-sentence items are bare, and items with an internal 。 end with 。. Numbered steps are bare, as in English.
  - A script checked every `**…**` span against the CommonMark flanking rules, including 三點（**⋮**）按鈕 and **設定圖示**（齒輪）, and none will render as literal asterisks.
- **Glyph forms.** The translation uses the Android zh-HK forms 啟, 說 and 閱 (開啟, 啟動, 視覺小說, 閱讀), not the 啓, 説 and 閲 that a mechanical s2hk conversion emits (that conversion produced 開啓, 小説 and 閲讀 here). There are no 裏 or 着.
- **支持 vs 支援.** 支援 is used for compatibility and support (支援 26 種遊戲語言, ## 支援, ## 支援的語言, 支援選擇卡片類型), and 支持 is kept for backing on Ko-fi (在 Ko-fi 上支持 PlayTranslate). This is exactly the parameters doc's sense rule.

## Hong Kong vs Mainland term audit

**Method.** I converted `readme/README.zh-CN.md` mechanically with the OpenCC dictionaries bundled in the app's own OpenCC4j 1.13.1 jar from the Gradle cache: STPhrases and STCharacters with longest match, then HKVariants, which approximates `s2hk`. I diffed the result line by line against the zh-HK file, then character by character to list the Han runs that survived unchanged.

**Result.** Every prose line was touched by the term pass, so no paragraph is a bare script conversion. These swaps were applied, all correctly:

| Mainland (zh-CN) | Hong Kong (zh-HK) |
|---|---|
| 实时 | 即時 |
| 应用 | 應用程式 (never bare 應用) |
| 屏幕 | 螢幕 |
| 点击此处 | 按此 |
| 反馈 / 获取帮助 | 回報 / 取得協助 |
| 服务器 | 伺服器 |
| 截取 | 擷取 |
| 单词 | 詞語 |
| 联网 | 連線上網 (but see the finding: the app says 網絡連線) |
| 假名注音 | 振假名 |
| 带专用按键 | 設有專用按鍵 |
| 双屏 / 分屏 / 两块屏幕 / 窗口化运行 | 雙螢幕 / 分割螢幕 / 兩個螢幕 / 以視窗模式執行 |
| 设备 | 裝置 |
| 自定义 | 自訂 |
| 设置 | 設定 |
| 默认 | 預設 |
| 导出 | 匯出 |
| 保存 | 儲存 |
| 列表 | 清單 |
| 音频 | 音訊 |
| 一起 | 一併 |
| 预设 | 現成設定 |
| 集成 / 接入 | 整合 |
| 信息 | 資訊 |
| 今后 | 日後 |
| 文本 | 文字 |
| 文件 (file) / 文件管理器 | 檔案 / 檔案管理器 |
| 安装未知应用 | 安裝不明應用程式 |
| 特殊应用权限 | 特別應用程式存取權 |
| 允许来自此来源的应用 | 允許此來源的應用程式 |
| 打开 / 开启 | 開啟 |
| Play 保护机制 | Play 安全防護 |
| 图标 | 圖示 |
| 高级 | 進階 |
| 厂商 | 製造商 |
| 通过 | 透過 |
| 两张表都 | 兩個表格均 |
| 在线 | 線上 (the app's own choice, 7 strings, no 網上) |
| 质量 | 質素 |
| 密钥 | 金鑰 |
| 配置 | 設定 |
| 顺序 | 次序 |
| 免费套餐 | 免費方案 |
| 访问权限 | 存取權限 |
| 致谢 | 鳴謝 |
| 库 | 程式庫 |
| 数据 | 資料 |
| 本项目 | PlayTranslate |

**Surviving runs, read and passed as natural Hong Kong usage:**

- 一款…應用程式, 視覺小說, 漫畫, 提出需求
- 對話一變就自動翻譯, 無需點按, 懸浮放大鏡, 詞典釋義, 讀音提示, 實體按鍵, 長按預覽, 掌機, 並排使用, 對話框、字幕
- 朗讀, 原文、譯文, 截圖, 卡片類型, 熱門牌組, 音高重音, 詞頻標籤, 漢字補充, 詞條釋義, 拍攝快照
- 側載, 含糊, 攔截, 個人資料, 齒輪, 顯示為灰色, 身份驗證, 三點（⋮）按鈕
- 後備方案, 兼容 (Hong Kong; Taiwan says 相容), 端點, 後端 URL, 抽認卡, 牌組, 許可證, 可選 (the app uses it too, in `crash_email_body`), 顯示給你看的語言
- The only survivor that should differ is 本地名稱 in the table headers, which is in the findings.

**Android labels:**

- **設定 → 應用程式 → 特別應用程式存取權 → 安裝不明應用程式, switch 允許此來源的應用程式** (from memory; the Settings app's strings are not on disk). These are AOSP Settings zh-rHK. 特別 is right for Hong Kong and the translator's choice should stay: 特殊應用程式存取權 is the zh-rTW label and 特殊应用权限 the zh-rCN one. 允許此來源的應用程式 is the zh-rHK switch, while zh-rTW says 允許來自這個來源的應用程式. OEM skins such as One UI word these screens differently, but the README follows the English AOSP path, as it should.
- **「有害的應用程式」** (on disk). The framework's `harmful_app_warning_title` in android-36 `values-zh-rHK` is 偵測到有害的應用程式, while zh-rTW is 偵測到有害應用程式, without 的. The README's fragment is an exact substring of the Hong Kong title, including the Hong Kong-only 的. It quotes part of the title just as the English "harmful app" quotes part of "Harmful app detected", so the fragment is faithful and is not a finding. Quoting the full 「偵測到有害的應用程式」 would be an optional improvement, but it would go beyond the source.
- **「未安裝應用程式」** (from memory) is the PackageInstaller failure text. **受限設定 / 允許受限設定** match the app (`restricted_settings_title`) and the AOSP zh-rHK list in the parameters doc.
- **分割螢幕** (from memory; the framework has no split-screen string) is the AOSP zh-rHK launcher/SystemUI wording. zh-rTW says 分割畫面, so the README did not inherit the Taiwan term.

**Play Store labels:** Play 商店, Google Play 安全防護, Play 安全防護, 個人資料圖示, 設定圖示 and 使用 Play 安全防護掃描應用程式 (from memory, no web access in this run). These are Google's Traditional Chinese Play Store wording, and they are internally consistent across the warning, the 5 steps and the closing line. I cannot confirm from here whether Google's Hong Kong build of the Play Store renders "Play Protect" differently from the Taiwan build. I think it is unlikely, but one look at the Play Protect page on an HK-locale device would settle it.

## Disposition (2026-10-06)

Applied all 4 💬 (網絡連線, 與在視窗中執行, 可無縫整合 Yomitan 詞典, 原文名稱 ×2).
