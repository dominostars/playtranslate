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

---

# Delta review — 2026-07-14 sync (174 keys)

Scope: the 170 newly translated + 4 English-changed keys (History screen, Advanced LLM
configuration + prompt editor, in-app updater, game-audio trim, translation-service
management, single-app capture, 38 `misc_*` dictionary chips).

**Mechanical layer verified programmatically — no 🛑.** All 174 keys present; every
`%n$s`/`%d` placeholder matches EN; all `<xliff:g>` spans byte-identical to EN (inner
content + `id` + `example`); `\n` preserved in `floating_menu_capture_screen`; the bare
Latin keyword tokens `{text} {strings} {N} {source} {source_code} {target} {target_code}`
are byte-identical in all six strings that carry them in running prose; zero raw `'`/`"`;
`settings_yomitan_count_summary` is correctly `other`-only (zh CLDR); `name=` untouched.
The three hits in the automated diff are all expected: the zh plural collapses EN's
`one`+`other` to `other`, `Download &amp; install` correctly becomes 下载并安装 (no
ampersand), and the `\n` escape trips the Han/Latin adjacency scan.

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `llm_prompt_row_system_subtitle`, `llm_prompt_advisory_too_long` | ⚠️ | 发送给云端和**本地** LLM 翻译器…；**本地模型**可能会变慢… | 发送给云端和**设备端** LLM 翻译器…；**设备端模型**可能会变慢… | **本地 is already taken on this exact page.** The committed `llm_backend_base_url_invalid` (line 701) reads “http:// 仅允许用于**本地**或局域网地址” — there 本地 means *localhost/LAN*. A Custom provider legitimately **is** a self-hosted LLM at a local URL (LM Studio), so “本地 LLM 翻译器” is genuinely ambiguous between *on-device (in-app MNN)* and *the server on my PC* — a distinction the sentence exists to draw. 设备端 is unambiguous and gives a clean parallel 云端 ↔ 设备端 (端侧 also works). EN’s own on-device/offline split is fine to mirror; it is 本地 specifically that collides. |
| `misc_honorific`, `misc_humble` | ⚠️ | 尊敬语 / 谦让语 | **敬辞** / **谦辞** | These are Chinese renderings of the *Japanese* grammar terms 尊敬語/謙譲語, not Chinese lexicographic labels. 现代汉语词典 marks these senses 〈敬〉/〈谦〉 → 敬辞/谦辞. The chips also render on **Chinese** source words (zh is a supported source language), where 敬辞/谦辞 is what a dictionary actually says; and both are 1 char shorter, which the width-constrained chip wants. `misc_polite` 礼貌语 can stay — the cluster remains three-way distinct. Counter-argument for the record: for a JA learner reading a Chinese UI, 尊敬语/谦让语 are the textbook terms, and the EN comments do cite sonkeigo/kenjougo. Not wrong, but not the canonical label. |
| `misc_obsolete` | ⚠️ | 废弃 | **废语** (or 已废) | 废弃 is a verb — “to discard / abandon” (废弃的工厂). As a bare chip it reads like *deprecated equipment*, not a dictionary register label. 废语 is the lexicographic term, is 2 chars, and lands parallel to `misc_archaic` 古语 — making the obsolescence cluster read as one family: 古语 / 废语 / 过时 / 历史词. |
| `audio_source_game_enable_hint` | ⚠️ | **未在录制**——开启后即可录制游戏音频，供以后的卡片使用 | **当前未录制**——开启后即可录制游戏音频，供以后的卡片使用 | 未在 + verb is a formal-written construction (未在规定期限内…) and reads stiff/MT-ish as a negative progressive in a casual switch subtitle; natural Chinese negates 正在录制 as 当前未录制 / 尚未录制. Rest of the string is good — 供以后的卡片使用 correctly preserves EN’s “future cards, not the one being edited” nuance. |
| `update_error_signature` | ⚠️ | 此更新**无法覆盖当前版本安装**。请从 GitHub 手动安装。 | **无法在当前版本上覆盖安装此更新。**请从 GitHub 手动安装。 | 覆盖安装 is a fixed compound (“install over”); splitting it as 覆盖…安装 leaves the sentence garden-pathing between “cannot overwrite the current version, [then] install” and “cannot overwrite the current version’s install”. Keeping 覆盖安装 intact fixes it. |
| `misc_offensive`, `misc_nonstandard` | 💬 | 冒犯 / 非标准 | 冒犯语 / 不规范 | 冒犯 is a transitive verb (“to offend”); as a standalone chip 冒犯语 reads as a label. 不规范 is the lexicographic term for a nonstandard form (非标准 is the spec/technical sense). Both are legible as-is — and 非标准 does buy a nice parallel with 非正式 — so this is optional polish, not a defect. |
| `stream_kind_prompt_message` | 💬 | 共享**单个**应用与共享整个屏幕时… | 共享**一个**应用与共享整个屏幕时… | The dialog’s own buttons are 共享一个应用 / 共享整个屏幕 (AOSP wording, correctly used). The body matches the whole-screen button verbatim but says 单个 for the other — so one half cross-references the button and the other doesn’t. Aligning to 一个 lets the user map prose → button on both. |
| `update_unknown_sources_message` | 💬 | …请在即将打开的设置页面中允许 PlayTranslate 安装应用更新。 | …请在即将打开的设置页面中开启“**允许来自此来源的应用**”。 | Android zh-CN calls the screen 安装未知应用 and the per-app switch **允许来自此来源的应用**. Naming the switch is the one place exact system wording pays off. **Note this is an EN-source question, not a zh defect** — the English also declines to name the toggle, so this should be decided for all 12 locales at once (or not at all). |
| `probe_initializing` | 💬 | 正在初始化… | 初始化… | Consistent with the file’s 正在X… family (正在验证…/正在查询…/正在加载…), so keeping it is defensible. Flagged only because the brief calls it a truncation risk: the chip sits beside a checker swatch, and 5 Han glyphs + ellipsis is the widest thing on it. 初始化… drops 2 glyphs with no loss. |
| `settings_debug_log_trace` | 💬 | 记录翻译日志**追踪** | 记录翻译日志**轨迹** | 追踪 is the verb “to trace”; as the object of 记录 it noun-piles. 轨迹 (or 跟踪记录) reads as a thing that gets recorded. Debug-only string, lowest priority; the 记录X pattern matches the committed `settings_debug_log_pinhole`. |
| `misc_manga_slang`, `misc_euphemistic` | 💬 | 漫画用语 / 委婉语 | 漫画俚语 / 婉辞 | Optional. `misc_slang` is 俚语, so “manga **slang**” → 漫画用语 quietly drops the slang nuance (though 网络用语 for internet slang is genuinely the natural Chinese term, so the family is not uniform in EN either). 婉辞 is the 2-char lexicographic label vs 3-char 委婉语. Both current forms are correct and clear — listed for completeness only. |

