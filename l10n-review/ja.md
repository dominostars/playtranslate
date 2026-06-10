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
