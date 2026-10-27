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

## Delta review 2026-10-07 (41 keys + 2 orphans: bug-report email, Support rows, kill notice, Fix disappearing icon page)

Mechanical layer verified: `python3 PKT/../tools/mech_check.py PKT/../keys.txt zh-rHK` reports
`checked 1 locales x 41 keys; problems: 0` (all 41 present; `<xliff:g>` spans and `%1$s`
byte-identical to EN; `\n` counts match in the email body; no unescaped `'`/`"`, stray tags,
double spaces or edge spaces), and `python3 scripts/l10n_diff.py --locale
app/src/main/res/values-zh-rHK/strings.xml` reports `missing=0 orphan=0 modified=0` (in sync).
Also checked: the file parses, no duplicate `name=`, the two orphans
(`settings_debug_export_logs_title` / `_subtitle`) are gone and `settings_debug_export_logs_subject`
stays; every Han character in the delta is in Big5 (no Simplified forms) and none of the
converter glyphs 啓 説 閲 裏 着 appears; no 您; quotes are 「」 throughout (nine keys), no “ ”.
**No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `MainActivity.maybeShowKillNotice` picks one body by exit kind and appends `' '` + `kill_notice_restored`
  only when a floating icon is actually on screen; See options (accent fill) opens `KeepRunningActivity`,
  Not now cancels. After 。 the code's ASCII space shows as a small extra gap in Chinese: harmless, and
  only the code could drop it.
- `KeepRunningActivity`: the Report a bug row under the cards binds the Settings row's two strings; the
  Xiaomi battery card reuses `keep_running_battery_line`; the close-after-lock card opens the system
  Battery screen (`ACTION_POWER_USAGE_SUMMARY`), the Huawei card the App launch list and the Samsung card
  the global firmware's Device care battery activity (`com.samsung.android.lool`), each falling back to
  App info.
- `LogExporter`: the no-email toast is `LENGTH_LONG` with the address filled in and is followed at once by
  the share sheet; the hub summary in `settings_row_hub.xml` is `singleLine` + `ellipsize="end"`.

### Findings (delta, round 1)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| keep_running_huawei_app_launch_line | ⚠️ | 為 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> 開啟「允許自動啟動」、「允許關聯啟動」和「允許在背景執行」。 | 為 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> 開啟「允許自動啟動」、「允許二次啟動」和「允許背景活動」。 | Huawei Hong Kong's own page, read today (consumer.huawei.com/hk, zh-hk00414587): 「…進入應用程式啟動管理，關閉應用程式的自動管理開關，…手動選擇啟用或關閉允許自動啟動、允許二次啟動或允許背景活動」; zh-hk00428704 repeats 允許背景活動. 關聯啟動 is the zh-CN label converted, and 在背景執行 a fresh translation of the English. The card title's 應用程式啟動管理 already matches the page. |
| keep_running_oppo_auto_launch_title | ⚠️ | 允許應用程式自啟動和完全背景行為 | 允許自動啟動和背景活動 | The China-ROM labels (允许应用自启动 / 允许完全后台行为) run through the file's swaps: 自啟動 is the Mainland abbreviation, and 後台→背景 turned a recognisable China-ROM label into 完全背景行為, which no ROM shows. OPPO's Hong Kong and Taiwan models run the international ColorOS, whose English switches are "Allow auto launch" and "Allow background activity"; the suggestion follows those and the English card, worded as Huawei HK words its own equivalent switches. One search found no Traditional Chinese ColorOS source, so the on-screen words stay unverified. |
| keep_running_huawei_close_after_lock_line | ⚠️ | 此選項位於「電池」>「更多電池設定」中。否則每次螢幕鎖定時 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> 都會被關閉。 | 此選項位於「電池」→「更多電池設定」中。開啟時，每次鎖定螢幕都會關閉 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g>。 | 否則 needs a condition to negate. Here it follows the sentence that says where the option is, so it attaches to the location ("if it is not there"); ja (オフにしないと) and ko (끄지 않으면) recast it the same way. Separator: next row. 電池 › 更多電池設定 is confirmed on Huawei HK's page, read today (zh-hk00428704: 前往設定 > 電池 > 更多電池設定). |
| keep_running_samsung_never_sleeping_line | 💬 | 位於「裝置維護」>「電量」>「背景使用量限制」中。手機需要記憶體時，會關閉正在休眠的應用程式。 | 位於「裝置維護」→「電量」→「背景使用量限制」中。手機需要記憶體時，會關閉正在休眠的應用程式。 | Separator only. These two keys hold the file's only bare `>` paths; every other path in the file is → (設定 → 無障礙設定 → 已下載的應用程式), the translator's notes say →, and the other 11 locales use an arrow (ar ←) or prose. No spaces beside 「」, which carry their own (ja does the same). The labels stay: they match Samsung Hong Kong's official page. |
| keep_running_xiaomi_autostart_title; a11y_stuck_message_xiaomi (not in the delta) | 💬 | `keep_running_xiaomi_autostart_title`: 允許「自啟動」<br>`a11y_stuck_message_xiaomi`: 在小米裝置上，你亦需在應用程式的系統設定中為 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> 開啟「自啟動」，並將省電策略設為「無限制」。否則系統會不斷停止該服務。 | `keep_running_xiaomi_autostart_title`: 允許「自動啟動」<br>`a11y_stuck_message_xiaomi`: 在小米裝置上，你亦需在應用程式的系統設定中為 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> 開啟「自動啟動」，並將省電策略設為「無限制」。否則系統會不斷停止該服務。 | 自啟動 is the Mainland 自启动 converted. Search summaries of Xiaomi's Hong Kong and Taiwan FAQs (mi.com/hk KA-499278, mi.com/tw KA-510918; the pages need JavaScript and were not read; Xiaomi marks them machine-translated from English) give the HyperOS switch as 後台自動啟動 (設定 → 應用程式 → 權限). 自動啟動 is inside that label and 自啟動 is not; Huawei HK (允許自動啟動) and this file's vivo card say 自動啟動 too. Secondary evidence on the full review's open item, hence 💬; change both keys or neither. |
| settings_support_report_bug_subtitle | 💬 | 以電郵將日誌傳送給支援團隊。長按改為分享。 | 將日誌電郵給支援團隊。長按改為分享。 | 273 dp against English's 256: the hold hint clips even at 411 dp (267 dp), where English fits whole. 電郵 as a verb is everyday Hong Kong usage (請電郵至…), and the suggestion measures 234 dp, whole from 393 dp up, tap action still first. The Fix disappearing icon page shows the same string wrapped. |

