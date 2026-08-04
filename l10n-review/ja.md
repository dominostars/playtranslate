# Japanese (values-ja) localization review

Mechanical pass: clean. All placeholders present, `<xliff:g>` inner content untouched, `<b>`/`\n`/`\{ \}`/`&lt;img&gt;` preserved, no unescaped apostrophes (the file uses 「」 throughout), plurals are `other`-only, brand names intact. No あなた anywhere; 使用する言語 is used for "Your Language" in both `lang_translate_to` and `pack_upgrade_label_target`. The intentionally-unlocalized "Example:" samples (聞く, ★★★, noun) were correctly left alone. No 🛑 findings.

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| offline_backend_row_a11y_fmt / offline_backend_row_a11y_no_speed_fmt | ❌ | 品質：\<xliff quality\> | drop the literal 品質： prefix, e.g. 「\<title\>。\<quality\>。速度：…」 | The quality bucket strings already carry the prefix (品質：低 etc., needed because they double as visible prose labels), so TalkBack composes 「品質：品質：良。」. A11y-only, but genuinely garbled. |
| a11y_out_of_5_stars | ⚠ | 5つ星中 | （5つ星中） | Code composes rating-then-clause: 「品質 4 5つ星中」 — number before 中-clause is backwards in Japanese. Parenthesizing makes the fixed order parse: 「品質 4（5つ星中）」. A11y-only. |
| backend_cooldown_status_fmt + backend_cooldown_retry_at/_on | ⚠ | %1$s · 再試行 15:42 | fmt → 「\<description\> · \<retry_time\>\<retry_word\>」 with retry word に再試行 | 「再試行 15:42」 reads as a dangling label. Reordering the whole xliff blocks is allowed and yields the natural 「15:42に再試行」. |
| accessibility_dialog_message | ⚠ | 上の画面のゲーム画面のスクリーンショット | 下の画面に開いたまま、上の画面のゲームのスクリーンショットを撮影するために | Double 画面 (「画面のゲーム画面の」) is clunky in the single most policy-sensitive string. Rest of the string is excellent. |
| anki_long_press_footer | ⚠ | 通常ankiカード作成画面に進むボタン | 通常は\<anki\>カード作成画面に進むボタン | Missing は after 通常 makes 通常 glue onto the brand: 「通常anki…」. One particle fixes it. |
| enhanced_auto_translate_subtitle_off | ⚠ | より見やすく、反応がよく、安定します。 | 表示が見やすくなり、反応と安定性が向上します。 | The く-form chain adverbially modifies 安定します — grammatically off ("readably, responsively, it stabilizes"). |
| llm_hardware_unsupported_arm64 / llm_hardware_unsupported_ram | 💬 | この端末では対応していません | この端末には対応していません | では+対応していません mismatches; the sibling `lang_setup_requires_64bit_msg` already uses the correct この端末は対応していません. |
| crash_dialog_message | 💬 | 最近OCRまたは検索したテキスト | 最近OCRで読み取った、または検索したテキスト | OCR isn't a する-verb as written; currently reads "text that was OCR or searched". |
| llm_status_low_memory_badge | 💬 | 代替で翻訳しています | 代替エンジンで翻訳しています | 代替で alone is elliptical to the point of oddness. |
| llm_low_memory_message | 💬 | この端末に合わない場合は | このモデルが端末に合わない場合は | Subject dropped one clause too far — what doesn't fit is ambiguous. |
| dialog_hotkey_setup_countdown | 💬 | 押し続けてください 1.4 | 押し続けてください（あと\<%1$s\>秒） | Bare trailing decimal; adding あと…秒 around the placeholder is free and much more natural. |
| status_hold_hint | 💬 | 長押しでクイック選択メニュー | 長押しでクイック選択メニューを表示 | Hint line ends on a bare noun; one word completes it. Quoted 「範囲」「自動」 correctly match the actual button labels. |
| mp_overlay_permission_message / overlay_hide_controls_title | 💬 | ゲーム画面コントロール | ゲーム画面のコントロール | `game_screen_controls_title` and `onboarding_a11y_body` use のコントロール; these two drop the の. Unify. |
| restricted_settings_message | 💬 | 3点メニュー（⋮）のボタンを選び | 3点メニュー（⋮）をタップし | 「メニューのボタンを選び」 is doubly indirect; タップ matches the rest of the file. |
| settings_header_ocr | 💬 | 画像からテキスト（OCR） | テキスト認識（OCR） | Literal calque of "Image-to-text"; テキスト認識 matches `status_ocr` (テキストを認識中…) and standard Android/Google wording. |
| tts_language_unsupported_dialog_title | 💬 | 言語が非対応です | この言語には対応していません | Slightly translationese as a dialog title; bodies below it already use 〜に対応していません. |

Sections checked and clean (not padded above): onboarding, word-detail sheet, language picker, pack-upgrade flow, region picker, capture lifecycle, all four MNN model families (consistently mirrored), Bergamot, Anki review sheet + content-source/flag labels, TTS, Quick Settings tile, Support, Debug, toasts.

## Verdicts

- **Register consistency:** Clean — です/ます prose, noun-form buttons, no plain-form leaks, no あなた anywhere.
- **Terminology consistency:** Strong — 設定/翻訳/ダウンロード/削除 vs 無効 vs オフ distinctions held throughout; only the ゲーム画面（の）コントロール wobble flagged.
- **Android-settings wording:** Correct — ユーザー補助, 他のアプリの上に重ねて表示, 従量制, クイック設定, テキスト読み上げ all match Android's own Japanese.
- **Punctuation:** Consistent — full-width 、。？！： in Japanese prose, half-width for file sizes and Latin runs.
- **Grammar around placeholders:** Good overall (を/で/に particles survive substitution; counters 台/個/件/字 appropriate); the cooldown line and the a11y star clause are the two composition misfires.
- **Truncation risk:** None — bottom bar is 設定/範囲/自動/停止 (2 chars each), キャプチャ\n範囲 fits the two-line button.
- **Legal text:** Faithful — §5(b) reference, EU／英国／韓国 enumeration, and 表明し保証します ("affirm and warrant") all preserved; no softening.
- **Overall:** fix-then-ship — one wrong (a11y-only) composition plus small polish items; quality is otherwise native-grade.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR set collapsed to Japanese's single `other`; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)

