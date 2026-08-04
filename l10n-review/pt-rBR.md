# Brazilian Portuguese (values-pt-rBR) targeted review

*(Targeted hotlist pass + whole-file scans, not a full string-by-string review.)*

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| hymt_legal_message | ❌ | `Ao tocar em Concordar, você declara e garante que:` | `Ao tocar em "Concordo — Ativar o Hunyuan", você declara e garante que:` (minimum: `Ao tocar em "Concordo"`) | The button (`hymt_legal_agree`) is **"Concordo — Ativar o Hunyuan"**; the message quotes a nonexistent button "Concordar" (infinitive, doesn't even match the first word). Same failure as 5 of 6 other languages. Everything else in the block is solid: §5(b) kept, UE/Reino Unido/Coreia do Sul list kept, "declara e garante" carries the affirm-and-warrant force, and clause (1) "Você não reside nem está localizado atualmente…" correctly negates both residing and located ("não… nem…" is proper continued negation, not a weak double negative). |
| settings_header_ocr | ⚠️ | `Imagem para texto (OCR)` | `Reconhecimento de texto (OCR)` | "Imagem para texto" is a calque; pt-BR settings UIs say "reconhecimento de texto/caracteres". The "(OCR)" suffix saves it from being misread, hence ⚠️ not ❌. |
| pack_upgrade_mandatory_message | ⚠️ | `Atualize agora ou exclua para escolher outro idioma.` | `Atualize agora ou exclua o pacote para escolher outro idioma.` | "exclua" has no object — in context the nearest candidate is "a atualização" / "a versão", so the objectless verb is genuinely ambiguous (delete the update?). Naming "o pacote" pins the referent and matches the `pack_upgrade_button_delete` = "Excluir" button. |
| settings_capture_interval_hint | ⚠️ | `Mínimo de %1$s segundos.` | `Mínimo: %1$s s` (reusing the existing "s" suffix style) or `Mínimo de %1$s segundo(s).` | EN comment says the value can be integer "1" → "Mínimo de 1 segundos" is an agreement error. (EN source "Minimum 1 seconds" has the same flaw — upstream drift, but PT can sidestep it.) |
| tts_language_unsupported_with_engine_message / tts_language_unsupported_unknown_engine_message | ⚠️ | `…não é compatível com %2$s.` / `…com %1$s.` | `…não é compatível com o %2$s.` | The PT file contains no localized language names (0 hits for "japonês"), so the placeholder fills from runtime display names — lowercase in pt ("japonês"). "compatível com japonês" reads clipped/non-native. Language names are uniformly masculine in Portuguese (o japonês, o russo, o alemão…), so adding "o" is safe for every value. "Mecanismo" for engine matches Android pt-BR TTS settings. |
| accessibility_dialog_message / overlay_icon_a11y_required_message | ⚠️ | `Configurações → Acessibilidade → Apps instalados → …` | `…→ Apps baixados → …` | Faithful to the EN source ("Installed apps"), but stock Android pt-BR labels that Accessibility section «Apps baixados», so the nav path won't match the user's device. Known upstream EN drift — fix here or in source. «Configurações» and «Acessibilidade» are correct. |
| nav_settings | ⚠️ | `Configurações` | `Ajustes` | 13 chars on the 8sp bottom bar vs EN "Settings" (8). Real truncation/shrink risk. Fine to keep «Configurações» everywhere else (21 other uses); only this bottom-bar label needs the short form. |
| status_hold_hint | 💬 | `Mantenha pressionado Regiões ou Auto para menus de seleção rápida` | `Mantenha pressionado "Regiões" ou "Auto" para abrir menus de seleção rápida` | Names exactly match the buttons (nav_regions = "Regiões", live_mode_auto_label = "Auto") ✓; caps-only marking plus the gender clash ("pressionado Regiões") mostly prevents the garden path, but quotes would remove all doubt. |
| floating_menu_btn_capture_region | 💬 | `Região de\ncaptura` | `Capturar\nregião` | Top line "Região de" (9 ch) vs EN "Capture" (7) at 9sp — likely fits, but the verb form is shorter and more button-like. |

Checked clean: hymt_legal_title/agree themselves; live_mode_auto_with_hint ("Auto %1$s" word order is acceptable as a UI label and neatly dodges gender agreement); status_idle ("Toque em Traduzir" matches the composed translate_button_prefix_translate = "Traduzir"); anki_sort_field_empty ("erros de rejeição por duplicação no envio" reads fine, no gibberish; straight `\"` quotes are the file-wide convention); anki_permission_rationale_message / anki_settings_grant_access_subtitle (comma + article "o PlayTranslate" keeps Anki and PlayTranslate from fusing; "Toque em Continuar" matches btn_continue); label_region_drag_hint (repeated "arraste o meio para mover a caixa inteira" keeps the move-scoping on the middle only); translate_button_prefix_translate/reload ("Traduzir/Recarregar Tela cheia" composes naturally); backend_cooldown line ("Nova tentativa às 15:42" / "Nova tentativa em 1 de jun" both read naturally); onboarding_a11y_title / mp_overlay_permission_title («Sobrepor a outros apps» — exact Android pt-BR system wording); quick_tile_add_row_title / settings_hotkeys_tile_add («bloco» + «Configurações rápidas» — exact Android pt-BR QS terms, mutually consistent); crash_dialog_discard «Descartar» and btn_clear «Limpar» (neither reads as Cancelar/Excluir); nav_regions/live_mode_pause_label/live_mode_auto_label (short enough).

## Scan results
- **Apostrophes:** clean — zero unescaped `'` in the file (all are `\'`). No build risk.
- **Register:** clean — zero hits for tu/teu/tua/contigo; informal `você` throughout, imperative forms consistent with it.
- **European Portuguese leakage:** clean — zero hits for transferir/transferência, ecrã, eliminar, guardar, aplicação, carregar no, telemóvel, rato, palavra-passe, ficheiro. File correctly uses baixar/download, tela, excluir, salvar, app, tocar, arquivo-free phrasing.
- **Brands:** clean — PlayTranslate/Anki/AnkiDroid/DeepL all untranslated; in-string occurrence counts match EN exactly once the `translatable="false"` strings the PT file rightly omits (app_name, tile_label, anki_section_header, settings_header_anki, deepl_settings_title) are accounted for.

Terminology spot-check: all consistent — Settings = «Configurações» (21×, no stray «Ajustes»); «Acessibilidade» uniform; deck = «baralho» (8×; "deck" appears only in key names/xliff ids); metered = «rede limitada» (8×, matches Android pt-BR Data-Saver wording); card = «cartão» (26×, zero «carta»); «pacote de idioma» uniform; hotkey = «atalho» (7×); TTS = «conversão de texto em voz» (8×, exact Android pt-BR term); screenshot = «captura de tela» uniform.

## Verdicts
- **Register:** pass — consistent informal você, no tuteo, no stiff formality.
- **Terminology:** pass — internally consistent across all nine spot-checked terms.
- **Brazilian-vs-European vocabulary:** pass — zero PT-PT leakage; this is genuine pt-BR.
- **Android-settings wording:** mostly pass — QS («bloco», «Configurações rápidas»), overlay («Sobrepor a outros apps»), and TTS wording match stock Android pt-BR; one mismatch: «Apps instalados» should be «Apps baixados» in the two accessibility nav paths (inherited from EN source drift).
- **Legal text:** one ❌ — the quoted button name «Concordar» doesn't match the actual «Concordo — Ativar o Hunyuan» button; substance (§5(b), country list, declara-e-garante, dual residing/located negation) is otherwise correct.
- **Truncation:** one real risk — «Configurações» on the 8sp bottom bar; propose «Ajustes» there only.
- **Overall:** **fix-then-ship** — 1 ❌ (legal button mismatch) and 6 ⚠️, no build-breakers; caveat: this was a targeted pass over a known-risk hotlist plus whole-file scans, not a full review.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR sets; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)
| name | severity | current | suggested | note |
|---|---|---|---|---|
| — | — | — | — | No findings. All 29 keys are natural, correct, BR-vocab-clean, você-register-consistent, and terminologically aligned with the existing file. |