### Clean areas (delta) — checked, no findings

**Conversion audit, key by key against zh-rCN.** Structure and placeholder order follow zh-rCN in all 41
keys, and the hand edits improve on it: 為了省電而, 任何畫面 for 任意界面, 降低…的機會, 優先次序 (as in
`yomitan_single_dict_subtitle`), 此選項. The swaps are all in: 傳送, 電郵, 應用程式 (no bare 應用), 檔案,
透過, 圖示, 裝置資訊, 記憶體, 設定, 新增, 開啟, 執行, 重新啟動, 背景; 支援團隊 takes the technical-support
sense under the 支援 header, while the donation row keeps 支持. Apart from the OEM labels in the rows above
(自啟動, 完全背景行為), no Mainland word survives. Glyphs: 啟 throughout and none of 啓 説 閲 裏 着; 什麼 as
in the file's two existing uses; 「」 only.

**Kill notice, read as the user meets it.** As in zh-rCN: each body with " 現在已重新開啟。" appended reads
naturally, since PlayTranslate stays the topic; 已開啟的 pairs with 重新開啟; 部分手機會為了省電而這樣做 is
the better of the two locales' sentences. 查看解決方法, and 暫不 = `btn_not_now`.

**Support card and the page.** 解決圖示消失問題 is byte-identical in the row and the toolbar (176 dp of
~272). 報告錯誤 in `keep_running_empty` matches the row beneath it, and 重現 matches `crash_email_body`.
Each 否則 line follows an imperative title; the OPPO and vivo lines are identical, as in English. The tile
and restart cards reuse 新增快速設定圖塊 and the `a11y_stuck_message` wording; 無障礙設定 is Android
zh-HK's own entry name.

**Email flow.** 傳送崩潰報告 / 傳送錯誤報告; the subject mirrors `crash_email_subject`; the body is
full-width, with 發生了什麼事？ and the precedent's 裝置資訊 / 近期日誌. 電郵 is Android zh-HK's word. The
toast wraps to 2 lines at 296 and 320 dp with the address whole on line 2; the hold path keeps 分享日誌 /
PlayTranslate 日誌.

**Platform labels.** 電池用量 (from 應用程式電池用量) and 「無限制」 match AOSP zh-HK. Samsung: 裝置維護,
電量, 背景使用量限制, 永不自動休眠的應用程式, 正在休眠的應用程式 and the 加入 verb all match Samsung Hong
Kong's official page. Huawei: 應用程式啟動管理 and 電池 › 更多電池設定 are confirmed on Huawei HK's own
pages; 鎖屏清理 is the historical name (known issue 1), unverifiable in Traditional Chinese and harmless.
vivo 自動啟動 / 背景高耗電 and Xiaomi 省電策略 / 無限制 / 最近任務 have no Hong Kong source; they read
naturally and stay.

**Widths and mechanics.** Keep-running subtitle 160 dp; the Report a bug subtitle's first sentence,
182 dp, is visible even at 216 dp. 你 throughout; Pangu spacing identical to zh-rCN and checked
programmatically.

### Verdict (round 1)

3 ⚠️ + 3 💬. To apply: `keep_running_huawei_app_launch_line`, `keep_running_oppo_auto_launch_title` and
`keep_running_huawei_close_after_lock_line` (⚠️), plus `keep_running_samsung_never_sleeping_line` and
`settings_support_report_bug_subtitle` (💬). The Xiaomi 自動啟動 pair is recommended as well; its evidence
is secondary, so it can wait for a device, but both keys move together.

### Round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 PKT/../tools/mech_check.py PKT/../keys.txt zh-rHK` reports
`checked 1 locales x 41 keys; problems: 0` (the same check on `a11y_stuck_message_xiaomi`, changed
outside the delta in round 1: 0), and `python3 scripts/l10n_diff.py --locale
app/src/main/res/values-zh-rHK/strings.xml` reports `missing=0 orphan=0 modified=0` (in sync). Also
re-checked: the file parses, no duplicate `name=`, the two orphans are gone and
`settings_debug_export_logs_subject` stays; every Han character in the delta and in
`a11y_stuck_message_xiaomi` is in Big5, none of 啓 説 閲 裏 着 appears; no 您; quotes 「」 only; no
bare `>` left; Pangu spacing at every Han/Latin and Han/placeholder boundary.
**No 🛑 build-breaking issues.**