## The 38 `misc_*` chips — cluster audit

All 38 labels are **distinct** (no two chips share a string), and every one is a real
Chinese term rather than a gloss of the English. Lengths: 20 × 2 chars, 10 × 3, 8 × 4
(the 4s are 仅用假名 / 仅用汉字 / 四字成语 / 女性用语 / 男性用语 / 网络用语 / 漫画用语 /
诗歌用语 — all inherently multi-concept, and shorter than their English source). The
sibling `pos_*` family tops out at 3 (形容词/助动词/缩略形), so the misc set runs one glyph
wider at the extreme; acceptable, and no candidate for shortening is both shorter *and*
as clear.

The four must-stay-distinguishable clusters:

| cluster | rendered | verdict |
|---|---|---|
| offensiveness | 贬义 · 冒犯 · 粗俗 · 蔑称 | **distinct.** 贬义 (derogatory *sense*) vs 蔑称 (a contemptuous *appellation*) vs 粗俗 (crude) vs 冒犯 (offensive) all separate cleanly. Only 冒犯’s verb form is a nit (above). |
| obsolescence | 古语 · 废弃 · 过时 · 历史词 | **distinct**, but 废弃 is off-register (above). 古语 / 过时 / 历史词 are all canonical. |
| informality | 口语 · 非正式 · 亲昵 · 俚语 | **clean — no findings.** All four are canonical lexicographic labels and mutually unmistakable. Note the translator correctly used 俚语 for slang and did **not** reach for 俗语 (which means *proverb/common saying*, not slang) — a trap this set walks straight past. |
| honorifics | 尊敬语 · 谦让语 · 礼貌语 | **distinct**, but the first two are JA-grammar calques (above). |

Correct per the glossary’s explicit hard constraints: `misc_yojijukugo` = **四字成语**
(exactly the prescribed zh term, not romanized); `misc_kana_only` / `misc_kanji_only` =
仅用假名 / 仅用汉字 (kana and kanji kept as the established terms). Also canonical and
worth naming: 拟声词, 贬义, 方言, 口语, 书面语, 惯用语, 比喻, 罕用, 儿语, 爱称, 新词,
诙谐, 敏感, 讽刺 (讽刺 is clearer here than the rhetorical 反语), 蔑称, 历史词.

## Clean areas (delta) — checked, no findings

- **移除 / 删除 / 清空 stay perfectly distinct**, which is the glossary’s headline risk.
  Services are **移除**d (`tr_service_delete_cd` 移除服务, `tr_service_remove_confirm`,
  `tr_service_remove_title_fmt`), history entries and models are **删除**d
  (`history_action_delete`, `history_delete_confirm_title`, `settings_ocr_disable_delete`),
  and clear-all is **清空** (`history_clear_menu`, `history_clear_confirm_title`).
  `tr_service_remove_message` even carries **both** in one sentence exactly as EN does:
  “将该服务从列表中**移除**，并**删除**已保存的 API 密钥”. Note the translator was not
  tricked by the key *name* `tr_service_delete_cd` (EN: “Remove service”) into writing 删除.