## Clean areas (delta)
- **BR-vs-EU vocabulary** — clean across all 29 keys and re-scanned whole-file: zero hits for transferir/transferência, ecrã, eliminar, guardar, aplicação, telemóvel, palavra-passe, ficheiro, "carregar no". The new strings use genuine pt-BR: `yomitan_auto_update_subtitle` → «Baixar e instalar…» (baixar, not transferir; matches `lang_download`=«Baixar» and the 25+ existing «Baixar…» download strings), `yomitan_import_summary_unknown_file` → «Arquivo desconhecido» (arquivo, not ficheiro; matches `yomitan_invalid_title`=«Arquivo inválido»), `settings_capture_interval_hint` neighbor uses «tela». BR-spelling marker present and correct: `anki_content_frequency_harmonic_desc` → «média harmônica» (circumflex, BR) not EU «harmónica».
- **você-register** — consistent; no tu/teu/tua introduced. The two new sentences that could have taken a 2nd-person pronoun stay impersonal/imperative, matching the file: `llm_backend_base_url_invalid` → «Use https:// …» (imperative), `anki_content_frequency_stylized_desc` → «Use somente em cartões do JPMN». The «você»-bearing neighbor (`llm_backend_invalid_key_alert_message_fmt`, «…a chave que você inseriu…») is untouched and consistent.
- **Agreement around placeholders / plurals** — `yomitan_import_summary_count` one/other resolve correctly with a real count: «1 de 1 dicionário importado.» / «4 de 6 dicionários importados.» — both noun and past participle agree (singular «dicionário importado», plural «dicionários importados»); the BR-risk gender/number target nailed. `yomitan_import_summary_more` one/other → «+1 outro» / «+3 outros» is the idiomatic "+N more (item[s])" rendering (better than a literal «mais»); masculine «outro/outros» is the correct generic for elided list entries. `yomitan_importing_progress` «Importando %1$d de %2$d…» reads naturally with integers dropped in; the four `summary_*` "label: %1$s" lines (`duplicates`/`invalid`/`no_space`/`failed`) all take a comma-separated names string cleanly with no agreement contact point.
- **Terminology reuse** — every term matches the rest of the file. acento tonal: `anki_content_pitch_position`(_desc) → «acento tonal» == `yomitan_category_pitch_accent`=«Acento tonal». frequência: «Lista de frequência»/«…por frequência» align with `yomitan_category_frequency`=«Frequência» and `anki_content_frequency`. dicionário: uniform (no «vocabulário»/«léxico» drift). importar/importação: «Importação concluída»/«Importando…»/«Já importados:» align with `yomitan_importing_title`, `yomitan_import_action`, and `yomitan_duplicate_title`=«Já importado»; sentence-case titles match all sibling `_title` strings («Falha na importação», «Espaço insuficiente»). áudio: `audio_source_picker_title`=«Áudio» matches `anki_content_*_audio_desc`=«Áudio de…». TTS: `audio_source_tts_name`=«Conversão de texto em voz» == `settings_header_text_to_speech`/`settings_cell_tts`/all `tts_*` (8×, the exact Android pt-BR term). «Avançado» (`llm_backend_advanced_header`) matches «avançada/avançados» elsewhere. baralho/cartão reused correctly in the Anki descs («cartões do JPMN», «ordenar os cartões»).
- **Android-wording / loading-error idioms** — `audio_no_results`=«Nenhum resultado» byte-matches `lang_search_no_results` and `dictionary_status_no_results` (3× identical); `audio_loading`=«Carregando…» matches the file-wide «Carregando…» convention (8+ uses); `audio_error_loading`=«Não foi possível carregar» matches the `word_detail_more_examples_error` pattern and the file's dominant «Não foi possível …» error register. `URL personalizada` is correctly feminine (a URL) and consistent with `label_add_custom_region`=«…personalizada»; the masculine `llm_backend_model_custom_entry`=«Personalizado…» is a different referent (generic model item), not an inconsistency.
- **Short-label truncation** — none of the 29 are bottom-bar/tiny labels. «Avançado» (8) is a card header, «Áudio» (5) a toolbar title, «URL personalizada» (17) a row label, «Atualização automática» (22) a toggle title — all roomy. «Conversão de texto em voz» (25) is a section header / switch-row label already shipped identically in 8+ places with no truncation, so no new risk.
- **The `Exemplo:` / quoted-field-name rule** — followed exactly. `anki_content_pitch_position_desc` keeps «Exemplo: 0,2» (label localized, sample as-is). All Anki/Lapis/JPMN field names left untranslated in typographic quotes: «PitchPosition», «PAOverride», «Frequency», «FrequenciesStylized», «FreqSort», «FrequencySort» — and brand spans `<xliff:g brand_lapis/brand_jpmn>` untouched. No raw-quote build risk (all field-name quotes are typographic “ ”, matching the EN source).
- **Naturalness / no calques** — the descs are rewritten as meaning, not word-for-word: `frequency_harmonic_desc` «(quanto menor, mais frequente)» for "(lower = more frequent)" (not a literal "=" calque); `pitch_position_desc` «Posição (ou posições) de queda do acento tonal… separadas por vírgula» renders "downstep position(s), comma-separated" idiomatically («queda» = downstep). The «do JPMN … dele» possessive in `frequency_stylized_desc` is colloquial-but-natural pt-BR (mildly redundant, not worth flagging).

## Verdict (delta)
**Ship as-is.** 0 🛑 / 0 ❌ / 0 ⚠️ / 0 💬 across the 29 keys. Checked: BR-vs-EU vocabulary (incl. whole-file re-scan), você-register, placeholder/plural agreement (both `<plurals>` gender+number verified with real counts), terminology reuse (acento tonal / frequência / dicionário / importar / áudio / Conversão de texto em voz / baralho / cartão), Android loading/no-results/error idioms, short-label truncation, and the Anki `Exemplo:`/field-name-as-is rule. This delta does not touch the pre-existing 1 ❌ + 6 ⚠️ above (different keys).

---

# Delta review — 2026-07-14 sync (174 keys)

Scope: the 170 newly translated + 4 changed-English keys (game-audio recording &
trim editor, History screen, Advanced LLM Configuration / prompt editor, the 38
`misc_*` dictionary chips, in-app updater, translation-service status lines,
stream-scope prompt, hotkeys, floating-menu panel, OCR picker).