**Round-1 fixes:**
- `keep_running_huawei_app_launch_line` (⚠️): applied, byte-identical: 「允許自動啟動」、「允許二次啟動」和「允許背景活動」,
  Huawei Hong Kong's own labels.
- `keep_running_oppo_auto_launch_title` (⚠️): applied, byte-identical (允許自動啟動和背景活動). It now shares
  自動啟動 and 背景活動 with the Huawei line and the vivo title.
- `keep_running_huawei_close_after_lock_line` (⚠️): applied, byte-identical; 開啟時 takes 此選項 as its subject.
- `keep_running_samsung_never_sleeping_line` (💬): applied, byte-identical. No `>` path is left in the file
  (the only `&gt;` is the `&lt;img&gt;` sample in `anki_content_picture_desc`).
- `keep_running_xiaomi_autostart_title` with `a11y_stuck_message_xiaomi` (💬, the second key outside the
  delta): both applied, byte-identical. The card says 允許「自動啟動」 and the alert says 開啟「自動啟動」; both
  also say 將省電策略設為「無限制」 in the same words. 自啟動 now appears nowhere in the file, and the out-of-delta
  string passes the same mechanical check as the delta.
- `settings_support_report_bug_subtitle` (💬): applied, byte-identical (將日誌電郵給支援團隊。長按改為分享。). It
  measures 234 dp in the regenerated packet, so it shows whole from 249 dp (393 dp phones) up, and its email
  sentence (143 dp) is whole even at 216 dp. The Fix disappearing icon page shows the same string wrapped.
- Nothing around them moved: `keep_running_title` and `settings_support_keep_running_title` are still both
  解決圖示消失問題, and the OPPO and vivo lines are still identical.

#### Findings (round 2)

No new findings.

#### Verdict (round 2)
**PASS.** Every round-1 fix landed. All 41 strings were re-read as the four surfaces with real values filled
in (PlayTranslate, support@playtranslate.com, 3.3.0), and each was compared with its zh-rCN key. No Mainland
word survives: 界面 became 任何畫面, 优先级 became 優先次序, 重启 became 重新啟動, 通过 became 透過, and 后台
became 背景 throughout. 推薦 for "recommended" follows the file's own 推薦 (`lang_section_suggested`,
`yomitan_category_recommended`). The glyphs are 啟 and 什麼, as in AOSP zh-HK; quotes are 「」; and 無障礙設定,
電池用量 and 「無限制」 match AOSP zh-HK. Widths: the Keep-running subtitle is 160 dp, the toolbar title takes
176 of ~272 dp, and the toast is 2 lines at both 296 and 320 dp, with the address whole on line 2.
Unverified, but not open: Xiaomi's 省電策略 and 最近任務, the OPPO switches, and vivo's 背景高耗電 still have no
Traditional Chinese OEM source. Four searches this pass (two for vivo Taiwan/Hong Kong, one for ColorOS in
Traditional Chinese, one for MIUI in Traditional Chinese) returned only English guides and the search engine's
own renderings, so nothing changes.

## Follow-up review 2026-10-07, round 1 (15 keys after the Fix disappearing icon rewrite)

Mechanical layer: `python3 TOOLS/mech_check.py F2/keys2.txt zh-rHK` reports
`checked 1 locales x 15 keys; problems: 0` (all 15 present; every `<xliff:g>` span, including
`app_name2` in the three two-span lines, byte-identical to EN; `\n` count 2 in `a11y_stuck_message`
as in EN; no unescaped `'`/`"`, stray tags, double spaces or edge spaces), and `python3
scripts/l10n_diff.py --locale app/src/main/res/values-zh-rHK/strings.xml` reports
`missing=0 orphan=0 modified=0` (in sync). Also checked: the file parses, no duplicate `name=`; the
four keys removed from English (`restricted_settings_title` / `_message`,
`keep_running_huawei_close_after_lock_title` / `_line`) are gone; every Han character in the 15 values
is in Big5 (no Simplified forms) and none of the converter glyphs 啓 説 閲 裏 着 appears; no 您; quotes
「」 only, no “ ”; Pangu spacing checked programmatically (as zh-rCN: one space at every Han/Latin,
Han/⋮ and Han/span boundary, none beside full-width punctuation or 「」, none between Han runs).
**No 🛑 build-breaking issues.**

**Render code read before reviewing.**
- `AccessibilityHelp.withRestrictedSettingsStep` appends the addendum on API 33+ only, as `message + "\n\n" + step`,
  in `MainActivity.showAccessibilityDialog` and `SettingsRenderer.showOverlayIconA11yAlert` (system AlertDialogs),
  `showAccessibilityRequiredAlert` (OverlayAlert, the three `a11y_required_*` messages) and
  `KeepRunningActivity.lineOf` (the accessibility card, not in its stuck state). The stuck alert
  (`SettingsRenderer.showA11yStuckAlert`) never takes it, and adds `a11y_stuck_message_xiaomi` after `\n\n`
  when `Build.MANUFACTURER` is Xiaomi.
- `KeepRunningItems.ids`: Xiaomi gets autostart, battery (with its own line now) and lock; the generic battery
  card is skipped on Xiaomi and hidden once the exemption holds; every other ROM gets its one card first.
  `openFirst` tries the ROM's screens (Xiaomi battery: `com.miui.powerkeeper` `HiddenAppsConfigActivity` with
  the package, the app's own battery page; generic battery: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, the
  AOSP 允許應用程式一律在背景中執行嗎？ dialog) and, when none opens, shows the `LENGTH_LONG` toast and opens
  App info (應用程式資料).