No findings. All 29 keys are native-grade and pass every quality axis. Adversarially checked and clean:

- **Naturalness / no calques** — `yomitan_importing_progress` (`%2$d件中%1$d件をインポート中…`) and `yomitan_import_summary_count` (`%2$d件中%1$d件の辞書をインポートしました。`) both correctly reorder to **total-first**, which is the only natural way to use the 件中 ("of N") construction; the positional placeholders are swapped accordingly (mechanically safe). `yomitan_import_summary_more` renders "+N more" as the idiomatic `他%1$d件` rather than a literal `＋N`. `llm_backend_base_url_invalid` splits the English em-dash into two sentences with 。 (`https://を使用してください。http://は…`), the correct JA convention. No literal/MT phrasing anywhere.
- **Terminology consistency** — 高低アクセント (`anki_content_pitch_position`) matches `yomitan_category_pitch_accent` and `yomitan_page_description`; 頻度 reused throughout the four frequency strings; the 8th sibling joins the established `強調表示した単語…` formula verbatim; ★評価 echoes the 星 of `anki_content_frequency_desc`; 形式 matches `anki_content_definition_desc`/`_stylized`. テキスト読み上げ (`audio_source_tts_name`) matches the canonical TTS term (`settings_header_text_to_speech` et al.). 音声 (`audio_source_picker_title`) matches `anki_group_audio`/`tts_voice_picker_title`. 結果がありません (`audio_no_results`) is identical to `lang_search_no_results` and `dictionary_status_no_results`. 読み込み中…/読み込めませんでした (`audio_loading`/`audio_error_loading`) mirror the `word_detail_more_examples_*` pair. カスタムURL aligns with the existing カスタム… / カスタム範囲 usage. 詳細設定 (`llm_backend_advanced_header`) is Android's standard "Advanced".
- **Register** — labels/titles are clean noun-form (高低アクセントの位置, 頻度リスト, 詳細設定, インポート完了, 自動更新, 音声); bodies use polite ます (…に使用します, …インストールします, …使用できます). No plain-form leak, no over-formality.
- **Full-width punctuation with half-width placeholders** — all four summary-line prefixes use full-width ： before the half-width `%1$s` (`インポート済み：`, `読み込めませんでした：`, `空き容量が足りません：`, `失敗：`), matching the house pattern (`status_error`, `anki_deck_label_format`, the MNN `_download_failed` rows). Counters/numbers stay half-width inside the 件中…件 frame.
- **No あなた** — confirmed absent across the whole file.
- **Plurals** — both `<plurals>` collapsed to a single `other` with the natural counter 件 (dictionaries → …件の辞書, elided names → 他…件). Correct for Japanese.
- **Short-label truncation** — 音声 (2 ch, "Audio" toolbar title), 詳細設定 (4 ch, "Advanced"), 自動更新 (4 ch), 不明なファイル (picker fallback) all comfortably fit; no risk.
- **The `Example:` rule** — `anki_content_pitch_position_desc` correctly keeps the source's literal `Example: 0,2` verbatim (the whole token is the unlocalized output sample); not flagged.
- **Brand handling** — `audio_source_commons_name` left as `Wikimedia Commons` (verbatim, doubles as the TTS-settings switch-row label per the EN comment); Lapis/JPMN and the quoted field names (PitchPosition, FreqSort, FrequenciesStylized…) untouched.

---

# Delta review — 2026-07-14 sync (174 keys)

Scope: the 170 newly translated + 4 changed-English keys (History screen, Advanced LLM prompt editor, in-app updater, game-audio trim editor, translation-service status lines, single-app capture, and the 38 `misc_*` dictionary tags).

