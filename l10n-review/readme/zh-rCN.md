# Simplified Chinese (zh-rCN) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] zh-rCN -> readme/README.zh-CN.md`, no errors, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Intro (video link) | 💬 | "[在 Persona 3 Reload 中使用 PlayTranslate]" | "[在《女神异闻录3 Reload》中使用 PlayTranslate]" | Optional. Atlus released the game in Simplified Chinese as 女神异闻录3 Reload (some store listings write a full-width ３), and that is the name Mainland players know. Chinese game titles take 《》. The link URL is unchanged, so the checker's video-line rule still holds. |
| Features > 离线使用 | 💬 | "还可以下载可选的离线翻译模型" | "还可以另行下载离线翻译模型" | 可以 already makes it optional, so 可选的 says it twice. 离线模型 itself matches `lang_section_offline_models_title`（下载离线模型）. |
| Features > 文字转语音; Features > Yomitan 集成 | 💬 | "可以在设置中更改默认语音" and "今后还会有更深入的集成" | "可以在设置中更改默认语音。" and "今后还会有更深入的集成。" | Both items already have a 。 in the middle but none at the end. In Chinese a list item with a sentence break inside should end with 。 too. The other multi-sentence items (导出到 Anki, 相机翻译, 文本历史记录) already do. The English has the same inconsistency. |
| Features > 导出到 Anki | 💬 | "目标单词、文字转语音和截图" | "目标单词、朗读音频和截图" | In a list of what goes on the card, 文字转语音 reads as the feature's name, not the audio clip it produces. The app's own card fields name audio by what it is (`anki_content_word_audio` 单词音频, `anki_content_sentence_audio` 句子音频). 单词列表 and 截图 already match `anki_content_words_table` / `anki_content_picture`. |
| Features > 文本历史记录 | 💬 | "保留截取到的句子记录。默认关闭。" | "保留截取到的句子。默认关闭。" | 记录 appears twice (文本历史记录：…句子记录). The fix is word-for-word the app's own `history_empty_off`（保留 PlayTranslate 截取到的句子）. |
| Supported Languages > Game languages table | 💬 | "中文 (简体)" and "中文 (繁体)" | "中文（简体）" and "中文（繁体）" | Half-width parentheses with a space inside a Han cell. zh-CN uses full-width （）, and this is also how Android's and CLDR's zh display names write a script qualifier. The checker compares only the native-name and code columns, so editing this cell is safe. |
| Optional: Online Translation Backends | 💬 | "默认情况下，翻译使用 [Lingva](https://github.com/thedaviddelta/lingva-translate)，并以 ML Kit 作为离线后备方案。" | "默认使用 [Lingva](https://github.com/thedaviddelta/lingva-translate) 进行翻译，并以 ML Kit 作为离线后备方案。" | 翻译使用 X copies the English word order of "translation uses X". A native writer puts 默认 on the verb. 后备方案 matches `tr_service_offline_footer`. |
| Optional: Online Translation Backends | 💬 | "每项服务在列表中单独占一项，你可以同时配置多项，再通过调整顺序决定优先由哪一项翻译：" | "每项服务在列表中单独列出，你可以同时配置多项，再调整顺序，决定优先由哪一项翻译：" | 每项服务…占一项 says 项 twice in one clause, and 通过…决定 is stiff. Keep 项 as the measure word: the app counts services with it (`tr_service_order_footer`: 每项已启用的服务…某项不可用). |
| Optional: Online Translation Backends > 自定义 | 💬 | "填入你自己的基础 URL 即可" | "填入你自己的后端 URL 即可" | The app never says 基础 URL. Its field is `llm_backend_base_url_label` 自定义 URL, with the hint `llm_backend_base_url_custom_hint` 输入你的后端 URL. 后端 URL matches the hint. The English README ("base URL") and the English app ("Custom URL") differ the same way, so this is faithful to the source, just not to the app. |

## Clean areas (checked, no findings)