- Titles and lines are the `settings_row_link` title and subtitle inside a card and wrap; the toast is the
  system text toast (2 lines, 14 sp).

### Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| a11y_restricted_settings_addendum; requires_android_11_message (outside F) | 💬 | `a11y_restricted_settings_addendum`: 在 Android 13 及更高版本中，如果開關顯示為灰色，請先點按一次，然後開啟 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> 的「應用程式資料」頁面，點按 ⋮ 選單並選擇「允許受限設定」。<br>`requires_android_11_message`: 此功能需要 Android 11 或更高版本。 | `a11y_restricted_settings_addendum`: 在 Android 13 或以上版本中，如果開關顯示為灰色，請先點按一次，然後開啟 <xliff:g id="app_name" example="PlayTranslate">PlayTranslate</xliff:g> 的「應用程式資料」頁面，點按 ⋮ 選單並選擇「允許受限設定」。<br>`requires_android_11_message`: 此功能需要 Android 11 或以上版本。 | The one Mainland phrasing in the 15 keys: 及更高版本 is zh-rCN's wording converted. Hong Kong writes "X and later" as X 或以上版本, and AOSP zh-HK's own idiom for "or more" is 或以上 (快速按開關按鈕 5 次或以上); 更高版本 occurs nowhere in AOSP zh-HK Settings. Understandable as it stands, hence 💬. The sibling outside the delta carries the same conversion (或更高版本, passed in the full review); change both or neither, so the file says it one way. |

### Clean areas — checked, no findings

**Each key against its zh-rCN key.** Structure, clause order and span order follow zh-rCN in all 15, and the
Hong Kong pass reached everything else: 設定, 搜尋, 選單, 開啟, 裝置, 應用程式 (no bare 應用), 背景 (no 後台),
圖示, 任何畫面 for 任意界面, 電池用量 for 电池使用, 加入 for Samsung's add, 你亦需 in the Xiaomi paragraph, and
自動啟動 for 自启动 in every place (自啟動 appears nowhere in the file). The OEM words that differ from zh-rCN differ
on purpose: Huawei HK's 二次啟動 for 关联启动 and OPPO's 背景活動 for 完全后台行为, as round 1 of the first pass
decided. A scripted hunt over the 15 values for the converted forms of the parameters file's Mainland list
(設置, 屏幕, 打開, 設備, 菜單, 界面, 圖標, 搜索, 後台, 響應, 重置 and the rest, plus 自啟動, 關聯啟動, 任意) found
none; the only hit for the extra phrase 更高版本 is the row above. Glyphs: 啟 and 為 throughout, none of 啓 説 閲
裏 着 爲.

**Surfaces read as sets.** The addendum closes each of the five messages naturally: after 前往：設定 → 無障礙設定 →
已下載的應用程式 → PlayTranslate → 啟用。 in the two system dialogs 開關 is that step's switch; after the three
`a11y_required_*` messages and the accessibility card's line it has no earlier antecedent, exactly as English's
"the switch" has none. Every card reads as title + line (允許 PlayTranslate 一律在背景中執行 / 如果手機關閉了…，此設定可讓它
自行重新啟動; 新增快速設定圖塊 / 可在任何畫面重新開啟…; the ROM cards' lines open with the search word or path and
close with the 否則 hedge where English has one). The stuck alert reads 無障礙服務需要重新啟動 → the two paragraphs →
the Xiaomi paragraph, whose 允許…自動啟動 and 將其電池用量設為「無限制」 are the two Xiaomi card titles' own words.
The toast then App info: 此手機上無法開啟該頁面, then 應用程式資料 opens, and 該選項 points back to the card title.

**Measured.** The toast wraps to 2 lines at 296 dp (此手機上無法開啟該頁面。請改為在手機的設定 | 中搜尋該選項。)
and at 320 dp (…的設定中 | 搜尋該選項。).

**Labels.** AOSP zh-HK, read in the cached android16-qpr2 Settings strings: 應用程式資料 (`application_info_label`),
允許受限設定 (`app_restricted_settings_lockscreen_title`, the same words as the deleted reviewed string),
允許應用程式一律在背景中執行嗎？ (`high_power_prompt_title`, which the battery title repeats as 一律在背景中執行),
無限制, and 電池用量 from 應用程式電池用量. Huawei, verified by the first pass on Huawei Hong Kong's own page:
應用程式啟動管理, the 自動管理 開關, 允許自動啟動, 允許二次啟動, 允許背景活動. Samsung, Samsung Hong Kong's official
page: 「電量」, 「背景使用量限制」, 「永不自動休眠的應用程式」 and the 加入 verb. Xiaomi: 自動啟動 sits inside the
HyperOS Hong Kong label 後台自動啟動 (secondary), so a Settings search for it matches; 省電策略, 「電池」, 「電量」 and
最近任務 have no Traditional Chinese source. OPPO 自動啟動 / 背景活動 and vivo 自動啟動 / 「電池」 / 背景高耗電 stay
unverified, as the first pass recorded; each line matches its unchanged title.

**Search words.** One per ROM: 「自動啟動」 for Xiaomi, OPPO and vivo, and Huawei's 「應用程式啟動管理」. The title
允許「自動啟動」 (outside F) quotes the same word the line quotes as the search word, so the card reads as one; the
bare 自動啟動 in the lines and the stuck alert is the verb, which English leaves unquoted too. No change.

