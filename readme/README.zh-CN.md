# PlayTranslate

<!-- l10n-header -->
<div align="center">

[English](../README.md) | 简体中文 | [繁體中文](./README.zh-HK.md) | [Español](./README.es.md) | [العربية](./README.ar.md) | [Français](./README.fr.md) | [Português (Brasil)](./README.pt-BR.md) | [Русский](./README.ru.md) | [Deutsch](./README.de.md) | [日本語](./README.ja.md) | [Türkçe](./README.tr.md) | [Tiếng Việt](./README.vi.md) | [한국어](./README.ko.md) | [ไทย](./README.th.md)

[![Downloads](https://img.shields.io/github/downloads/dominostars/playtranslate/total)](https://github.com/dominostars/playtranslate/releases)
[![Stars](https://img.shields.io/github/stars/dominostars/playtranslate?style=flat)](https://github.com/dominostars/playtranslate/stargazers)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![License](https://img.shields.io/github/license/dominostars/playtranslate)](https://github.com/dominostars/playtranslate/blob/main/LICENSE)

</div>
<!-- /l10n-header -->

一款 Android 实时翻译与语言学习应用，适用于游戏、视觉小说、漫画以及屏幕上的任何其他文字。支持 26 种游戏语言，可翻译成 59 种语言！

[点击此处下载最新版本](https://github.com/dominostars/playtranslate/releases/latest)

如需反馈问题、获取帮助或提出需求，欢迎加入 [Discord 服务器](https://discord.gg/DVCj6p7MUC)

[在 Persona 3 Reload 中使用 PlayTranslate](https://github.com/user-attachments/assets/e89c2c6e-92f3-41d2-8e51-5483beaca612)

## 功能

- **一键翻译**：点按一次，即可截取游戏画面并翻译其中的文字
- **自动翻译**：对话一变就自动翻译，无需点按
- **单词查询**：把悬浮放大镜移到任意单词上，立即显示词典释义
- **离线使用**：OCR 和词典查询无需联网，还可以另行下载离线翻译模型
- **假名注音／拼音模式**：在汉字上方实时显示读音提示
- **快捷键**：把一个实体按键设为长按预览翻译或假名注音，特别适合带专用按键的掌机
- **双屏与分屏**：在 Ayn Thor 等双屏设备上可同时使用两块屏幕，也能在 Android 分屏模式下与窗口化运行的游戏并排使用
- **截取区域**：只截取对话框、字幕或任意自定义区域
- **文字转语音**：把文字朗读出来。可以在设置中更改默认语音。
- **导出到 Anki**：把句子保存到 AnkiDroid，包含原文、译文、单词列表、目标单词、朗读音频和截图。甚至还能录制游戏音频一起保存！支持选择卡片类型，并内置热门牌组的预设。
- **Yomitan 集成**：无缝接入 Yomitan 词典，支持音高重音、词频标签和汉字补充信息，合并后的词条释义随处可用（包括 Anki）。今后还会有更深入的集成。
- **相机翻译**：用相机对准现实中的文字即可实时阅读，也可以拍摄快照，再点按其中的单词进行查询。
- **文本历史记录**：保留截取到的句子。默认关闭。

## 安装方法

1. [点击此处下载最新版本](https://github.com/dominostars/playtranslate/releases/latest)
2. 在 Android 设备上允许你的浏览器或文件管理器安装未知应用：打开**设置 → 应用 → 特殊应用权限 → 安装未知应用**，选择该应用，然后开启**允许来自此来源的应用**。首次打开 APK 时，Android 也会提示你直接前往该设置
3. 打开 APK 文件，点按“安装”
4. 首次启动时，按照引导步骤授予所需的权限

### 无法安装？

在部分 Android 设备上，**Google Play 保护机制**会拦截侧载的 APK，并显示含糊的“未安装应用”或“有害应用”警告。遇到这种情况时，可以暂时关闭扫描：

1. 打开 **Play 商店**
2. 点按右上角的**个人资料图标**
3. 点按 **Play 保护机制**
4. 点按右上角的**设置图标**（齿轮）
5. 关闭**使用 Play 保护机制扫描应用**

装好 APK 后，记得重新开启 Play 保护机制，让它继续扫描你的其他应用。

### 无法开启无障碍权限？

部分高级功能（例如快捷键长按预览）会提示你开启无障碍权限。在 Android 13 及更高版本中，通过下载的 APK 安装的应用，其无障碍开关会显示为灰色，点按开关会出现“受限制的设置”提示。解除限制的方法：

1. 在**设置 → 无障碍**中选择 PlayTranslate，点按一次灰色的开关，然后关闭“受限制的设置”提示。
2. 打开“应用信息”页面：**设置 → 应用 → PlayTranslate**。
3. 点按 **⋮** 菜单并选择**允许受限制的设置**。完成第 1 步后才会出现此选项。
4. 如有提示，请完成身份验证，然后返回无障碍设置并打开开关。

## 支持

如需反馈问题、获取帮助或提出需求，欢迎加入 [Discord 服务器](https://discord.gg/DVCj6p7MUC)

你也可以通过 https://ko-fi.com/playtranslate 在 Ko-fi 上支持 PlayTranslate

## 支持的语言

PlayTranslate 可以把 **26 种游戏语言**（能从屏幕上读取的文字）翻译成 **59 种翻译语言**（显示给你看的语言）。两张表都按全球使用人数排序。

### 游戏语言（从屏幕读取）

| 语言 | 本地名称 | 代码 |
|----------|------------------|---------|
| 英语       | English          | en      |
| 中文（简体）  | 简体中文             | zh      |
| 中文（繁体）  | 繁體中文             | zh-Hant |
| 印地语      | हिन्दी           | hi      |
| 西班牙语     | Español          | es      |
| 阿拉伯语     | العربية          | ar      |
| 法语       | Français         | fr      |
| 葡萄牙语     | Português        | pt      |
| 俄语       | Русский          | ru      |
| 印度尼西亚语   | Bahasa Indonesia | id      |
| 德语       | Deutsch          | de      |
| 日语       | 日本語              | ja      |
| 土耳其语     | Türkçe           | tr      |
| 越南语      | Tiếng Việt       | vi      |
| 韩语       | 한국어              | ko      |
| 意大利语     | Italiano         | it      |
| 泰语       | ไทย              | th      |
| 荷兰语      | Nederlands       | nl      |
| 罗马尼亚语    | Română           | ro      |
| 匈牙利语     | Magyar           | hu      |
| 瑞典语      | Svenska          | sv      |
| 加泰罗尼亚语   | Català           | ca      |
| 丹麦语      | Dansk            | da      |
| 芬兰语      | Suomi            | fi      |
| 挪威语      | Norsk            | no      |
| 波兰语      | Polski           | pl      |

### 翻译语言（译文显示的语言）

| 语言 | 本地名称 | 代码 |
|----------|------------------|------|
| 英语       | English          | en   |
| 中文       | 中文               | zh   |
| 印地语      | हिन्दी           | hi   |
| 西班牙语     | Español          | es   |
| 阿拉伯语     | العربية          | ar   |
| 法语       | Français         | fr   |
| 孟加拉语     | বাংলা            | bn   |
| 葡萄牙语     | Português        | pt   |
| 俄语       | Русский          | ru   |
| 乌尔都语     | اردو             | ur   |
| 印度尼西亚语   | Bahasa Indonesia | id   |
| 斯瓦希里语    | Kiswahili        | sw   |
| 德语       | Deutsch          | de   |
| 日语       | 日本語              | ja   |
| 马拉地语     | मराठी            | mr   |
| 泰卢固语     | తెలుగు           | te   |
| 土耳其语     | Türkçe           | tr   |
| 越南语      | Tiếng Việt       | vi   |
| 韩语       | 한국어              | ko   |
| 泰米尔语     | தமிழ்            | ta   |
| 波斯语      | فارسی            | fa   |
| 意大利语     | Italiano         | it   |
| 泰语       | ไทย              | th   |
| 古吉拉特语    | ગુજરાતી          | gu   |
| 波兰语      | Polski           | pl   |
| 乌克兰语     | Українська       | uk   |
| 他加禄语     | Tagalog          | tl   |
| 马来语      | Bahasa Melayu    | ms   |
| 卡纳达语     | ಕನ್ನಡ            | kn   |
| 荷兰语      | Nederlands       | nl   |
| 罗马尼亚语    | Română           | ro   |
| 匈牙利语     | Magyar           | hu   |
| 希腊语      | Ελληνικά         | el   |
| 捷克语      | Čeština          | cs   |
| 瑞典语      | Svenska          | sv   |
| 白俄罗斯语    | Беларуская       | be   |
| 希伯来语     | עברית            | he   |
| 保加利亚语    | Български        | bg   |
| 加泰罗尼亚语   | Català           | ca   |
| 斯洛伐克语    | Slovenčina       | sk   |
| 海地克里奥尔语  | Kreyòl Ayisyen   | ht   |
| 克罗地亚语    | Hrvatski         | hr   |
| 丹麦语      | Dansk            | da   |
| 芬兰语      | Suomi            | fi   |
| 挪威语      | Norsk            | no   |
| 阿尔巴尼亚语   | Shqip            | sq   |
| 加利西亚语    | Galego           | gl   |
| 斯洛文尼亚语   | Slovenščina      | sl   |
| 立陶宛语     | Lietuvių         | lt   |
| 拉脱维亚语    | Latviešu         | lv   |
| 南非荷兰语    | Afrikaans        | af   |
| 马其顿语     | Македонски       | mk   |
| 爱沙尼亚语    | Eesti            | et   |
| 格鲁吉亚语    | ქართული          | ka   |
| 威尔士语     | Cymraeg          | cy   |
| 马耳他语     | Malti            | mt   |
| 冰岛语      | Íslenska         | is   |
| 爱尔兰语     | Gaeilge          | ga   |
| 世界语      | Esperanto        | eo   |

## 可选：在线翻译服务

默认使用 [Lingva](https://github.com/thedaviddelta/lingva-translate) 进行翻译，并以 ML Kit 作为离线后备方案。想要更高质量的翻译，可以在**设置 → 翻译服务**中为以下任意服务填入 API 密钥。服务数量不限，每项服务在列表中单独列出，你可以同时配置多项，再调整顺序，决定优先由哪一项翻译：

- **DeepL**：提供免费套餐，前往 [deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer) 申请
- **OpenAI**：[platform.openai.com](https://platform.openai.com/api-keys)，可在应用内选择模型
- **Gemini**：[aistudio.google.com](https://aistudio.google.com/app/apikey)，可在应用内选择模型
- **DeepSeek**：[platform.deepseek.com](https://platform.deepseek.com/api_keys)，可在应用内选择模型
- **Mistral**：[console.mistral.ai](https://console.mistral.ai/api-keys)，可在应用内选择模型
- **Groq**：[console.groq.com](https://console.groq.com/keys)，可在应用内选择模型
- **OpenRouter**：[openrouter.ai](https://openrouter.ai/keys)，可在应用内选择模型
- **Claude**：[platform.claude.com](https://platform.claude.com/settings/keys)，可在应用内选择模型
- **自定义**：任何其他兼容 OpenAI 的端点，填入你自己的后端 URL 即可

## 可选：Anki 抽认卡

安装 [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki)，并在设置中授予 PlayTranslate 访问权限，即可把卡片直接导出到你的牌组。

## 致谢与许可证

PlayTranslate 所用的库、模型和语言数据的致谢信息，请查看英文版 README 中的[致谢部分](https://github.com/dominostars/playtranslate#credits)。本项目采用 GPL 3.0 许可证，详见 [LICENSE](https://github.com/dominostars/playtranslate/blob/main/LICENSE)。
