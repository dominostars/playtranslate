# PlayTranslate

<!-- l10n-header -->
<div align="center">

[English](../README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-HK.md) | [Español](./README.es.md) | [العربية](./README.ar.md) | Français | [Português (Brasil)](./README.pt-BR.md) | [Русский](./README.ru.md) | [Deutsch](./README.de.md) | [日本語](./README.ja.md) | [Türkçe](./README.tr.md) | [Tiếng Việt](./README.vi.md) | [한국어](./README.ko.md) | [ไทย](./README.th.md)

[![Downloads](https://img.shields.io/github/downloads/dominostars/playtranslate/total)](https://github.com/dominostars/playtranslate/releases)
[![Stars](https://img.shields.io/github/stars/dominostars/playtranslate?style=flat)](https://github.com/dominostars/playtranslate/stargazers)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![License](https://img.shields.io/github/license/dominostars/playtranslate)](https://github.com/dominostars/playtranslate/blob/main/LICENSE)

</div>
<!-- /l10n-header -->

PlayTranslate est une application Android de traduction en temps réel et d'apprentissage des langues, pour les jeux, les visual novels, les mangas et tout autre texte affiché à l'écran. Elle prend en charge 26 langues de jeu et 59 langues de traduction !

[Cliquez ici pour télécharger la dernière version](https://github.com/dominostars/playtranslate/releases/latest)

Pour signaler un problème, obtenir de l'aide ou faire une suggestion, rejoignez le [serveur Discord](https://discord.gg/DVCj6p7MUC).

[PlayTranslate sur Persona 3 Reload](https://github.com/user-attachments/assets/e89c2c6e-92f3-41d2-8e51-5483beaca612)

## Fonctionnalités

- **Traduction en un appui** : un seul appui suffit pour capturer l'écran de jeu et traduire le texte qu'il contient.
- **Mode de traduction automatique** : chaque nouvelle ligne de dialogue est traduite d'elle-même, sans que vous ayez à appuyer sur quoi que ce soit.
- **Recherche de mots** : survolez n'importe quel mot avec la loupe flottante pour voir aussitôt sa définition dans le dictionnaire.
- **Hors ligne** : la reconnaissance de texte (OCR) et la recherche dans le dictionnaire fonctionnent sans connexion Internet, et vous pouvez télécharger en option des modèles de traduction hors ligne.
- **Mode furigana et pinyin** : affiche en temps réel des indications de lecture au-dessus des caractères.
- **Raccourcis** : configurez une touche physique pour afficher un aperçu des traductions ou des furigana tant que vous la maintenez enfoncée. Idéal pour les consoles portables dotées de boutons dédiés.
- **Double écran et écran partagé** : fonctionne sur les deux écrans des appareils à double affichage, comme l'Ayn Thor, ou en écran partagé sous Android, à côté d'un jeu en mode fenêtré.
- **Zones de capture** : limitez la capture à la boîte de dialogue, aux sous-titres ou à toute autre zone de votre choix.
- **Synthèse vocale** : faites lire le texte à voix haute. Vous pouvez changer la voix par défaut dans les paramètres.
- **Export vers Anki** : enregistrez des phrases dans AnkiDroid avec le texte original, la traduction, la liste de mots, les mots cibles, l'audio de synthèse vocale et une capture d'écran. Vous pouvez même enregistrer l'audio du jeu et l'ajouter à la carte ! Choisissez le type de carte, avec des préréglages pour les paquets populaires.
- **Intégration de Yomitan** : les dictionnaires Yomitan s'intègrent de façon transparente dans toute l'application (y compris dans Anki), avec l'accent tonal, les badges de fréquence, des informations enrichies sur les kanji et des définitions fusionnées. Une intégration plus poussée est à venir.
- **Traduction avec l'appareil photo** : pointez l'appareil photo vers un texte autour de vous et lisez sa traduction en direct, ou prenez un instantané pour appuyer sur les mots et les rechercher.
- **Historique du texte** : gardez une trace des phrases capturées. Désactivé par défaut.

## Installation

1. [Cliquez ici pour télécharger la dernière version](https://github.com/dominostars/playtranslate/releases/latest)
2. Sur votre appareil Android, autorisez votre navigateur ou votre gestionnaire de fichiers à installer des applications inconnues : ouvrez **Paramètres → Applications → Accès spéciaux des applications → Installer des applis inconnues**, sélectionnez le navigateur ou le gestionnaire de fichiers, puis activez **Autoriser cette source**. Android vous propose aussi d'y accéder directement lorsque vous ouvrez l'APK pour la première fois
3. Ouvrez l'APK et appuyez sur « Installer »
4. Au premier lancement, suivez les étapes de configuration pour accorder les autorisations nécessaires

### Impossible d'installer l'application ?

Sur certains appareils Android, **Google Play Protect** bloque l'installation des APK téléchargés en dehors du Play Store et affiche un avertissement vague du type « Application non installée » ou « Application dangereuse détectée ». Dans ce cas, désactivez temporairement l'analyse :

1. Ouvrez le **Play Store**
2. Appuyez sur votre **icône de profil** (en haut à droite)
3. Appuyez sur **Play Protect**
4. Appuyez sur l'**icône en forme d'engrenage** (en haut à droite)
5. Désactivez **Analyser les applications avec Play Protect**

Installez l'APK, puis réactivez Play Protect pour qu'il continue d'analyser vos autres applications.

### Impossible d'activer l'accessibilité ?

Quelques fonctionnalités avancées (comme maintenir un raccourci pour afficher les traductions) vous demanderont d'activer l'autorisation d'accessibilité. Certains fabricants d'appareils Android empêchent par défaut les applications installées en dehors du Play Store d'obtenir cette autorisation : l'option peut alors apparaître grisée dans les Paramètres ou afficher le message « Paramètre restreint ». Pour la débloquer :

1. Ouvrez **Paramètres → Applications → PlayTranslate**
2. Appuyez sur le menu **⋮** (en haut à droite)
3. Appuyez sur **Autoriser les paramètres restreints**
4. Authentifiez-vous lorsque l'appareil vous le demande

Vous pouvez maintenant activer l'accessibilité pour PlayTranslate.

## Aide et soutien

Pour signaler un problème, obtenir de l'aide ou faire une suggestion, rejoignez le [serveur Discord](https://discord.gg/DVCj6p7MUC).

Vous pouvez soutenir PlayTranslate sur Ko-fi : https://ko-fi.com/playtranslate

## Langues prises en charge

PlayTranslate traduit depuis **26 langues de jeu** (celles qu'il sait lire à l'écran) vers **59 langues de traduction** (la langue dans laquelle la traduction s'affiche). Les deux tableaux sont classés selon le nombre total de locuteurs dans le monde.

### Langues de jeu (lues à l'écran)

| Langue                 | Nom dans la langue        | Code    |
|------------------------|------------------|---------|
| Anglais                | English          | en      |
| Chinois (simplifié)    | 简体中文             | zh      |
| Chinois (traditionnel) | 繁體中文             | zh-Hant |
| Hindi                  | हिन्दी           | hi      |
| Espagnol               | Español          | es      |
| Arabe                  | العربية          | ar      |
| Français               | Français         | fr      |
| Portugais              | Português        | pt      |
| Russe                  | Русский          | ru      |
| Indonésien             | Bahasa Indonesia | id      |
| Allemand               | Deutsch          | de      |
| Japonais               | 日本語              | ja      |
| Turc                   | Türkçe           | tr      |
| Vietnamien             | Tiếng Việt       | vi      |
| Coréen                 | 한국어              | ko      |
| Italien                | Italiano         | it      |
| Thaï                   | ไทย              | th      |
| Néerlandais            | Nederlands       | nl      |
| Roumain                | Română           | ro      |
| Hongrois               | Magyar           | hu      |
| Suédois                | Svenska          | sv      |
| Catalan                | Català           | ca      |
| Danois                 | Dansk            | da      |
| Finnois                | Suomi            | fi      |
| Norvégien              | Norsk            | no      |
| Polonais               | Polski           | pl      |

### Langues de traduction (celles que vous lisez)

| Langue         | Nom dans la langue        | Code |
|----------------|------------------|------|
| Anglais        | English          | en   |
| Chinois        | 中文               | zh   |
| Hindi          | हिन्दी           | hi   |
| Espagnol       | Español          | es   |
| Arabe          | العربية          | ar   |
| Français       | Français         | fr   |
| Bengali        | বাংলা            | bn   |
| Portugais      | Português        | pt   |
| Russe          | Русский          | ru   |
| Ourdou         | اردو             | ur   |
| Indonésien     | Bahasa Indonesia | id   |
| Swahili        | Kiswahili        | sw   |
| Allemand       | Deutsch          | de   |
| Japonais       | 日本語              | ja   |
| Marathi        | मराठी            | mr   |
| Télougou       | తెలుగు           | te   |
| Turc           | Türkçe           | tr   |
| Vietnamien     | Tiếng Việt       | vi   |
| Coréen         | 한국어              | ko   |
| Tamoul         | தமிழ்            | ta   |
| Persan         | فارسی            | fa   |
| Italien        | Italiano         | it   |
| Thaï           | ไทย              | th   |
| Goudjarati     | ગુજરાતી          | gu   |
| Polonais       | Polski           | pl   |
| Ukrainien      | Українська       | uk   |
| Tagalog        | Tagalog          | tl   |
| Malais         | Bahasa Melayu    | ms   |
| Kannada        | ಕನ್ನಡ            | kn   |
| Néerlandais    | Nederlands       | nl   |
| Roumain        | Română           | ro   |
| Hongrois       | Magyar           | hu   |
| Grec           | Ελληνικά         | el   |
| Tchèque        | Čeština          | cs   |
| Suédois        | Svenska          | sv   |
| Biélorusse     | Беларуская       | be   |
| Hébreu         | עברית            | he   |
| Bulgare        | Български        | bg   |
| Catalan        | Català           | ca   |
| Slovaque       | Slovenčina       | sk   |
| Créole haïtien | Kreyòl Ayisyen   | ht   |
| Croate         | Hrvatski         | hr   |
| Danois         | Dansk            | da   |
| Finnois        | Suomi            | fi   |
| Norvégien      | Norsk            | no   |
| Albanais       | Shqip            | sq   |
| Galicien       | Galego           | gl   |
| Slovène        | Slovenščina      | sl   |
| Lituanien      | Lietuvių         | lt   |
| Letton         | Latviešu         | lv   |
| Afrikaans      | Afrikaans        | af   |
| Macédonien     | Македонски       | mk   |
| Estonien       | Eesti            | et   |
| Géorgien       | ქართული          | ka   |
| Gallois        | Cymraeg          | cy   |
| Maltais        | Malti            | mt   |
| Islandais      | Íslenska         | is   |
| Irlandais      | Gaeilge          | ga   |
| Espéranto      | Esperanto        | eo   |

## Services de traduction en ligne (facultatif)

Par défaut, la traduction passe par [Lingva](https://github.com/thedaviddelta/lingva-translate), et ML Kit sert de solution de secours hors ligne lorsque la traduction en ligne est indisponible. Pour des traductions de meilleure qualité, vous pouvez ajouter une clé d'API pour l'un des services suivants dans **Paramètres → Services de traduction**. Ajoutez-en autant que vous le souhaitez. Chaque service a sa propre entrée dans la liste, ce qui vous permet d'en configurer plusieurs et de les réorganiser pour choisir celui qui traduit en premier.

- **DeepL** : offre gratuite sur [deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer)
- **OpenAI** : [platform.openai.com](https://platform.openai.com/api-keys), avec choix du modèle dans l'application
- **Gemini** : [aistudio.google.com](https://aistudio.google.com/app/apikey), avec choix du modèle dans l'application
- **DeepSeek** : [platform.deepseek.com](https://platform.deepseek.com/api_keys), avec choix du modèle dans l'application
- **Mistral** : [console.mistral.ai](https://console.mistral.ai/api-keys), avec choix du modèle dans l'application
- **Groq** : [console.groq.com](https://console.groq.com/keys), avec choix du modèle dans l'application
- **OpenRouter** : [openrouter.ai](https://openrouter.ai/keys), avec choix du modèle dans l'application
- **Claude** : [platform.claude.com](https://platform.claude.com/settings/keys), avec choix du modèle dans l'application
- **Personnalisé** : tout autre serveur compatible avec l'API d'OpenAI ; saisissez l'URL de votre serveur dans le champ « URL personnalisée »

## Cartes Anki (facultatif)

Installez [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki), puis, dans les Paramètres de PlayTranslate, autorisez l'application à accéder à AnkiDroid pour ajouter des cartes directement à vos paquets.

## Crédits et licence

La liste des bibliothèques, modèles et données linguistiques utilisés figure dans la [section « Credits » du README en anglais](https://github.com/dominostars/playtranslate#credits). PlayTranslate est distribué sous licence [GPL 3.0](https://github.com/dominostars/playtranslate/blob/main/LICENSE).
