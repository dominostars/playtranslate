# Traditional Chinese, Hong Kong (values-zh-rHK) localization review

Full review, 2026-10-05. The locale arrived complete in PR #37 from an outside contributor
(merged as 7082ab9d): all 1,009 translatable keys, in the same order as `values-zh-rCN`, built
from that reviewed file plus a hand-applied Hong Kong term pass.

Method. Every string was read. To separate the contributor's wording from script conversion,
each value was diffed against an OpenCC `s2hk` conversion of the `zh-rCN` value for the same
key: at merge, 474 keys differed (hand-edited) and 535 were identical (never touched). The
hand-edited half was read against English; the untouched half was scanned for Mainland
vocabulary, which is where almost every finding below came from. Android wording was checked
against AOSP's own `values-zh-rHK` (Settings, SystemUI, framework and SettingsLib on `main`,
plus Settings and SystemUI on the android14, android15 and android16 release branches).

Mechanical layer verified programmatically, before and after the fixes: all string and plurals
names present, no extras (`l10n_diff.py` missing=0 orphan=0 modified=0 against the `l10n-sync`
baseline); every `%n$s`/`%d` placeholder present; `<xliff:g>` spans, `<b>`, `\n`, `\{ \}` and
`&lt;`/`&gt;` counts match English wherever `zh-rCN` matches it (the only differences are the
ones `zh-rCN` already carries: `&amp;` rendered as 及 / 並 / 與 in three keys, the Anki brand
span dropped in `history_action_anki`, an `example` attribute on `dictionary_entries_count`); no
raw `'` or `"`; all 11 `<plurals>` are `other` only; brand names untouched; no Simplified
characters; Han/Latin spacing identical to `zh-rCN` in every key. `aapt2 compile` and
`:app:processDebugResources` pass. **No 🛑 build-breaking issues.**

## Findings

"Current" is the text as merged in 7082ab9d. Everything marked applied is now in the file
(47 keys changed, not yet committed).