**Mechanical layer verified programmatically over all 174 keys:** every key present
and of the right type; all `%1$s`/`%2$s`/`%d` placeholders present and matching EN;
every `<xliff:g>` span byte-identical to EN (inner content **and** `id`/`example`);
the bare Latin keyword tokens `{text} {source} {source_code} {target} {target_code}
{context} {N} {strings}` byte-identical in running prose (`llm_prompt_fatal_*`,
`llm_prompt_advisory_*`); `\n` preserved in `floating_menu_capture_screen`; zero raw
`'` or `"`; `<plurals>` = one/other (correct CLDR set for pt). **No 🛑
build-breaking issues.** (`update_dialog_download` legitimately has no `&amp;` —
pt-BR uses the word «e».)

**Counts: 0 🛑 · 0 ❌ · 6 ⚠️ · 7 💬**

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| game_audio_trim_use_tts | ⚠️ | `Usar TTS em vez disso` | `Usar conversão de texto em voz` (if width allows) — otherwise `Usar TTS` | **Terminology collision inside one flow.** The audio picker names this exact source `audio_source_tts_name` = «Conversão de texto em voz» (the Android pt-BR term, 7× in the file); the button that switches *to* it says «TTS». The file has never used a bare «TTS» before (only inside the product name «Google TTS»). Layout checked: `activity_game_audio_trim.xml` puts `btnTrimUseTts` + `btnTrimNoAudio` + `btnTrimSave` in **one horizontal row**, and «Usar TTS em vez disso» (21) is already the longest of the three — so the 30-char full term is width-hostile. Either way the current string should shrink: «em vez disso» is redundant next to «Sem áudio». If «TTS» is kept, record it as a deliberate width exception. |
| llm_prompt_row_batch_subtitle · llm_prompt_row_translation_subtitle | ⚠️ | `A solicitação que os serviços na nuvem usam…` / `A solicitação que envolve cada frase que você consulta.` | `O prompt que os serviços na nuvem usam para traduzir uma tela inteira de uma só vez.` / `O prompt aplicado a cada frase que você consulta.` | **2 keys, one fix.** The glossary binds *prompt* to **one noun across all `llm_prompt_*`** and explicitly says *not "request", not "query"*. These two subtitles introduce «solicitação» (= request) for the very object their own row titles call «Prompt em lote» / «Prompt de tradução», so the card reads with two nouns for one thing. (EN gets away with "the request" because it reads as a gloss; in pt «solicitação» carries a bureaucratic "application/form" sense and lands as a second term.) Bonus: «que envolve cada frase» is ambiguous — «envolver» reads first as *to involve*, not *to wrap around*. |
| hotkey_auto_hint_title · hotkey_auto_hint_dialog_title | ⚠️ | `Toque para iniciar/parar o Auto <xliff:g …>%1$s</xliff:g>` / `Auto <xliff:g …>%1$s</xliff:g>` | `Toque para iniciar/parar o <xliff:g …>%1$s</xliff:g> automático` / `<xliff:g …>%1$s</xliff:g> automático` | **2 keys, one fix.** `HotkeysSettingsActivity.render()` draws all four rows on one screen for JA/ZH: «Segure para mostrar traduções» / «Toque para iniciar/parar **a tradução automática**» then «Segure para mostrar Furigana» / «Toque para iniciar/parar **o Auto Furigana**». Same action, two treatments — one translated, one leaving raw English «Auto» in running prose. `hotkey_auto_hint_dialog_title` is currently byte-identical to English. The already-committed dialog-title pair is parallel («Mostrar traduções» / «Mostrar <hint>»), so the auto pair should be too: «Tradução automática» / «Furigana automático». Both hint values (Furigana, Pinyin) are masculine in pt, so «automático» agrees for every runtime value. *(Alternative, if the intent is to name the bottom-bar button `live_mode_auto_with_hint` = «Auto %1$s»: keep «Auto %1$s» here and change `hotkey_auto_translation_*` to match instead — but do not ship both conventions.)* |
| stream_kind_prompt_message | ⚠️ | `…e o sistema não informa o que foi compartilhado.` | `…e o sistema não informa qual das duas opções foi compartilhada.` | EN says the system doesn't say **which** was shared (of the two). «o que foi compartilhado» = *what* was shared, which reads as "what content" and quietly undercuts the dialog's whole premise — and clashes with its own title, `stream_kind_prompt_title` = «**Qual** opção de compartilhamento você escolheu?». One word, «qual». |
| llm_prompt_advisory_missing_target · llm_prompt_advisory_missing_source | ⚠️ | `…o modelo não será informado para qual idioma traduzir.` / `…o modelo não será informado de qual idioma está traduzindo.` | `…o modelo não saberá para qual idioma traduzir.` / `…o modelo não saberá de qual idioma está traduzindo.` | **2 keys, one fix** (optionally 3 — apply to `_missing_count` too, for a uniform «o modelo não saberá …» across the trio). «informado **para** qual» is ungrammatical in pt (*informar* takes *de/sobre*), and in `_missing_source` the single «de» has to serve both «informado de» and «traduzindo de», so one of the two ends up stranded. «não saberá» is idiomatic, shorter, and fixes both. Tokens `{source}`/`{source_code}`/`{target}`/`{target_code}` stay verbatim. |
| update_unknown_sources_message | ⚠️ | `…permita que o PlayTranslate instale atualizações de apps na tela de configurações que será aberta.` | `…ative "Permitir desta fonte" para o PlayTranslate na tela de configurações que será aberta.` | Android-wording mismatch. The screen this opens is pt-BR **«Instalar apps desconhecidos»** and the switch on it reads **«Permitir desta fonte»** — a user hunting for a toggle called "instalar atualizações de apps" won't find it. Same class as the «Apps instalados» → «Apps baixados» finding from the previous pass (which was applied): inherited EN drift, fixable here or upstream. `update_unknown_sources_button` = «Abrir configurações» is byte-identical to the committed `btn_open_overlay_settings` / `mp_overlay_permission_button` ✓. |
| misc_yojijukugo | 💬 | `Composto de quatro caracteres` (29) | `Composto de 4 kanji` (19) — or `Composto de 4 caracteres` (24) | Longest chip in the set by 12 chars, and 2× the longest committed `pos_*` («Verbo auxiliar», 14). Chips are width-constrained and render side by side. pt has no native lexicographic term, so a description is right (correctly *not* romanized) — just a shorter one. «kanji» is already a loanword in the file (`misc_kanji_only` = «Somente kanji»), and the tag only ever fires on Japanese. |
| misc_slur | 💬 | `Insulto` | `Injúria` | Weakest link in the offensiveness cluster: the four are lexically distinct (Pejorativo · Ofensivo · Chulo · Insulto), but semantically «Insulto» and «Ofensivo» collapse into each other for a pt reader, losing the group-directed sense that makes a *slur* its own class. «Injúria» is a real pt term with exactly that force (cf. *injúria racial*), is shorter, and separates cleanly from «Ofensivo». |
| floating_menu_capture_screen | 💬 | `Capturar\ntela` | keep — but apply the previously-filed fix to its sibling | Not wrong; it's the *pair* that's off. The same floating-menu button renders `floating_menu_capture_screen` = «Capturar\ntela» (verb-first) when the region is full-screen and the committed `floating_menu_btn_capture_region` = «Região de\ncaptura» (noun-first) otherwise. The 2026-06-23 review already suggested «Capturar\nregião» for the latter; applying it makes the two states «Capturar\ntela» / «Capturar\nregião» — one shape, both lines short. |
| game_audio_trim_duration | 💬 | `%1$s s selecionados · %2$s s gravados` | `Seleção: %1$s s · Gravação: %2$s s` | Reads fine at realistic values («2,4 s selecionados · 147 s gravados»), but the selection genuinely can be 1.0 s → «1,0 s **selecionados**» (strict pt wants *selecionado*). The colon form carries the same information, drops both participles, and is shorter — useful since this string also serves as the card-editor row title. |
| settings_llm_context_subtitle | 💬 | `Somente online. Fornece aos tradutores LLM online as últimas linhas do texto registrado…` | `Somente online. Envia as últimas linhas do texto registrado aos tradutores LLM para pronomes e nomes mais consistentes.` | «online» twice in one breath, and the dative-before-accusative inversion («Fornece aos tradutores … as últimas linhas») is heavy. The leading «Somente online.» already carries the scope, so the second «online» is redundant. |
| audio_source_game_enable_hint · settings_ocr_use_manga_subtitle | 💬 | `…ative para capturar o áudio do jogo **para** os próximos cartões` / `AVISO: Alta qualidade, mas lento.` | `…ative para capturar o áudio do jogo nos próximos cartões` / `AVISO: tem alta qualidade, mas é lento.` | Two unrelated polish nits. (1) «para … para» in one clause. (2) «Alta qualidade, mas lento» puts a masculine adjective next to a feminine noun; the intended subject is *o MangaOCR*, but the surface reads as an agreement slip. Everything else in that warning is right — «o modo automático» matches the committed `settings_hide_overlays_during_auto_mode`. |
| probe_initializing · misc_internet_slang · audio_source_game_ready | 💬 | `Inicializando…` / `Gíria de internet` / `Do que você jogou recentemente` | `Iniciando…` / `Gíria da internet` / `Da sua sessão de jogo recente` | Three one-liners. (1) The chip is a ~1.5 s transient beside a checker pattern and the EN comment says "keep short"; «Iniciando…» is 4 chars tighter. (2) BR says *gíria **da** internet*. (3) «Do que você jogou recentemente» is understandable but loose for a row subtitle. |