- **Homograph trap avoided.** `update_error_downgrade` = 下载的版本**不比**已安装的版本**新**。
  The 不比…新 negative comparative is exactly right, and the 更新 = *update* / *newer*
  ambiguity that would invert this sentence never arises.
- **The Chinese-expanding-placeholder spacing rule is now RIGHT** — and this delta is the
  first batch to get it right. `hotkey_show_hint_title` (长按以显示%1$s),
  `hotkey_auto_hint_title`, `hotkey_auto_hint_dialog_title` all omit the space, because
  `%1$s` fills with 假名/拼音. That is precisely the systematic defect the full review
  filed against the committed file (`status_no_text` et al.). Every Latin/digit-filling
  placeholder in the delta correctly *keeps* its spaces (`由 %1$s 识别`, `下载大小：%1$s`,
  `已选 %1$s 秒`, `还需 %1$s`, `已导入 %d 部词典`). Zero regressions.
- **Pangu / punctuation: programmatically clean.** Zero half-width `,.?!:;` after Han;
  zero spaces before full-width punctuation; zero missing Han↔Latin spaces (`选择 OCR 工具`,
  `LLM 高级配置`, `输入你的后端 URL`, `从 GitHub 获取更新`); full-width （）：？ throughout;
  no double spaces. 破折号 —— used natively in 5 delta strings, correct.
- **`ocr_source_label` mirrors `translation_source_label` structurally**, per the hard
  constraint: committed 由 %1$s **翻译** → delta 由 %1$s **识别**. It also reuses the app’s
  own OCR verb (识别, cf. `lang_setup_requires_64bit_msg` 文字识别) rather than calquing
  “scanned” as 扫描. This is the single best-executed string in the delta.
- **“Captured” = 截取, one verb, no second one introduced.** `history_toggle_subtitle`,
  `settings_cell_history_summary_on/off`, `history_empty_off`, `error_capture_blocked_secure`,
  `error_single_app_not_fullscreen` all use 截取, matching the committed 屏幕截取 /
  截取游戏画面. And `floating_menu_capture_screen` = 截取\n屏幕 is a byte-parallel of the
  committed `floating_menu_btn_capture_region` = 截取\n区域 — same two-line shape, fits.
- **Terminology reuses the committed file rather than inventing.** 提供商
  (`llm_backend_provider_label`) matches `tr_service_order_footer`; 翻译服务 matches
  `settings_cell_translation_services`; 密钥 matches the whole API-key family; 查询用量
  matches the committed `tr_service_status_loading` (正在查询用量…); 查询 for lookup matches
  查询单词/词典查询; 开/关 in `settings_cell_history_summary_*` matches
  `capture_lifecycle_state_on/off`; the ` · ` separator has six committed precedents;
  按流量计费的网络 in `update_dialog_metered_note` is the exact params-doc metered term;
  文字转语音 in `game_audio_trim_use_tts` matches `settings_cell_tts`; 后备方案 in
  `llm_status_low_memory_badge` matches `tr_service_offline_footer`. `settings_ocr_disable_manga_msg`
  reuses the committed `bergamot_disable_message` phrasing almost verbatim (保留已下载的模型，
  …还是删除它以释放空间), and `settings_ocr_disable_delete` is byte-identical to the committed
  `bergamot_disable_delete` (删除模型).
- **One noun for “prompt”: 提示词**, across all 20 `llm_prompt_*` keys, never 请求/查询.
  “keyword” = 关键词, kept distinct. Sibling dialog buttons are uniformly verb-form
  (放弃 / 仍然保存 / 重置 / 保留模型 / 删除模型).
- **Measure words / plurals.** `settings_yomitan_count_summary` is `other`-only with 部
  (已导入 %d 部词典) — matching the committed `yomitan_import_summary_count` (%2$d 部词典中的
  %1$d 部). `tr_service_status_usage_today_fmt` = 今日：%1$s **个** token — 个 is the correct
  and standard classifier for tokens in Chinese LLM copy. `update_error_no_space` needs no
  classifier for a byte size and uses none (还需 230 MB). All three read correctly with a real
  value dropped in.
- **Register: casual 你 throughout, zero 您** (programmatically confirmed across all 174).
  Simplified only; no Traditional characters.
- **The deliberate decisions were respected.** `stream_kind_share_one_app` / `_entire_screen`
  = 共享一个应用 / 共享整个屏幕, which is AOSP SystemUI’s own zh-CN wording (not a fresh
  translation). `llm_prompt_kw_source_desc` / `_target_desc` keep **Japanese** / **English**
  in Latin, correctly, because those are the literal runtime expansions.
  `llm_status_low_memory_badge` left untouched with its native ——.
- **The EN `<!-- comment -->` was actually read**: `settings_ocr_use_manga_subtitle` renders
  “Not recommended for auto” as 不建议用于**自动翻译** — the comment’s clarification, not the
  literal “auto”. `game_audio_trim_save`/`_play` keep EN’s *selection* noun as one term
  (使用/播放**所选片段**). `game_audio_trim_no_audio` = 不使用音频 correctly reads as the
  *action* (send the card with no audio), not a status.