| name | severity | current | suggested | note |
|---|---|---|---|---|
| onboarding_a11y_title | ❌ | 顯示在其他應用程式上層 | 在其他應用程式上面顯示 | The English comment says this is the Android system-settings name of the overlay permission and must match the OS translation; it is embedded in `onboarding_a11y_enable_title` as 啟用「%1$s」. Android zh-HK names the setting 在其他應用程式上面顯示 (`system_alert_window_settings`, `draw_overlay`; identical on android14, 15, 16 and main). The merged text was a conversion of the zh-CN name with 應用程式 swapped in. Applied. |
| onboarding_a11y_intro, mp_overlay_permission_title, mp_overlay_permission_message | ⚠️ | …顯示在其他應用程式上層 | …在其他應用程式上面顯示 | Same term in the sibling strings; the message quotes it in 「」 as the permission's name. The closing clause of the message (顯示在遊戲上層) is ordinary prose and was left. Applied. |
| qwen_mnn_, qwen35_2b_mnn_, gemma_e2b_mnn_, hymt2_, hymt_ `metered_warning_title` / `_message`, update_dialog_metered_note | ⚠️ | 按流量計費 | 按用量收費 | 11 keys, one fix. All were untouched conversions of the zh-CN Android term. Android zh-HK labels a metered network 按用量收費 (`data_usage_metered_yes`, `wifi_metered_label`); 流量 in this sense and 計費 are Mainland usage. Applied. |
| stream_kind_share_one_app, stream_kind_share_entire_screen, stream_kind_prompt_message | ⚠️ | 分享單一應用程式 / 分享整個螢幕 | 分享一個應用程式 / 分享整個螢幕畫面 | The parameters doc makes this a hard constraint: the buttons name the option the user just tapped in the system consent dialog. Android 16 zh-HK shows 分享一個應用程式 and 分享整個螢幕畫面; Android 14 and 15 show 單一應用程式 and 整個螢幕畫面. The merged text matched neither. English tracks the Android 16 wording, so the fix does too, in the buttons and in the sentence of the prompt that repeats them. Applied. |
| settings_support_donate_title | ⚠️ | 支援 PlayTranslate | 支持 PlayTranslate | Casualty of the file-wide 支持→支援 swap, which is right in the other twelve places it landed (不支援, 不再受支援, the 支援 group header). 支援 is technical support or compatibility; the donation row means backing the project, which is 支持. Applied. |
| settings_ocr_delete_camera_note, settings_ocr_delete_import_note, settings_ocr_delete_camera_import_note | ⚠️ | 切換回你的預設選擇 | 切換回你的標準選擇 | English says "your standard selection": the engine the user picked on the main OCR page, which the camera or import tool falls back to. `zh-rCN` keeps 标准选择 apart from the 默认 badge (`settings_ocr_default_badge`) that marks the recommended engine on the same screen, and `ja` does the same (標準の選択 / デフォルト). The term pass rewrote 標準 as 預設, the badge's own word, so the note read as "will switch to the 預設 engine", which is false whenever the user's standard pick is a different engine. Found on the post-merge pass. Applied. |
| translation_error_bad_response, translation_error_timeout | ⚠️ | 響應異常 / 無響應 | 回應異常 / 無回應 | Mainland 響應 in two untouched strings; Android zh-HK says 回應 (應用程式無回應). 無回應 keeps the timeout pill at three characters. Applied. |
| llm_prompt_reset, tr_service_status_quota_with_reset_fmt | ⚠️ | 重置 | 重設 | Android zh-HK renders Reset as 重設 throughout and never as 重置. Applied. |
| yomitan_update_skipped_title, overlay_turn_off_message | ⚠️ | 未應用更新 / 在 %1$s 應用中重新開啟 | 未套用更新 / 在 %1$s 應用程式中重新開啟 | The file's only two bare 應用. The first is the verb "apply", which is 套用 in Hong Kong usage; the second is the noun, 應用程式 in the other 25 places. Applied. |
| accessibility_dialog_message, overlay_icon_a11y_required_message | 💬 | 設定 → 無障礙 → 已下載的應用程式 | 設定 → 無障礙設定 → 已下載的應用程式 | The contributor already matched 已下載的應用程式. The step before it is titled 無障礙設定 on the Settings home page in Android zh-HK (`accessibility_settings`, same on 14, 15, 16), and the file's own buttons already say 開啟無障礙設定. Applied. |
| settings_ocr_note_builtin, yomitan_single_dict_subtitle | 💬 | 內建 | 內置 | The term pass changed the converted 內置 to 內建, which is the Taiwan form; Android zh-HK uses 內置 (內置喇叭, 內置顯示屏). Applied. |
| yomitan_update_skipped_message, translation_error_discard_card_message, history_live_session_title, yomitan_update_busy_message, settings_overlay_min_text_example, llm_prompt_row_translation_subtitle, llm_prompt_row_batch_title, update_check_failed_message | 💬 | 匹配, 丟失, 會話, 示例文字, 構建, 批量, 查找 | 相符, 遺失, 工作階段, 範例文字, 建立, 批次, 尋找 | Further Mainland carry-overs in untouched strings. 會話 reads as "conversation" in Hong Kong, which matters on a History card that groups game dialogue; Android zh-HK says 工作階段. The file already uses 建立 and 批次 elsewhere. Applied. |
| anki_models_unavailable, update_check_failed_message | 💬 | 無法連線 AnkiDroid / 無法連線 GitHub | 無法連線至 AnkiDroid / 無法連線至 GitHub | 連接→連線 left two transitive uses; the contributor's own `yomitan_update_check_failed_message` writes 無法連線至…. Applied. |
| yomitan_collection_imported_title | 💬 | 已匯入合集 | 已匯入詞典集合 | "Collection" was 集合 in `yomitan_page_description` (the contributor's edit) and 合集 here (untouched). Unified on the hand-chosen word, with 詞典 so the title stands alone. Applied. |
| update_dialog_view_release, settings_vertical_grow_subtitle, settings_ocr_use_manga_subtitle, history_empty_none, camera_no_text_hint | 💬 | 説, 閲, 裏, 着 | 說, 閱, 裡, 著 | Five converter glyph forms left beside the 啟 the contributor normalised by hand in 34 places. Android zh-HK uses 說, 閱, 裡 and 著 exclusively. Both sets are valid in Hong Kong; this is consistency with the file's own choice and with the system text around it. Applied. |
| icon_gesture_tap and 17 other keys | 💬 | 點按 | 輕按 | Android zh-HK says 輕按 (167 uses to 1). 點按 is nonetheless natural in Hong Kong (it is the iOS term there) and the file uses it uniformly, so it stays. **Left as a decision.** |
| nine `anki_content_*_desc` keys, anki_content_flag_targeted_sentence_desc | 💬 | 醒目標示詞語的… / 含醒目標示目標詞語的句子 | 醒目標示的詞語的… | Without 的 the compound can parse as an instruction ("highlight the word's…"), and the flag string stutters on 目標…目標. The structure mirrors `zh-rCN`'s 高亮单词的…, the contributor is a native reader, and adding 的 produces a double-的 chain. **Left as a decision.** |
| settings_header_configure | 💬 | 設定 | 各項設定 | 配置→設定 makes the Configure group header the same word as the screen it sits on (`settings_title`). `zh-rCN` has 配置 / 设置 and `ja` 各種設定 / 設定. Redundant rather than wrong. **Left as a decision.** |
| a11y_stuck_message_xiaomi | 💬 | 「自啟動」, 省電策略, 「無限制」 | (unverified) | These are a conversion of Xiaomi's zh-CN labels. Xiaomi's own Traditional Chinese labels may differ (Autostart is possibly 自動啟動). No source was available to check, and a web search turned up nothing usable. **Left; needs a Xiaomi device set to zh-HK.** |

## Verdicts

- **Register consistency**: clean. Casual 你 throughout (56 uses), zero 您, the same concise
  tone as `zh-rCN`.
- **Terminology consistency**: strong after the fixes. 設定 / 螢幕 / 擷取 / 儲存 / 開啟 /
  裝置 / 應用程式 / 檔案 / 音訊 / 記憶體 / 質素 / 網絡 / 帳戶 / 金鑰 / 欄位 / 詞語 / 詞典 /
  牌組 / 卡片 / 語言包 / 快捷鍵 / 疊加層 / 即時模式 / 振假名 are uniform, with no Mainland
  form of any of them left.
- **Android-settings wording**: the contributor matched 已下載的應用程式, 允許受限設定,
  快速設定 / 圖塊, 狀態列 and 文字轉語音. The misses were the overlay permission, the
  metered label, the share-scope buttons and the Accessibility entry, all now aligned to
  AOSP zh-HK.
- **Han/Latin spacing**: identical to `zh-rCN` in every key, including no space around
  placeholders that fill with a Chinese language name (偵測到%1$s文字, 不支援%2$s).
- **Grammar around placeholders**: good. Measure words are right (台顯示器, 部詞典, 個牌組,
  個釋義), and the reordered `%2$d 部詞典中的 %1$d 部` survives conversion.
- **Truncation risk**: none. The bottom bar is two characters per item (自動 / 暫停 / 設定 /
  區域), the two-line buttons are 擷取\n區域 and 擷取\n螢幕, and no hand edit lengthened a
  width-constrained label. The longest change, 分享整個螢幕畫面, is an alert button.
- **Legal text** (`hymt_legal_*`): a faithful conversion of the reviewed `zh-rCN` text. §5(b),
  the 歐盟 / 英國 / 韓國 list and 聲明並保證 are intact; the only edits are the quote style
  around 同意 and 啓→啟 on the button.
- **Overall**: ship. One ❌, eight ⚠️ and ten 💬; the ❌, all eight ⚠️ and six 💬 are applied,
  four 💬 are recorded decisions.

## Clean areas (checked, no findings)

- **The hand-edited half.** Apart from the findings above, the 474 hand edits are accurate
  and natural: 64 位元裝置, 全螢幕, 狀態列通知, 堆疊追蹤, JSON 陣列, 上下文視窗,
  漫畫壓縮檔, 瀏覽器擴充功能, 中繼資料, 私隱政策.
- **File versus document.** 文件→檔案 was applied to files (21 uses) while "document" in
  `settings_cell_image_import_summary` and `image_import_landing_title` was deliberately
  turned from 文檔 into 文件, the Hong Kong word for a document. The two remaining 文件 are
  those, and they are correct.
- **Rewritten sentences.** Where the contributor restructured rather than swapped
  (`tr_service_order_footer`, `tr_service_offline_footer`, `yomitan_page_description`,
  `llm_backend_base_url_invalid`, `stream_kind_prompt_message`, `error_single_app_not_fullscreen`)
  the meaning tracks English, including the fallback order and the "be wary for auto
  translation" warning.
- **Quotes.** All 36 quote pairs are 「」; no curly or straight quotes remain. Quoted button
  and field names still match their targets (「翻譯」, 「繼續」, 「暫時隱藏」 / 「關閉」,
  「正面」 / 「背面」, 「自訂…」).
- **Furigana.** 振假名 in all 12 places (`zh-rCN` says 假名注音); bare 假名 is kept for kana
  in `anki_content_reading`, and 讀音 for "reading". The composed hint strings
  (自動%1$s, 長按以顯示%1$s) read correctly with 振假名 and with 拼音.
- **Keyword tokens.** `{text}`, `{strings}`, `{source}`, `{source_code}`, `{target}`,
  `{target_code}` and `{N}` are byte-identical in the bare-prose strings.
- **`Example:` samples.** 聞く, きく, 友達に<b>聞いた</b>, ★★★, noun and 0,2 are left
  unlocalized; the label is 例子：.
- **The `misc_*` and `pos_*` chips.** Pure conversions of the audited `zh-rCN` labels. The
  four clusters stay distinct (貶義 / 冒犯語 / 粗俗 / 蔑稱; 古語 / 廢語 / 過時 / 歷史詞;
  口語 / 非正式 / 親暱 / 俚語; 敬辭 / 謙辭 / 禮貌語), no label contains a separator
  character, and 網絡用語 is the Hong Kong form.
- **Adjacent rows.** 任何語言 (`lang_pick_any`) stays distinct from 全部
  (`lang_section_all`); the update alerts keep 已更新到最新版本 apart from 已是最新版本.
- **Em dashes.** The file keeps `zh-rCN`'s "——", which is native punctuation and exempt from
  the English-only em-dash rule.

## How the file differs from a script conversion of zh-rCN

After the fixes, 494 of 1,009 values differ from OpenCC `s2hk`(`zh-rCN`). The swaps, with the
number of occurrences converted (none of the left-hand forms remain):

| Mainland (converted) | Hong Kong | n | | Mainland (converted) | Hong Kong | n |
|---|---|---|---|---|---|---|
| 單詞 | 詞語 | 46 | | 密鑰 | 金鑰 | 15 |
| 設置 | 設定 | 41 | | 識別 | 辨識 | 12 |
| 截取 | 擷取 | 38 | | 假名注音 | 振假名 | 12 |
| “ ” | 「」 | 36 | | 高亮 | 醒目標示 | 11 |
| 屏幕 | 螢幕 | 36 | | 文本 | 文字 | 11 |
| 啓 | 啟 | 34 | | 按流量計費 | 按用量收費 | 11 |
| 導入 | 匯入 | 30 | | 當前 | 目前 | 10 |
| 打開 | 開啟 | 24 | | 獲取 | 取得 | 10 |
| 設備 | 裝置 | 24 | | 加載 | 載入 | 9 |
| 質量 | 質素 | 24 | | 列表 | 清單 | 9 |
| 保存 | 儲存 | 22 | | 檢測 | 偵測 | 8 |
| 音頻 | 音訊 | 22 | | 默認 | 預設 | 8 |
| 內存 | 記憶體 | 21 | | 拖動 | 拖曳 | 7 |
| 文件 (file) | 檔案 | 21 | | 實時 | 即時 | 7 |
| 添加 | 新增 | 20 | | 運行 | 執行 | 7 |
| 字段 | 欄位 | 17 | | 賬 (賬號, 賬户, 賬單) | 帳 | 7 |
| 禁用 | 停用 | 16 | | 菜單 / 自定義 / 創建 | 選單 / 自訂 / 建立 | 6 each |
| 支持 | 支援 | 12 | | | | |

Smaller ones: 導出→匯出, 字符→字元, 隱私→私隱, 界面→介面, 圖標→圖示, 粘貼→貼上,
搜索→搜尋, 鏈接→連結, 後台→背景, 元數據→中繼資料, 模板→範本, 數組→陣列, 窗口→視窗,
狀態欄→狀態列, 顯示屏→顯示器, 共享→分享, 連接→連線, 發送→傳送. 支持 stays 支持 only on
the donation row.

## Not verified here

- **On-device rendering.** The contributor reports a pass on an Android 16 phone; nothing
  was installed or run for this review, and the 47 post-merge edits have not been seen on a
  device.
- **Taiwan and Macau users.** By Android's script-based matching this file is also what
  zh-TW and zh-MO users receive, since it is the app's only Traditional-script locale. That
  follows from the platform's resolution rules and was not confirmed on a zh-TW device. The
  wording is Hong Kong's (質素, 私隱, 網絡, 圖塊), readable in Taiwan but not Taiwan usage.
- **Xiaomi's labels** in `a11y_stuck_message_xiaomi` (see the last finding).