**CHANGED keys moved only where English moved** (compared with HEAD and the pre-rewrite values):
`a11y_stuck_message` is the reviewed string minus 這通常發生在系統為省電而強制停止應用程式後。, byte for byte;
`a11y_stuck_message_xiaomi` changes only 為…開啟「自動啟動」 → 允許…自動啟動, 省電策略 → 其電池用量 and 會 → 可能會;
`keep_running_xiaomi_lock_line` keeps its first sentence; `keep_running_xiaomi_battery_title` swaps only the entry
name; the tile line drops 只需滑動並點按一次 as English dropped the swipe. The rewritten lines carry every clause
of the new English, the hedges as 可能 and the battery line's verified effect without one.

**Two-span lines.** Xiaomi, OPPO and vivo read naturally with PlayTranslate in both places; 它 then refers back
to the second mention unambiguously.

**Other.** 系統設定 for "app settings" mirrors 應用程式的系統設定 in the stuck alert the same Xiaomi user reads.
`kill_notice_body_other` (outside F) already says 為了省電而這樣做, which the zh-rCN report's optional 💬 would match.
你 only, 點按 as the file decided, 「」 only, full-width （）、。， and Pangu spacing identical to zh-rCN.

### Verdict (round 1)

0 ❌, 0 ⚠️, 1 💬 (one fix, two keys, one of them outside F). To apply: 或以上版本 in
`a11y_restricted_settings_addendum` and `requires_android_11_message`, both or neither. Otherwise **PASS**.
Labels left unverified, as recorded: Xiaomi's names other than 自動啟動, and the OPPO and vivo words.

### Follow-up round 2 (2026-10-07), final review after applying round 1

Mechanical layer re-run: `python3 TOOLS/mech_check.py F2/keys2.txt zh-rHK` reports
`checked 1 locales x 15 keys; problems: 0`; the same check on `requires_android_11_message` (the round-1
fix outside the 15), on `F2/keys-extra-f2.txt` and on the five ROM card titles reports 0; and
`python3 scripts/l10n_diff.py --locale app/src/main/res/values-zh-rHK/strings.xml` reports
`missing=0 orphan=0 modified=0` (in sync). Also re-checked: the file parses, no duplicate `name=`; the
four keys removed from English are gone; every Han character in the 15 values and in
`requires_android_11_message` is in Big5, and none of 啓 説 閲 裏 着 爲 appears; no 您 in the file; quotes
「」 only (56 pairs, no “ ”); Pangu spacing checked programmatically (as zh-rCN: one space at every
Han/Latin, Han/⋮ and Han/span boundary, none beside full-width punctuation or 「」, none between Han runs,
no half-width punctuation).
**No 🛑 build-breaking issues.**

**Round-1 fixes:**
- `a11y_restricted_settings_addendum` with `requires_android_11_message` (💬, one fix, two keys, the second outside
  the 15): both applied, byte-identical (在 Android 13 或以上版本中 / 此功能需要 Android 11 或以上版本。). 更高版本 and
  及更高 now appear nowhere in the file, so it says "or later" one way. The addendum is the same length (或以上 replaces
  及更高), so it still closes all five messages and the card line as one sentence, and the title 需要 Android 11 over
  此功能需要 Android 11 或以上版本。 reads correctly.

#### Findings (round 2)

No new findings.

#### Verdict (round 2)
**PASS.** The round-1 fix landed in both keys and nothing else moved: the regenerated packet matches the file in
all 53 rows (the 15 keys and the 38 strings around them), and the only key the working-tree diff touches outside
the two passes' sets is `requires_android_11_message`, the fix itself. The 15 keys were re-read as the user meets
them and against zh-rCN key by key. A scripted hunt for converted forms from the parameters block's Mainland list
(設置, 屏幕, 打開, 設備, 菜單, 界面, 圖標, 搜索, 後台, 重啟, 任意, 自啟動, 關聯啟動, 更高 and the rest) finds none. The OEM
words differ from zh-rCN only where Hong Kong sources or the first pass's decisions call for it (二次啟動, 背景活動,
自動啟動, Samsung's 電量 and 加入), and quotes are 「」. AOSP labels re-read in the cached android16-qpr2 zh-HK
Settings strings: 應用程式資料, 允許受限設定, 允許應用程式一律在背景中執行嗎？, 無限制, 應用程式電池用量. The cards, the
stuck alert's Xiaomi paragraph and the toast (28 characters, 2 lines at 296 and 320 dp) read as round 1 recorded.
One new data point, not a finding: vivo Hong Kong's own support site (vivo.com/hk/zh/support, the 耗電 FAQ list,
read today) titles a question 可限制應用程式於背景使用時消耗電量嗎？. vivo HK therefore says 背景 for "background", as the
vivo card's 背景高耗電 does, rather than the converted China-ROM 後台. The answer did not load, so the switch label
itself stays unverified. Labels left unverified, as recorded: Xiaomi's names other than 自動啟動, the OPPO words,
and vivo's 自動啟動 and 背景高耗電.

## Delta review 2026-10-14 (Bilibili Index-Translate: 3 keys)

Mechanical layer: `python3 scripts/l10n_diff.py --locale app/src/main/res/values-zh-rHK/strings.xml` reports `values-zh-rHK missing=0 orphan=0 modified=0`, "in sync". The three new names are each present once, in zh-rCN's key order, and are the file's only change (`git diff`). The delta has no `<xliff:g>`, placeholders or markup, no unescaped `'`/`"`, and no dashes. A script checked it for Big5-only Han, 「」 only, Pangu spacing, NFC, and no odd or double spaces. **No 🛑 build-breaking issues.**

No findings.