## Clean areas (delta) — checked, no findings

- **Brazilian-vs-European vocabulary — clean.** Zero hits across all 174 delta keys
  for transferir/transferência, ecrã, eliminar, guardar, aplicação, telemóvel,
  palavra-passe, ficheiro, utilizador, registar, "está a …". The delta is genuine
  pt-BR: «Baixar e instalar» / «O download não foi concluído» / «baixe … no GitHub»
  (never *transferir*), «tela» throughout (`error_capture_blocked_secure`,
  `stream_kind_share_entire_screen`, `ocr_picker_message`), «excluir» for delete,
  «salvar» for save, «app/apps» never *aplicação*, «arquivo baixado» never
  *ficheiro*. BR orthography markers correct: «Onomatopeia» (no accent, post-1990),
  «mangá», «irônico», «histórico».
- **Register — consistent with the committed file's two-way convention.** Verified
  against the committed strings, not assumed. *Buttons / action rows / toolbar
  actions = infinitive*: Excluir · Copiar · Remover · Descartar · Redefinir ·
  Parar · Manter modelo · Excluir modelo · Usar seleção · Reproduzir seleção ·
  Baixar e instalar · Tentar novamente · Ver notas da versão · Abrir configurações ·
  Salvar mesmo assim · Adicionar ao Anki · Compartilhar um app — 100% infinitive,
  and «Excluir modelo» / «Abrir configurações» are byte-identical to committed
  siblings (`bergamot_disable_delete`, `qwen_mnn_disable_delete`, `hymt_disable_delete`,
  `llm_low_memory_delete` / `btn_open_overlay_settings`, `mp_overlay_permission_button`).
  *Descriptive switch subtitles = 3rd-person indicative*: «Mantém os últimos
  minutos…», «Salva as frases capturadas…», «Fornece aos tradutores…» — matching the
  committed `settings_vertical_grow_subtitle` («Alarga as caixas…») and
  `yomitan_single_dict_subtitle` («As definições vêm…»). *Actionable / CTA rows =
  você-imperative*: «Importe dicionários…», «ative para capturar…», «Insira a URL…» —
  matching committed `settings_cell_dictionary_summary` («Consulte definições…»),
  `settings_anki_get_app_summary` («Baixe o AnkiDroid…»), `yomitan_alias_hint`
  («Adicione um apelido»). **No você/tu mixing; zero tu/teu/tua in the delta.**
- **Remover vs Excluir vs Limpar — all three stay distinct**, exactly as English
  keeps them. Services are *removed*: `tr_service_remove_confirm` «Remover»,
  `tr_service_delete_cd` «Remover serviço», `tr_service_remove_title_fmt` «Remover o
  %1$s?». Entries and models are *deleted*: `history_action_delete` /
  `history_delete_confirm_title` / `settings_ocr_disable_delete` → «Excluir…».
  History is *cleared*: `history_clear_menu` «Limpar histórico» (matching the
  committed `btn_clear` = «Limpar»). And `tr_service_remove_message` correctly
  carries **both** verbs in one sentence — «Isso **remove** o serviço da lista e
  **exclui** a chave de API salva.» — which is the string most likely to have
  collapsed them, and didn't.
- **`prompt` is one noun across `llm_prompt_*`** (aside from the «solicitação»
  finding above): masculine «o prompt» everywhere — «O prompt não pode ficar vazio»,
  «Este prompt é muito longo», «O prompt precisa incluir {strings}», «neste prompt»,
  «por este prompt», and the three row titles «Prompt do sistema» / «Prompt de
  tradução» / «Prompt em lote». Never *pedido*, never *consulta*, never *comando*.
  `llm_prompt_keywords_header` = «Palavras-chave» keeps *keyword* distinct from
  *placeholder* ✓.
- **The 38 `misc_*` chips.** All four clusters internally distinguishable:
  offensiveness = Pejorativo · Ofensivo · **Chulo** · Insulto; obsolescence =
  Arcaico · Obsoleto · Antiquado · Histórico; informality = Coloquial · Informal ·
  Familiar · Gíria; honorifics = Honorífico · Humilde · Cortês. They read as real pt
  lexicographic labels, not glosses of English — «Figurado» (the *fig.* of Houaiss),
  «Pejorativo», «Antiquado», «Eufemismo», «Dialetal», «Irônico» for *Sarcastic* (the
  pt dictionary label; no `misc_ironic` key to collide with). **The `misc_vulgar`
  false-friend trap is correctly handled: «Chulo», not «Vulgar»** (which in pt means
  *common/banal*). «Humilde» / «Cortês» are the established pt renderings of kenjōgo
  / teineigo. kana/kanji kept as loanwords per the glossary; `misc_yojijukugo` is
  described, not romanized. Noun-vs-adjective mixing (Gíria, Neologismo,
  Onomatopeia, Eufemismo, Insulto as nouns) is normal pt lexicographic practice and
  matches the committed `pos_*` register and brevity.