- **Accuracy and completeness.** All 13 feature bullets, the 4 install steps, the 5 Play Protect steps, the 4 restricted-settings steps and the 9 backends are present, in order, with nothing added, dropped or softened. "Off by default", "temporarily", "re-enable afterward", the Ayn Thor example, "incl. Anki" and "pick a model at runtime" (rendered as 可在应用内选择模型, which reads well) are all kept. 26 and 59 appear in both the intro and Supported Languages.
- **Register.** Casual 你 throughout: install step 2, the accessibility section, Ko-fi, the language intro, backends, Anki. There are no 您. The tone is friendly and concise (对话一变就自动翻译, 记得重新开启…), not machine translation.
- **Terminology vs strings.xml.** These match the app exactly:
  - 翻译服务 = `settings_cell_translation_services`
  - 快捷键 = `settings_cell_hotkeys`
  - 文字转语音 = `settings_cell_tts`
  - 自动翻译 = `live_mode_auto_translate_label`
  - 假名注音 = `header_action_furigana`, used throughout. There is no stray 振假名 or bare 假名, and the app has none left either.
  - 拼音 = `hint_label_pinyin_lower`
  - 截取区域 = `menu_capture_region`
  - 游戏音频 = `audio_source_game_name`
  - Anki 抽认卡 = `settings_cell_anki`, used as the H2
  - 允许受限设置 and the 三点（⋮）按钮 wording, both lifted from `restricted_settings_title` / `restricted_settings_message`
  - 文本历史记录 = `history_toggle_title` 保留文本历史记录
  - 拍摄快照 = `camera_shutter_cd`
  - 单词查询 = `onboarding_welcome_learn_body`
  - 长按预览 = `settings_overlay_mode_subtitle`
  - API 密钥, 卡片类型, 牌组, 音高重音 (`yomitan_category_pitch_accent`), 自定义 (`llm_backend_preset_custom`), 后备方案, 无障碍权限

  悬浮放大镜 is not an app string, but it is accurate: the drag-lookup surface is `MagnifierLens`, which shows zoomed pixels while you drag.
- **Android / Play Store wording.** 设置 → 安全 → 安装未知应用 uses AOSP zh-CN's label for the screen. The path itself mirrors the English source. The app's own zh-rCN review already confirmed 安装未知应用 and 允许受限设置 against the OS. The Play steps match Google's Simplified Chinese wording (from memory; no web access in this run): 打开 Play 商店 → 点按右上角的个人资料图标 → Play 保护机制 → 设置图标 → 关闭"使用 Play 保护机制扫描应用". 未安装应用 is the AOSP PackageInstaller failure text. 受限设置 matches the system dialog title the app also uses.
- **Links and tables.** Every URL is absolute and byte-identical to English (the checker confirms this). Link text reads naturally (点击此处下载最新版本 / 最新 APK, Discord 服务器, 致谢部分, LICENSE). Both tables have translated headers (语言 / 本地名称 / 代码). The native-name and code columns are untouched. The Language column uses standard Mainland names (韩语, 他加禄语, 南非荷兰语, 海地克里奥尔语, …). The raw-source column padding is misaligned because CJK characters are double-width. That only shows in the raw file and GitHub renders the tables fine, so it is not flagged.
- **Pointer section.** 致谢与许可证 points to the English Credits anchor and states GPL 3.0 with the LICENSE link. It is accurate and natural.
- **Typography.**
  - Pangu spacing was checked by script over all prose. There is exactly one space at every Han and Latin/digit boundary, including bold spans (点按 **Play 保护机制**, 打开 **Play 商店**) and links (前往 [deepl.com/…] 申请). There are no spaces before full-width punctuation and no 汉␠汉 gaps.
  - Punctuation is full-width throughout: ：，。（）？！ and “” for 安装 / 未安装应用 / 受限设置. There are no ASCII quotes and no em dashes (the English dashes became ，).
  - The full-width slash in 假名注音／拼音模式 matches the app's 单词／词头.
  - The → separators keep the app's spaced style (设置 → 无障碍 → …).
  - Simplified only: the only Traditional characters are in the native-name column (繁體中文, 日本語), which is correct.