- **`update_dialog_view_release`** — the prior full review’s 💬 (查看版本 “view version
  number”) is resolved by this sync: EN changed to “View release notes” and zh now reads
  查看更新说明. Closed.

**Net: 0 🛑, 0 ❌, 5 ⚠️, 6 💬.** No mistranslation, no inverted logic, no broken
cross-reference, no mechanical defect anywhere in the 174. The ⚠️s are one real
terminology collision (本地), two lexicographic-label register calls in the chip set, and
two prose-naturalness slips. This is the strongest of the deltas reviewed against this
file so far — notably, it *fixes* the placeholder-spacing class of bug rather than
extending it. **Ship after the five ⚠️s.**

---

# Delta review round 2 — 2026-07-14

Fresh independent re-derivation of all 174 delta keys after round 1's twelve corrections
were applied. Primary target: **regressions introduced by the fixes.** Round-1 findings
were read only to know *what changed*; every string below was re-derived from the English
source + its `<!-- comment -->`.

**Mechanical layer re-verified programmatically — no 🛑.** All 174 keys present; every
`%n$s`/`%d` matches EN; all `<xliff:g>` spans byte-identical (inner + `id` + `example`);
`{text} {strings} {N} {source} {source_code} {target} {target_code}` byte-identical in all
six prose strings that carry them; `\n` preserved in `floating_menu_capture_screen`; zero
raw `'`/`"`; zero half-width `()`; zero half-width `,.?!:;` after Han; zero spaces before
full-width punctuation; no double spaces; `settings_yomitan_count_summary` correctly
`other`-only; file parses as valid XML; **zero 您** and **zero Traditional characters**
across all 174. The three automated-diff hits are the expected artifacts (zh plural
collapse, `Download &amp; install` → 下载并安装, the `\n` escape).

## Findings (delta, round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `llm_prompt_advisory_too_long` | 💬 | 此提示词很长。设备端模型可能会变慢或**耗尽上下文**。 | …可能会变慢或**耗尽上下文窗口**。（或 **超出上下文长度**） | Round 1 fixed the *first* half of this sentence (本地→设备端) and left the second. 上下文 on its own is not obviously a consumable, so bare 耗尽上下文 ("exhaust the context") reads a beat oddly; Chinese LLM copy consumes the 窗口 or exceeds the 长度. Not a regression — a round-1 miss. Clear as-is, so 💬 only. |
| `settings_llm_context_subtitle` | 💬 | …让代词和**名称**的翻译更加一致。 | …让代词和**专有名词**的翻译更加一致。 | 名称 connotes the designation *of a thing* (产品名称/公司名称). What this feature actually stabilises across lines is character names, place names, item names — 专有名词 is the translation-industry term and covers all three. EN's bare "names" is the ambiguous one; zh can be precise for free. |
| `history_clear_confirm_message` | 💬 | 所有已保存的**文本**都将从此设备上删除。 | 所有已保存的**记录**都将从此设备上删除。 | The screen's unit of content is a 记录 (`history_delete_confirm_title` 删除这条**记录**？), but the clear-all body switches to the mass noun 文本, weakening EN's countable "Every saved **line**". Optional: 文本 does tie back to 文本历史记录 (`history_toggle_title`), so the current form is defensible. |

## Regression audit — the twelve strings round 1 changed

Each re-derived from scratch, then checked for a new collision and for an orphaned sibling.

