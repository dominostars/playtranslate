# Brazilian Portuguese (pt-rBR) README localization review

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] pt-rBR -> readme/README.pt-BR.md`, no warnings). **No 🛑 issues.**

## Findings

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Não consegue instalar? (intro) | ⚠️ | "mostra um aviso vago, como “App não instalado” ou “App nocivo detectado”." | "mostra um aviso vago, como “O app não foi instalado” ou “App nocivo detectado”." | The English quotes the installer's own failure text so the reader recognizes it. From memory, the pt-BR package installer says "O app não foi instalado." (its siblings read "O app não foi instalado porque…"); "App não instalado" is the English wording translated word for word. "App nocivo detectado" is exact: it is `harmful_app_warning_title` in the framework's values-pt-rBR on disk. Check on a pt-BR device if one is to hand. |
| Recursos > Tradução com um toque | 💬 | "capture a tela do jogo e traduza o texto dela com um só toque" | "capture a tela do jogo e traduza o texto que aparece nela com um só toque" | "o texto dela" is correct but clipped; "o texto que aparece nela" reads more natural. Optional. |
| Como instalar > step 2 | 💬 | "O Android também se oferece para abrir essa tela na primeira vez que você abrir o APK" | "O Android também oferece levar você até essa tela na primeira vez que você abrir o APK." | Removes the "abrir ... abrir" repetition and is closer to "take you there". The item already has a full stop mid-item ("**Permitir desta fonte**. O Android…"), so it should end with one too. |
| Não consegue ativar a acessibilidade? | 💬 | "Certos fabricantes de Android impedem, por padrão," | "Certos fabricantes de aparelhos Android impedem, por padrão," | OEMs make devices, not Android; "fabricantes de aparelhos Android" is how pt-BR tech writing usually says it. Optional. |
| Serviços de tradução online (opcional) | 💬 | "e o ML Kit faz a tradução offline como alternativa sempre que a tradução online estiver indisponível." | "e o ML Kit entra como alternativa offline sempre que a tradução online estiver indisponível." | The meaning is right (ML Kit is used whenever online translation is unavailable, as `tr_service_offline_footer` says: "usadas como alternativa quando as traduções online estão indisponíveis"). This only cuts "tradução" from four uses to three in two sentences. Optional. |
| Serviços de tradução online (opcional) | 💬 | "você pode adicionar em **Configurações → Serviços de tradução** uma chave de API de qualquer um dos serviços abaixo." | "você pode adicionar uma chave de API de qualquer um dos serviços abaixo em **Configurações → Serviços de tradução**." | The path wedged between verb and object reads stiff. The usual order (verb, object, place) is just as clear here. |
| Serviços de tradução online (opcional) | 💬 | "Adicione quantos quiser: cada serviço é um item separado na lista," | "Adicione quantos quiser. Cada serviço é um item separado na lista," | Two colons in one sentence (this one and the one that introduces the list). Use a full stop here and keep the list colon. |
| Recursos (list punctuation) | 💬 | "Ótimo para consoles portáteis com botões dedicados"; "Você pode mudar a voz padrão nas Configurações"; "Uma integração ainda mais completa está por vir" | "Ótimo para consoles portáteis com botões dedicados."; "Você pode mudar a voz padrão nas Configurações."; "Uma integração ainda mais completa está por vir." | These three items contain a sentence break but have no closing full stop, while the Anki, camera and history items do. The English has the same unevenness, so this is optional. Add the stops for consistency, or leave the list as it is. |

No ❌ findings. One ⚠️ (the installer message, from memory); the rest is polish.

## Clean areas (checked, no findings)

**Register.** The whole file uses informal você. Every imperative takes the você form: capture, traduza, passe, defina, restrinja, ouça, salve, aponte, tire, guarde, permita, abra, escolha, ative, toque, siga, desative, instale, faça, conceda, entre, adicione, insira, clique. A grep finds no tu forms (teu/tua/tens/podes/queres) and no o senhor. The enclitics (incluí-lo, consultá-las, reordená-los) are normal written pt-BR.

**Accuracy.** All 13 feature bullets are present and in order. So are the 4 install steps, the 5 Play Protect steps, the 4 restricted-settings steps and the 9 translation services. The counts 26 and 59 are kept. Nothing is softened. Three small additions are all accurate and help the reader:
- "baixar modelos offline" says the models are downloads, matching `lang_section_offline_models_title` "Baixar modelos offline".
- "acesso ao AnkiDroid" says what the access is for.
- The Custom line names the app's field, “URL personalizada”, which is `llm_backend_base_url_label`, for "your own base URL".

"Sideloaded" becomes "instalados fora da Play Store", with no anglicism. "Look for deeper integration in the future" becomes "Uma integração ainda mais completa está por vir", which promises no more than the English. The ML Kit sentence keeps the required meaning: ML Kit is used whenever online translation is unavailable, not only when there is no connection.

**Terminology vs strings.xml.** Every term matches the app:
- Serviços de tradução (`settings_cell_translation_services`, also the backends heading)
- Atalhos (`settings_cell_hotkeys`)
- Conversão de texto em voz (`settings_cell_tts`; the Anki bullet's "o áudio da conversão de texto em voz" mirrors `anki_content_word_audio_desc` "Áudio de conversão de texto em voz")
- Histórico de texto (`settings_cell_history` "Histórico", `history_toggle_title` "Manter o histórico de texto"; "registro das frases capturadas" mirrors `settings_cell_history_summary_*`)
- Tradução automática (`live_mode_auto_translate_label`)
- furigana / pinyin, lowercase in running text as `hint_label_pinyin_lower`
- Regiões de captura (`menu_capture_region` "Região de captura", `nav_regions` "Regiões")
- Câmera (`settings_cell_camera`), Dicionário / consultas ao dicionário (`settings_cell_dictionary`)
- Flashcards do Anki (`settings_cell_anki`, used as the heading)
- áudio do jogo (`audio_source_game_name`)
- tirar um instantâneo (`camera_shutter_cd`)
- "a visualização ao manter um atalho pressionado" (`settings_overlay_mode_subtitle` "visualização ao manter pressionado")
- Permitir configurações restritas (`restricted_settings_title` and `restricted_settings_message`)
- Personalizado (`llm_backend_preset_custom`)
- baralho / cartão / tipo de cartão (`anki_card_type_row_label`)
- acento tonal (`yomitan_category_pitch_accent`)

**Android / Play Store wording.** These all match; the appendix gives the source for each. The Android path in install step 2 and its switch. The restricted-setting message, the menu item and the path "Configurações → Apps → PlayTranslate". The Play Store steps: Play Store, ícone do perfil, Play Protect, ícone de engrenagem, "Verificar apps com o Play Protect". "Toque em Instalar" matches the installer button.

**Links and tables.**
- Install step 1 links to the releases page with the intro's link text, as intended. The release links are absolute, which is correct for a file under `readme/`.
- The Discord, video, API-key, AnkiDroid, `#credits` and LICENSE links resolve to the same targets as the English.
- Both tables translate their header cells (Idioma / Nome nativo / Código). Both language-name columns use standard Brazilian exonyms (Holandês, Polonês, Tcheco, Macedônio, Africâner, Suaíli, Télugo, Canarim, Guzerate, Tagalo).
- A script diff confirms that the native-name and code columns match the English row for row.
- In the raw file, the second table's Código separator is shorter than its header cell and the CJK rows' padding is uneven. Neither shows on the rendered page.
- The pointer section names libraries, models and linguistic data, links to the English `#credits` anchor, and gives the license with its link.