- **`settings_yomitan_count_summary` — one/other, both correct for their ranges.**
  `one` → «1 dicionário importado» (singular noun + singular participle); `other` →
  «3 dicionários importados» (both plural). Gender and number agree in both. The
  0 case never reaches this plural (`settings_yomitan_empty_summary` covers it).
- **Grammar around placeholders, read with real values.** «Manter o modelo baixado
  (68 MB) ou **excluí-lo** para liberar espaço?» (correct enclisis, masculine
  *modelo*); «Espaço livre insuficiente … (**necessário:** 230 MB)» — restructured
  with a colon, dodging agreement entirely; «Ative-**o** para manter as frases…»
  (masculine *o histórico*); «Todas as linhas salvas serão **excluídas**» (feminine
  plural ✓); «A persona e as instruções **enviadas**» (feminine plural across a
  coordinated subject ✓); «A tradução está pausada — … **Ela** é retomada» (feminine
  ✓); «Hoje: 12.345 tokens» (locale-grouped ✓); «Tamanho do download: 128 MB» ✓.
  `tr_service_remove_title_fmt` = «Remover **o** %1$s?» is precedented by the
  committed `hymt_disable_title` / `qwen_mnn_disable_title` / `yomitan_delete_title`
  («Desativar o Hunyuan-MT?», «Excluir o Jitendex.org?») and reads as *o [serviço]
  OpenAI*; note the committed `settings_ocr_delete_title` drops the article
  («Excluir PaddleOCR?»), so dropping it here would also be defensible and would
  sidestep the *a OpenAI* (company, feminine) reading — left unflagged as the
  precedent supports both.
- **Cross-references into the committed file.** `ocr_source_label` = «Reconhecido
  por %1$s» is a structural mirror of the committed `translation_source_label` =
  «Traduzido por %1$s» ✓ (exactly what the glossary asks). `add_online_service_title`
  / `tr_service_add_online` use «serviço de tradução», matching the committed page
  title `settings_cell_translation_services` = «Serviços de tradução» ✓.
  `llm_backend_provider_label` = «Provedor» matches the committed
  `tr_service_order_footer` («a política de privacidade de cada **provedor**») ✓.
  `floating_menu_panel_overlays` = «Sobreposições» matches the committed
  `settings_overlay_mode_title` / `settings_hide_overlays_during_auto_mode` ✓.
  `anki_game_audio_row_subtitle`'s «cartões de frase» is byte-identical to the
  committed `anki_content_words_table` ✓. `settings_yomitan_empty_summary`'s
  «consultas de palavras» is byte-identical to the committed
  `onboarding_welcome_learn_body` ✓. `cd_choose_ocr` == `ocr_picker_title` ✓ (as in
  EN). «captura/capturadas» reuses the app's established capture verb ✓.
  `settings_debug_log_trace` keeping English *trace* matches the committed
  `settings_debug_log_pinhole` and `crash_dialog_message` («stack trace») ✓.
  «Segure» (hotkey combo) vs the committed «Mantenha pressionado» (UI long-press) is
  **not** drift — EN distinguishes them too (`status_hold_hint` = "Long-press").
- **Deliberate decisions honored:** `stream_kind_share_one_app` /
  `_share_entire_screen` follow the system consent dialog's own pt-BR wording (not
  re-derived from EN); `llm_prompt_kw_source_desc` / `_target_desc` keep *Japanese* /
  *English* in Latin as the literal runtime expansions; `llm_status_low_memory_badge`
  left untouched (its dash not flagged); `service_llm_badge` = «LLM» kept as the
  initialism per the glossary.

## Verdict (delta)

**Fix-then-ship.** 0 🛑 / 0 ❌ / 6 ⚠️ / 7 💬 across 174 keys. Nothing is
*wrong* — no mistranslation, no broken cross-reference, no agreement bug that fires
on a real runtime value, and the two highest-risk groups (the 38 `misc_*` chips and
the `Remover`/`Excluir`/`Limpar` triad) came through clean, including the
`misc_vulgar` false-friend trap. The six ⚠️ are all **consistency** defects: three
are one term/pattern rendered two ways inside a single screen (TTS on the trim row,
*prompt* vs *solicitação* on the services card, *Auto X* vs *tradução automática* on
the hotkeys screen), one is a precision loss that contradicts its own dialog title,
one is a Portuguese grammar slip after *informado*, and one is inherited
Android-wording drift from the English source. All have one-line fixes.

---

# Delta review round 2 — 2026-07-14

Fresh independent pass over the corrected file. Primary target: **regressions
introduced by the round-1 fixes** (19 strings changed). Every changed string
re-derived from scratch; every placeholder read with a real runtime value; the 38
`misc_*` labels re-checked for `.distinct()` collapse.

**Mechanical layer re-verified programmatically over all 174 keys:** every key
present and of the right type; `%1$s`/`%2$s`/`%d` parity with EN; every `<xliff:g>`
span byte-identical to EN (inner text **and** `id`/`example`); the bare
`{text} {source} {source_code} {target} {target_code} {N} {strings}` keywords
byte-identical in running prose; `<b>`/`\n`/`\{ \}`/`&lt;&gt;&amp;` counts match;
no raw `'` or `"`; `<plurals>` = one/other. **No 🛑.**

**Counts: 0 🛑 · 0 ❌ · 1 ⚠️ · 1 💬**

## Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| probe_initializing | ⚠️ | `Iniciando…` | `Inicializando…` (revert) | **Regression from round 1, on a false premise.** Round 1 shortened this "4 chars tighter" for a width-constrained chip. **The chip has no width constraint.** `StreamKindProbe.ProbeView.onMeasure()` does `setMeasuredDimension(SIZE_PX + (labelPaint.measureText(labelText) + 2*labelPadding))` — it *sizes itself from the localized string* — and its own comment says so: *"Width is MEASURED from the localized string — every locale fits exactly, no fixed guess."* The 4 characters bought **0 dp** (measured: 86.5 dp vs 106.7 dp chip, both fine). What they cost: (a) precision — «Iniciando…» is *Starting…*, not *Initializing…*; (b) it now **byte-collides with the committed `settings_ocr_downloading_msg` = «Iniciando…»**, whose EN genuinely *is* "Starting…". Two distinct EN states, one pt string. They never co-render, so nothing breaks — but the change is a pure loss and the revert is free. |
| hotkey_auto_hint_title · hotkey_auto_hint_dialog_title | 💬 | `…parar o %1$s automático` / `%1$s automático` | keep — but see note | **The fix is correct; this is a latent boundary condition.** Verified: `hintLabel` is filled from `overlay_mode_option_furigana` / `_pinyin` only (`HotkeysSettingsActivity:101`), and **both are masculine in pt** (*o furigana*, *o pinyin*) — so «o %1$s automático» agrees for every value the code can produce today. ✓ But the phrasing hard-codes masculine agreement, and `HintTextKind` already reserves a third value, `HARAKAT` (`Language.kt:217`, *"reserved so the architecture stays forward-compatible"*; deferred pending a diacritizer). Its natural pt label would be **feminine** («Vocalização») and would silently produce *«o Vocalização automático»*. Not a bug now — flagging so whoever wires HARAKAT up revisits this string (and the six other locales that took the same shape). Related, for the code owner: `HotkeysSettingsActivity`'s `when` maps HARAKAT through `else ->` to the **Furigana** label, so it would also mislabel the row. |

