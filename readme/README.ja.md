# PlayTranslate

<!-- l10n-header -->
<div align="center">

[English](../README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-HK.md) | [Español](./README.es.md) | [العربية](./README.ar.md) | [Français](./README.fr.md) | [Português (Brasil)](./README.pt-BR.md) | [Русский](./README.ru.md) | [Deutsch](./README.de.md) | 日本語 | [Türkçe](./README.tr.md) | [Tiếng Việt](./README.vi.md) | [한국어](./README.ko.md) | [ไทย](./README.th.md)

[![Downloads](https://img.shields.io/github/downloads/dominostars/playtranslate/total)](https://github.com/dominostars/playtranslate/releases)
[![Stars](https://img.shields.io/github/stars/dominostars/playtranslate?style=flat)](https://github.com/dominostars/playtranslate/stargazers)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![License](https://img.shields.io/github/license/dominostars/playtranslate)](https://github.com/dominostars/playtranslate/blob/main/LICENSE)

</div>
<!-- /l10n-header -->

ゲーム、ビジュアルノベル、漫画など、画面に表示されるあらゆるテキストをリアルタイムで翻訳し、語学学習にも使えるAndroidアプリです。ゲームの言語は26種類、翻訳先の言語は59種類に対応しています！

[最新リリースのダウンロードはこちら](https://github.com/dominostars/playtranslate/releases/latest)

不具合の報告やご要望、サポートが必要な場合は、[Discordサーバー](https://discord.gg/DVCj6p7MUC)にご参加ください。

[Persona 3 ReloadでPlayTranslateを使っている様子](https://github.com/user-attachments/assets/e89c2c6e-92f3-41d2-8e51-5483beaca612)

## 機能

- **ワンタップ翻訳**：ゲーム画面をキャプチャして、表示されているテキストをワンタップで翻訳します。
- **自動翻訳モード**：セリフが変わるたびに自動で翻訳します。タップは必要ありません。
- **単語検索**：フローティングのルーペを単語にかざすと、辞書の定義がすぐに表示されます。
- **オフライン**：OCRと辞書検索はインターネットに接続しなくても使えます。必要に応じて、オフライン翻訳モデルをダウンロードして使うこともできます。
- **ふりがな／ピンインモード**：文字の上に読みをリアルタイムで表示します。
- **ホットキー**：物理キーを割り当てると、長押しで翻訳やふりがなをプレビューできます。専用ボタンを備えた携帯ゲーム機にぴったりです。
- **2画面と分割画面**：Ayn Thorなどの2画面端末では、両方の画面にまたがって使えます。Androidの分割画面で、ウィンドウ表示のゲームと並べて使うこともできます。
- **キャプチャ範囲**：キャプチャする範囲を、セリフ枠や字幕、任意のカスタム範囲だけに絞り込めます。
- **テキスト読み上げ**：テキストを音声で読み上げます。デフォルトの音声は設定で変更できます。
- **Ankiへのエクスポート**：原文、翻訳、単語リスト、対象の単語、テキスト読み上げの音声、スクリーンショットを含めて、文をAnkiDroidに保存できます。ゲーム音声を録音して含めることもできます！カードタイプを選べるほか、人気のデッキ向けのプリセットも用意しています。
- **Yomitan連携**：Yomitan辞書をシームレスに組み込めます。高低アクセント、頻度ラベル、漢字の詳細情報に対応し、複数の辞書の定義を単語ごとにまとめて、Ankiのカードも含めたあらゆる場面で表示します。今後さらに連携を深める予定です。
- **カメラ翻訳**：身の回りのテキストにカメラを向けると、その場で翻訳を読めます。静止画を撮影して、単語をタップして調べることもできます。
- **テキスト履歴**：キャプチャした文を記録します。デフォルトではオフです。

## インストール方法

1. [最新リリースのダウンロードはこちら](https://github.com/dominostars/playtranslate/releases/latest)します。
2. Android端末で、APKを開くブラウザまたはファイルマネージャーに不明なアプリのインストールを許可します。**設定 → アプリ → 特別なアプリアクセス → 不明なアプリのインストール**を開き、対象のアプリを選んで**この提供元のアプリを許可**をオンにします。APKを初めて開いたときに、Androidがこの設定画面へ案内することもあります。
3. APKを開き、「インストール」をタップします。
4. 初回起動時は、画面の案内に従って必要な権限を許可します。

### インストールできない場合

一部のAndroid端末では、**Google Play プロテクト**がストア外から入手したAPKをブロックし、「アプリはインストールされていません」や「有害なアプリ」といった分かりにくい警告を表示することがあります。その場合は、次の手順でスキャンを一時的にオフにしてください。

1. **Play ストア**を開きます。
2. 右上の**プロフィールアイコン**をタップします。
3. **Play プロテクト**をタップします。
4. 右上の**歯車アイコン**をタップします。
5. **Play プロテクトでアプリをスキャン**をオフにします。

APKのインストールが終わったら、Play プロテクトをオンに戻して、他のアプリのスキャンを続けてください。

### ユーザー補助をオンにできない場合

一部の高度な機能（ホットキーの長押しプレビューなど）を使うときは、ユーザー補助の権限をオンにするよう求められます。Android 13以降では、ダウンロードしたAPKファイルからインストールしたアプリはユーザー補助のスイッチがグレー表示になり、タップすると「制限付き設定」というメッセージが表示されます。制限を解除する手順は次のとおりです。

1. **設定 → ユーザー補助**でPlayTranslateを選択し、グレー表示のスイッチを一度タップします。「制限付き設定」のメッセージを閉じます。
2. アプリ情報の画面（**設定 → アプリ → PlayTranslate**）を開きます。
3. 3点メニュー（**⋮**）をタップし、**制限付き設定を許可**を選択します。この項目は手順1の後にだけ表示されます。
4. 認証を求められたら認証し、ユーザー補助の画面に戻ってスイッチをオンにします。

## サポート

不具合の報告やご要望、サポートが必要な場合は、[Discordサーバー](https://discord.gg/DVCj6p7MUC)にご参加ください。

Ko-fiでPlayTranslateを支援することもできます：https://ko-fi.com/playtranslate

## 対応言語

PlayTranslateは、**26種類のゲームの言語**（画面から読み取れるテキストの言語）から、**59種類の翻訳先の言語**（翻訳結果を表示する言語）に翻訳します。どちらの表も、世界全体の話者数が多い順に並んでいます。

### ゲームの言語（画面から読み取る言語）

| 言語 | 現地語表記 | コード |
|-----------|------------------|---------|
| 英語        | English          | en      |
| 中国語（簡体字） | 简体中文             | zh      |
| 中国語（繁体字） | 繁體中文             | zh-Hant |
| ヒンディー語    | हिन्दी           | hi      |
| スペイン語     | Español          | es      |
| アラビア語     | العربية          | ar      |
| フランス語     | Français         | fr      |
| ポルトガル語    | Português        | pt      |
| ロシア語      | Русский          | ru      |
| インドネシア語   | Bahasa Indonesia | id      |
| ドイツ語      | Deutsch          | de      |
| 日本語       | 日本語              | ja      |
| トルコ語      | Türkçe           | tr      |
| ベトナム語     | Tiếng Việt       | vi      |
| 韓国語       | 한국어              | ko      |
| イタリア語     | Italiano         | it      |
| タイ語       | ไทย              | th      |
| オランダ語     | Nederlands       | nl      |
| ルーマニア語    | Română           | ro      |
| ハンガリー語    | Magyar           | hu      |
| スウェーデン語   | Svenska          | sv      |
| カタロニア語    | Català           | ca      |
| デンマーク語    | Dansk            | da      |
| フィンランド語   | Suomi            | fi      |
| ノルウェー語    | Norsk            | no      |
| ポーランド語    | Polski           | pl      |

### 翻訳先の言語（翻訳して表示する言語）

| 言語 | 現地語表記 | コード |
|------------|------------------|------|
| 英語         | English          | en   |
| 中国語        | 中文               | zh   |
| ヒンディー語     | हिन्दी           | hi   |
| スペイン語      | Español          | es   |
| アラビア語      | العربية          | ar   |
| フランス語      | Français         | fr   |
| ベンガル語      | বাংলা            | bn   |
| ポルトガル語     | Português        | pt   |
| ロシア語       | Русский          | ru   |
| ウルドゥー語     | اردو             | ur   |
| インドネシア語    | Bahasa Indonesia | id   |
| スワヒリ語      | Kiswahili        | sw   |
| ドイツ語       | Deutsch          | de   |
| 日本語        | 日本語              | ja   |
| マラーティー語    | मराठी            | mr   |
| テルグ語       | తెలుగు           | te   |
| トルコ語       | Türkçe           | tr   |
| ベトナム語      | Tiếng Việt       | vi   |
| 韓国語        | 한국어              | ko   |
| タミル語       | தமிழ்            | ta   |
| ペルシア語      | فارسی            | fa   |
| イタリア語      | Italiano         | it   |
| タイ語        | ไทย              | th   |
| グジャラート語    | ગુજરાતી          | gu   |
| ポーランド語     | Polski           | pl   |
| ウクライナ語     | Українська       | uk   |
| タガログ語      | Tagalog          | tl   |
| マレー語       | Bahasa Melayu    | ms   |
| カンナダ語      | ಕನ್ನಡ            | kn   |
| オランダ語      | Nederlands       | nl   |
| ルーマニア語     | Română           | ro   |
| ハンガリー語     | Magyar           | hu   |
| ギリシャ語      | Ελληνικά         | el   |
| チェコ語       | Čeština          | cs   |
| スウェーデン語    | Svenska          | sv   |
| ベラルーシ語     | Беларуская       | be   |
| ヘブライ語      | עברית            | he   |
| ブルガリア語     | Български        | bg   |
| カタロニア語     | Català           | ca   |
| スロバキア語     | Slovenčina       | sk   |
| ハイチ・クレオール語 | Kreyòl Ayisyen   | ht   |
| クロアチア語     | Hrvatski         | hr   |
| デンマーク語     | Dansk            | da   |
| フィンランド語    | Suomi            | fi   |
| ノルウェー語     | Norsk            | no   |
| アルバニア語     | Shqip            | sq   |
| ガリシア語      | Galego           | gl   |
| スロベニア語     | Slovenščina      | sl   |
| リトアニア語     | Lietuvių         | lt   |
| ラトビア語      | Latviešu         | lv   |
| アフリカーンス語   | Afrikaans        | af   |
| マケドニア語     | Македонски       | mk   |
| エストニア語     | Eesti            | et   |
| ジョージア語     | ქართული          | ka   |
| ウェールズ語     | Cymraeg          | cy   |
| マルタ語       | Malti            | mt   |
| アイスランド語    | Íslenska         | is   |
| アイルランド語    | Gaeilge          | ga   |
| エスペラント語    | Esperanto        | eo   |

## オンライン翻訳サービス（任意）

デフォルトでは、翻訳には[Lingva](https://github.com/thedaviddelta/lingva-translate)を使い、オンライン翻訳を利用できないときは、オフラインで動作するML Kitで代替します。より高品質な翻訳を使いたい場合は、**設定 → 翻訳サービス**で、次のいずれかのAPIキーを登録できます。サービスはいくつでも追加できます。各サービスはリスト内の個別の項目になるため、複数を設定しておき、並べ替えて最初に翻訳に使うサービスを選べます。

- **DeepL**：[deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer)（無料プランあり）
- **OpenAI**：[platform.openai.com](https://platform.openai.com/api-keys)（モデルはアプリ内で選択）
- **Gemini**：[aistudio.google.com](https://aistudio.google.com/app/apikey)（モデルはアプリ内で選択）
- **DeepSeek**：[platform.deepseek.com](https://platform.deepseek.com/api_keys)（モデルはアプリ内で選択）
- **Mistral**：[console.mistral.ai](https://console.mistral.ai/api-keys)（モデルはアプリ内で選択）
- **Groq**：[console.groq.com](https://console.groq.com/keys)（モデルはアプリ内で選択）
- **OpenRouter**：[openrouter.ai](https://openrouter.ai/keys)（モデルはアプリ内で選択）
- **Claude**：[platform.claude.com](https://platform.claude.com/settings/keys)（モデルはアプリ内で選択）
- **カスタム**：その他のOpenAI互換エンドポイント（「カスタムURL」に独自のベースURLを入力）

## Ankiフラッシュカード（任意）

[AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki)をインストールし、PlayTranslateの設定でAnkiDroidへのアクセスを許可すると、カードをデッキに直接エクスポートできます。

## クレジットとライセンス

ライブラリ、モデル、言語データのクレジットと、GPL 3.0ライセンスについては、英語版READMEの[クレジット](https://github.com/dominostars/playtranslate#credits)と[ライセンス](https://github.com/dominostars/playtranslate/blob/main/LICENSE)をご覧ください。