**Typography.** Curly pt-BR quotes (“ ”) are used throughout, with no straight quotes. There are no em or en dashes; the English dashes in the backends list became parentheses, which read well. The arrows (→) are spaced consistently. Headings use sentence case. "a Play Store" takes the feminine article, as in Brazilian usage. Brand names are untouched.

## Brazilian Portuguese appendix

**Ruling on "Configurações" vs "Ajustes".** Keep "Configurações" everywhere in the README, both for Android's Settings and for the app's own settings. No change is needed.
- *Android Settings* (install step 2, the "a chave nas Configurações" sentence, restricted-settings step 1): the pt-BR system says Configurações. This is `global_action_settings` "Configurações" in the framework's values-pt-rBR on disk; values-pt-rPT has "Definições" instead.
- *The app's own settings* ("nas Configurações" in the TTS bullet and the Anki section, and the "**Configurações → Serviços de tradução**" path): the screen the reader lands on is titled "Configurações" (`settings_title`, `tvSettingsTitle` in dialog_settings.xml). The app's own breadcrumb also starts in-app paths with that word: `slow_ocr_prompt_message` passes `settings_title` as the first crumb of "Configurações → Captura e sobreposição → …". App prose says it the same way (`note_mlkit_account_issue`: "Verifique o serviço nas Configurações").
- "Ajustes" (`nav_settings`) appears only as the gear button's label: the 8sp caption on the main screen, the drawer row and the floating-icon menu. It works as a short name for the same place, and pt-BR readers know both words (iOS pt-BR calls its Settings "Ajustes"). Switching the README to "Ajustes" would break the match with the screen title and the in-app breadcrumbs. It would also make the in-app path look different from the Android path for no gain. If anything should become consistent, that happens in the app, not in the README.

**Android and Play Store labels: where each was checked.**

