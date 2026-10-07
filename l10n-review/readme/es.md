# Spanish (es) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] es -> readme/README.es.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Features > Atajos | 💬 | "configura una tecla física que, mientras la mantienes presionada, muestre una vista previa de las traducciones o del furigana." | "configura una tecla física que, mientras la mantengas presionada, muestre una vista previa de las traducciones o del furigana." | Mood mismatch: the relative clause is subjunctive ("muestre"), so the hypothetical "mientras" clause inside it should be too. |
| Features > Sin conexión | 💬 | "y para traducir puedes descargar, si quieres, modelos sin conexión." | "y, si quieres, puedes descargar modelos de traducción sin conexión." | "si quieres" wedged between verb and object reads choppy; "modelos de traducción" says what the models are, as the English does, and still echoes `lang_section_offline_models_title` ("Descargar modelos sin conexión"). |
| How to Install > step 2 | 💬 | "Android también te ofrece ir a esa pantalla la primera vez que abres el APK" | "Android también te ofrece ir a esa pantalla la primera vez que abres el APK." | Item has a sentence-ending period in the middle ("…**Permitir de esta fuente**. Android…") but none at the end. Spanish lists put a period after items that are full sentences, as the Features list already does. The short steps can stay without one. |
| Optional: Online Translation Backends | 💬 | "Añade tantos como quieras: cada servicio es una entrada independiente de la lista, así que puedes tener varios configurados" | "Añade tantos como quieras. Cada servicio es una entrada independiente de la lista, así que puedes tener varios configurados" | Two colons in one sentence (this one plus the one that introduces the list). Spanish style avoids that; a period here keeps the list colon. |
| Support (heading) | 💬 | "## Soporte" | "## Ayuda y apoyo" | Optional. "Soporte" covers only the help half; the section also covers donations, which the body translates as "apoyar" (Ko-fi). Nothing links to the anchor. Keeping "Soporte" is fine, since it is the usual GitHub heading. |
| Supported Languages > both table headers | 💬 | "\| Idioma               \| Nombre nativo    \| Código  \|" (and "\| Idioma           \| Nombre nativo    \| Código \|") | "\| Idioma               \| En su idioma     \| Código  \|" (same change in table 2) | Optional. "Nombre nativo" is understandable and common in language pickers, but it is a mild calque ("nativo" is said of speakers); "En su idioma" or "Nombre en su idioma" is more idiomatic. Keeping it is acceptable. |

No ❌ or ⚠️ findings. Accuracy, register and app terminology are all clean; the six rows above are polish.

## Clean areas (checked, no findings)

**Register.** Informal tú throughout (únete, toca, abre, elige, activa, autentícate, "se te pida", "te ofrece"). No usted forms: the one "su" (line 15) is "the word's definition". No vosotros. ¿…? and ¡…! appear on both headings that are questions and on both exclamations (intro, Anki audio).

**Accuracy.** Every feature bullet, install step, Play Protect step and restricted-settings step is present, in order and with the same count (4 / 5 / 4). The numbers 26 and 59 are kept. Nothing is softened or added. "Sideloaded" is rendered as "que no proceden de Google Play" / "instaladas desde fuera de Google Play" rather than an anglicism. The "Look for deeper integration" promise is no stronger than the English. The ML Kit sentence ("ML Kit traduce sin conexión como alternativa cuando la traducción en línea no está disponible") means "used whenever online translation is unavailable" and mirrors `tr_service_offline_footer` ("se usan como alternativa cuando las traducciones en línea no están disponibles"). It does not narrow ML Kit to no-connection only.

**Terminology vs strings.xml.** All match:
- Servicios de traducción (`settings_cell_translation_services`, also used for "Backends" in the heading)
- Atajos (`settings_cell_hotkeys`)
- Texto a voz (`settings_cell_tts`)
- Historial de texto (`settings_cell_history` / `history_toggle_title` "Conservar el historial de texto")
- Traducción automática (`live_mode_auto_translate_label`)
- furigana / pinyin, lowercase as in `hint_label_pinyin_lower`
- Regiones de captura (`menu_capture_region`, `nav_regions`)
- Traducción con la cámara (`settings_cell_camera`), with "toma una instantánea" matching `camera_shutter_cd` "Tomar una instantánea"
- consultas al diccionario (`settings_cell_dictionary`)
- Tarjetas de Anki (`settings_cell_anki`, as the heading)
- audio del juego (`audio_source_game_name`)
- Permitir ajustes restringidos (`restricted_settings_title` / `restricted_settings_message`)
- descargar … modelos sin conexión (`lang_section_offline_models_title`)
- Personalizado and "URL personalizada" for the Custom backend (`llm_backend_preset_custom`, `llm_backend_base_url_label`, hint "Introduce la URL de tu servidor")
- "clave de API", "mazo", "acento tonal", "palabras objetivo", "vista previa al mantener presionado" (`settings_overlay_mode_subtitle`), "Mantén presionado" (presionar, not pulsar, as the app does), "icono" without accent and "añadir" rather than "agregar", as the app does

"se integran de forma fluida" looks like a calque of "seamlessly", but it is the app's own wording in `yomitan_page_description`, so it is not flagged.

