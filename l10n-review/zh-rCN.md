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

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR set; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| yomitan_importing_progress | 💬 | 正在导入第 %1$d / %2$d 个… | 正在导入 %1$d / %2$d… | `第 X / Y 个` mixes the ordinal classifier 第…个 ("the Nth") with a current/total slash, which don't pair cleanly; 第 implies a single ordinal, not "current of total". A bare `%1$d / %2$d` (or `第 %1$d 个，共 %2$d 个`) reads more naturally as progress. Placeholders stay positional/byte-identical; the EN noun-omission intent is preserved either way. Minor — current form is still understandable. |

## Clean areas (delta)
- **Pangu spacing — all 29 keys clean.** Every Han↔Latin/number boundary carries exactly one space (`词频列表（JPMN 风格）`, `自定义 URL`, `高亮单词的 ★ 评级`, `本地或局域网地址`); no space before any full-width punctuation (programmatic scan for ` [，。、？！：；）”]` over the new lines: zero hits); no space between Han runs; numeric/`%n$d` placeholders correctly spaced as Latin runs (`第 %1$d / %2$d 个`, `%2$d 部词典中的 %1$d 部`). Brand names against full-width quotes (`Lapis 的“PitchPosition”`) and the em-dash `https://——http://` (no surrounding space, matching the file's `——` convention at lines 277/279) are both correct. The only spacing "hits" in the double-space scan were XML indentation, not content.
- **Terminology reuse — uniform.** 音高重音 (pitch accent) matches `yomitan_category_pitch_accent`/`yomitan_page_description`; 词频 (frequency) matches `yomitan_category_frequency`/`anki_content_frequency`; 词典 (dictionary) and 导入 (import) match the surrounding Yomitan block; 文字转语音 (TTS) and 音频 (audio) match `settings_cell_tts`/`anki_group_audio`; `无结果` is byte-identical to the established `lang_search_no_results`/`dictionary_status_no_results`; `正在加载…` / `无法加载` follow the file-wide loading pattern (`anki_deck_picker_loading`, `word_detail_more_examples_error`); 自定义 URL matches the 自定义 family; 局域网 (LAN) is the correct Simplified term. The 风格 (label) vs 样式格式 (desc) pairing tracks EN's own "JPMN style" vs "styled format" distinction, not a split.
- **Register — casual 你 throughout, zero 您;** concise friendly tone consistent (请使用…, 请仅在…卡片上使用). No formal/informal mixing introduced.
- **Plurals / measure words — both collapsed to a single `other` with the right classifier:** 部 for 词典 in `yomitan_import_summary_count` (`%2$d 部词典中的 %1$d 部`, positionally-reordered placeholders, byte-identical spans), 项 for elided names in `yomitan_import_summary_more` (`+%1$d 项`). Generic 个 in the progress string is acceptable (noun deliberately omitted in EN).
- **Short-label truncation — none.** Anki audio cells (`无结果`, `正在加载…`, `无法加载`), picker title (`音频`), source names (`文字转语音`, `Wikimedia Commons`), and the Advanced header (`高级`) are all short; no overflow risk.
- **The `Example:` / quoted-field-name rule — honored.** `anki_content_pitch_position_desc` renders `示例：0,2` with the `0,2` sample left verbatim (matching the file's `示例：聞く`/`示例：★★★` precedent), and the Anki field names (`“PitchPosition”`, `“PAOverride”`, `“Frequency”`, `“FrequenciesStylized”`, `“FreqSort”`, `“FrequencySort”`) are kept as-is in straight English inside full-width quotes — correctly not flagged.
- **`<xliff:g>` integrity:** all brand spans (Lapis/JPMN) and `%1$d`/`%2$d`/`%1$s` placeholders byte-identical to EN; reordering in `yomitan_import_summary_count` is a legal positional move only.

Net: no 🛑/❌/⚠️ in the delta — one 💬 nit (`yomitan_importing_progress` classifier phrasing). The +29 keys are ship-ready as-is.