| Label in README | Verdict | Source |
|---|---|---|
| Configurações | ✓ | on disk: `global_action_settings` (values-pt-rBR) |
| Apps | ✓ | from memory (AOSP Settings, Android 12+) |
| Acesso especial para apps | ✓ | from memory (AOSP Settings `special_access`) |
| Instalar apps desconhecidos | ✓ | from memory (AOSP Settings `install_other_apps`) |
| Permitir desta fonte | ✓ | from memory (AOSP Settings `external_source_switch_title`) |
| Instalar (button) | ✓ | from memory (PackageInstaller) |
| “App não instalado” | ⚠️ → “O app não foi instalado” | from memory (PackageInstaller `install_failed`); see Findings |
| “App nocivo detectado” | ✓ exact | on disk: `harmful_app_warning_title` (values-pt-rBR; pt-rPT has "Aplicação prejudicial detetada") |
| Google Play Protect / Play Protect / Play Store | ✓ | brand names, not translated |
| ícone do perfil, ícone de engrenagem | ✓ | from memory (Google pt-BR help: "toque no ícone do perfil") |
| Verificar apps com o Play Protect | ✓ | from memory (Play Store pt-BR) |
| “Configuração restrita” | ✓ | from memory (AOSP Settings restricted-setting dialog title) |
| Permitir configurações restritas | ✓ | from memory (App info ⋮ menu); identical to the app's `restricted_settings_title` |
| a chave (toggle) | ✓ | Google pt-BR help uses "chave" for switches |
| Acessibilidade / acessibilidade | ✓ | on disk: `accessibility_binding_label` "Acessibilidade" |

**European Portuguese sweep.** I grepped for and read through the usual EU forms: ecrã, transferir, aplicação, definições (as Settings), utilizador, ficheiro, telemóvel, registo, contacto, facto, premir/carregar em, "estar a + infinitive", eliminar, partilhar, detetado, câmara, polaco, checo, Macedónio, Estónio, aprendizagem. None appear. The grep's only hits were "definições" meaning dictionary definitions, "predefinições" (presets) and "Tcheco". Every one of these words is Brazilian: tela, baixar, app, Configurações, gerenciador de arquivos, registro, câmera, Polonês, Tcheco, Macedônio, Estoniano, aprendizado, mangás, consoles portáteis. "Ótimo" and "ícone" have the Brazilian accents.

**"(opcional)" heading suffix.** "## Serviços de tradução online (opcional)" and "## Flashcards do Anki (opcional)" use the same pattern. A parenthesized lowercase suffix after a sentence-case heading is idiomatic in pt-BR documentation, and it reads better than a calque of "Opcional:" as a prefix. No finding.

## Disposition (2026-10-06)

Applied all 8: the ⚠️ (“O app não foi instalado”, from memory, to be confirmed on a pt-BR device)
and the 7 💬 as suggested. Settings stays "Configurações" throughout, per the reviewer's ruling.

## Delta review 2026-10-08 ("Can't enable accessibility?" rewritten)

Mechanical layer: `readme_l10n_check.py` -> PASS (`[PASS] pt-rBR -> readme/README.pt-BR.md`, no warnings). **No 🛑 issues.**

| section | severity | current | suggested | note |
|---|---|---|---|---|
| Não consegue ativar a acessibilidade? (intro) | 💬 | "de qualquer app instalado por um APK baixado" | "de qualquer app instalado a partir de um APK baixado" | "instalado por" can read as the agent ("installed by a downloaded APK"); "a partir de" is the usual pt-BR for installing from a file and mirrors "from a downloaded APK". Optional. |

Clean areas: The four steps follow the English order and content, "Essa opção só aparece depois da etapa 1" keeps the only-after-step-1 condition, and the old closing sentence is gone, as in English. Every Android label is byte-identical to AOSP Android 16 Settings (pt-rBR) on disk: Configurações, Acessibilidade, Apps, Informações do app (Android's label, not the English's "AppInfo"), “Configuração restrita” (`blocked_by_restricted_settings_title`) and **Permitir configurações restritas** (`app_restricted_settings_lockscreen_title`). The app's `a11y_restricted_settings_addendum` uses the same words (chave acinzentada, “Informações do app”, menu ⋮, “Permitir configurações restritas”), and the path agrees with "Configurações → Acessibilidade → …" in `overlay_icon_a11y_required_message` and `accessibility_dialog_message`. Register is você with você-form imperatives (selecione, toque, feche, abra, escolha, faça, volte, ative); vocabulary is Brazilian (baixado, app, chave for the switch as in the addendum, etapa as in install step 4), and "chave" for the switch stays clear of "chave de API" in the backends section. Bold sits on the same four labels as English, numbering 1 to 4, curly quotes, spaced arrows, no dashes, text NFC.

### Disposition (2026-10-08)

Applied the 1 💬 ("a partir de um APK baixado").