## The trim-button row — measured

The brief asked for a hard number. `activity_game_audio_trim.xml` bottom row:
`btnTrimUseTts` (TextButton) · 4dp · `btnTrimNoAudio` (TextButton) · `Space` ·
`btnTrimSave` (filled Button), inside a horizontal `LinearLayout` with 12dp padding.

**One correction to the brief's premise:** the row is not weight-free — there *is* a
`Space` with `layout_weight="1"` between NoAudio and Save. It changes nothing about
the failure mode: a weighted `Space` absorbs *positive* slack only. When content
exceeds the width, `LinearLayout` clamps its share to 0 and the **last** child
(`btnTrimSave`) is pushed past the right edge and clipped — it is never re-measured
smaller, because it sits *after* the weighted child and is therefore measured against
the full parent width, not the remainder.

Measured with real glyph advances (Roboto Medium, `TextAppearance.M3.Sys.Typescale.LabelLarge`
= 14sp + 0.00714em letter-spacing), including the **88dp `android:minWidth` floor**
that every `MaterialButton` inherits from `Base.Widget.AppCompat.Button`, and M3's
12dp/12dp (text) and 24dp/24dp (filled) horizontal padding:

| row | UseTTS | NoAudio | Save | **row total** | 360dp | 411dp |
|---|---|---|---|---|---|---|
| **pt-BR (current)** | «Usar TTS» → 88.0dp *(at the minWidth floor)* | «Sem áudio» → 91.3dp | «Usar seleção» → 130.1dp | **337.4dp** | **FITS, 22.6dp slack** | **FITS, 73.6dp slack** |
| pt-BR before round 1 | «Usar TTS em vez disso» → 169.4dp | 91.3dp | 130.1dp | 418.8dp | overflow 58.8dp | overflow 7.8dp |
| pt-BR w/ full term (rejected) | «Usar conversão de texto em voz» → 227.2dp | 91.3dp | 130.1dp | 476.6dp | overflow 116.6dp | overflow 65.6dp |
| EN (baseline) | "Use TTS instead" → 128.0dp | 88.0dp | 133.7dp | 377.7dp | overflow 17.7dp | fits, 33.3dp |

**`game_audio_trim_save` is fully visible and reachable on both a 360dp and a 411dp
phone.** Three things fall out of the numbers:

1. **The round-1 width exception was load-bearing and correctly called.** «Usar TTS em
   vez disso» overflowed 360dp by 59dp *and 411dp by 8dp* — Save was clipped on both.
   The full term («Usar conversão de texto em voz») overflowed every width. Settled
   decision confirmed, not re-litigated.
2. **«Usar TTS» sits exactly on the 88dp `minWidth` floor** (82.7dp of content clamped
   up to 88dp). Any label under ~64dp of text costs the same. Shortening it further
   would buy literally **0dp**; there is no remaining width lever here.
3. **pt-BR is now 40dp *narrower* than English.** The EN row (377.7dp) already
   overflows a 360dp phone by ~18dp at default font scale, clipping Save's right edge
   in English. That is a **layout defect, not a locale defect** — pt-BR is strictly
   safer than the source. Reported here for the code owner: `btnTrimSave` needs
   `layout_weight`/ellipsize, or the row needs to wrap. pt-BR's own headroom on 360dp
   runs out at about **fontScale 1.15** (row → 363dp); 411dp holds to ~1.3.

## Clean areas (round 2) — checked, no findings

- **All 19 round-1 edits re-derived. 18 are right; the 19th is the ⚠️ above.**
  `misc_female_speech`/`_male_speech` → «Fala feminina/masculina» **fixes a real
  false friend** the round-1 note undersold: *«Termo feminino»* in pt reads first as
  *feminine-gender word* (grammatical gender), which is not what the tag means. «Fala»
  kills that reading, matches the key name (`misc_female_speech`) and the EN comment
  ("used chiefly by/about women"), and collides with nothing («a fala original» in
  `anki_game_audio_row_subtitle` is a different screen and a different sense).
  `misc_slur` → «Injúria» separates cleanly from «Ofensivo» and keeps the
  group-directed force (*injúria racial*); it replaces a noun with a noun, so it does
  not disturb the set's noun/adjective mix. `error_capture_blocked_secure` → «o app
  capturado» now **byte-matches its sibling** `error_single_app_not_fullscreen` («o app
  capturado não está ocupando a tela inteira») — the fix created consistency rather
  than breaking it. `game_audio_trim_duration` → «Seleção: … · Gravação: …» dodges the
  *1,0 s selecionados* agreement bug and re-uses «seleção» from `game_audio_trim_play`
  /`_save`. `stream_kind_prompt_message` → «qual das duas opções foi compartilhada»
  (feminine agreement ✓) now echoes its own title `stream_kind_prompt_title` («Qual
  opção de compartilhamento…»). `settings_ocr_use_manga_subtitle` → «tem alta
  qualidade, mas é lento» is a normal pt null-subject clause (pro-drop) with the
  subject recoverable from the row title «Usar o MangaOCR» — no agreement slip left.
- **«solicitação» is fully purged** — 0 hits file-wide. *prompt* is now one masculine
  noun across all 10 `llm_prompt_*` strings that name it.
- **The three `llm_prompt_advisory_missing_*` are now uniform and grammatical.**
  «Falta {N}/{source}/{target}…: o modelo não saberá …» — singular «Falta» is correct
  with the *ou*-coordinated subjects; «não saberá **de** qual idioma está traduzindo»
  / «**para** qual idioma traduzir» take the right prepositions and mirror EN's own
  progressive-vs-infinitive split. All tokens verbatim.
- **`update_unknown_sources_message` names the real toggle.** Android pt-BR shows
  **«Instalar apps desconhecidos»** with a **«Permitir desta fonte»** switch; the
  string now says «ative "Permitir desta fonte" para o PlayTranslate». Findable.
- **The 38 `misc_*` labels are all distinct** — verified programmatically, including
  case- and accent-folded. **Nothing collapses through `renderMisc`'s `.distinct()`**
  (`MiscLabels.kt:31`). All four clusters still separate after the edits:
  offensiveness = Pejorativo · Ofensivo · Chulo · **Injúria** · Sensível;
  obsolescence = Arcaico · Obsoleto · Antiquado · Histórico · Raro;
  informality = Coloquial · Informal · Familiar · Gíria · Gíria da internet · Gíria de
  mangá; honorifics = Honorífico · Humilde · Cortês. The `misc_vulgar` false-friend
  trap stays correctly handled («Chulo», not «Vulgar»). Per the brief, **no label was
  considered for shortening** — they wrap, they don't truncate. The `misc_historical`
  = «Histórico» / `history_screen_title` = «Histórico» homograph is unavoidable in pt
  (one word for *historical* and *history*), the two never co-render, and both are the
  right word — checked and accepted.