- **Markdown rendering.** Every `**…**` span was checked against the CommonMark flanking rules. Each opener and closer is valid next to CJK characters and full-width punctuation, including 三点（**⋮**）按钮 and **设置图标**（齿轮）, so none will render as literal asterisks. The bare Ko-fi URL autolinks because it has a space on both sides. Keep it that way: a "：https://…" rewrite would stop GitHub's extended autolink from firing (it needs whitespace or `(` before the URL), and a full-width ，right after the URL would be swallowed into it.

## Mainland Android and Play wording appendix

- **安装未知应用.** This is AOSP zh-CN's label for "Install unknown apps", so Pixel and other near-stock devices show it. Xiaomi HyperOS/MIUI keeps the same phrase (from memory, unverified). Huawei/Honor builds word it around 外部来源应用 (also from memory). The README can only follow the English path, so this is not a finding. If the source ever names the per-app switch, the AOSP zh-CN label is 允许来自此来源的应用.
- **Play 保护机制 / Play 商店.** This is Google's own Simplified Chinese branding (the full name is Google Play 保护机制), and the README uses it consistently in the warning, the steps and the closing line. A Mainland user on a GMS device sees exactly these labels.
- **Beyond this review's scope, for the source author.** Most Mainland-market phones ship without Google Play, so the "Won't install?" section reaches only users on imported or global ROMs. Those users more often hit their OEM's own installer guard (for example a 纯净模式 setting on Huawei and Xiaomi builds; from memory, unverified). Discord, and the default Lingva path (it proxies Google Translate), are also typically unreachable from the Mainland without a VPN. Neither is a translation defect: the translation correctly does not add content the English lacks.

## Disposition (2026-10-06)

Applied 8 of 9: 另行下载, the two trailing 。, 朗读音频, 保留截取到的句子, fullwidth （） in the
table (fixed in `scripts/readme_lang_tables.java` for every ja/zh locale, not only here),
默认使用 Lingva 进行翻译, 单独列出 / 再调整顺序, 后端 URL. Not applied: the video link text keeps
"Persona 3 Reload" in Latin, as the translation brief lists it with the brand names and
the link text is replaced by GitHub's video player on render.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] zh-rCN -> readme/README.zh-CN.md`, no warnings; also PASS with `--require-header`). **No 🛑 issues.**

No findings.

Clean areas: The four steps are in the English order with nothing added, dropped or softened: 点按一次, 灰色的开关, closing the dialog, 如有提示, and the condition as 完成第 1 步后才会出现此选项. The register is 你 with bare imperatives, as in the rest of the file. A script checked the section for Pangu spacing (including 第 1 步 and the spaced ⋮), “ ” only, full-width punctuation and GB2312-only Han. The bold spans are the English four, and every `**` flanks correctly. The labels were read in AOSP android16-qpr2 on disk and in Android 13 through LineageOS 20's `values-zh-rCN`, which carries AOSP 13's translations. 应用信息, 允许受限制的设置 and step 4's 无障碍设置 (`accessibility_settings_title`) are identical on both versions; 应用 and 受限制的设置 were read on 16 only. All of them agree with the app's `a11y_restricted_settings_addendum`. Checked and kept: 设置 → 无障碍 is not Android 16's `accessibility_settings` (无障碍功能). It is, however, Android 13's exact label (无障碍) and a prefix of the newer one, and it is the app's own path in `accessibility_dialog_message` and `overlay_icon_a11y_required_message`. 无障碍功能 would be exact only on newer versions and stop being exact on 13, the Thor's version. This supersedes the 2026-10-06 lines above that call 允许受限设置 / 受限设置 the OS labels. AOSP says 允许受限制的设置 on 13 and 16, and 受限制的设置 on 16. The keys those lines cite (`restricted_settings_title`, `restricted_settings_message`) are deleted.

### Disposition (2026-10-08)

No findings; nothing to apply.