**Mechanical layer re-verified programmatically — no 🛑.** All 174 keys present; `%1$s`/`%2$s`/`%d` parity exact; every `<xliff:g>` `id`/`example`/inner content byte-identical to EN; the bare Latin keyword literals (`{text}` `{source}` `{source_code}` `{target}` `{target_code}` `{N}` `{strings}`) reproduced byte-for-byte with half-width braces in all 6 prose strings; `floating_menu_capture_screen` keeps its single `\n`; no unescaped `'`; `<plurals>` correctly collapsed to `other` only; **no あなた / 貴方 / お客様 anywhere in the file**; no half-width `?` or `!`.

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| misc_onomatopoeia | ❌ | 擬音語・擬態語 | オノマトペ | `MiscLabels.renderMiscText` joins the tags of a sense with **`" · "`**, and JMdict senses routinely carry several. The chip's internal `・` is the same mark as the join separator at 12sp grey italic, so a word tagged on-mim + col renders 「擬音語・擬態語 · 口語」 — **three** tags where there are two. No other chip contains `・`. オノマトペ is the standard umbrella term (covers 擬音語 *and* 擬態語), separator-safe, and 2 chars shorter. (擬音語 alone would also work but drops the mimetics.) |
| misc_formal + misc_informal | ❌ | 正式 / 略式 | 改まった語 / くだけた語 | 正式↔略式 is the Japanese **full-form vs abbreviated-form** axis (正式名称／略式手続き), not the formal↔informal **register** axis. On a dictionary sense 略式 reads "this is the shortened form" — and `pos_abbreviation` = **略語** already renders two lines above it on the same sense (POS eyebrow → gloss → misc line), so ケータイ would show 略語 then 略式 and conflate the two axes exactly where it matters. 正式 likewise reads "the official/correct form" and bleeds into the correctness axis owned by 非標準. 改まった語／くだけた語 is the standard descriptive register pair in Japanese lexicography and collides with nothing. |
| error_capture_blocked_secure | ❌ | **この**アプリは画面キャプチャを許可していないため、何も読み取れません。 | **キャプチャ対象の**アプリが画面キャプチャを許可していないため、何も読み取れません。 | Shown in PlayTranslate's *own* in-app panel, so 「このアプリ」 reads as PlayTranslate — it tells the user our app is at fault. The referent is the *captured* app (FLAG_SECURE, per the EN comment), and the sibling two lines away in the file (`error_single_app_not_fullscreen`) already establishes the correct term 「キャプチャ対象のアプリ」. Mis-attributes the blame. |
| misc_offensive | ⚠️ | 侮辱語 | 不快語 | Offensiveness cluster must stay distinguishable, and `derog` + offensive co-occur on the same sense constantly. 軽蔑語 ("contempt word") and 侮辱語 ("insult word") are near-indistinguishable side by side. **差別語・不快語** is the canonical Japanese doublet (publishing/broadcasting style guides); `misc_slur` is already 差別語, so 不快語 completes the pair and separates cleanly from 軽蔑語. Cluster then reads 軽蔑語／不快語／卑語／差別語 — four distinct, all canonical, all 3 chars. |
| misc_familiar + misc_endearing | ⚠️ | 親密 / 親愛 | 親しい間柄 / 親愛 | Two 2-char 親-initial abstract nouns; on one word a reader cannot tell 親密 ("intimacy") from 親愛 ("affection"), and neither is a lexicographic label. Keep 親愛 for endearing (it is the closer fit) and move familiar to a usage description. Alternative for familiar: 「なれなれしい語」. |
| misc_humorous | ⚠️ | ユーモア | おどけた語 | ユーモア is the noun "humour", so the line reads 「口語 · ユーモア」 = "colloquial · humour" — it labels the word as *being* humour, not as *used humorously*. Every sibling is an 〜語 noun or a usage noun (皮肉, 婉曲, 比喩). 「ユーモラス」 is the minimal-change alternative if a katakana form is wanted. |
| update_error_incomplete, update_error_verification | ⚠️ | もう一度お試し**いただくか**、… | もう一度**試すか**、… | 〜いただく is humble/elevated and is the **only** 〜いただくか in the file. The committed formula is 「もう一度お試しください」 (7 occurrences: `anki_send_failed_message`, `anki_models_unavailable`, `llm_backend_invalid_key_alert_message_fmt`, `settings_ocr_download_failed`, `yomitan_no_space_message`, `yomitan_io_error_message`, `yomitan_download_error_message`). House register bans stiff over-formality. Plain 試すか in the subordinate clause with the final ください keeps it polite and consistent. |
| update_unknown_sources_message | ⚠️ | …<PlayTranslate>に**アプリの更新のインストール**を許可してください。 | …<PlayTranslate>の**「この提供元のアプリを許可」をオンに**してください。 | Two problems: the の-chain (アプリ**の**更新**の**インストール) is clunky, and the string does not name what Android actually shows. `ACTION_MANAGE_UNKNOWN_APP_SOURCES` opens 「不明なアプリのインストール」 whose toggle is **「この提供元のアプリを許可」** — naming it is exactly what makes the toggle findable. `restricted_settings_message` already sets the precedent of quoting an Android control in 「」 (「制限付き設定を許可」). Rest of the string is fine. |
| stream_kind_prompt_message | ⚠️ | **1つ**のアプリを共有した場合と… | **1個**のアプリを共有した場合と… | The dialog body uses the つ counter while its own button one tap below (`stream_kind_share_one_app`, AOSP-verbatim 「1 個のアプリを共有」) uses 個. Body and button must agree on the counter. Not flagging the button itself — it is deliberately AOSP SystemUI wording, and its half-width numeral space matches Android-ja's own 「2 件の通知」 convention. |
| llm_prompt_advisory_missing_count | ⚠️ | {N}がありません。**いくつのフレーズを想定すべきかが**モデルに伝わりません。 | {N}がありません。**フレーズの数が**モデルに伝わりません。 | Its two siblings are clean and parallel (「翻訳元の言語がモデルに伝わりません。」／「翻訳先の言語がモデルに伝わりません。」). This one breaks the pattern with a nominalized embedded question + 〜かが — stiff, and reads like MT. The fix restores the [X が モデルに伝わりません] frame across all three. |
| llm_prompt_row_system_subtitle | ⚠️ | クラウドと端末内の**LLM翻訳**に送られるペルソナと指示。 | クラウドと端末内の**LLM翻訳サービス**に送られるペルソナと指示。 | "LLM translators" are the *backends*; 「LLM翻訳」 names the *activity*, so the sentence reads "sent to LLM translation". The sibling in the same feature area (`settings_llm_context_subtitle`) already says 「オンラインのLLM翻訳サービス」, and the page's own noun is 翻訳サービス (`settings_cell_translation_services`). |
| llm_prompt_warning_title | ⚠️ | プロンプトを確認**してください** | プロンプトを確認 | The only 〜てください **dialog title** in the file. Committed titles are 体言止め or declarative: 「ホットキーを設定」, 「空きメモリが不足しています」, 「カードを追加できませんでした」, 「インポート済み」, 「…しますか？」. House register: noun/plain form for titles, です・ます in bodies. Declarative alternative that parallels its sibling `llm_prompt_invalid_title` (「このプロンプトは保存できません」): 「このプロンプトに問題があります」. |
| settings_llm_context_subtitle | 💬 | オンラインのみ。オンラインのLLM翻訳サービスに**記録済みテキスト**の直近数行を渡し、… | オンラインのみ。オンラインのLLM翻訳サービスに**テキスト履歴**の直近数行を渡し、… | 記録済みテキスト is a third noun for a feature the file already names twice (`history_toggle_title` 「テキスト履歴」, and 「キャプチャした文」). Tying it to the feature name tells the user *which* toggle feeds this one. |
| tr_service_key_tail_fmt | 💬 | キー ••••<key_tail> | キー：••••<key_tail> | Half-width word-space; the JA params ban spaces between words, and every other label prefix in the file uses a full-width ：(本日：, ダウンロードサイズ：, インポート済み：, 失敗：). |
| tr_service_remove_message | 💬 | サービスをリストから**削除**し、保存済みのAPIキーも**削除**します。 | 保存済みのAPIキーとともに、サービスをリストから削除します。 | 削除 twice in one short sentence. |
| update_error_no_space | 💬 | **更新をダウンロードする**空き容量が足りません（…） | **更新のダウンロードに必要な**空き容量が足りません（…） | Gapless 連体修飾 on 容量 reads oddly ("free space that downloads the update"). Compare the committed `yomitan_no_space_message`: 「この辞書のインポートには約…の空き容量が必要です」. |
| game_audio_trim_duration | 💬 | <seconds>秒を選択 · 録音済み<total>秒 | 選択<seconds>秒 · 録音済み<total>秒 | The two halves have opposite structure ([N秒を選択] vs [録音済みN秒]). This string also doubles as the sentence-audio **row title** in the card editor, where 「2.4秒を選択」 can read as a command rather than a readout; a prefix-labelled pair reads as a readout in both places. |
| tr_service_status_no_usage_today | 💬 | 本日の使用はありません | 本日：使用なし | Correct as-is, but its fmt sibling is 「本日：<12,345>トークン」 — the parallel form makes the two status lines read as one family and is shorter for a one-line status. |
| misc_idiomatic | 💬 | 慣用表現 | 慣用句 | 慣用句 is the label Japanese dictionaries actually print, and is 1 char shorter. |
| misc_rare | 💬 | まれ | まれな語 | A bare adverb among 〜語 / usage nouns; the 〜語 form reads as a label rather than a stray word. |
| misc_manga_slang | 💬 | 漫画スラング | マンガスラング | Mixed kanji+katakana compound; its sibling `misc_internet_slang` is all-katakana ネットスラング. |