**Terms the task asked about.**
- *Consulta de palabras.* Keep. It is the noun the app uses: "consultas de palabras" in `onboarding_welcome_learn_body` and `settings_yomitan_empty_summary`, and "Consulta definiciones de palabras" in `settings_cell_dictionary_summary`. "Búsqueda de palabras" would also be natural, but it would add a second noun.
- *configuraciones predefinidas.* Keep. No app string names these presets. "ajustes predefinidos" would collide with "Ajustes" (Settings). "plantilla" is already taken by Anki templates ("campos de plantilla"). "preajustes" is less common. The plural "configuraciones" is understood everywhere.
- *"(opcional)" heading suffix.* Natural Spanish: lowercase inside parentheses, after the noun phrase. Better than mirroring "Opcional: …".
- *Nombre nativo.* Acceptable. An optional alternative is in the findings.

**"app" vs "aplicación".** Consistent, and the same rule the app's strings follow. Running prose always says "app" ("una app de Android", "elige la app", "Al abrir la app", "Abre la app **Play Store**", "tus otras apps", "en la app"). "aplicación/aplicaciones" appears only inside quoted Android or Play Store labels and messages, and in the step-2 phrase that paraphrases "Instalar aplicaciones desconocidas".

**Links and tables.**
- Every URL is byte-identical to the English. The two relative links are absolute.
- Link texts read naturally ("Haz clic aquí para descargar…", "servidor de Discord", "README en inglés").
- The video line stands alone.
- Both tables have translated headers (Idioma / Nombre nativo / Código) and unchanged native-name and code columns (the checker verifies this). CLDR Spanish names are correct (finés, neerlandés, guyaratí, canarés, afrikáans).
- In the raw Markdown, table 2's separator row is narrower than its "Código" header cell. GitHub renders this identically, so it is not a finding.
- The pointer section names libraries, models and linguistic data, says GPL 3.0, and links exactly `#credits` and `LICENSE`.

**Typography.**
- Quotes are “ ” as the parameters table specifies.
- The text after a feature-name colon starts lowercase, which is correct in Spanish.
- Settings arrows are spaced "→".
- No em dashes; the English "—" clauses became parentheses or semicolons.
- "Chino (simplificado)" uses lowercase inside the parentheses.
- "los APK" stays invariable in the plural.

## Spanish appendix: es-ES vs es-419 Android labels

The README uses one consistent set of Android and Play Store labels: AOSP's generic `values-es`. That is the set a Spain-locale device shows. Latin American devices run `es-US`/`es-419` and show a parallel set. Verified on disk (android-36 framework):
- `global_action_settings`: es "Ajustes", es-US "Configuración"
- `harmful_app_warning_title`: es "Se ha detectado una aplicación dañina", es-US "Se detectó una app dañina"

The rest are from memory:

| README label | AOSP / Play Store `es` | `es-US` / es-419 (from memory) |
|---|---|---|
| Ajustes | Ajustes (on disk) | Configuración (on disk) |
| Aplicaciones | Aplicaciones | Apps |
| Acceso especial de aplicaciones | same | Acceso especial de apps |
| Instalar aplicaciones desconocidas | same | Instalar apps desconocidas |
| Permitir de esta fuente | same | Permitir desde esta fuente |
| “Ajuste restringido” | same | Configuración restringida |
| Permitir ajustes restringidos | same (= app's `restricted_settings_title`) | Permitir configuración restringida |
| “No se ha instalado la aplicación” | same (PackageInstaller) | No se instaló la app |
| “aplicación dañina” | same (on disk) | app dañina (on disk) |
| Analizar aplicaciones con Play Protect | same (Play Store es-ES) | Analizar apps con Play Protect (least certain) |

**Verdict: keep the README as it is, with no row in the findings.**
- This is a property of the label set, not a slip. The app's reviewed strings made the same choice: "Ajustes", "Ajustes rápidos", "Aplicaciones descargadas", "Permitir ajustes restringidos". The parameters doc fixes Ajustes for Settings. A README that switched to the es-419 set would disagree with the app.
- The two sets differ mostly in Ajustes/Configuración and aplicaciones/apps, so a Latin American reader can still follow every path.
- Step 2 already ends with the "Android te ofrece ir a esa pantalla" fallback.
- Adding the second set in parentheses would add text the English does not have, and would rest on labels I can only cite from memory.
- If the owner wants a hedge anyway, the place for it is step 2's path. A parenthetical there would trip the checker's bold-span warning only if it is bolded.
- "No se ha instalado la aplicación" uses the compound perfect, which is Spain-flavoured for a just-finished event. It is also the verbatim `es` system message, and it sits inside a quote presented as an example of a vague warning, so it is recognisable in either region. The same goes for "aplicación dañina".

Other regional-vocabulary checks:
- "gestor de archivos" leans slightly to Spain (Latin America often says "administrador de archivos") but is understood everywhere. Not flagged.
- "informar de problemas" is standard (not the anglicism "reportar").
- "icono" (not "ícono") and "presionado" (not "pulsado") follow the app.
- No ordenador/computadora, celular/móvil, coger or vosotros.

## Disposition (2026-10-06)

Applied all 6 💬 (mantengas, modelos de traducción sin conexión, final period on step 2, period
instead of a second colon, Ayuda y apoyo, En su idioma ×2).