- **Placeholders read with real values.** «Furigana automático» / «Pinyin automático»
  ✓ (both masculine — the round-1 assertion **verified in code**, not assumed);
  «Reconhecido por PaddleOCR» ✓ (mirrors the committed `translation_source_label`);
  «Remover o OpenAI?» ✓; «Hoje: 12.345 tokens» ✓ — confirmed locale-grouped in code
  (`UsageTracker.todayString()` = `NumberFormat.getNumberInstance(Locale.getDefault())`),
  so pt-BR really does get a dot as the thousands separator, not the EN comma;
  «Manter o modelo baixado (68 MB) ou excluí-lo…» ✓; «Ative-o…» ✓ (masc. *o
  histórico*); «Ela é retomada…» ✓ (fem. *a tradução*); «A persona e as instruções
  enviadas» ✓ (fem. pl. across a coordinated subject).
- **`settings_yomitan_count_summary` plurals.** one → «1 dicionário importado», other
  → «3 dicionários importados» — noun *and* participle agree in both. pt's CLDR `one`
  category also covers **0**, which would read «0 dicionário importado» — but
  `RootSettingsViewModel.yomitanDigest()` short-circuits `count == 0` to
  `settings_yomitan_empty_summary`, so the zero form is unreachable. Verified.
- **`game_audio_trim_duration` does not wrap.** «Seleção: 2.4 s · Gravação: 147 s» =
  213.7dp at 15sp (`Text.PT.RowTitle`) against 264dp available on a 360dp phone
  (48dp side insets); worst realistic case «10.0 s / 999 s» = 222.2dp. Only ~8dp wider
  than EN.
- **BR-vs-EU vocabulary, register.** Re-scanned all 174 delta keys: zero hits for
  transferir/ecrã/eliminar/guardar/aplicação/telemóvel/ficheiro/utilizador/registar/
  "está a …". Zero tu/teu/tua. `você` appears in exactly the four places where 2nd
  person is natural. BR orthography markers correct (Onomatopeia, mangá, Irônico,
  Cortês, Injúria).
- **The four delta strings identical to English are all correct, not leftovers:**
  `misc_familiar`, `misc_formal`, `misc_informal` (the pt words *are* these), and
  `service_llm_badge` = «LLM» (settled initialism).
- **Short-label expansion.** Nothing in the delta lands in the tiny-label danger zone
  (8sp bottom bar, two-line capture button). The worst ratios — «Personalizado» (2.2×),
  «Escolher ferramenta de OCR» (1.7×), «Salvar mesmo assim» (1.6×) — sit in dialog
  buttons, dialog titles and settings rows, all of which wrap or have room.
- **Settled decisions honored, not re-litigated:** `game_audio_trim_use_tts` = «Usar
  TTS» (width exception — and now *measured*, see above); the AOSP share-button
  wording; `llm_prompt_kw_source_desc`/`_target_desc` keeping *Japanese*/*English*;
  `llm_status_low_memory_badge`'s dash; `misc_*` never shortened for width. The two
  round-1 💬s that were declined (`misc_yojijukugo`, `audio_source_game_ready`) are
  not re-filed — declining `misc_yojijukugo` is now positively *correct* under the
  no-shortening rule.

## Code defects visible from pt-BR (not locale bugs — do not fix in `values-pt-rBR`)

- Already known: `GameAudioTrimActivity` / `SentenceAnkiContentFragment` format
  seconds with `Locale.US`, so `game_audio_trim_duration` renders **«Seleção: 2.4 s»**
  where pt-BR requires **«2,4 s»**. `humanSize()` likewise forces a decimal point —
  «Tamanho do download: 1.2 GB» should be «1,2 GB». (The *unit* half of the
  `humanSize()` bug is invisible here: pt-BR uses MB/GB anyway.)
- New, from this pass: the trim button row overflows a 360dp phone **in English**
  (see the table above) — `btnTrimSave` needs a weight or the row needs to wrap.
- New, from this pass: `HotkeysSettingsActivity`'s `hintLabel` `when` sends
  `HintTextKind.HARAKAT` through `else ->` to the *Furigana* label. Dormant today
  (HARAKAT is reserved and unused), but it will mislabel the row and break pt's
  masculine agreement the day it's wired up.

## Verdict (round 2)

**SHIP.** 0 🛑 / 0 ❌ / 1 ⚠️ / 1 💬 across 174 keys. The round-1 corrections landed
cleanly: 18 of 19 are right, several are better than their own rationale claimed, and
none introduced an agreement, terminology or cross-reference regression. The single ⚠️
(`probe_initializing`) is a free revert with zero user-visible impact, and the 💬 is a
forward-looking note, not a defect. The two highest-risk surfaces both came through:
the 38 `misc_*` chips are all distinct with every cluster intact, and the trim row now
fits with 23dp to spare on a 360dp phone — 40dp narrower than the English source.

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
quotes; `<plurals>` categories exactly one/other. `./gradlew :app:processDebugResources`
is green. **No 🛑 build-breaking issues.**

### Findings (delta) — all applied

| name | severity | was | now | why |
|---|---|---|---|---|
| `settings_ocr_note_mlkit` | ⚠️ | "Rápido, mesmo em telas com muito texto" | "Ágil, mesmo em telas com muito texto" | The English comment forbids reusing the literal Fast tier label; the first pass reused «Rápido», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

**Brazilian vocabulary only** — tela (never ecrã), arquivo, salvo (never guardado), app, buscar, remover — and **você** throughout. The definite article before the brand follows the committed file: «O PlayTranslate … é a versão mais recente» (`update_none_message`), «a acessibilidade está ativada para o PlayTranslate» (`a11y_stuck_message`), matching `update_dialog_message` and `anki_permission_rationale_message`. **mecanismo** was chosen for *engine* because it is Android's own pt-BR word for a pluggable engine, leaving **ferramenta** for *tool* and **modelo** for the downloadable model — all three meet in `settings_ocr_delete_camera_import_note`. **instantâneo** for the camera freeze-frame keeps «captura de tela» free (`anki_group_screenshot`). “ ” quotes in `a11y_stuck_message_xiaomi`. `settings_support_check_updates_title_available` byte-matches `update_dialog_title` (Atualização disponível). Plurals one/other, with agreement read at each band.

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

Keys under review: `card_words_in_sentence`, `anki_added_sentence_success`,
`anki_added_word_success`, `game_audio_zoom_hint`, `anki_first_field_unmapped`,
`anki_first_field_empty`, `history_hide_translations_toggle_title`,
`history_hide_translations_toggle_subtitle`.

Mechanical layer verified programmatically over these 8 keys: all present, no extras;
placeholder multisets identical to EN (`%1$s` in the two first-field strings, none
elsewhere); every `<xliff:g>` span byte-identical to EN including `id` and `example`
(`field_name`/`Key`, `brand_anki`/`Anki`); `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;`
counts match; no raw `'` or `"` in visible text; `name="…"` untouched; Anki left
untranslated. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `game_audio_zoom_hint` | ⚠️ | "Pince para mostrar mais ou menos áudio" | "Aproxime ou afaste os dedos para mostrar mais ou menos áudio" | *Pinçar* is a Microsoft-terminology rendering of "pinch", not the wording Brazilian users meet on Android — Google's pt-BR (Maps, Fotos, Acessibilidade) says **"Aproxime ou afaste os dedos" / "Junte ou afaste os dedos"**. Bare imperative "Pince" gives no clue that two fingers are involved, and the comment says this caption exists precisely because pinch is *the one gesture with no visual affordance* — so it has to teach the gesture, not name it. Every sibling locale that isn't calquing a Latin cognate spells the gesture out (ru «Сведите или разведите пальцы», de "Ziehe zwei Finger zusammen oder auseinander", ko 두 손가락을 모으거나 벌려, vi "Chụm hai ngón", ar قرّب إصبعيك أو باعد بينهما, tr "iki parmağınızla sıkıştırın"); fr *Pincez* / es *Pellizca* work only because those verbs are the established gesture words in their locales, and *pinçar* is not in pt-BR. **Brevity is not a defence here:** the caption is a `match_parent` / `wrap_content` `TextView` at 11sp in `anki_game_audio_panel.xml` with no `maxLines` and no `ellipsize`, so it wraps freely — de already ships 82 chars in that exact view against pt-BR's 38. The long form (57 chars) costs one wrapped line and buys a gesture the user can actually perform. |

### Clean areas (delta) — checked, no findings

**Quoting convention is defensible.** Both first-field strings use curly “ ” — the
locale's documented quote pair, and a per-string mirror of EN, whose own comment
declares "The “ ” curly quotes are intentional typography". That is the rule the whole
pt-BR file already follows: curly where EN is curly or where EN has no quotes and pt-BR
adds them for an inline name (`anki_content_pitch_position_desc` “PitchPosition”,
`anki_content_frequency_values_desc` “Frequency”, `settings_ocr_footer_guidance`,
`a11y_stuck_message_xiaomi` “Sem restrições”), escaped `\"` where EN is escaped
(`anki_content_source_pick_title`, `status_no_text`, `onboarding_a11y_enable_title`,
the `anki_content_flag_*_desc` "x" markers). Note for **upstream, not for this locale**:
`anki_first_field_unmapped` (curly) and `anki_content_source_pick_title` (straight) quote
the *same* user field name on surfaces that appear seconds apart — the toast fires and
the mapping dialog opens right behind it — so the user sees “Key” then "Key". pt-BR
reproduces EN's own split byte-for-byte; the inconsistency lives in
`values/strings.xml`.

**One-tap toasts — agreement and compound both hold.** *Cartão* is masculine, so
"Cartão de frase **adicionado**" / "Cartão de palavra **adicionado**" agree correctly
(the trap here is a translator reaching for *carta*/*tarjeta*-style feminine agreement;
es and fr legitimately have "añadida"/"ajoutée" because their noun is feminine). The
compounds match the mode chips the toast is making visible — `anki_mode_sentence`
= "Frase", `anki_mode_word` = "Palavra" — and "cartão de frase" is the file's existing
term (`anki_game_audio_row_subtitle`, `anki_content_words_table`,
`anki_content_flag_sentence`), so the toast reads as the same object the rest of the UI
names. Naming the subject where `anki_added_no_audio` leaves it bare ("Adicionado ao
Anki (áudio indisponível)") is right, not a divergence: these two strings exist *because*
the applied mode is otherwise invisible.

**"nota" is the correct term, and it is AnkiDroid's own.** Anki/AnkiDroid ship
pt-BR with the Note/Card distinction intact — *nota* vs *cartão* — so a user who reads
"o Anki usa o primeiro campo para identificar a nota" maps it straight onto what
AnkiDroid shows them. Introducing it here rather than collapsing it into *cartão* is the
better call, because the sentence is specifically about note-level duplicate identity.
Residual friction is upstream: the app says *card type* (pt-BR "tipo de cartão",
`anki_field_mapping_unconfigured`, `anki_card_type_no_models`) where AnkiDroid pt-BR says
*tipo de nota*. That mismatch is inherited from the EN source and must not be "fixed"
in the locale file alone.

**`anki_first_field_unmapped` — verb, preposition, article, length all check out.**
*Mapear* is the file's mapping verb (`anki_content_source_pick_title` = "Mapear",
`anki_card_type_basic_no_mapping` = "mapeamento de campos"), and "Mapeie" is the você
imperative the file uses throughout ("Configure", "Salve", "Toque em"). "no campo “X”"
earns its extra words: it supplies a masculine head noun in front of a *user-defined*
field name, so *nenhum* value of `%1$s` can drag the sentence into a gender error — the
same protection the fronted "O campo" gives `anki_first_field_empty`. "para que o Anki
identifique" follows the file's brand-article convention (`anki_send_failed_message`
"O AnkiDroid não aceitou o cartão", `anki_not_installed_message`, `anki_models_unavailable`
"conectar ao AnkiDroid"). On the two-line toast clamp: 66 chars with a 3-char field name
is mid-pack, not a pt-BR outlier (es 66, tr 69, de 62, fr 62, ru 57, EN 51) — the clamp
risk is shared and comes from long user field names, not from this translation. If
headroom is ever wanted, "para o Anki identificar a nota" (personal infinitive, equally
idiomatic in BR) trims it without touching the gender anchor.