| changed string | verdict |
|---|---|
| `llm_prompt_row_system_subtitle`, `llm_prompt_advisory_too_long` (本地 → 设备端) | **Correct, and complete.** 本地 now appears in **zero** delta strings; its only occurrence file-wide is the committed `llm_backend_base_url_invalid`, where it means *localhost/LAN* — the collision is fully resolved. No orphan: those two were the only delta strings carrying EN "on-device". |
| `misc_honorific` / `misc_humble` (→ 敬辞 / 谦辞) | **Correct.** These are the 现代汉语词典 labels 〈敬〉/〈谦〉, and they are what a *Chinese*-source dictionary entry actually says — which matters, since zh is a supported source language and these chips render on Chinese words. Still three-way distinct against `misc_polite` 礼貌语. |
| `misc_obsolete` (→ 废语) | **Correct.** 废弃 was a verb ("to discard"); 废语 is a register label and lands parallel to `misc_archaic` 古语, so the obsolescence cluster now reads as one family. |
| `misc_offensive` (→ 冒犯语) | **Correct.** 冒犯 alone was a transitive verb. The chips do not truncate, so the extra glyph costs nothing. |
| `misc_nonstandard` (→ 不规范) | **Correct.** 非标准 is the spec/technical sense; 不规范 is the lexicographic one. The lost 非正式/非标准 rhyme is not worth the wrong sense. |
| `audio_source_game_enable_hint` (→ 当前未录制) | **Correct.** Reads as a natural negative progressive; 供以后的卡片使用 still carries EN's "future cards, not this one" nuance. Consistent with the block's 录制 (`anki_game_audio_row_title`, `_permission_denied`). |
| `update_error_signature` (→ 无法在当前版本上覆盖安装此更新。) | **Correct.** 覆盖安装 is intact as one compound, and the garden-path is gone. |
| `stream_kind_prompt_message` (单个 → 一个) | **Correct.** Both halves of the body now byte-match their buttons (共享一个应用 / 共享整个屏幕). The translator's 与 (rather than 和) is load-bearing: "A**与**B…并不相同" forces the contrastive reading, so 一个 does not garden-path into "share one app **and** the whole screen". 单个 survives only in two committed, unrelated strings (`anki_content_frequency_harmonic_desc`, `yomitan_single_dict_title`) where it is correct. |
| `error_capture_blocked_secure` (→ 被截取的应用) | **Correct, and better than EN.** EN's "this app" is genuinely ambiguous with PlayTranslate itself; the `<!-- comment -->` says "the captured app", which is what zh now renders. It also matches its sibling `error_single_app_not_fullscreen` (被截取的应用未占满屏幕). |
| `settings_debug_log_trace` (→ 记录翻译日志跟踪信息) | **Correct — and it improved on round 1's own suggestion.** 跟踪 (not 轨迹) is the file's established noun for *trace*, anchored by the committed `crash_dialog_message` 堆栈跟踪 ("stack trace"); 信息 nominalises it so 记录…跟踪 can't be misread as two verbs. Length matches the sibling debug rows (记录实时模式针孔指标). |

## The 38 `misc_*` chips — post-fix cluster re-audit

**All 38 labels are distinct** (verified programmatically against the string set). Since
`renderMisc` ends in `.distinct()`, this is the load-bearing property, and none of round
1's five label edits collapsed a pair. Lengths are now 21 × 2 chars, 9 × 3, 8 × 4 — the
edits shifted two labels shorter (敬辞/谦辞) and one longer (冒犯语), net-neutral. No label
contains ` · ` or `/`, so neither the misc join (`" · "`) nor the inflection join (`", "`)
can be spoofed.

| cluster | rendered | verdict |
|---|---|---|
| offensiveness | 贬义 · 冒犯语 · 粗俗 · 蔑称 (+ 敏感) | **distinct and separable.** 贬义 (derogatory *sense*) / 冒犯语 (offensive *language*) / 粗俗 (crude) / 蔑称 (contemptuous *appellation*) / 敏感 (sensitive) — five labels, five concepts, no overlap. |
| obsolescence | 古语 · 废语 · 过时 · 历史词 | **distinct and separable.** Now one coherent family; 罕用 (rare) sits adjacent without colliding. |
| informality | 口语 · 非正式 · 亲昵 · 俚语 | **clean.** Unchanged by round 1 and still the best-executed cluster (俚语 for slang, correctly *not* 俗语). 不规范 joins the neighbourhood without colliding with 非正式. |
| honorifics | 敬辞 · 谦辞 · 礼貌语 | **distinct and separable.** 敬辞 elevates the addressee, 谦辞 lowers the speaker, 礼貌语 is the polite register — exactly the 尊敬語/謙譲語/丁寧語 three-way. The suffix set is now mixed (辞/语), but Chinese dictionary labels are natively heterogeneous (〈书〉〈口〉〈敬〉〈谦〉), so this is not a defect. |

## Clean areas (delta, round 2) — checked, no findings

- **云端 ↔ 设备端 reads unambiguously.** The delta now carries a five-way term system with
  **zero overlap**, mirroring EN's own five: cloud = **云端** (`llm_prompt_row_system_subtitle`,
  `llm_prompt_row_batch_subtitle`), on-device = **设备端** (the two fixed strings), online =
  **在线** (`settings_llm_context_subtitle`, `tr_service_add_online`), offline = **离线**
  (committed only — zero delta hits), localhost/LAN = **本地** (committed only — zero delta
  hits). The 端…端 suffix parallel in 发送给**云端**和**设备端** LLM 翻译器 makes the contrast
  crisp in a way 本地 never could. The mapping is one-EN-term-to-one-zh-term throughout.
- **移除 / 删除 / 清空 remain three distinct verbs**, and the committed 清除 is a correctly
  scoped fourth. 移除 = take out of a container (`tr_service_remove_*`, `tr_service_delete_cd`
  移除服务 — the key *name* says "delete" and did not mislead the translator); 删除 = destroy
  (`history_action_delete`, `history_delete_confirm_title`, `settings_ocr_disable_delete`);
  清空 = empty a collection (`history_clear_menu`, `history_clear_confirm_title`); 清除 =
  clear an input field (committed `btn_clear`, `lang_search_clear_cd`, `dictionary_clear_query`).
  `tr_service_remove_message` still carries 移除 **and** 删除 in one sentence exactly as EN does.
  EN "Clear" maps to two zh verbs — but that is correct differentiation (you 清空 a list, you
  清除 a box), not a split.