Clean areas: Render code read first. The note is `tvProviderNote` (13 sp, no line cap), shown where the key section would be. The picker and the remove alert switch to `_keyless` when `account.requiresKey` is false (Lingva, Index-Translate). **Derivation from zh-rCN.** The three values keep zh-rCN's structure, with the table's swaps applied: 密钥→金鑰, 截取→擷取, 发送→傳送, 获取→取得, 列表→清單, 通过→透過, 自定义→自訂, “ ”→「」. No Mainland term survives. The departures beyond the table are all natural Hong Kong usage. 營運 is the HK/TW order of zh-rCN's 运营. 未有公布 is the common HK written form. 上限 matches this file's `translation_error_daily_quota` 每日上限, and zh-rCN's review proposes the same. 傳送至 sits where the file otherwise maps 发送给 to 傳送給 (`crash_dialog_message`), but it is as natural for a service as `email_no_app_fallback`'s 傳送至. 請稍後再試 replaces the derived 重試, and it is Android's own Hong Kong wording: android-36 `values-zh-rHK` has 稍後再試 four times (`httpErrorIO`, `httpErrorTooManyRequests`, `face_error_lockout`, `restr_pin_try_later`) and 稍後重試 never. **`llm_backend_index_translate_note`** has all four English facts. 未有公布使用上限 keeps "publishes no usage limits" and does not say there are none. API 金鑰 is `llm_backend_api_key_label`, 「擷取到的文字」 is `tr_service_order_footer`'s own phrase, and 無需 is the form of `service_no_account_required` (無需帳戶). **`llm_model_picker_fetch_failed_keyless`** matches its sibling except for the middle clause, and 「自訂…」 is `llm_backend_model_custom_entry` in the same corner quotes. **`tr_service_remove_message_keyless`** is its sibling without the key clause. Read with the title 「移除 Bilibili Index-Translate？」 and the 移除 button, the alert uses one verb, and remove (移除) stays distinct from delete (刪除).

Outside this delta, for the record: `anki_models_unavailable` still says 請稍後重試, the Mainland-derived form. With AOSP's 稍後再試 above, it is now the file's odd one out. This is reported, not reviewed here.

### Disposition (2026-10-14)