**`anki_first_field_empty` reads as a native alert.** Fronting "O campo" before the
quoted name is the load-bearing choice — "“Expressão” está vazio/vazia" would be
unresolvable, "O campo “Expressão” está vazio" is always right. "então" as the
consequence connector matches EN's own conversational "so" and sits correctly in a
você-register app (*portanto* would over-formalize). *neste cartão* (the card at hand)
vs anaphoric *esse campo* (the field just named) is the standard BR este/esse split, not
a slip. Making "it needs a value" explicit as "esse campo precisa ter um valor" resolves
an ambiguity the EN leaves open (field or note?), and the alert has no length budget to
protect.

**History strings sit inside their family.** *Ocultar* is the file's established hide
verb (`overlay_hide_for_now`, `settings_hide_overlays_during_auto_mode`,
`cd_toggle_translation_visibility`, `floating_icon_close_label_hide`) — no second verb
introduced. *texto capturado* reuses the app's captured-verb (`history_toggle_subtitle`
"frases capturadas", `settings_cell_history_summary_*`, `tr_service_order_footer` "o
texto capturado"), and the sentence/text split mirrors EN's own. *linha* for a History
row matches `history_empty_none` ("As linhas aparecem…") and
`history_clear_confirm_message` ("Todas as linhas salvas"). The 3rd-person + imperative
mix is EN's structure and the siblings' style: "Mostra apenas…" has the same implicit
subject as "Salva as frases capturadas" (`history_toggle_subtitle`) and "Guarda uma
foto…" (`history_capture_image_toggle_subtitle`), while "Toque em uma linha" matches
`anki_words_helper` ("Toque em uma palavra para destacá-la"). **Dropping EN's possessive
("its translation" → "a tradução") is the right call, not an omission** — in a você-register
pt-BR app "sua tradução" reads first as *your* translation, and "a tradução dela" is
clunky; the referent is pinned by "uma linha" three words earlier in the same sentence.

**`card_words_in_sentence`.** "Palavras na frase" is sentence case, so the card CSS
`text-transform` yields PALAVRAS NA FRASE cleanly (no accents to mangle); *frase* matches
`anki_mode_sentence`; 17 chars, same as EN. The na/da choice is genuinely split across
the locale set (es "Palabras **en** la frase", fr "Mots **de** la phrase") — pt-BR's
reading is fine either way and not worth a churn on a string that is baked into every
sentence card already sent.

### Verdict (delta)

One ⚠️ (`game_audio_zoom_hint`), no ❌, no 🛑. The other seven are clean.