- **Measure words — every counted noun re-read with a real value.** 部 for dictionaries
  (`settings_yomitan_count_summary`: 已导入 3 部词典; and correct at count = 1); 个 for tokens
  (`tr_service_status_usage_today_fmt`: 今日：12,345 个 token) and for phrases
  (`llm_prompt_advisory_missing_count`: 多少个短语); 条 for history entries
  (`history_delete_confirm_title` 这条记录, `history_empty_none` 逐条出现); 行 for lines of
  context; 秒 / MB used bare as units, correctly taking no classifier
  (`game_audio_trim_duration`, `update_error_no_space`). No missing or wrong classifier.
- **Placeholder spacing survived the fixes intact.** Re-verified all 20 placeholder-bearing
  delta strings with real values dropped in. `hint_label` resolves to **假名注音** or **拼音**
  — both Chinese — so `hotkey_show_hint_title` (长按以显示假名注音), `hotkey_auto_hint_title`
  and `hotkey_auto_hint_dialog_title` are right to omit the space, and the last is
  byte-identical to the committed `live_mode_auto_with_hint` (自动%1$s). Every Latin/digit
  placeholder correctly keeps its spaces (由 PaddleOCR 识别, 下载大小：128 MB, 还需 230 MB,
  移除 OpenAI？, 密钥 ••••4f2a). No space after full-width ：or ，(开启该权限时，Android 可能…),
  none before ）or ？. Zero regressions.
- **Full-width punctuation — programmatically clean.** No half-width `()`, no half-width
  `,.?!:;` after Han. Full-width （）：？，。used throughout; 破折号 —— native and correct in the
  five strings that carry EN's em dash (the locale exemption applies). The half-width `/` in
  点按以启动/停止… matches the committed `inflection_passive` 被动/可能.
