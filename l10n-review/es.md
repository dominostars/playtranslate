# Spanish (values-es) targeted review

*(Targeted hotlist pass + whole-file scans, not a full string-by-string review.)*

## Findings

| name | severity | current | suggested | note |
|---|---|---|---|---|
| hymt_legal_message | ❌ | "Al tocar Aceptar, declaras y garantizas que:" | "Al tocar \"Acepto: activar Hunyuan\", declaras y garantizas que:" (or at minimum "Al tocar \"Acepto\"") | The button (hymt_legal_agree) is **"Acepto: activar Hunyuan"** — there is no "Aceptar" button in this dialog. Same exact-match failure as the other 5 languages. Everything else in the legal block is solid: §5(b) kept, "la Unión Europea, el Reino Unido y Corea del Sur" kept, "declaras y garantizas" carries the affirm-and-warrant force, and clause (1) "No resides ni te encuentras actualmente en…" covers both residing and located with a single clean negation. |
| settings_header_ocr | ⚠️ | "Imagen a texto (OCR)" | "Reconocimiento de texto (OCR)" | "Imagen a texto" is a calque of "Image-to-text"; not idiomatic as a section header. |
| status_idle | ⚠️ | "Toca Traducir para capturar la pantalla del juego" | "Toca \"Traducir\" para capturar la pantalla del juego" | Genuine garden path: *tocar + infinitive* is the idiom "it's someone's turn / it's time to" — "toca traducir" reads as "time to translate." Quoting the button name kills the misreading. Name matches the real button (translate_button_prefix_translate = "Traducir"). |
| status_hold_hint | ⚠️ | "Mantén presionado Regiones o Auto para ver menús de selección rápida" | "Mantén presionado \"Regiones\" o \"Auto\" para ver menús de selección rápida" | Unmarked button names; "presionado Regiones" also momentarily reads as a failed participle agreement (Regiones is fem. pl.). Quotes fix both. Names do match nav_regions = "Regiones", live_mode_auto_label = "Auto". |
| tts_language_unsupported_with_engine_message | ⚠️ | "…pero no es compatible con \<lang\>." | "…pero no es compatible con el \<lang\>." | Renders "no es compatible con japonés" — Spanish needs the article with language names ("con el japonés"). Safe fix: all Spanish language names are masculine, so "el" before the placeholder always agrees. Same fix for **tts_language_unsupported_unknown_engine_message** ("…activo no es compatible con el \<lang\>"). |
| pack_upgrade_mandatory_message | ⚠️ | "Actualiza ahora o elimínala para elegir otro idioma." | "Actualiza ahora o elimina el paquete instalado para elegir otro idioma." | "elimínala" has two feminine candidates — "Esta actualización" (the clause subject) and "la versión instalada". Reading it as "delete the update" is the natural-but-wrong parse. Name the referent explicitly. |
| settings_capture_interval_hint | ⚠️ | "Mínimo \<n\> segundos." | "Mínimo: \<n\> s." | When the value is "1" this renders "Mínimo 1 segundos" — ungrammatical. The unit abbreviation sidesteps agreement for both "0.5" and "1". (EN has the same flaw, but es shouldn't inherit it.) |
| accessibility_dialog_message | ⚠️ | "Ajustes → Accesibilidad → Apps instaladas → …" | "Ajustes → Accesibilidad → Aplicaciones descargadas → …" | Stock Android Spanish names that accessibility section «Aplicaciones descargadas»; "Apps instaladas" is inherited from the EN drift ("Installed apps"). Same path in **overlay_icon_a11y_required_message**. "Ajustes → Accesibilidad" itself matches Android es. |
| quick_tile_add_row_title | 💬 | "Añadir mosaico a Ajustes rápidos" | judge: "Añadir función a Ajustes rápidos" or keep | Cross-locale mix: "mosaico" is the es-419 SystemUI term for tile, while "Ajustes rápidos" is the es-ES name (es-419 says "Configuración rápida"; es-ES QS edit uses "funciones"). Understandable as-is; flagging for awareness, not insisting. Same word in **settings_hotkeys_tile_add** ("Añadir mosaico") — at least it's internally consistent. |
| floating_menu_btn_capture_region | 💬 | "Región de\ncaptura" | "Capturar\nregión" | First line is 9 chars vs EN's 7 at 9sp under a 54dp icon — borderline. The verb form is also more action-shaped for a menu button, and shorter (8/6). |

Checked clean: live_mode_auto_with_hint ("Auto Furigana" parallels the "Auto" toggle — fine); anki_sort_field_empty ("errores de rechazo por duplicado al enviar" is clear, no gibberish calque); anki_permission_rationale_message / anki_settings_grant_access_subtitle (comma cleanly separates "…a Anki, PlayTranslate necesita…", and "Toca Continuar" matches btn_continue = "Continuar"); label_region_drag_hint (repeating "arrastra el centro para mover todo el cuadro" preserves the middle-only scoping); translate_button_prefix_translate/_reload ("Traducir/Recargar Pantalla completa" composes fine); backend_cooldown_status_fmt + retry_at/retry_on ("Límite… · Reintentar a las 3:42" / "Reintentar el 1 jun" read naturally; only edge case is "a las 1:00" where "a la" would be strictly correct — not worth a string change); onboarding_a11y_title / mp_overlay_permission_title ("Mostrar sobre otras apps" = exact es-419 Android wording; es-ES says "aplicaciones" but this is fine for neutral); crash_dialog_discard ("Descartar" — exactly right, not Cancelar/Eliminar); btn_clear ("Borrar" is the standard Android es term for Clear, correct for wiping a field); nav_settings/live_mode_pause_label/nav_regions/live_mode_auto_label ("Ajustes"/"Pausar"/"Regiones"/"Auto" — all short, agreed Settings term used, no Configuración anywhere).

## Scan results
- **Apostrophes:** 0 unescaped `'` in the entire file (PCRE negative-lookbehind scan) — clean.
- **Register:** no `usted`/`ustedes`, no vosotros forms, no -áis/-éis verbs; tú-imperatives throughout; verbs uniform ("toca" ×11, "presionado/presionas" ×12, zero "pulsa") — clean.
- **Inverted punctuation:** all 21 strings containing ?/! open with ¿/¡ (incl. mid-string questions like "…¿Continuar?"); no ?/! in multi-line continuation content — clean.
- **Brands:** PlayTranslate ×36, DeepL ×13, AnkiDroid ×15, Anki always as-is; no translated brand forms found — clean.
- **Regionalisms:** zero hits for ordenador/computadora/coger/enchufar/celular/vosotros — vocabulary is neutral throughout.

## Verdicts
- **Register:** pass — consistent informal tú, neutral international.
- **Terminology:** pass — mazo (8, no "baraja"), atajo (7), texto a voz (8, no "síntesis de voz"), captura de pantalla (9), Accesibilidad (14), "red de uso medido" (8, internally consistent and acceptable — Android uses both "de/con uso medido" across surfaces), paquete(s) de idioma consistent ("paquete de definiciones" is a distinct, correct concept).
- **Android-settings wording:** mostly pass — «Ajustes», «Accesibilidad», «Ajustes rápidos», «Mostrar sobre otras apps» all match; the one drift is «Apps instaladas» vs stock «Aplicaciones descargadas» (inherited from EN).
- **Inverted punctuation:** pass.
- **Legal text:** fix required — content and force are correct, but the quoted button name ("Aceptar") doesn't match the actual button ("Acepto: activar Hunyuan").
- **Truncation:** pass — all 8sp bar labels short; one 💬 on the two-line floating button.
- **Overall:** **fix-then-ship** — one ❌ (legal button mismatch) and six ⚠️, all one-line string edits; no structural problems. Caveat: this was a targeted hotlist pass, not a full review.

---

# Delta review — 2026-06-23 sync (+29 keys)
Scope: Anki pitch/frequency content options, OpenAI custom base URL, Yomitan multi-file import + auto-update, Anki audio picker. Mechanical layer re-verified programmatically (analyzer 0/0; placeholder parity; plural CLDR sets; processDebugResources BUILD SUCCESSFUL) — no 🛑.

## Findings (delta)

**Zero findings.** All 29 keys read as native-quality, neutral-international Spanish, with consistent tú-register and terminology. The one structural trap a reviewer would expect to flag here — the curly-quote → escaped-straight-quote conversion on field-name literals — is in fact the **correct** house convention, so it is intentionally *not* flagged (see below). No ❌/⚠️/💬.

| name | severity | current | suggested | note |
|---|---|---|---|---|
| — | — | — | — | (no findings) |

## Clean areas (delta)

- **¿ / ¡ inverted punctuation:** none of the 29 strings is a question or exclamation (all are labels, descriptions, status lines, or list prefixes), so no opener is required and none is missing. Verified by scanning every in-scope line for `?!¿¡` — zero hits. No risk here this sync.
- **tú-register + neutral vocab:** every imperative/verb form is tú and matches its siblings — `Usa https://` (`llm_backend_base_url_invalid`), `Úsalo solo en tarjetas de JPMN` (`anki_content_frequency_stylized_desc`, parallels the existing `anki_content_flag_*_desc` "Úsalo…" rows), `Importando … de …` (`yomitan_importing_progress`). No `usted`, no vosotros (`-áis/-éis`), no regionalisms. `yomitan_auto_update_subtitle` uses the descriptive **infinitive** ("Descargar e instalar automáticamente…"), which is the file's convention for toggle subtitles that describe an automatic behavior rather than command the user (cf. `yomitan_single_dict_subtitle`, `tts_voice_default_subtitle`) — correct, not a register slip.
- **Agreement around placeholders (the `summary_count` plural):** both forms agree — `one` = "Se importó %1$d de %2$d **diccionario**." / `other` = "Se importaron %1$d de %2$d **diccionarios**." Verb (importó/importaron) and noun (diccionario/diccionarios) both track quantity; the one-form even reads correctly at the literal "1 de 1 diccionario." The list-prefix lines use number-agnostic phrasing that works for a 1-or-many comma list: `No se pudieron leer:` / `Espacio insuficiente:` / `Ya importados:` / `Error:`. `yomitan_import_summary_more` plural ("+%1$d más") is invariant in Spanish and correct for both forms.
- **Terminology reuse (all match the rest of the file):** *acento tonal* / *descenso tonal* (`anki_content_pitch_position*`) align with `yomitan_category_pitch_accent` = "Acento tonal" and `yomitan_page_description`; *frecuencia* aligns with `yomitan_category_frequency`; *diccionario* (25×) and *tarjeta* (38×, zero "carta") are uniform; *Texto a voz* (`audio_source_tts_name`) matches `settings_header_text_to_speech` / `settings_cell_tts` (no "síntesis de voz"); *palabra resaltada* (9×) is the consistent rendering of "highlighted word"; *URL personalizada* parallels the existing `…_custom_entry` "Personalizado…"; *Avanzado* is the standard Android section header. Failure strings reuse the dominant "No se pudo…" pattern (`audio_error_loading` = "No se pudo cargar", `yomitan_import_summary_title_none` = "No se pudo importar"). Brand names (Lapis, JPMN, Wikimedia Commons, Yomitan) left untranslated.
- **The `\"FieldName\"` / `Ejemplo:` rule (deliberately not flagged):** the four `anki_content_*_desc` strings localize the explanatory prose, keep the literal field names verbatim (`\"PitchPosition\"`, `\"PAOverride\"`, `\"Frequency\"`, `\"FrequenciesStylized\"`, `\"FreqSort\"`, `\"FrequencySort\"`), and translate "Example:" → "Ejemplo:" while leaving the sample `0,2` as-is — all per spec. The EN curly quotes around those literals are rendered as **escaped straight quotes** `\"…\"`, which is the established Spanish-file convention (39 `\"` occurrences across the Anki `_desc` block and elsewhere; the lone curly-quote string, `onboarding_a11y_enable_title`, quotes an Android setting name, a different case). Converting to straight quotes here is therefore correct and consistent, not a deviation.
- **Short-label truncation:** the new short labels are all comfortably sized for their surfaces — `audio_source_picker_title` "Audio" (toolbar title), `audio_no_results` "Sin resultados", `audio_loading` "Cargando…", `llm_backend_advanced_header` "Avanzado", `yomitan_auto_update_label` "Actualización automática" (toggle row title; full-width, fine). None sits in a tiny bottom-bar slot, so no shortening needed.
- **Placeholder/markup integrity (spot-confirmed alongside the quality pass):** `yomitan_importing_progress` keeps both `%1$d`/`%2$d` spans; `summary_duplicates/invalid/no_space/failed` keep the single `%1$s` names span; both plurals keep their count spans; em-dash in `llm_backend_base_url_invalid` preserved, with "LAN" sensibly expanded to "red local (LAN)". No unescaped apostrophes in any of the 29 lines.

**Verdict:** ship as-is — no edits needed for this delta. (Scoped to the 29 synced keys; pre-existing findings above are unchanged.)

---

# Delta review — 2026-07-14 sync (174 keys)

Scope: the 174 delta keys (170 new + 4 changed) — game-audio capture/trim, History screen, Advanced LLM prompt editor, in-app updater, translation-service rows, stream-scope prompt, and the 38 `misc_*` dictionary chips. Independent review; findings are mine, not the translator's.

**Mechanical layer verified programmatically (delta only):** all 174 names present, none missing; every `%1$s`/`%2$s`/`%d` placeholder present and matching; all `<xliff:g>` spans byte-identical to EN in inner content, `id` and `example`; `\n` count matches (`floating_menu_capture_screen` keeps its break); `<b>`, `\{ \}`, `&lt;/&gt;` counts match; **0** raw/unescaped `'` or `"` in delta text content; `<plurals>` CLDR set is `one`/`other`; the eight literal keyword tokens (`{text}` `{source}` `{source_code}` `{target}` `{target_code}` `{context}` `{N}` `{strings}`) all survive byte-identical, including the three that appear as bare literals in running prose. File parses as XML. **No 🛑 build-breaking issues.**

The one EN/ES glyph delta is intentional and correct: `update_dialog_download` renders EN's `&amp;` as the conjunction — and as **"e"**, not "y", which is the right form before *instalar*. Same correct choice in `update_error_incomplete` / `_verification` ("**u** obtén", not "o obtén").

## Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| misc_female_speech<br>misc_male_speech | ❌ | "Término femenino"<br>"Término masculino" | "Habla femenina"<br>"Habla masculina" | **Reads as grammatical gender, not speech register.** The tag is `FEMALE_SPEECH`, whose vocabulary aliases are *"female term or language"* / *"female speech"* (`misc_vocabulary.json`) — a *register* mark meaning "used chiefly by/about women". But `misc_*` chips render **side by side with `pos_*` chips**, so the user sees `[Sustantivo] [Término femenino]` and reads "feminine noun". And these tags are not Japanese-only: `MiscVocabulary` feeds `scripts/wiktionary_filters.py filter_misc`, so they render on **every** source pack — including es/fr/de, where nouns genuinely *do* have gender and the misreading is not a misreading at all, it's the default one. "Habla femenina/masculina" is the standard Spanish linguistic term for 女性語/男性語 and is also **shorter** (14/15 vs 16/17). (Alt if brevity wins: "Uso femenino" / "Uso masculino".) |
| game_audio_trim_duration | ⚠️ | "\<seconds\> s seleccionados · \<total_seconds\> s **grabados**" | "Selección: \<seconds\> s · Grabación: \<total_seconds\> s" | **Number disagreement, confirmed reachable.** `%2$s` is `(totalDurationMs / 1000).toString()` (`GameAudioTrimActivity.kt:206`, `SentenceAnkiContentFragment.kt:293`) — a bare integer. The code's own comment says the buffer "holds only what has played since recording started this session", i.e. a tiny total is exactly the case this readout exists to explain → **"1 s grabados"** (should be *grabado*). `%1$s` is always `"%.1f"` so it is already safe (Spanish takes plural after a decimal). The label:value form kills agreement at every count and is the same shape already adopted for `settings_capture_interval_hint` ("Mínimo: \<n\> s."). Reads fine in both render sites (waveform header and card-editor row title). |
| update_error_no_space | ⚠️ | "…la actualización (**se necesitan** \<needed\>)." | "…la actualización (espacio necesario: \<needed\>)." | **Number disagreement, confirmed reachable.** `humanSize()` (`LlmModelUtils.kt:11`) emits `"%.0f MB"` and `"%d KB"` — **integers** — so "1 MB" / "1 KB" are real values, and `needed` is a *shortfall*, which is exactly where small values live → "se necesitan 1 MB". The label form is agreement-free at every value and keeps EN's parenthetical. |
| audio_source_game_enable_hint | ⚠️ | "No se está grabando: **actívalo** para capturar el audio del juego y usarlo en futuras tarjetas" | "La grabación está desactivada: **actívala** para capturar el audio del juego y usarlo en futuras tarjetas" | **Dangling masculine clitic.** "actívalo" has no masculine antecedent: the referent primed by "No se está grabando" is *la grabación* (fem.), and the only masculine noun in the sentence is *el audio del juego* — which you don't activate. The file's own sibling gets this right by naming the subject first: `history_empty_off` = "El historial de texto está desactivado. **Actívalo** para…". The suggestion reuses that shape *and* the file's existing wording for this very feature (`anki_game_audio_permission_denied` = "la grabación … permanece **desactivada**"). (Lighter alt: "…: **activa esta opción** para…".) |
| update_dialog_metered_note | ⚠️ | "Estás usando una **conexión** de uso medido." | "Estás usando una **red** de uso medido." | Terminology drift. The file's established term is **"red de uso medido"** — 4× in the committed file (`qwen_mnn_metered_warning_title`, `qwen35_2b_mnn_…`, `gemma_e2b_mnn_…`, `hymt_metered_warning_title`), plus "Esta **red** está marcada como de uso medido" ×4. The domain glossary names this exact string ("metered connection → reuse the existing metered-network term"). Tracking EN's head noun introduces a second surface form for one concept. |
| llm_status_low_memory_badge | ⚠️ | "Memoria insuficiente: traduciendo **con alternativa**" | "Memoria insuficiente: traduciendo **con una alternativa**" | Missing article. Spanish does not drop the article before a countable singular noun the way English does; "con alternativa" is not idiomatic even in terse status text. The file's own correct bare use is *after* «como»: `tr_service_offline_footer` "se usan **como** alternativa" — which is a different construction. Still comfortably short for the row (51 chars). *(Dash deliberately not flagged, per brief.)* |
| misc_yojijukugo | 💬 | "Compuesto de cuatro caracteres" (30) | "Compuesto de 4 caracteres" (25) | Truncation risk on a width-constrained chip: it is **2× the longest `pos_*` sibling** ("Verbo auxiliar", 14) and 13 chars longer than the next-longest `misc_*`. Describing it is correct (Spanish has no native term, and the glossary forbids romanizing "yojijukugo") — just tighten it. |
| misc_idiomatic | 💬 | "Idiomático" | "Modismo" | As a bare chip, "Idiomático" reads as *"relating to language"*; the collocation that carries the sense is "expresión idiomática". **"Modismo"** is the standard Spanish lexicographic term for an idiomatic expression (and the tag's own alias is literally `idiomatic expression`), is shorter, and collides with neither `pos_phrase` = "Locución" nor `pos_expression` = "Expresión". |

## Clean areas (delta) — checked, no findings

- **Inverted punctuation.** All **7** questions in the delta open correctly, including the two the brief called out as mid-string/placeholder risks: `history_clear_confirm_title` «¿Borrar todo el historial?», `history_delete_confirm_title` «¿Eliminar esta entrada?», `llm_prompt_discard_title` «¿Descartar los cambios?», `settings_ocr_disable_manga_title` «¿Desactivar MangaOCR?», `settings_ocr_disable_manga_msg` «¿Conservar el modelo descargado (…) o eliminarlo…?», `stream_kind_prompt_title` «¿Qué opción de uso compartido elegiste?», `tr_service_remove_title_fmt` «¿Quitar \<service\>?». Zero exclamations in the delta, so no `¡` is owed. Programmatic `?`/`¿` and `!`/`¡` count parity: clean.
- **Register.** Zero `usted`/`ustedes`, zero vosotros forms, zero `-áis`/`-éis`. (The only regex hit, `game_audio_trim_use_tts` "en **su** lugar", is the fixed idiom for "instead", not a possessive.) The file's three-way system holds across the delta: **buttons/content-descriptions = infinitive** (Copiar, Eliminar, Descartar, Restablecer, Quitar, Reintentar, Detener, Abrir ajustes, Descargar e instalar, Guardar de todos modos, Usar/Reproducir la selección, Eliminar/Conservar modelo, Añadir a Anki, Cambiar el idioma de origen/destino, Elegir herramienta de OCR, Editar región, Quitar servicio); **instructional prose = tú imperative** (Introduce la URL…, Importa diccionarios…, Actívalo…, Inténtalo de nuevo…, Toca para iniciar o detener…, Mantén presionado…); **titles = noun phrase or infinitive** (Historial, Error de actualización, Recortar el audio del juego, Añadir servicio de traducción). **No imperative button among infinitive siblings.** `llm_prompt_warning_title` "Revisa este prompt" is the delta's only imperative *title* — considered and passed: EN is imperative there too ("Check this prompt"), it is an advisory call-to-action, and its fatal sibling correctly stays a statement ("No se puede guardar este prompt"), mirroring EN's own pairing.
- **Prose-imperative vs button-infinitive is not a cross-reference break.** `update_error_incomplete`/`_verification` say "Inténtalo de nuevo" in the body while the button (`update_error_retry`) says "Reintentar". In EN both are "Try again". This is the register system working as designed, not drift — EN neither quotes nor capitalizes it as a button citation.
- **The *prompt* / *instrucción* collision does not exist.** "prompt" is the single noun across all 20 `llm_prompt_*` keys (44 occurrences, masc.); **"instrucciones" appears exactly once in the whole file** — in `llm_prompt_row_system_subtitle`, precisely where EN says "instructions". "petición" appears exactly twice — in the two subtitles where EN itself says "request". No term is doing double duty.
- **The *Obsoleto* collision does not exist.** `deprecated_badge_label` = "Obsoleto" (committed); `misc_obsolete` = **"Desusado"** — which is not a dodge but the *correct* DLE mark (`desus.`). Deliberate and right.
- **Remove / Delete / Clear stay three distinct verbs**, exactly as EN keeps them: **Quitar** (services — `tr_service_remove_confirm`, `_title_fmt`, `_delete_cd`), **Eliminar** (entries/models — `history_action_delete`, `settings_ocr_disable_delete`), **Borrar** (clear-all — `history_clear_menu`, matching committed `btn_clear`/`lang_search_clear_cd`). `tr_service_remove_message` nails the distinction in one sentence: "Esto **quita** el servicio de la lista y **elimina** su clave de API guardada." `history_clear_confirm_message` correctly uses "se eliminarán" because **EN's own message says "deleted"** under a "Clear" action.
- **`settings_yomitan_count_summary` plurals.** `one` = "%d **diccionario importado**", `other` = "%d **diccionarios importados**" — noun *and* participle both track the count, and the `one` form reads correctly at the literal "1 diccionario importado". CLDR set is `one`/`other`. Correct.
- **The other placeholder-adjacent strings read correctly with real values.** "¿Quitar **OpenAI**?" (no article → no gender risk, unlike a "Remove the %s?" shape); "Tamaño de la descarga: **128 MB**" and "…el modelo descargado (**68 MB**)…" are label/parenthetical → agreement-free; "Hoy: **12.345** tokens"; "Reconocido por **PaddleOCR**"; "Abrir **PlayTranslate**"; "**Auto Furigana**"; "**{text}** no se rellena en este prompt…". Only the two flagged above can disagree.
- **`ocr_source_label` mirrors its sibling's structure**, as the glossary requires: committed `translation_source_label` = "**Traducido por** %1$s" → delta "**Reconocido por** %1$s". Not a fresh translation of the English.
- **"Captured" stays one verb.** `settings_cell_history_summary_on/off` "frases **capturadas**", `history_toggle_subtitle` "las frases **capturadas**", `history_empty_off` "las frases que **captura** PlayTranslate", `audio_source_game_enable_hint` "**capturar** el audio", `floating_menu_capture_screen` "**Capturar**" — all on the committed "captura de pantalla" verb. No second verb introduced.
- **Hotkey rows byte-match their real control labels** — the best work in this delta. `hotkey_auto_translation_dialog_title` = "Traducción automática" **byte-matches** committed `live_mode_auto_translate_label`; `hotkey_auto_hint_dialog_title` = "Auto \<hint\>" **byte-matches** committed `live_mode_auto_with_hint`. The resulting asymmetry ("…detener **la traducción automática**" vs "…detener **Auto** Furigana") is therefore *correct*: each row names the control the user actually sees. `settings_ocr_use_manga_subtitle` "el **modo automático**" likewise matches committed `settings_hide_overlays_during_auto_mode` / `settings_overlay_mode_subtitle`.
- **Other terminology all resolves against the committed file:** *servicio de traducción* (`settings_cell_translation_services` = "Servicios de traducción"); *Proveedor*; *LLM* kept as the Latin initialism; *Superposiciones* (`settings_overlay_mode_title` "Modo de superposición", `settings_hide_overlays_*`); *modo/traducción **en vivo*** (`error_live_mode_unsupported_backend`); *tarjetas de frase* (`anki_content_flag_sentence`, `anki_content_words_table`); *texto a voz*; *consultas de palabras* (`onboarding_welcome_learn_body`); *lote* ↔ "Prompt por lotes"; *Palabras clave*; *Personalizado* (`llm_backend_preset_custom`); *clave de API*; *alternativa* = fallback. `settings_header_advanced_llm` "**Configuración** avanzada de LLM" is the file's only "Configuración" — considered and passed: EN deliberately says *configuration*, not *Settings*, and rendering it "Ajustes" would collapse two distinct English terms. System-settings strings correctly keep **"ajustes"** (`update_unknown_sources_button` "Abrir ajustes").
- **`stream_kind_*` (deliberate, not flagged).** The two share-scope buttons keep AOSP SystemUI's Spanish ("Compartir una aplicación" / "Compartir toda la pantalla"). Worth noting the body was made to *agree with them*: `stream_kind_prompt_message` says "una sola **aplicación**" where the rest of the file says "app". Those are the **only 2** "aplicación" in the file vs 19 "app/apps" — and that is the right call, because the dialog must read coherently against its own AOSP-locked buttons.
- **The 38 `misc_*` chips — the four clusters are each internally distinguishable**, and most are real DLE marks rather than glosses of the English: obsolescence = **Arcaico / Desusado** (`desus.`) **/ Anticuado** (`ant.`) **/ Histórico**, with **Poco usado** (`p. us.`) correctly kept outside the cluster for `misc_rare`; informality = **Coloquial** (`coloq.`) **/ Informal / Familiar** (`fam.`) **/ Jerga**, with a consistent Jerga / Jerga de internet / Jerga del manga family; honorifics = **Honorífico / Humilde / Cortés**, the standard Spanish trio for sonkeigo/kenjōgo/teineigo and distinct from **Formal**; offensiveness = **Despectivo** (`despect.`) **/ Ofensivo / Vulgar** (`vulg.`) **/ Insulto** — four distinct words, and while "Insulto" is a noun-gloss rather than a mark, Spanish lexicography has no dedicated *slur* mark and it is the shortest unambiguous option, so it stands. Also correct: **Figurado** (`fig.`), **Poético** (`poét.`), **Irónico** (`irón.`), **Onomatopeya**, **Infantil** (`infant.`), **Neologismo**, **Solo kana** / **Solo kanji** (loanwords kept per glossary). Register and brevity match the committed `pos_*` family.
- **Truncation.** `service_llm_badge` = "LLM" (3); `probe_initializing` = "Iniciando…" (10 — correctly chosen over the longer, more literal "Inicializando…", per the "keep short" note); `floating_menu_capture_screen` = "Capturar\npantalla" (8/8) — its `\n` survives, and its first line is exactly the "Capturar" that the previous cycle recommended for the sibling `floating_menu_btn_capture_region`, so the two buttons now line up. Only `misc_yojijukugo` needs shortening (💬 above).

**Verdict:** **fix-then-ship.** One ❌ (the fem/male chips assert grammatical gender instead of speech register — the only finding that changes what the UI *claims*), five ⚠️ (two confirmed-reachable number-agreement breaks, one dangling clitic, one glossary term, one missing article), two 💬. All are one-line string edits; no structural problems. The delta is otherwise strong — the Remove/Delete/Clear three-way split, the DLE-grade `misc_*` marks, the "e"/"u" conjunction choices, and the hotkey↔control byte-matches are all better than a literal pass would have produced.

---

# Delta review round 2 — 2026-07-14

Fresh independent re-derivation of the 174 delta keys. I wrote none of this and reviewed none of it. Primary target per brief: **regressions introduced by round 1's 8 edits**.

**Mechanical layer re-verified after the edits (delta only):** file parses as XML; `%n$s`/`%d` placeholder sets match EN key-for-key; every `<xliff:g>` span matches EN on `id` *and* inner content; the eight literal keyword tokens (`{text}` `{source}` `{source_code}` `{target}` `{target_code}` `{context}` `{N}` `{strings}`) survive byte-identical; `\n` counts match; **0** unescaped `'`/`"` in delta text; plurals CLDR set is `one`/`other`. All 7 delta questions open with `¿`, zero `!` so zero `¡` owed — parity clean. **No 🛑.**

## Round-1 fixes, re-derived from scratch

| key | round-1 change | verdict |
|---|---|---|
| `misc_female_speech` / `misc_male_speech` | → "Habla femenina" / "Habla masculina" | **Holds.** `habla` is a feminine noun (takes `el` for phonetic reasons only), so both adjectives correctly agree feminine — "Habla masculina" is right, not a typo. Rendered beside a `pos_*` chip (`Sustantivo · Habla femenina`) it can no longer be parsed as "feminine noun": it is a noun phrase, not an adjective. Verified `renderMisc`'s `.distinct()` (`MiscLabels.kt:26`) — **all 38 `misc_*` labels are still mutually distinct**, so nothing silently collapses; **no `misc_*` ↔ `pos_*` label is identical**. |
| `game_audio_trim_duration` | → "Selección: %1$s s · Grabación: %2$s s" | **Holds, and is a net width win.** Agreement-free at every count. Measured at 15sp (`Text.PT.RowTitle`): new = **229.8dp** vs pre-fix **247.7dp** — the fix made it *narrower*, and it fits the trim column (264dp at 360dp) and the Anki cell. No wrap regression. |
| `update_error_no_space` | → "(espacio necesario: %1$s)" | **Holds** — and it is more literally correct than round 1 knew: `preflightStorage` (`ApkUpdateManager.kt:74`) returns `(assetSize − partial) + 100 MB headroom`, i.e. **the total space required**, so "espacio necesario" is exactly the quantity. (Side note: the 100 MB headroom means the old "se necesitan %s" was never actually reachable at "1 MB" — but the replacement is correct and no worse.) |
| `audio_source_game_enable_hint` | → "La grabación está desactivada: actívala…" | **Holds.** Clitic now resolves (`la` → *la grabación*), and the later "usarlo" still correctly points at *el audio del juego*. Checked the truncation risk the fix created (90 → 103 chars): it renders into `settings_row_switch`'s `tvRowSubtitle`, style `Text.PT.RowSubtitle` — **no `maxLines`, no `ellipsize`** — so the extra length can only wrap, never clip. |
| `update_dialog_metered_note` | → "red de uso medido" | **Holds.** Matches the 4 committed uses of the term; no second surface form. |
| `llm_status_low_memory_badge` | → "traduciendo con una alternativa" | **Holds.** Grammatical, and it cannot overflow: it renders into `tvOfflineWarningLine` (`settings_row_backend_offline.xml:209`), `match_parent` × `wrap_content`, **no `maxLines`** — despite the key's name it is a wrapping warning *line*, not a fixed-width badge. |
| `error_capture_blocked_secure` | → "la app capturada" | **Holds, and fixes more than it claimed.** The error fires on a sustained all-black captured frame (`ReconcilerLiveMode.kt:360`), i.e. the *captured* surface is FLAG_SECURE — so the referent is right in both single-app and whole-screen modes. It also kills EN's real ambiguity ("this app" could be read as PlayTranslate itself) and now **matches its sibling** `error_single_app_not_fullscreen`, which already said "la app capturada". |

**Zero regressions from the 8 edits.** Round 1's two 💬 were correctly *not* applied: `misc_idiomatic` stays "Idiomático" (settled), and `misc_yojijukugo` stays "Compuesto de cuatro caracteres" — the brief retracts that one outright (misc chips join into one wrapping `TextView` with no `maxLines`/`ellipsize`, so accuracy beats brevity and it must **not** be shortened).

## Findings (round 2)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| game_audio_trim_use_tts<br>game_audio_trim_save | ❌ | "Usar texto a voz en su lugar"<br>"Usar la selección" | "Usar TTS" **or** "Texto a voz"<br>"Usar selección" | **The trim action row overflows and hard-clips the primary confirm button. Measured: 472dp.** See the measurement below. Both strings must change: the 88dp `minWidth` floor means shortening `use_tts` *alone* cannot fit a 360dp screen while `save` = "Usar la selección". |
| update_error_no_space | 💬 | "No hay suficiente **espacio** libre … (**espacio** necesario: 230 MB)." | "No hay suficiente espacio para descargar la actualización (espacio necesario: 230 MB)." | Round-1's fix introduced an *espacio … espacio* echo in one sentence. Dropping "libre" removes it at no cost to meaning. Cosmetic. |
| settings_llm_context_subtitle | 💬 | "Solo en línea. Envía a **los traductores LLM** las últimas líneas de texto registrado…" | "Solo en línea. Envía las últimas líneas de texto registrado a **los traductores LLM en la nube**…" | EN marks the destination as *online* **twice** ("Online only. Give **online** LLM translators…") — deliberate redundancy on a data-egress disclosure. ES marks it once. The scope is still recoverable from "Solo en línea.", so this is polish, not a defect; reusing "en la nube" (already the delta's term in `llm_prompt_row_system_subtitle`) restores the signal without an "en línea … en línea" echo. |
| error_capture_blocked_secure | 💬 | "la app **capturada** bloquea la **captura** de pantalla" | "…la app capturada **impide** la captura de pantalla." | Same-root echo introduced by the (correct) round-1 fix. "impide" breaks it. Purely cosmetic — the string is accurate as it stands. |

## The trim-row measurement (`activity_game_audio_trim.xml`)

**Model** — read from the layout + the resolved styles, not assumed:
- Row = `LinearLayout`, `match_parent`, `padding=12dp` (→ 24dp horizontal).
- `btnTrimUseTts`, `btnTrimNoAudio` = `Widget.Material3.Button.TextButton` → `m3_btn_text_btn_padding_left/right` = **12dp + 12dp**; `btnTrimNoAudio` has `layout_marginStart=4dp`.
- `btnTrimSave` has **no** `style` → theme `materialButtonStyle` → `Widget.Material3.Button` (filled) → `m3_btn_padding_left/right` = **24dp + 24dp**. (Theme is stock `Theme.Material3.*`; no `materialButtonStyle` override in `styles.xml`.)
- All three inherit **`android:minWidth = 88dp`** from `Base.Widget.AppCompat.Button` (Material overrides only `maxWidth`). This floor is load-bearing below.
- Type: `?attr/textAppearanceLabelLarge` → **14sp, sans-serif-medium, letterSpacing 0.00714286em** (= +0.1dp per char); `textAllCaps=false` in the layout. Measured with the real Roboto (google/fonts variable, wght 500 / wdth 100).
- The `Space` (0dp, `weight=1`) is a **spacer, not a shrinker**: on overflow `LinearLayout` hands it `max(0, delta)` = **0**, and the non-weighted buttons keep their natural widths. `MaterialButton` sets no `ellipsize`, so the last child simply runs off the right edge and is pixel-clipped.

**Spanish, at 14sp:**

| button | string | width |
|---|---|---|
| `game_audio_trim_use_tts` | "Usar texto a voz en su lugar" | 200.0dp |
| `game_audio_trim_no_audio` | "Sin audio" | 88.0dp *(at the minWidth floor)* |
| `game_audio_trim_save` | "Usar la selección" | 156.1dp |

> **Row = 12 + 200.0 + 4 + 88.0 + [Space→0] + 156.1 + 12 = 472.1dp**

**Is `game_audio_trim_save` reachable?** **No — it is clipped at both widths.** Laid out LTR it occupies x = **[304, 460]**, its label x = [328, 436]:

| screen | overflow | confirm button visible | label visible | user sees |
|---|---|---|---|---|
| **360dp** | **+112dp** | 56 / 156dp (**36%**) | 32 / 108dp (30%) | ≈ **"Usar"**, right edge gone |
| **411dp** | **+61dp** | 107 / 156dp (**69%**) | 83 / 108dp (77%) | ≈ **"Usar la selec"**, hard cut mid-word |

The 56dp sliver still exceeds the 48dp touch target, so the button is technically *tappable* — but at neither width is it fully visible, and at 360dp its label is unreadable. For a screen's primary confirm, that is a break.

**Root cause is the layout, not Spanish.** English measures **377.6dp** and therefore *already overflows a 360dp phone* (it fits 411dp). Spanish is the second-worst locale and fails at **every** common width. Six other locales fail too:

| fits both | fr 530 · de 571 · tr 565 · ru 535 · vi 489 · **es 472** · ar 448 — overflow 360 **and** 411 |
|---|
| **EN 378** — overflows 360 only · th 357 · pt-BR 337 · ja 326 · ko 297 · zh 292 — fit both |

**The locale fix, with the floor math.** Because `no_audio` is already pinned at the 88dp `minWidth` floor, the row's *minimum possible* width while `save` = "Usar la selección" is `12 + 88 + 4 + 88 + 156.1 + 12` = **360.1dp** — over budget even if `use_tts` were a single character. **`game_audio_trim_use_tts` cannot be fixed on its own.** Both must shrink:

| variant | row | 360dp | 411dp |
|---|---|---|---|
| current | 472.1dp | OVER | OVER |
| "Usar texto a voz" + "Usar la selección" | 398.2dp | OVER | ok |
| "Usar TTS" + "Usar la selección" | 360.1dp | **OVER by 0.1dp** | ok |
| **"Texto a voz" + "Usar selección"** | **354.6dp** | ok (5dp slack) | ok |
| **"Usar TTS" + "Usar selección"** | **345.2dp** | ok (15dp slack) | ok |

- "Usar TTS" is the widest-margin option and has direct precedent (pt-BR took exactly this width exception). Its cost: the ES file otherwise only uses "TTS" inside the brand "Google TTS"; standalone the glossary term is "Texto a voz".
- "Texto a voz" keeps the glossary term (`audio_source_tts_name` = "Texto a voz"), still fits, and pairs cleanly with the noun-phrase sibling "Sin audio". 10dp less slack.
- **If `save` drops its article, `game_audio_trim_play` should too** — "Reproducir la selección" → "Reproducir selección" — or the two selection verbs stop being parallel. (Play is centered in a 264dp column at 193dp; it fits either way, so this is for consistency, not width.)

**Caveat the string edit cannot remove:** even the best variant clears a 360dp phone by 5–15dp. Any `fontScale > 1.0` (accessibility large text) re-breaks the row instantly, and EN stays broken at 360dp regardless. **The durable fix is the layout** — give the two `TextButton`s `layout_weight` + `maxLines=1` + `ellipsize=end` so they yield instead of pushing, or stack the row. Filed here because I was asked to measure it; the layout change is code, not `values-es`.

## Clean areas (round 2) — checked, no findings

- **Agreement around placeholders with real values.** Swept every delta string that takes one: "¿Quitar **OpenAI**?" (no article → no gender exposure), "Abrir **PlayTranslate**", "Actualizando **PlayTranslate**", "Reconocido por **PaddleOCR**", "Clave ••••**4f2a**", "Tamaño de la descarga: **128 MB**", "(espacio necesario: **230 MB**)", "¿Conservar el modelo descargado (**68 MB**) o **eliminarlo**…?" (→ *el modelo* ✓), "Auto **Furigana**", "**{text}** no se rellena…", "Selección: **2,4** s · Grabación: **147** s". **After round 1's edits there is no reachable agreement break left in the delta.**
- **`tr_service_status_usage_today_fmt` is safe.** "Hoy: %1$s tokens" is fed by `UsageTracker.todayString()` = `NumberFormat.getNumberInstance(Locale.getDefault())` (`UsageTracker.kt:61`) → renders "12.345" with the Spanish thousands separator. Correctly localized; not a third instance of the `Locale.US` bug class.
- **The 38 `misc_*` chips.** All distinct (verified against `.distinct()`); four clusters still separable — offensiveness (Despectivo / Ofensivo / Vulgar / Insulto), obsolescence (Arcaico / Desusado / Anticuado / Histórico, with Poco usado held out for `rare`), informality (Coloquial / Informal / Familiar / Jerga + Jerga de internet / del manga), honorifics (Honorífico / Humilde / Cortés, distinct from Formal) — and the new Habla femenina / masculina pair sits outside all four. The set mixes nouns (Eufemismo, Onomatopeya, Jerga, Insulto) with adjectives (Arcaico, Figurado, Coloquial); considered and passed — that mix is normal for Spanish lexicographic marks and mirrors what each term naturally is.
- **`misc_polite` = "Cortés" collides with `inflection_polite` = "Cortés" — inherited, not a bug.** EN has the identical collision (`misc_polite` = `inflection_polite` = "Polite"), the two render in different rows through different joins, and both readings are correct (丁寧語 register vs ます polite form). No action.
- **Register survived the edits.** "actívala" (`audio_source_game_enable_hint`) now matches the shape of its committed sibling `history_empty_off` ("Actívalo"). Buttons stay infinitive (Copiar / Eliminar / Descartar / Restablecer / Quitar / Reintentar / Detener / Abrir ajustes / Guardar de todos modos), prose stays tú-imperative (Introduce / Actívala / Inténtalo / Toca / Mantén presionado), titles stay noun-phrase. Zero `usted`, zero vosotros, zero `-áis`/`-éis`.
- **Terminology after the edits.** "red de uso medido" is now the single form (no "conexión" variant left). "la app capturada" is now used by both `error_capture_blocked_secure` and `error_single_app_not_fullscreen`. "grabación" is the one noun for the recording across `audio_source_game_enable_hint`, `anki_game_audio_permission_denied` and `game_audio_trim_duration`'s new "Grabación:" label. "alternativa" = fallback, unchanged.

**Verdict: FIX FIRST** — 1 ❌, 0 ⚠️, 3 💬. The ❌ is the trim action row: at **472dp** it overruns a 360dp phone by 112dp and a 411dp phone by 61dp, hard-clipping the primary confirm button (36% / 69% visible). It needs **two** string edits (`game_audio_trim_use_tts` *and* `game_audio_trim_save`) because the 88dp button `minWidth` floor puts the row's best case at 360.1dp otherwise — plus a layout fix that is out of scope for this file and that EN needs too. Round 1's 8 corrections are all sound and introduced **no regressions**; everything else in the delta is clean.

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
| `settings_ocr_note_mlkit` | ⚠️ | "Rápido, incluso en pantallas con mucho texto" | "Ágil, incluso en pantallas con mucho texto" | The English comment forbids reusing the literal Fast tier label; the first pass reused «Rápido», the same word as `ocr_label_paddle_fast`, so the two rows read as the same tier sitting side by side in one list. |

### Clean areas (delta) — checked, no findings

**tú** throughout — Cambia, Puedes, Añade, Comprueba, prueba, Toca, Elige, Apunta, Permítelo, acércate, Desliza, Úsalo, Importa, activa, configura, desactiva. Neutral international vocabulary only: ajustes, pantalla, archivo, aplicación/app, eliminar — no vosotros and no regionalisms. The delta contains no question or exclamation sentence, so there is no ¿ / ¡ contact point to get wrong; the one interrogative surface nearby (`settings_ocr_delete_title`) is untouched and already correct. “ ” quotes in `a11y_stuck_message_xiaomi` and `settings_ocr_footer_guidance`. **motor** (engine) / **herramienta** (tool) / **modelo** (model) stay distinct and all three meet in `settings_ocr_delete_camera_import_note`. **instantánea** for the camera freeze-frame keeps «captura de pantalla» free (`anki_group_screenshot`). Gender and number agree around every placeholder read with a real value (Se importó 1 diccionario / Se importaron 4 diccionarios). Plurals one/other.

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

Independent pass — I wrote none of these strings. Scope: `card_words_in_sentence`,
`anki_added_sentence_success`, `anki_added_word_success`, `game_audio_zoom_hint`,
`anki_first_field_unmapped`, `anki_first_field_empty`,
`history_hide_translations_toggle_title`, `history_hide_translations_toggle_subtitle`.

**Mechanical layer verified programmatically over the 8 keys:** all 8 present, no extras;
file parses as XML; every `<xliff:g>` span matches EN byte-for-byte on `id`, `example`
*and* inner content (`field_name`/`brand_anki` spans in both first-field strings);
placeholder multisets identical to EN (`%1$s` ×1 in each first-field string, none
elsewhere); `<b>`, `\n`, `\{ \}`, `&lt;/&gt;/&amp;` counts match (all zero here); **0**
unescaped `'` or `"` in the delta — the four field-name quotes are escaped straight
`\"`; `name=` untouched; `Anki` untranslated in all four strings that carry it. Each key
also sits in its EN neighbourhood (`anki_added_no_audio` → sentence → word;
`audio_source_game_enable_hint` → zoom hint; `anki_field_mapping_unconfigured` →
unmapped → empty; `history_capture_image_toggle_subtitle` → title → subtitle). The one
file-order divergence from EN is the pre-existing relocation of the `pos_*` /
`inflection_*` blocks, untouched by this delta. **No 🛑 build-breaking issues.**

### Findings (delta)

| name | severity | current | suggested | note |
|---|---|---|---|---|
| `anki_first_field_empty` | ⚠️ | "…<xliff:g>Anki</xliff:g> usa el primer campo para identificar la nota, así que **necesita** un valor en todas las tarjetas." | "…, así que **ese campo** necesita un valor en todas las tarjetas." | Pro-drop hands the null subject of `necesita` to the last overt 3sg subject — **Anki** — so the clause lands as "Anki needs a value on every card" and the alert stops pointing at the field the user has to fill. EN's "it" is loose in the same spot, but English adjacency pulls it back to "the first field"; Spanish subject continuity does not. Both sibling locales had to name the referent for exactly this reason (`pt-rBR` "então **esse campo** precisa ter um valor", `fr` "**ce champ** doit donc contenir une valeur"). It is a full alert, so the three extra characters are free. |
| `history_hide_translations_toggle_title` | 💬 | "Ocultar las traducciones" | "Ocultar traducciones" | The article is grammatical, but this file uses it only where the referent is deictic — the translations currently on the game screen (`translate_button_subtitle_hold_to_hide_translations`, `cd_toggle_translation_visibility`, `overlay_hide_controls_title`). Bare label positions drop it: the sibling toggle title immediately above is `history_capture_image_toggle_title` = "Guardar imágenes de captura", and the closest verb-matched labels are `settings_hide_overlays_during_auto_mode` = "Ocultar superposiciones" and `hotkey_show_translations_dialog_title` = "Mostrar traducciones". Also 24 → 20 chars in a settings-row title. |
| `anki_first_field_unmapped` | 💬 | "Asigna un valor a \"…\" para que <xliff:g>Anki</xliff:g> **pueda** identificar la nota." | "Asigna un valor a \"…\" para que <xliff:g>Anki</xliff:g> identifique la nota." | The EN comment pins this string to the Android 12+ two-line toast clamp. At `example="Key"` ES is 64 chars to EN's 51; with a long real field name — `IsTargetedSentenceCard`, which this app's own `anki_content_flag_targeted_sentence_desc` names — it is 83 to EN's 70, and the field name is user-defined and unbounded. `para que` + subjunctive already carries the "can", so dropping `pueda` buys back 6 chars with no loss of meaning; `fr` shipped that same compression ("pour qu\'Anki identifie la note"). Not wrong as written — take this only if the toast is observed clipping. |

### Clean areas (delta) — checked, no findings

**The two one-tap toasts are the strongest pair in the delta.** `anki_added_sentence_success`
"Tarjeta de frase añadida a Anki" / `anki_added_word_success` "Tarjeta de palabra añadida a
Anki": feminine `añadida` agrees with `tarjeta`; the compounds byte-match the mode chips the
toast exists to make visible — `anki_mode_sentence` = "Frase", `anki_mode_word` = "Palabra" —
and "tarjeta de frase" is already the file's established compound (`anki_game_audio_row_subtitle`,
`anki_content_words_table`, `anki_content_flag_sentence`). `añadir` (not `agregar`) matches
`anki_added_no_audio` and `history_action_anki`, keeping the neutral-international line. I
checked the participle-attachment trap — `tarjeta` and `frase`/`palabra` are both feminine, so
`añadida` is formally free to attach to the nearer noun — and cleared it: "tarjeta de frase" is a
determinerless classifying compound, which blocks internal modification, so the head-noun reading
is the only live one. One out-of-scope observation, no action asked: `anki_added_no_audio`
"Añadido a Anki" is masculine and now sits one string above two feminine "…añadida" toasts;
that asymmetry is inherited from EN ("Added to Anki" vs "Sentence card added to Anki") and the
string is not in this delta.

**`nota` for Anki's "note" is the right call, and the mixed vocabulary is EN's, not the
translator's.** AnkiDroid's own Spanish UI uses *nota* / *tipo de nota*, so a user who has ever
opened AnkiDroid reads it immediately. The file otherwise says *tarjeta* / *tipo de tarjeta*
because the app's English says "card type" where Anki says "note type" — a source-side divergence,
not a locale drift. `anki_first_field_empty` puts both nouns in one alert ("está vacío en esta
**tarjeta** … identificar la **nota** … en todas las **tarjetas**"), which is faithful to EN and
actually makes the one-note-many-cards relation visible rather than hiding it.

**`Asigna` is the file's mapping verb.** It matches `anki_content_source_pick_title` = "Asignar
\"%1$s\"" — the very dialog that opens right after this toast — and `anki_card_type_edit_mapping_row_label`
= "Editar asignación de campos". No stray *mapear*. The fronted "El campo \"X\"" in
`anki_first_field_empty` is a deliberate improvement on EN's bare quoted placeholder: it gives
`está vacío` a masculine anchor instead of leaving agreement to a free-form user string, the same
move `pt-rBR` made ("O campo …"). Kept out of `anki_first_field_unmapped`, where the placeholder is
a `a`-marked object and needs no anchor — correct asymmetry, not an inconsistency.

**Field-name quotes: escaped straight `\"` is correct and already adjudicated.** EN's comment
calls its curly quotes intentional typography, and `de`/`fr`/`pt-rBR` all used their typographic
pairs for these two keys — but this file's rule (settled in the 2026-06-23 delta review above) is
*escaped straight quotes for literal field names*, curly `“ ”` reserved for names the user sees in
Android or in another app's chrome (`onboarding_a11y_enable_title`, `a11y_stuck_message_xiaomi`,
`settings_ocr_footer_guidance`), and `« »` for this app's own button labels
(`status_idle`, `status_hold_hint`, `hymt_legal_message`). The two new strings quote an AnkiDroid
field name and match `anki_content_source_pick_title` and the `anki_content_*_desc` block exactly —
the sub-family the user meets in the same flow. Note for the record that the premise "the file has
zero curly quotes" is not accurate: it has 3 `“ ”` pairs and 4 `« »` pairs. The `\"` choice is
family-consistent, not file-uniform, and family consistency is the right axis here.

**`card_words_in_sentence`** "Palabras en la frase" — *frase* matches the app's one word for
"sentence" everywhere (`anki_mode_sentence`, `history_toggle_subtitle`), so the baked-in card header
and the mode chip that produced the card agree. Uppercased by the card CSS it is 20 chars to EN's
17; the alternative "Palabras de la frase" is the same length and no clearer, so there is no
shortening available and none needed for a full-width card-back header.

**`game_audio_zoom_hint`** "Pellizca para ver más o menos audio" — *pellizcar* is Google's own
Spanish verb for the pinch gesture, and the tú imperative is right. The bare, object-less imperative
is this file's established hint shape (`notif_text` "Toca para volver a…", `cd_drag_to_reorder`
"Arrastra para reordenar", `capture_sliver_expand_hint` "Toca para ver más opciones"), so no object
is owed. I considered the "más o menos" fixed-adverbial garden path ("roughly") and cleared it:
`ver más o menos` in the adverbial reading would mean "to see approximately", which is semantically
empty, so the comparative-quantifier reading resolves at once. I also checked the alternatives that
would remove the adjacency — *ampliar/reducir el audio*, *acercar/alejar el audio* — and rejected
them: every one of them invites a volume reading, which "ver" cleanly forecloses. At 35 chars to
EN's 32 it is safe in a centered caption. No waveform noun exists in this file, so none was owed.

**`history_hide_translations_toggle_subtitle`** "Muestra solo el texto capturado. Toca una línea
para ver su traducción." — the 3sg-descriptive opener is the file's toggle-subtitle voice, not a
register slip: `history_toggle_subtitle` "Guarda las frases capturadas…" and
`history_capture_image_toggle_subtitle` "Conserva una foto de cada captura…" are the two subtitles
it sits between, both built the same way. *texto capturado* reuses the app's established capture
verb (`history_toggle_subtitle`, `history_empty_off`) rather than opening a second one. **línea**
for EN's "row" is better than a literal *fila*: this screen already calls its entries *líneas*
(`history_empty_none` "Las líneas aparecen a medida que se traducen", `history_clear_confirm_message`
"Todas las líneas guardadas"), and EN itself uses three nouns (line/row/entry) for one object, so
the translator collapsed to the one the screen already teaches — `entrada` stays reserved for
`history_delete_confirm_title`, matching EN's "entry". Second sentence imperative matches EN.

**Register, punctuation, brands.** tú throughout the delta (Asigna, Pellizca, Toca; Muestra/Conserva
in the descriptive voice; `Ocultar` infinitive for the label). No `usted`, no vosotros forms, no
regionalisms (*audio*, *tarjeta*, *pantalla*, *campo*). None of the 8 is a question or exclamation,
so there is no ¿ / ¡ contact point to miss. `Anki` untranslated in all four occurrences.

**Verdict: ship with one edit.** One ⚠️ (`anki_first_field_empty`'s null subject — it changes what
the alert tells the user to do) and two 💬. No ❌, no 🛑.