No findings in the delta; nothing to apply. The out-of-delta note (`anki_models_unavailable` still says 請稍後重試 where Android's Hong Kong wording is 請稍後再試) is reported to the developer.

## Delta review 2026-10-27 (PlayTranslate Cloud: 24 keys; plus the owed review of 15 keys localized outside the sync)

Mechanical layer: `python3 SP/mechcheck.py <the 39 keys>` reports `39 keys x 13 locales: 0 problems` (no line for zh-rHK: every key present, `<xliff:g>` spans byte-identical to EN, placeholders and markup counts match, no raw `'`/`"`, no stray or double spaces, XML well-formed); `python3 scripts/l10n_diff.py --locale app/src/main/res/values-zh-rHK/strings.xml` reports `values-zh-rHK missing=0 orphan=0 modified=0`, "in sync". **No 🛑 build-breaking issues.**

**Render code read before reviewing** (the same surfaces as zh-rCN's section of this date). The error pill is 14 sp, two lines, ellipsized, and for a PlayTranslate Cloud error its gear opens the cloud page. On the cloud page, the two switch rows wrap (`settings_row_switch.xml`, no `maxLines`); the key label sits over a `singleLine` field whose hint is the key hint; the status card is `pt_cloud_status_title` with the value at its end, 未檢查 until the check that every resume and Save starts (檢查中). The hub summary is `singleLine` + ellipsized, about 216 dp on a 360 dp phone. The Capture & overlay cloud row shows its subtitle in every switch state. The Translation services status line wraps. The quick-menu keys are contentDescription only. The toast is `LENGTH_LONG`, then App info opens. Group headers get `uppercase(Locale.ROOT)`, a no-op on Han. The thinking segments are `buildPillToggle` (13 sp shrinking to 10 sp). The crop page title and the crop button's label are one string. The load-more row is 13 sp with `maxLines = 1`.

### Findings

No findings in the 39 keys: 0 ❌, 0 ⚠️, 0 💬.

### Clean areas (checked, no findings)

**Derivation from zh-rCN, key by key.** All 39 values keep zh-rCN's structure, clause order and span position (`%1$s：…`, `…（%1$s）`, `…（%1$d）`), and the key order of the two files is identical (1,087 names, same sequence). Every swap from the parameters block that the delta touches is in: 服务器→伺服器, 设备→裝置, 识别→辨識, 屏幕→螢幕, 运行→執行, 密钥→金鑰, 粘贴→貼上, 连接→連線, 响应→回應, 打开→開啟, 设置→設定, 搜索→搜尋, 拖动→拖曳, 加载→載入. 内置 stays 內置, as the block says, not Taiwan's 內建. Three hand edits go beyond the table, and each is Hong Kong usage. 性能→效能: AOSP zh-HK has 效能 4 times to 性能 once, including 為達致更佳效能，一次只能開啟其中一個遊戲, and the file says 請留意效能 in `tr_service_offline_footer`. 计费→收費: this matches 按用量收費, and the file has no 計費. 且→而且 is natural either way. Appendix below.

**Error pill and status row.** 未執行 is both the pill's word (`translation_error_not_running`) and the status row's (`pt_cloud_status_not_running`), and it pairs with 執行中. Android zh-HK's own "running" is 執行中 (「%1$s」執行中, 駕駛應用程式執行中). 無法連線 = `translation_error_unreachable` and 回應異常 = `translation_error_bad_response`, byte for byte. AOSP zh-HK has 無法連線 3 times to 無法連接 once. The set 執行中 / 啟動中 / 未執行 / 無法連線 / 回應異常（500） / 檢查中 / 未檢查 holds short, parallel state words for one slot. Out of context, 未執行 could read as "not executed". Here it fills the slot where 執行中 shows, or follows a service name in the pill, so it reads as "not running". 服務 needs no 狀態 after it, since the value beside it completes the row.

**Cloud page.** 辨識螢幕文字 / 翻譯文字 read as a pair. 辨識 is the file's OCR verb (14 uses) and AOSP zh-HK's verb for "recognise" (無法辨識指紋). 客戶端金鑰 and 貼上你收到的金鑰 match `llm_backend_api_key_label` (API 金鑰), `tr_service_key_tail_fmt` (金鑰 ••••) and `hint_deepl_key` (在此貼上你的…). 客戶端 is the Hong Kong form; Taiwan says 用戶端 (from memory; neither appears in the framework strings). The two subtitles keep every English fact, as in zh-rCN.

**Settings hub.** Measured at 13 sp (PingFang HK): 伺服器級 OCR 和翻譯 is 128 dp, 僅伺服器級 OCR 98 dp, 僅翻譯 39 dp. All are whole in the ~216 dp summary, and still at 180 dp. 關 is the file's Off (`capture_lifecycle_state_off`, 關 · 擷取到的句子記錄).

**Other surfaces.** In the OCR section, 伺服器級 OCR，裝置零負載 follows the comma-note shape of the engine notes under it (最適合遊戲，詞語查詢最精準), under the header 文字辨識（OCR）. 內置頂尖付費翻譯模型 stands where the store rows say 需要帳戶. The quick menu reads PlayTranslate Cloud：可用 / ：不可用 (= `backend_status_unavailable_default`). The toast 無法開啟該頁面。請改為在手機的設定中搜尋該選項。 drops 此手機上 as English dropped "on this phone", keeps the reviewed second sentence, and measures 24 characters, 336 dp at 14 sp: 2 lines at 296 dp (…搜尋該 | 選項。) and at 320 dp.

**Part B.** 變位 / 變格 / 詞形 sit beside 釋義 and 漢字 as one-noun headers. 裁剪截圖 serves as both the title and the button label; 截圖 is the file's screenshot word, and 裁剪 is the Hong Kong form (Taiwan 裁切, from memory; the framework strings have no crop label) and the file's audio-trim word (儲存時裁剪). 拖曳邊緣以裁剪截圖 opens like `region_overlay_drag_instruction` (拖曳邊緣，將螢幕擷取範圍限制在此區域). Only the connector differs: 以 suits a short purpose, and the region pill's comma carries a long 將 clause. The two pills never share a screen. 思考模式 / 思考強度 share 思考, and the subtitles keep 推理, token in Latin (as in 個 token) and 按輸出 token 收費. The segments are 關 / 最低 / 低 / 中 / 高, nowhere near the width limit. 載入更多（2） sits beside 其他相符 and 正在查詢….

**Collisions on shared screens: none.** The cloud page holds the toolbar brand, 辨識螢幕文字 / 翻譯文字, 客戶端金鑰, 儲存 and 服務, with no swap landing on a neighbour's word. On the hub, the pitch sits under the Configure header 設定, a recorded decision, and repeats no word of it. In the OCR section, the 預設 badge and the cloud row share no word. The LLM page's 思考 rows sit under the 取得 %1$s API 金鑰 link (`llm_backend_get_key_title_fmt`) without overlap.

### HK-vs-Mainland term audit (the 39 keys)

| keys | zh-rCN | zh-rHK | how it was derived |
|---|---|---|---|
| translation_error_not_running, pt_cloud_status_running, pt_cloud_status_not_running | 未运行 / 运行中 | 未執行 / 執行中 | 运行→執行; Android zh-HK says 執行中 |
| settings_pt_cloud_summary_pitch, settings_pt_cloud_summary_ocr_only, pt_cloud_ocr_row_subtitle, pt_cloud_ocr_toggle_subtitle | 服务器级 / 服务器 GPU / 设备 / 运行 / 性能 | 伺服器級 / 伺服器 GPU / 裝置 / 執行 / 效能 | table swaps, plus 效能 (AOSP zh-HK, the file) |
| pt_cloud_ocr_toggle_title | 识别屏幕文字 | 辨識螢幕文字 | 识别→辨識, 屏幕→螢幕 |
| pt_cloud_translate_toggle_subtitle, pt_cloud_translate_row_subtitle_no_key | 内置 … 付费 | 內置 … 付費 | 內置 kept (not 內建) |
| pt_cloud_key_label, pt_cloud_key_hint | 客户端密钥 / 粘贴 | 客戶端金鑰 / 貼上 | 密钥→金鑰, 粘贴→貼上 |
| pt_cloud_status_unreachable, pt_cloud_status_other | 无法连接 / 响应异常 | 無法連線 / 回應異常 | 连接→連線, 响应→回應; = the pill family |
| pt_cloud_status_starting | 启动中 | 啟動中 | 啟, not 啓 |
| keep_running_screen_unavailable | 打开 / 设置 / 搜索 | 開啟 / 設定 / 搜尋 | three table swaps |
| anki_screenshot_crop_instruction | 拖动 | 拖曳 | 拖动→拖曳 |
| llm_backend_thinking_subtitle, llm_backend_reasoning_subtitle | 且 … 计费 | 而且 … 收費 | 收費 as in 按用量收費 |
| load_more_count | 加载更多 | 載入更多 | 加载→載入 |
| settings_pt_cloud_summary_off, settings_pt_cloud_summary_translation_only, pt_cloud_translate_toggle_title, pt_cloud_status_title, pt_cloud_status_checking, pt_cloud_status_unchecked, floating_menu_pt_cloud_available, floating_menu_pt_cloud_unavailable, anki_screenshot_crop, word_detail_group_*, llm_backend_thinking_title, llm_backend_reasoning_title, the five level segments | 关 / 仅翻译 / 翻译文字 / 服务 / 检查中 / 未检查 / 可用 / 不可用 / 裁剪截图 / 变位 / 变格 / 词形 / 思考模式 / 思考强度 / 关 最低 低 中 高 | the same, script only | each is already Hong Kong usage (see the notes below) |

Script, glyphs and spacing, checked by script over the 39 values: every Han character is in Big5. None of 啓 説 閲 裏 着 爲 appears (啟動中, 請改為). There are no quotes in the delta, so the 「」 rule does not arise. No 您; 你 appears in the key hint. Pangu spacing is identical to zh-rCN: one space at each Han/Latin seam (伺服器級 OCR 和翻譯, 伺服器 GPU 上, 輸出 token 收費), and none beside full-width punctuation or at the span seams. All values are NFC.

Data points, recorded here and not raised:
- **不可用 in the quick-menu label.** Android zh-HK pairs 可用 with 無法使用 (`media_route_status_available` / `_not_available`) and has no 不可用. This file says 不可用 in nine other strings, including `backend_status_unavailable_default` and the contributor's own rewrite of `tr_service_order_footer` (某項服務不可用時). The delta follows the file, and the word is ordinary Hong Kong written usage.
- **檢查中 / 啟動中.** Android zh-HK writes in-flight states as 正在檢查 / 正在啟動 (5 each; the 中 forms do not occur), while its states take 中 (使用中, 執行中). In a value slot that cycles 檢查中 → 執行中, the parallel 中 form reads naturally in Hong Kong and matches zh-rCN's set.
- **金鑰.** Android zh-HK's only two "key" strings say 密鑰 (點對點加密聯絡人密鑰); zh-TW says 金鑰. The file's 金鑰 (18 uses, now including the two new keys) is the full review's recorded swap (密鑰→金鑰), so the delta is right to follow it. This is noted only in case that decision is ever revisited file-wide.
- **辨識.** Android zh-HK uses 辨識 for the verb "recognise" (無法辨識指紋) and 識別 in noun compounds (生物識別). 辨識螢幕文字 is the verb, which fits the table's 识别→辨識.

### Disposition (2026-10-27)

No findings; nothing to apply.

### Round 2 (2026-10-27), final review after applying round 1

Mechanical layer: `python3 SP/mechcheck.py <the 39 keys>` reports `39 keys x 13 locales: 0 problems` (no line for zh-rHK); `python3 scripts/l10n_diff.py --locale app/src/main/res/values-zh-rHK/strings.xml` reports `values-zh-rHK missing=0 orphan=0 modified=0`, "in sync". **No 🛑 build-breaking issues.**

Round-1 changes checked: none to check. Round 1 had no findings and its Disposition applied nothing, and the file is the one round 1 read: its SHA-1 (`ac4a71ff…`) matches `SP/locale-hashes-after-fixes.txt`, it was last written at 11:33, before round 1's zh scratch work (12:03 to 12:09), no `SP/fixes-*.tsv` row names zh-rHK, every value the round-1 section and its term audit quote is the file's value, and `git diff` still shows exactly the 24 Part A keys (23 added, `keep_running_screen_unavailable` reworded).

#### Findings (round 2)

No findings.

#### Verdict (round 2)

**PASS**; nothing is open in the strings. Each of the 39 values was re-derived from zh-rCN's value of the same key. Every table swap these words need is in (伺服器, 裝置, 辨識, 螢幕, 執行, 金鑰, 貼上, 連線, 回應, 開啟, 設定, 搜尋, 拖曳, 載入; 內置 kept), the hand edits 效能, 收費 and 而且 are Hong Kong usage, and the script-only spans (頂尖, 零負載, 多款, 選用, 推理, 作答, 程度, 變位, 變格, 詞形, 裁剪, 該頁面, 該選項, 檢查中, 不可用, 最低 and the rest) hold no Mainland word. The framework's zh-HK strings on disk confirm the Android-facing words: 貼上 (`paste`), 搜尋 (`search_go`), 正在載入 (`loading`), 關 (`capital_off`, exactly the file's Off) and 執行中 for a running app (「%1$s」執行中). Glyphs are Android's (啟動中, 請改為; none of 啓 説 閲 裏 着 爲), every Han character is in Big5, there is no 您, no quote arises, spacing matches zh-rCN, and widths match it (PingFang HK: toast 336 dp, 2 lines at 296 and 256 dp). No swap collides on a shared screen: the cloud page's 執行 (OCR subtitle) and 執行中 (status) agree, and the LLM page's 關 segment and 關閉 close button are different controls. A data point, not raised: zh-HK also uses 運行 for software (停止運行 where zh-TW says 停止執行), so 未執行 is the table's pick between two Hong Kong forms, kept because it pairs with 執行中; round 1's other data points (不可用, 檢查中 / 啟動中, 金鑰) stand. Outside the strings (read in code), the English cooldown description from `PtCloudErrors`, app-wide and already recorded in other locales and reported, can show "Not running · 重試於 15:42" under 未執行.