- **Truncation — none, including the one row worth measuring.** `activity_game_audio_trim.xml`
  puts three buttons in a single horizontal row; zh (改用文字转语音 / 不使用音频 / 使用所选片段
  = 18 Han glyphs) is **narrower** than the EN it already ships with ("Use TTS instead" /
  "No audio" / "Use selection"). Same for the hotkey rows (点按以启动/停止自动假名注音 < "Tap to
  start/stop Auto Furigana"). The misc chips wrap rather than ellipsize, so length is moot there.
- **Register unchanged by the fixes: casual 你, zero 您, zero Traditional characters** across
  all 174 (programmatic). 开/关 in `settings_cell_history_summary_*` still matches the
  committed `capture_lifecycle_state_on/off`.
- **One noun per concept, still.** 提示词 across all 20 `llm_prompt_*` keys (never 请求/查询);
  关键词 for keyword; 请求 for the wrapped request; 上下文 for context; 短语 for phrase (vs 文本
  for text, mirroring EN's own phrases/text split); 截取 for capture, 截图 for screenshot
  (matching committed `anki_screenshot_remove_content_description`); 查询 for both word lookup
  and usage check (two EN verbs, one zh verb — but in unconfusable contexts, and 查询用量 matches
  the committed `tr_service_status_loading`).
- **No logic inversions.** Re-read every negative/conditional: `update_error_downgrade`
  (不比…新), `settings_ocr_use_manga_subtitle` (不建议用于自动翻译), `settings_llm_context_subtitle`
  (仅限在线), `audio_source_game_enable_hint` (供**以后**的卡片使用), `game_audio_trim_no_audio`
  (不使用音频 — the action, not a status), `history_empty_off`, `update_unknown_sources_message`
  (已下载的更新会被保留). All correct.
- **Out-of-scope FYI, no action:** the delta's 账号 (`service_account_required*`,
  `service_no_account_required`) is the right word for a sign-up account, but the *committed*
  `crash_dialog_message` says 账户信息 for the same EN "account". Committed and outside the
  delta, so not filed — noted only so it isn't rediscovered as a delta defect next round.

**Net round 2: 0 🛑, 0 ❌, 0 ⚠️, 3 💬.** All twelve round-1 fixes are correct; none
introduced a collision, a broken agreement, or an orphaned sibling, and one
(`settings_debug_log_trace`) improved on the suggestion it was given. The 38 misc labels
remain mutually distinct — the property `.distinct()` depends on — and all four clusters
stay separable. The three 💬s are optional polish on strings that are already clear.
**SHIP.**

## Delta review — 2026-07-25 sync (95 keys)

Scope: the 89 keys `scripts/l10n_diff.py` reported MISSING and the 6 it reported
MODIFIED against the `l10n-sync` baseline (`54809b6c`) — the camera tool, the file-import
tool, the slow-OCR rescue prompt, the PaddleOCR accurate/fast tier split, the manual
update check, the History capture + live-session cards, the accessibility-stuck alert,
the audio-recording row, and the capture standby state. Two orphans
(`settings_footer_version`, `settings_ocr_footer`) were deleted.

Two of the six MODIFIED keys — `capture_lifecycle_on_subtitle` and
`capture_lifecycle_off_subtitle` — were already carrying the current English meaning in
every locale; they flag only because the baseline tag has not advanced since 2026-07-14.
No change was needed. The other four (`game_screen_controls_title`,
`settings_ocr_use_manga_subtitle`, `yomitan_page_description`, `yomitan_importing_message`)
were genuinely stale and were re-translated.

Mechanical layer verified programmatically over the delta: every translatable EN key
present and no extras; placeholder multisets identical to EN; all `<xliff:g>` spans
byte-identical to EN; `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match; no unescaped
quotes; `<plurals>` categories exactly other. `./gradlew :app:processDebugResources`
is green. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | was | now | why |
|---|---|---|---|---|
| `settings_ocr_note_mlkit` | ⚠️ | "即使画面文字很多也很快" | "即使画面文字很多也迅速" | The English comment forbids reusing the literal Fast tier label; the first pass reused 快, the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

Pangu spacing audited on every new string that mixes scripts — 「PlayTranslate 需要相机权限」, 「此 PDF 有密码保护」, 「PDF 和漫画压缩包」, 「已导入 <xliff:g>%d</xliff:g> 部词典」, 「<xliff:g>%d</xliff:g> 行」 — and deliberately suppressed around the language placeholder in `image_import_no_text` and `camera_snapshot_no_text`, which fill with Chinese (未在此图像中检测到%1$s文字), matching the committed `status_no_text`. The em dash in `camera_no_text_hint` is written —— per the locale's rule. 你 throughout, no 您. 自启动 and 无限制 in `a11y_stuck_message_xiaomi` are MIUI's own Chinese labels, so the setting is findable. 截取 kept as the capture verb (matching 截取与叠加层 and 正在截取画面); 快照 for the camera freeze-frame stays distinct from 截图, which `anki_group_screenshot` owns. 引擎 (engine) / 工具 (tool) / 模型 (model) stay three separate nouns — all three meet in `settings_ocr_delete_camera_import_note`. Plurals `other` only, with 部 / 行 measure words.

**Render constraints read, not guessed.** `capture_show_on_screen` renders through
`Text.PT.GroupHeader` (`textAllCaps`, `letterSpacing` 0.12) at 9sp in
`section_target.xml`, but the view is `wrap_content` in a row whose sibling label carries
`layout_weight="1"` — the label squeezes, this button never clips, so no accuracy was
traded for brevity. `capture_sliver_expand_hint` is `isSingleLine` but sits `WRAP` and
centred in a screen-wide sheet strip. `camera_region_remove` measures itself
`UNSPECIFIED` before placement (`CameraRegionUi`), so the pill grows to its text.
`image_import_no_text` / `camera_snapshot_no_text` locate the tappable language span by
the invisible FSI/PDI sentinels `markNoTextLanguage` injects, not by substring search, so
word order and a tight prefix are both safe.

### Verdict

**PASS.** One ⚠️ found and fixed, no ❌.

---

## Delta review 2026-08-04 (8 keys: one-tap card toasts, first-field guard, hide-translations toggle, waveform zoom hint)

Independent review — the reviewer did not write these translations. Scope is the eight
keys added by the working diff: `card_words_in_sentence`, `anki_added_sentence_success`,
`anki_added_word_success`, `game_audio_zoom_hint`, `anki_first_field_unmapped`,
`anki_first_field_empty`, `history_hide_translations_toggle_title`,
`history_hide_translations_toggle_subtitle`. The locale's translator agent was killed
before its own self-verification pass, so the mechanical layer was re-run from scratch
here rather than trusted.

Mechanical layer verified programmatically over the delta: all eight keys present, no
duplicates, no extras; every `<xliff:g>` span byte-identical to EN in inner content, `id`
and `example`, and none re-indexed, split or reordered; placeholder multisets identical
to EN (`%1$s` ×1 in both first-field strings, none elsewhere); `<b>`, `\n`, `\{ \}`,
`&lt;/&gt;/&amp;` counts match; no unescaped `'` or `"`; `name="…"` untouched; brand name
Anki left untranslated and outside the translated run. Anchor positions confirmed against
English document order — each new key sits between the same two neighbours it has in
`values/strings.xml` (`word_detail_common` → `card_words_in_sentence`;
`anki_added_no_audio` → `_sentence_success` → `_word_success` → `anki_adding_in_progress`;
`audio_source_game_enable_hint` → `game_audio_zoom_hint` → `game_audio_trim_duration`;
`anki_field_mapping_unconfigured` → `_unmapped` → `_empty` → `anki_models_unavailable`;
`history_capture_image_toggle_subtitle` → the two `history_hide_translations_*`
→ `history_live_session_title`). The one apparent order divergence at
`card_words_in_sentence` is the file's long-standing placement of the whole `pos_*` block
near the end, not a misplacement of the new key. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `game_audio_zoom_hint` | 💬 | 双指张合可显示更多或更少的音频 | 双指张合可查看更多或更少的音频 | The pinch term itself is right — 双指张合 is AOSP/Google's own zh-CN wording, not 捏合 — and the caption fits the cell. Only the verb is a shade off: 显示 makes the app the actor, while Chinese gesture hints put the user there (双指张合可查看更多内容). 显示更多或更少的音频 also tracks the English word-for-word, and "less audio" is looser in Chinese than in English, where the waveform makes it obvious. Optional; the current text is grammatical and clear. |

### Clean areas (delta) — checked, no findings

**The two one-tap toasts pattern-match their family.** `anki_added_sentence_success` /
`anki_added_word_success` reuse `anki_added_no_audio`'s exact tail 已添加到 Anki rather
than inventing a second "added to Anki" phrasing, and they name the card shape with the
same two words the mode switch uses — 句子 from `anki_mode_sentence`, 单词 from
`anki_mode_word` — which is the whole point of these strings (one-tap applies the
remembered mode silently, so the toast is where it becomes visible). 卡片 for "card"
matches `anki_send_failed_title` / `anki_card_type_*`, and stays distinct from 抽认卡,
which `anki_permission_rationale_message` uses for "flashcard".

**笔记 is the right word and is used consistently.** It is Anki/AnkiDroid's own zh-CN term
for a note (against 卡片 for card, 牌组 for deck), so the note-vs-card distinction the two
first-field strings depend on survives: `anki_first_field_empty` keeps 该卡片 / 每张卡片
for the card and 识别笔记 for the note, exactly mirroring English. Both strings use 笔记 —
no drift between them. Worth recording that 笔记 appears nowhere else in this locale,
because the app renders Anki's *note types* as 卡片类型 throughout (mirroring English's own
"card type" choice), so a user meets 笔记 for the first time here. That is inherited from
the English source, not a translation defect, and both strings name Anki as the actor
(Anki 使用第一个字段来识别笔记), which frames 笔记 as Anki's concept rather than the app's.
No change recommended.

