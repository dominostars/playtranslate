# Simplified Chinese (values-zh-rCN) localization review

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| hymt_legal_message | ❌ | 你目前并未在欧盟、英国或韩国境内**居住或所在**。 | 你目前并未**居住于或身处**欧盟、英国或韩国境内。 | "所在" cannot serve as a coordinate predicate with 居住 — clause (1) of the attestation is ungrammatical. Everything else in the legal block is faithful: §5(b) kept, 欧盟/英国/韩国 enumeration kept, "affirm and warrant" rendered with proper force as 声明并保证. Only this grammar slip needs fixing. |
| onboarding_a11y_title | ⚠ | 在其他应用上层显示 | 显示在其他应用的上层 | Stock Android zh-CN names this permission page "显示在其他应用的上层". Match it so users can find the toggle. Same phrase in `mp_overlay_permission_title`, `mp_overlay_permission_message`（"在其他应用上层显示"权限）and `onboarding_a11y_body`（需要在其他应用上层显示）. |
| quick_tile_add_row_title | ⚠ | 添加快捷设置**磁贴** | 添加快捷设置**图块** | AOSP zh-CN calls QS tiles 图块 (e.g. "按住并拖动即可添加图块"); 磁贴 is Windows/OEM vocabulary. Also `settings_hotkeys_tile_add`（添加磁贴 → 添加图块）. 快捷设置 itself is correct. |
| legacy_engines_removed_message | ⚠ | **你旧版的**离线翻译器 | **你的旧版**离线翻译器 / 旧版离线翻译器 | Possessive misplaced; 你旧版的 is not natural Chinese. |
| overlay_mode_option_furigana | ⚠ | 假名 | 振假名 | Terminology split: furigana = 假名 here, in `hint_label_furigana_lower`, `settings_hotkeys_furigana`, `cd_toggle_inline_furigana`（内嵌假名）and `onboarding_welcome_body`（读音指南（假名…）), but = 振假名 in `anki_content_expression_furigana` / `anki_content_sentence_furigana`. 假名 alone means the kana syllabary, and `anki_content_reading`（单词读音（假名））uses it in *that* correct sense — so the same word names two different things. Standardize the ruby-annotation feature on 振假名. |
| status_no_text | ⚠ | 检测到 %1$s 文字 | 检测到%1$s文字 | Systematic: placeholders that expand to *localized Chinese* language names are wrapped in spaces, producing 汉␠汉 spacing at runtime（"检测到 日语 文字"）. Same pattern: `lang_setup_requires_64bit_msg`（%1$s 的文字识别）, `pack_upgrade_progress_format`(_with_bytes)（正在下载 日语…）, `lang_section_offline_models_subtitle`（…英语 的离线翻译）, `anki_section_description`（创建 英语 抽认卡）, `target_pack_migration_title`/`_message`, `custom_region_edit_title`, `tr_service_status_quota_with_reset_fmt`（6月1日 重置）. Inconsistent with the TTS strings, which correctly omit the space（`tts_language_unsupported_with_engine_message` 不支持%2$s, `tts_voices_section_header` %1$s语音）. Keep spaces only where the value is Latin (model names, engine names, byte sizes — those are all correct). |
| accessibility_dialog_message | 💬 | 设置 → 无障碍 → **已安装的应用** | 已下载的应用 | Stock Android's Accessibility screen section is "已下载的应用" (Downloaded apps). The English source also says "Installed apps", so this is faithful — but the nav path is the one place exact system wording pays off. Also `overlay_icon_a11y_required_message`. |
| onboarding_welcome_body | 💬 | 将屏幕上的文字转换为翻译 | 即时翻译屏幕上的文字 | "转换为翻译" is literal MT-flavored phrasing. |
| onboarding_welcome_tagline | 💬 | 畅玩其他语言游戏 | 畅玩外语游戏 | 外语 is the natural word here. |
| lang_setup_preloading_message | 💬 | 请稍候片刻 | 请稍候 / 请稍等片刻 | 稍候 already contains "a moment"; 稍候片刻 is redundant. |
| update_dialog_view_release | 💬 | 查看版本 | 查看新版本 | "查看版本" reads as "view version number"; the button opens the release page. |
| tts_no_engine_dialog_title | 💬 | 无文字转语音 | 无文字转语音引擎 | As a bare dialog title it reads clipped; adding 引擎 matches the body. |
| anki_sort_field_empty | 💬 | 空值会在发送时导致重复拒绝错误 | 空值会在发送时被视为重复而遭拒 | "重复拒绝错误" is an opaque calque of "duplicate-rejection errors". |

Mechanical rules: no violations found — all `<xliff:g>` inner content intact, placeholders present, `<b>`/`\n`/`\{ \}`/`&lt;img&gt;` preserved, full-width quotes used throughout (no unescaped `'`/`"`), plurals are `other`-only, brand names untouched. The Anki "Example:" samples (聞く, ★★★, noun) are correctly left unlocalized. No Traditional characters found.

## Verdicts

- **Register consistency**: clean — casual 你 throughout, zero 您, concise friendly tone (好的 for OK is consistent and fits the register).
- **Terminology consistency**: strong — 设置/翻译/下载/删除/无障碍/牌组/卡片类型/抽认卡/语言包/快捷键/文字转语音/屏幕截取/叠加层/按流量计费的网络 are uniform; one real split (furigana: 假名 vs 振假名).
- **Android-settings wording**: 无障碍, 按流量计费, 快捷设置, 允许受限设置 all match the OS; misses on "显示在其他应用的上层" and QS "图块", plus the 已安装的应用 nav-path nit.
- **Han/Latin spacing**: Latin/number spacing is uniformly correct, including around placeholders and before full-width punctuation; the only defect is extra spaces around placeholders that expand to Chinese language names (inconsistent with the TTS strings, which get it right).
- **Grammar around placeholders**: good — measure words correct (台显示屏, 个牌组, 颗星), byte/RAM compositions read naturally; one grammar error in the legal clause.
- **Truncation risk**: none — bottom bar items are all two characters (自动/暂停/设置/区域), 截取\n区域 fits the two-line button.
- **Legal text**: faithful and conservative — §5(b), the EU/UK/South Korea list, and 声明并保证 all preserved; fix the (1)-clause grammar before shipping.
- **Overall**: **fix-then-ship** — one legal-text grammar error and two Android-wording alignments; everything else is polish.