## Clean areas (checked, no findings)

- **`misc_*` — the 27 that are right.** 口語 / 俗語 / 卑語 / 差別語 / 雅語 / 文語 / 古語 / 廃語 / 古風 / 方言 / 幼児語 / 女性語 / 男性語 / **尊敬語・謙譲語・丁寧語** / 婉曲 / 皮肉 / 比喩 / 新語 / 非標準 / 要注意 / **四字熟語** / かな表記 / 漢字表記 / 歴史用語 / ネットスラング / 軽蔑語 are all dictionary-standard Japanese lexicographic labels, not glosses of the English. The honorifics cluster (尊敬語／謙譲語／丁寧語) is exactly the canonical三分法 and is flawless. The obsolescence cluster (古語／廃語／古風／歴史用語) is four genuinely distinct terms. `misc_yojijukugo` correctly uses 四字熟語 per the glossary, and kana/kanji stay native. Register and brevity match the committed `pos_*` family (名詞, 助数詞, ことわざ, 略語 — 2–4 chars); after the three chip fixes above, the longest chip is 5 chars.
- **The keyword tokens.** All 6 prose strings carrying bare `{…}` literals keep them Latin and half-width-braced, un-spaced, un-cased. `llm_prompt_kw_source_desc` / `_target_desc` correctly keep 「（例：Japanese）」/「（例：English）」 in Latin — these are the literal runtime expansions, per the brief.
- **Glossary terms, all matched against the committed file.** プロンプト (never リクエスト for the template itself — リクエスト is used only where EN says "request"); キーワード; **プロバイダー** (matches `tr_service_order_footer`); 翻訳サービス (matches `settings_cell_translation_services`); **キャプチャ**した文 (the app's established capture verb, reused in all three History strings); 履歴 as one noun across `settings_cell_history` / `history_screen_title`; 従量制 (metered); テキスト読み上げ (TTS); ゲーム音声 works as both pill and section header; LLM kept as the Latin initialism; 詳細設定 (Advanced) reused from the previous sync; オーバーレイ matches `settings_cell_capture_overlay`; 文カード matches `anki_content_words_table` / `anki_content_flag_sentence`.
- **Remove vs Delete vs Clear.** Japanese has no natural Remove/Delete split, so 削除 is used for both (`tr_service_*` and `history_action_delete` / `settings_ocr_disable_delete`) — which is exactly what the glossary permits. **Clear** is correctly held apart as 消去 (`history_clear_menu`, `history_clear_confirm_title`): the committed クリア is reserved for clearing *input fields* (`btn_clear`, `dictionary_clear_query`, `lang_search_clear_cd`), and would be far too weak for a destructive disk wipe. Deliberate and right.
- **`ocr_source_label`** = 「<PaddleOCR>による読み取り」 mirrors the committed `translation_source_label` 「<DeepL>による翻訳」 *structurally*, exactly as the glossary mandates — not a fresh translation of "Scanned by".
- **`floating_menu_capture_screen`** = 「キャプチャ\n全画面」 is the exact parallel of the committed `floating_menu_btn_capture_region` 「キャプチャ\n範囲」 — same two-line shape, same 4+3 char split, fits the square button.
- **Truncation.** `service_llm_badge` = LLM (3); `probe_initializing` = 初期化中… (5); the two-line capture button fits; the `misc_*` line wraps (MATCH_PARENT/WRAP_CONTENT, no ellipsize) so length is a soft concern — but see `misc_onomatopoeia` above, whose problem is the separator, not the width.
- **Plurals.** `settings_yomitan_count_summary` is `other`-only with the natural counter 件 — correct for Japanese, and consistent with the two plurals shipped in the 2026-06-23 sync.
- **Punctuation.** Full-width 。、？（）「」 and ：throughout; half-width digits, placeholders, sizes, Latin runs and the ` · ` separator (the established house separator — `word_detail_tatoeba_attribution`, `anki_group_words_count`, `settings_anki_digest`, `backend_cooldown_status_fmt` all use it). No half-width ? or !.
- **Register.** Buttons and titles are 体言止め or plain (破棄, リセット, このまま保存, 音声なし, 選択範囲を使用, モデルを残す, 設定を開く, 再試行, 更新のインストールを許可); dialog bodies are short です・ます. Confirm dialogs consistently use 〜しますか？. The only two register misfires are `llm_prompt_warning_title` and the 〜いただく pair, both flagged above.
- **No あなた, and no covert "you".** `update_dialog_metered_note` ("You're on a metered connection") → 「現在の接続は従量制です。」; `llm_prompt_discard_message` ("Your edits") → 「このプロンプトの編集内容」; `audio_source_game_ready` ("From your recent gameplay") → 「直近のゲームプレイから」; `llm_backend_base_url_custom_hint` ("your backend's URL") → 「バックエンドのURLを入力」; `llm_prompt_row_translation_subtitle` ("each phrase you look up") → 「調べるフレーズごとに」; `stream_kind_prompt_title` ("did you pick") → 「…選択しましたか？」. Every English second person is dissolved correctly — none of these read as a literal rendering.
- **`llm_status_low_memory_badge`** left alone per the brief (English-only em-dash→comma swap); the JA already carries the 代替エンジン fix from the previous review round.
- **Grammar around placeholders**, read with real values substituted: 「128 MB」「230 MB」「68 MB」「12,345 トークン」「OpenAI を削除しますか？」「PaddleOCR による読み取り」「2.4 秒 / 147 秒」「4f2a」— particles (に/を/で/から) and counters (秒/件/個) all survive substitution. `update_error_no_space` 「あと230 MB必要です」 and `settings_ocr_disable_manga_msg` 「（68 MB）を残しますか、それとも削除して空き容量を増やしますか？」 both read naturally.

## Verdicts

- **The 38 `misc_*` chips:** strong overall — the translator reached for the Japanese lexicographic tradition rather than glossing the English, and the honorifics, obsolescence, kana/kanji and 四字熟語 tags are exactly right. Three defects: the `・` inside 擬音語・擬態語 collides with the tag-list separator; 正式／略式 is the wrong axis (and collides with the committed 略語); 侮辱語/軽蔑語 and 親密/親愛 are too close to tell apart on one word.
- **Overall:** fix-then-ship. Three ❌ (one rendering break, one wrong-axis pair, one mis-attributed error message), nine ⚠️ (mostly register/consistency drift against the committed file), nine 💬. No 🛑. Quality is otherwise native-grade.

---

## Delta review round 2 — 2026-07-14

Fresh independent pass over the corrected file (174 delta keys). Primary target: regressions introduced by the round-1 fixes.

**Mechanical layer re-verified — no 🛑.** XML well-formed; **0 raw `&`**; every `%n$s`/`%d` and every `<xliff:g>` `id`/`example`/inner byte-identical to EN; `\n` intact in `floating_menu_capture_screen`; the 7 bare `{token}` literals half-width and un-cased; all `<plurals>` `other`-only; no unescaped `'`; **no half-width `? ! ( ) : ,` anywhere in JA prose**; **zero あなた / 貴方 / お客様 in the whole file**. `update_dialog_download` correctly drops EN's `&amp;` for the て-form (「ダウンロードしてインストール」) — not a missing escape.

### Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| game_audio_trim_use_tts | ❌ | 代わりにテキスト読み上げを使用 | 読み上げを使用 | **Round-1 miss, not a regression — and it breaks the screen.** `activity_game_audio_trim.xml` L80–116 is a rigid horizontal `LinearLayout`: three `wrap_content` MaterialButtons (this one, 音声なし, 選択範囲を使用) + one `weight=1` Space. **No `ellipsize`, no `maxLines`, no button weights, no scroll container, and no `screenOrientation` on `GameAudioTrimActivity`** — so it renders in portrait on a phone. All three buttons are always visible (`GameAudioTrimActivity.kt` only attaches click listeners). Row text: EN **18.0 em** → JA **26.0 em** = **364 dp of text alone**, which exceeds a 360 dp phone's *entire width* before ~120 dp of button padding and 24 dp of container padding. LinearLayout measures the last `wrap_content` child against whatever is left, so **the primary 「選択範囲を使用」 Save button — the one button the screen exists for — is squeezed to near-zero and its label destroyed.** The brief's own note that **pt-BR shortened this exact string to "Usar TTS" as a deliberate width exception ("the button row was measured")** is direct evidence the row is width-critical; pt-BR is now the shortest of all 12 (14.5 em). 「読み上げを使用」 returns JA to exactly the 18.0 em EN budget. If the canonical テキスト読み上げ must survive, 「TTSを使用」 also fits and mirrors the accepted pt-BR precedent. **See the cross-locale note below — this is a layout defect, not a JA defect.** |
| misc_familiar | ⚠️ | 親しい間柄 | 親しい間柄の語 | **Regression in form introduced by the round-1 fix.** Round 1 changed `misc_humorous` ユーモア → おどけた語 on an explicit rule: *"ユーモア is the noun 'humour', so the line reads 「口語 · ユーモア」 — it labels the word as being humour, not as used humorously. Every sibling is an 〜語 noun or a usage noun."* 親しい間柄 fails that identical test — it is a **relationship** noun, so the chip line reads 「口語 · 親しい間柄」 = "colloquial · a close relationship". Round 1 applied its own rule to one sibling and not the other: the three *other* register chips it rewrote in the same pass (くだけた語 / 改まった語 / おどけた語) are all V-た+語 label forms, leaving 親しい間柄 the **only** chip in the informality cluster that isn't a word-label. The distinctness gain over 親愛 (`misc_endearing`) is real and must be kept — adding 〜の語 keeps it and restores the label form. Collides with nothing. *(Companion nit: `misc_endearing` = 親愛 is also a bare abstract noun; 「親愛の語」 would make the pair parallel. Lower priority — 親愛 is short and reads acceptably alone.)* |
| stream_kind_prompt_message | ⚠️ | …**ライブ翻訳**の動作は異なります。 | …**ライブモード**の動作は異なります。 | **Round-1 miss.** ライブ翻訳 appears **nowhere else in the file**. The committed JA term for this feature is **ライブモード** — used by `error_live_mode_unsupported_backend` and `error_screen_capture_denied`, **the two strings immediately above this one in the same file section** (L299/L301 vs L311), plus `accessibility_dialog_message` and `settings_debug_log_pinhole`. The brief's own settled note for **ru** says this exact key "deliberately keeps «в реальном времени» **to match the committed 'Live mode' term**" — the established policy for this key is to align it with the locale's committed Live-mode wording, and JA didn't. The file now carries three names for one feature (自動翻訳 / ライブモード / ライブ翻訳). One-word swap; the 1個 counter fix from round 1 is correct and stays. |
| game_audio_trim_duration | 💬 | 選択\<seconds\>秒 · 録音済み\<total\>秒 | 選択：\<seconds\>秒 · 録音済み：\<total\>秒 | The round-1 fix restored parallelism but stopped one step short of the house pattern: **every other label:value string in the file uses a full-width ：** (本日：, ダウンロードサイズ：, キー：, インポート済み：, 失敗：). Here the label abuts the numeral bare — 「選択2.4秒」. It also coins a third word for the selection: its own sibling buttons say **選択範囲** (`_save` 選択範囲を使用, `_play` 選択範囲を再生), and `l10n-language-parameters.md` states the trim editor's selection "is a **selection**". It renders in two places (the trim readout *and* the Anki sentence-audio **row title**, `SentenceAnkiContentFragment.refreshSentenceAudioTitle`), so it must read as a readout in both. |
| misc_idiomatic | 💬 | 慣用表現 | 慣用句 | New argument for round 1's already-declined suggestion. JMdict tags idioms `exp` + `id` together constantly (油を売る, 手を焼く…), so `WordResultCell` renders the POS header **表現** (`pos_expression`) and, two lines below it, the misc chip **慣用表現** — the tag literally contains the POS printed above it. 慣用句 is the label Japanese dictionaries actually print, is a char shorter, and breaks the echo. Optional. |

### The `misc_*` set — the specific questions, answered

- **All 38 labels are pairwise distinct** (verified programmatically). `renderMisc`'s `.distinct()` collapses nothing.
- **No label contains `·` / `・` / `/` / `,` / a space** — the separator rule (`l10n-language-parameters.md` L142) holds across all 38. **オノマトペ is not merely separator-safe, it is *more accurate* than the string it replaced**: JMdict's `on-mim` is "onomatopoeic **or mimetic**", and オノマトペ is the standard umbrella covering 擬音語 *and* 擬態語 — 擬音語 alone would have dropped the mimetics. Correct fix, no regression.
- **No label collides with any `pos_*` or `inflection_*` label** (exact-match check, all three families). The nearest pair is `misc_polite` 丁寧語 / `inflection_polite` 丁寧, which **can** co-render on one `WordResultCell` (the conjugation line above, the register line below the gloss) — but 丁寧語 (a keigo class) vs 丁寧 (a polite conjugation) is a real, standard Japanese distinction, and the two sit on different lines in different type. Not a defect. The round-1 略式 ↔ 略語 (`pos_abbreviation`) collision is **gone**.
- **All four clusters remain separable:**
  - **offensiveness** — 軽蔑語 / 不快語 / 卑語 / 差別語: four canonical 3-char terms. **不快語 is round 1's best call**: it completes the standard 差別語・不快語 doublet while separating cleanly from 軽蔑語, which the old 侮辱語 did not.
  - **obsolescence** — 古語 / 廃語 / 古風 / 歴史用語 ✔ (まれな語 sits cleanly alongside).
  - **informality** — 口語 / くだけた語 / 親しい間柄 / 俗語: distinct ✔ (form issue flagged above).
  - **honorifics** — 尊敬語 / 謙譲語 / 丁寧語: the canonical 三分法, untouched, flawless.
- **改まった語 / くだけた語 is the right axis, and it buys more than it was asked to.** It leaves the file with two *orthogonal* canonical pairs — 口語↔文語 (spoken vs written) and 改まった語↔くだけた語 (formal vs informal) — where 正式/略式 had conflated formality with abbreviation. Endorsed.
- **`misc_female_speech` / `misc_male_speech`** = 女性語 / 男性語 — reads as "women's / men's speech", **not** grammatical gender (the trap the parameters doc calls out). ✔

### Round-1 fixes re-derived from scratch — all confirmed correct

- **`error_capture_blocked_secure`** — verified against the render site. It fires **only** from `ReconcilerLiveMode` (the single-app *task-stream* reconciler — its own comment: *"a task stream contains no system UI"*), on a sustained all-black frame. There genuinely **is** one captured app, so 「キャプチャ対象のアプリ」 is factually right and matches its sibling `error_single_app_not_fullscreen`. The old 「このアプリ」 did blame PlayTranslate. Fix confirmed.
- **`update_error_no_space`** — the EN `<!-- comment -->` says `%1$s` = "human-readable byte count **still needed**", so 「あと230 MB必要です」 is correct, and *more* precise than the EN. The applied wording (「更新のダウンロードには空き容量が足りません」) is more idiomatic than round 1's own suggestion.
- **Register fixes both landed.** `llm_prompt_warning_title` is now declarative (「このプロンプトに問題があります」), parallel to `llm_prompt_invalid_title` (「このプロンプトは保存できません」), and distinguishes the advisory dialog (which offers このまま保存) from the fatal one. **No 〜てください dialog titles remain.** The 〜いただくか humble form is gone from both update errors: 「もう一度試すか、…ください」 is the correct Japanese pattern (plain subordinate clause, politeness carried by the final predicate only).
- `tr_service_key_tail_fmt` (キー：), `tr_service_remove_message` (double-削除 gone, meaning intact), `tr_service_status_no_usage_today` (本日：使用なし, now one family with its fmt sibling), `llm_prompt_advisory_missing_count` (the [X が モデルに伝わりません] frame restored across all three advisories), `llm_prompt_row_system_subtitle` (LLM翻訳サービス), `settings_llm_context_subtitle` (テキスト履歴 — ties the toggle to the feature that feeds it), `update_unknown_sources_message` (「この提供元のアプリを許可」 is Android-ja's actual toggle label; the 「」 quoting matches the `restricted_settings_message` precedent) — **all correct, no regressions.**

### Clean areas (checked, no findings)

- **Hotkey section (new).** 自動翻訳 matches the committed `live_mode_auto_translate_label` / `settings_header_auto_translate`; 「長押しで…を表示」 matches `translate_button_subtitle_hold_to_show_translations`; and `hotkey_section_translations` = 翻訳 is byte-identical to `overlay_mode_option_translation`, which `HotkeysSettingsActivity` uses to title the *sibling* group (ふりがな / ピンイン) — the two group headers read as one family. Substituted: 「タップで自動ふりがなを開始・停止」 / 「自動ピンイン」 ✔.
- **Plurals at each band.** `settings_yomitan_count_summary` `other`-only: at 1 → 「1件の辞書をインポート済み」, at 3 → 「3件の辞書をインポート済み」 ✔.
- **Grammar around placeholders**, read with real values: 「PaddleOCRによる読み取り」「OpenAIを削除しますか？」「キー：••••4f2a」「本日：12,345トークン」「ダウンロードサイズ：128 MB」「あと230 MB必要です」「（68 MB）を残しますか、それとも削除して…」「PlayTranslateを更新中」「PlayTranslateを開く」 — particles and counters all survive substitution.
- **Terminology.** 履歴 / テキスト履歴 (one noun, and 消去 correctly reserved for the destructive Clear vs. 削除 for a single entry); キャプチャした文; プロンプト never リクエスト for the template; プロバイダー; 翻訳サービス; 従量制; テキスト読み上げ; 端末内 (on-device, both occurrences); 注意 (the house WARNING word — 警告 appears nowhere); オーバーレイ; 範囲.
- **Register.** Buttons/titles 体言止め or plain (破棄, リセット, このまま保存, 再試行, 設定を開く, モデルを残す, 音声なし, 選択範囲を使用); dialog bodies です・ます; confirmations consistently 〜しますか？.
- `floating_menu_capture_screen` 「キャプチャ\n全画面」 mirrors the committed 「キャプチャ\n範囲」 (same line-1, one char wider on line 2; `fitLabel` auto-shrinks). No risk.

### Cross-locale note — this is a layout defect, not a Japanese defect

The trim-editor action row is a rigid `LinearLayout` budgeted for EN's **18.0 em**. Measured text width of the three labels, per locale:

`fr 33.0 · tr 32.0 · de 31.0 · ru 27.0 · es 27.0 · ar 26.0 · **ja 26.0** · vi 25.5 · ko 19.0 · th 18.5 · en 18.0 · zh 18.0 · pt-BR 14.5`

**Eight locales are over the EN budget**, most of them far worse than Japanese. Shortening eight strings is whack-a-mole; the durable fix is the row itself (`flexWrap`, button weights + `ellipsize`, or a vertical stack). The ❌ above is the lever available inside a `values-ja` file, but the generator should be fixed in the layout.

### Verdict

- **Round-1 fixes:** 20/20 re-derived; **19 confirmed correct**, 1 (`misc_familiar`) fixed the distinctness problem but introduced a form regression against round 1's own stated rule. The three ❌ corrections (オノマトペ, 改まった語/くだけた語, キャプチャ対象のアプリ) are all **verified right against the code**, and two are *better* than the English.
- **The 38 `misc_*` chips:** distinct, separator-clean, collision-free, all four clusters separable. Native-grade.
- **Overall: FIX FIRST** — one ❌ (`game_audio_trim_use_tts` clips the Save button off the trim editor on a portrait phone), two ⚠️, two 💬. No 🛑. Everything else in the delta is ship-ready.

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
| `settings_ocr_note_mlkit` | ⚠️ | "文字が多い画面でも高速" | "文字が多い画面でも軽快" | The English comment forbids reusing the literal Fast tier label; the first pass reused 高速, the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |
| `history_capture_camera_cd` · `history_capture_screen_cd` | ⚠️ | "カメラのキャプチャ" / "画面のキャプチャ" | "カメラキャプチャ" / "画面キャプチャ" | The file's established compound is 画面キャプチャ (`capture_lifecycle_on_subtitle`, `settings_hide_overlays_during_auto_mode` neighbourhood); the の form introduced a second spelling of the same term. The pair now reads parallel as origin badges. |
| `camera_no_text_hint` | ⚠️ | "…近づけるか、タップして…" | "…カメラを近づけるか、タップして…" | 近づける is transitive; with no object the hint reads as "bring [something] closer" and leaves the reader to guess what moves. Naming カメラ resolves it. |

### Clean areas (delta) — checked, no findings

Full-width punctuation throughout the new strings — （）for the PaddleOCR tier labels (the EN comment's own 高精度 / 高速 examples), ：in `settings_support_check_updates_subtitle` and the slow-OCR settings path, 、。in every body. No あなた anywhere in the delta. 端末 for "device", matching `settings_ocr_delete_msg`. ユーザー補助 for accessibility across all three `a11y_stuck_*` strings. ライブモード / 自動モード / 自動翻訳 reused verbatim from `error_live_mode_unsupported_backend`, `settings_hide_overlays_during_auto_mode` and `settings_header_auto_translate` rather than reinvented. エンジン for the OCR engine stays distinct from ツール (tool) and モデル (the downloadable model) — all three collide in `settings_ocr_delete_camera_import_note` and stay separable. 静止画 for the camera freeze-frame, deliberately not スクリーンショット (which `anki_group_screenshot` already owns). Plurals carry `other` only. Ctrl left untranslated in `dialog_hotkey_setup_typing_key` per its EN comment.

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

**PASS.** Three ⚠️ found and fixed, no ❌. The remaining delta re-derives as correct.

---

## Delta review 2026-08-04 (8 keys: one-tap card toasts, first-field guard, hide-translations toggle, waveform zoom hint)

Scope: the 8 keys added by `84d28c88` (one-tap success toasts, card-mode memory),
`51536300` (Anki first-field guard), the History hide-translations sub-toggle, and the
in-card trim-waveform caption. Reviewed independently against
`app/src/main/res/values/strings.xml` and its translator comments; **report only — no
edits were made to `values-ja/strings.xml`.**

Mechanical layer verified programmatically over the 8 keys: all present, no extras; every
`<xliff:g>` span byte-identical to EN including `id`/`example` attributes and inner
`%1$s`; placeholder multisets identical (`anki_first_field_unmapped` and
`anki_first_field_empty` each carry exactly one `%1$s`, both wrapped); `<b>`, `\n`,
`\{ \}`, `&lt;/&gt;/&amp;` counts match; no unescaped `'` or `"`; `name=` untouched; brand
`Anki` untranslated inside every span. The EN curly quotes `“ ”` around the field-name
placeholder are rendered as 「 」 in both first-field strings — the ja quote convention
per `l10n-language-parameters.md`, outside the `<xliff:g>` span, and therefore correct
rather than a mechanical deviation. **No 🛑 build-breaking issues.**

### Findings (delta) — reported, not applied

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `game_audio_zoom_hint` | 💬 | 「ピンチ操作で表示する音声の範囲を調整」 | 「ピンチ操作で音声の表示範囲を調整」 | ピンチ操作 is the right Android JA gesture noun (Google's own JA uses ピンチ操作で拡大/縮小) — no issue there. But 表示する音声の範囲 is a relative clause where Japanese has a ready compound: 音声の表示範囲. It also momentarily mis-parses as "the audio to display", and costs 2 characters on a caption that is small, centred and wraps. Meaning is unchanged; this is polish. |
| `anki_first_field_empty` | 💬 | 「…最初のフィールドでノートを識別するため、すべてのカードに値が必要です。」 | 「…最初のフィールドでノートを識別するため、どのカードでもこのフィールドに値が必要です。」 | EN's "it needs a value on every card" — "it" is the *first field*. The JA drops the referent one clause too far, so the tail reads literally as "every card needs a value" (a value of what?). Recoverable from 最初のフィールド in the preceding clause, so 💬 not ⚠️; naming the field closes it. Length is free here — this string's primary surface is a full alert. |
| `anki_first_field_empty` | 💬 | (no JA change) | (source-side) | **Not a Japanese defect — flagging for the parent.** The EN comment says "Shown in a full alert, so length is fine", but `AnkiOneTapDispatch.oneTapResultToast` surfaces `AnkiSendResult.Failed.message` as a `Toast.LENGTH_LONG`, so the one-tap path shows this string as a toast under the Android 12+ two-line clamp. At ~53 full-width-equivalents the JA clips there; EN (~118 Latin chars) clips too. Affects all 12 locales equally; the fix belongs in EN/code, not in ja. |

No 🛑, ❌ or ⚠️ in this delta.

### Clean areas (delta) — checked, no findings

**The two one-tap toasts do exactly what their comments demand.**
`anki_added_sentence_success` (文カードをAnkiに追加しました) and `anki_added_word_success`
(単語カードをAnkiに追加しました) name the card shape with the *same* words as the mode
chips — `anki_mode_sentence` 文 / `anki_mode_word` 単語 — so the silently-applied default
becomes visible in the words the user last saw on the toggle. Both pattern-match
`anki_added_no_audio`'s 「Ankiに追加しました」 verbatim, and 文カード is not a new coinage:
the file already carries it in `anki_game_audio_row_subtitle`, `anki_content_words_table`,
`anki_content_flag_sentence` and `anki_content_flag_targeted_sentence`. 単語カード is first
use here; it is also the everyday JA word for a ring-bound vocabulary flashcard, but the
mode-label contrast (and the fact that the file spells generic "flashcards" as
フラッシュカード in `anki_permission_rationale_message`) keeps the reading unambiguous.
Considered and accepted — changing it would break the mandated tie to 単語.

**ノート is the right call, and is used consistently.** Anki's own Japanese localization
distinguishes ノート (note) from カード (card), and the note/card split is exactly what
these two strings are about — the duplicate checksum lives on the *note*, not the card.
Both `anki_first_field_unmapped` and `anki_first_field_empty` use ノート for "note" and
カード for "card", with no leakage in either direction. This mirrors EN's own deliberate
split, where the user-facing model picker says "card type" (ja カードタイプ, per
`anki_card_type_row_label`) but the first-field strings say "note". Collapsing ノート into
カード would have made both strings factually wrong. The only other ノート in the file,
`update_dialog_view_release`'s リリースノート, is an unrelated compound.

**Mapping terminology matches the dialog that opens next.** `anki_first_field_unmapped`'s
「値を割り当ててください」 reuses 割り当て — the verb `anki_content_source_pick_title`
(「%1$s」を割り当て, EN "Map \"%1$s\"") already uses for the same action in the very dialog
this toast precedes. It stays distinct from `anki_card_type_edit_mapping_row_label`'s
マッピング and `anki_field_mapping_unconfigured`'s 設定, mirroring EN's own
Map/mapping/Configure split.

**Toast-length clamp checked, not guessed.** `anki_first_field_unmapped` is
`Toast.LENGTH_LONG` in `AnkiSendDispatch` (Android 12+ two-line clamp). The JA measures
~27 full-width + 7 half-width characters ≈ 30 full-width-equivalents; a 14sp toast on a
360dp-wide screen fits roughly 22 per line, i.e. ~44 over two lines. It fits with room for
a user-defined field name considerably longer than the `Key` example. No shortening
needed — and the string keeps the brand span rather than trading it for brevity.

**Sentence-final punctuation follows the file's own surface rule.**
`anki_first_field_unmapped` (toast) ends with no 。, matching `anki_field_mapping_unconfigured`
and `anki_permission_denied`; `anki_first_field_empty` (alert body) keeps its 。, matching
`anki_models_unavailable` and `anki_send_failed_message`. EN's trailing period on the toast
was correctly dropped rather than transliterated.

**History strings reuse the established capture vocabulary.**
`history_hide_translations_toggle_subtitle`'s キャプチャした文 is byte-identical to the
phrase already in `settings_cell_history_summary_on`/`_off`, `history_toggle_subtitle` and
`history_empty_off` — no second capture verb was introduced, which is the specific trap
`l10n-language-parameters.md` calls out for this family. 翻訳 for "translation" matches
`anki_group_translation`. "Row" is rendered 項目, matching `history_delete_confirm_title`'s
この項目 — and notably *not* 行, which would have collided with the `history_line_count`
plural's %d行 ("lines") on the same screen. The subtitle's second sentence
(項目をタップすると翻訳が表示されます) delivers the "one tap away" promise the EN comment
asks for, without overstating it. `history_hide_translations_toggle_title` (翻訳を非表示)
is parallel in form to its neighbour `history_capture_image_toggle_title`
(キャプチャ画像を保存), and its です/ます subtitle matches
`history_capture_image_toggle_subtitle`'s 保存します.

**`card_words_in_sentence`.** 文中の単語 reads as a natural section header and sits
parallel with `anki_group_words_count`'s カード内の単語 ("Words on card") — same 〜の単語
frame, different container, which is exactly the EN pair's relationship. The card CSS's
ALL CAPS transform is inert on kana/kanji, so unlike Turkish there is no casing hazard,
and no accuracy was traded for a shorter header.

**Register and mechanics across the delta.** No あなた anywhere. Full-width 、。「」 throughout,
half-width Latin/placeholders. Loanword katakana (ピンチ操作, キャプチャ, カード, フィールド,
ノート). Polite です/ます in the two body-text strings, clipped noun/〜する form in the
headers, labels and captions — the split the ja register calls for. Every brand span
untranslated. No per-string English comments leaked into the locale file, and the section
banners and key ordering still match English.

### Verdict

**PASS.** No 🛑/❌/⚠️. Three 💬, one of which is a source-side/all-locale observation rather
than a Japanese issue. The ノート decision is correct and consistent; the two one-tap
toasts satisfy the mode-naming contract; the History strings sit inside the file's existing
capture vocabulary.