**Toast clamp has margin.** `anki_first_field_unmapped` renders 20 Han characters plus
`Anki` and the field name — about 48 half-width columns with the `Key` example, against
English's 52 for the same string. It is *shorter* than the source it was clamped for, so
the Android 12+ two-line ceiling is no tighter here than in English, and no accuracy was
traded for brevity. `anki_first_field_empty` is a full alert, where its 51 characters are
fine.

**Quoting and punctuation follow the file's house style.** The field name is wrapped in
full-width “ ” in both first-field strings — the locale's registered quote pair, and the
same convention already used for inline names in `anki_content_source_pick_title`
(映射“%1$s”), `anki_card_type_basic_no_mapping` (“正面”和“背面”) and
`anki_permission_rationale_message` (点按“继续”). Full-width ，。 throughout; no half-width
punctuation leaked in.

**Pangu spacing audited on every mixed-script string.** 已添加到 Anki and 以便 Anki 识别 take
the single Han↔Latin space; 字段为空。Anki 使用 correctly takes *no* space after the
full-width 。 before the Latin run; and no space is inserted inside “%1$s”, where the
quotes are full-width — correct on both counts. The Han-only strings need none.

**History terminology is the established one, not a second coinage.** 译文 for
"translation" matches `section_translation`, `anki_group_translation`,
`cd_copy_translation` and `cd_toggle_translation_visibility` — and is the right half of
the 译文/翻译 pair, since the toggle hides the translated *text*, not the act. 截取到的 is
the app's committed capture verb in exactly the participle form
`history_toggle_subtitle` (将截取到的句子保存在此设备上) and
`settings_cell_history_summary_on` (截取到的句子记录) already use, so no second capture
verb was introduced. 文字 rather than 文本 is the correct pick of the file's two words for
"text": 文字 is what the OCR reads (`status_no_text` 检测到%1$s文字), which is what a
History row holds. 点按 matches the file's tap verb, and 记录 matches
`history_delete_confirm_title` / `history_clear_confirm_message` for a history entry.

**`card_words_in_sentence` matches the app's own header style.** 句子中的单词 parallels
`anki_group_words_count`'s 卡片内的单词 — the same 「…的单词」 shape — so the baked-in card
header and the editor group header read as one system. The compact 句中单词 (the analogue
of ja 文中の単語) would also work, but breaking the parallel to save two characters is not
worth it on a header with no width constraint; the card CSS's ALL CAPS is a no-op on Han.

**Register and script.** None of the eight strings addresses the user with a pronoun, so
the 你 / never-您 rule is not contacted; no 您 was introduced. Simplified characters only
(单 显 请 为 识 别 笔 记 该 张 须 隐 译 点). Measure word 张 correctly applied to 卡片 in
`anki_first_field_empty`. Grammar re-read with real values substituted for `%1$s` — both
`Key` and a longer free-form name like `Expression (Japanese)` — reads correctly in both
first-field strings, since the placeholder sits inside quotes followed by the classifier
字段 and never carries a bare grammatical attachment.

### Verdict

**PASS.** One 💬 nit, no 🛑 / ❌ / ⚠️. Nothing blocks shipping these eight keys.
