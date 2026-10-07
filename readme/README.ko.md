# PlayTranslate

<!-- l10n-header -->
<div align="center">

[English](../README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-HK.md) | [Español](./README.es.md) | [العربية](./README.ar.md) | [Français](./README.fr.md) | [Português (Brasil)](./README.pt-BR.md) | [Русский](./README.ru.md) | [Deutsch](./README.de.md) | [日本語](./README.ja.md) | [Türkçe](./README.tr.md) | [Tiếng Việt](./README.vi.md) | 한국어 | [ไทย](./README.th.md)

[![Downloads](https://img.shields.io/github/downloads/dominostars/playtranslate/total)](https://github.com/dominostars/playtranslate/releases)
[![Stars](https://img.shields.io/github/stars/dominostars/playtranslate?style=flat)](https://github.com/dominostars/playtranslate/stargazers)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![License](https://img.shields.io/github/license/dominostars/playtranslate)](https://github.com/dominostars/playtranslate/blob/main/LICENSE)

</div>
<!-- /l10n-header -->

게임, 비주얼 노벨, 만화를 비롯해 화면에 보이는 모든 텍스트를 실시간으로 번역하고 언어 학습에도 활용할 수 있는 Android 앱입니다. 게임 언어 26개와 번역 언어 59개를 지원합니다!

[여기를 눌러 최신 릴리스를 다운로드하세요](https://github.com/dominostars/playtranslate/releases/latest)

문제를 신고하거나 도움을 받거나 기능을 요청하려면 [Discord 서버](https://discord.gg/DVCj6p7MUC)에 참여해 주세요.

[Persona 3 Reload에서 PlayTranslate를 사용하는 모습](https://github.com/user-attachments/assets/e89c2c6e-92f3-41d2-8e51-5483beaca612)

## 기능

- **원탭 번역**: 한 번 탭하면 게임 화면을 캡처해 화면 속 텍스트를 번역합니다.
- **자동 번역 모드**: 대사가 바뀔 때마다 탭할 필요 없이 자동으로 번역합니다.
- **단어 검색**: 플로팅 돋보기를 단어 위에 올리면 사전 정의가 바로 표시됩니다.
- **오프라인**: OCR과 사전 검색은 인터넷 연결 없이도 작동하며, 필요하면 오프라인 번역 모델을 다운로드해 사용할 수도 있습니다.
- **후리가나/병음 모드**: 글자 위에 읽는 법을 실시간으로 표시합니다.
- **단축키**: 물리 키를 지정하면 길게 누르는 동안 번역이나 후리가나를 미리 볼 수 있습니다. 전용 버튼이 있는 휴대용 게임기에 특히 유용합니다.
- **듀얼 스크린 및 화면 분할**: Ayn Thor 같은 듀얼 디스플레이 기기에서는 두 화면에 걸쳐 작동하고, Android 화면 분할에서는 창 모드로 실행한 게임과 나란히 사용할 수 있습니다.
- **캡처 영역**: 대화창이나 자막, 직접 지정한 영역만 잘라서 캡처합니다.
- **텍스트 음성 변환**: 텍스트를 소리 내어 읽어 줍니다. 기본 음성은 설정에서 바꿀 수 있습니다.
- **Anki 내보내기**: 원문, 번역, 단어 목록, 대상 단어, 텍스트 음성 변환 오디오, 스크린샷을 포함해 문장을 AnkiDroid에 저장합니다. 게임 오디오를 녹음해 함께 넣을 수도 있습니다! 카드 유형을 선택할 수 있으며, 인기 있는 덱에 맞춘 프리셋도 제공합니다.
- **Yomitan 연동**: Yomitan 사전이 앱에 자연스럽게 통합되어 고저 악센트, 빈도 표시, 한자 상세 정보, 여러 사전을 합친 용어 정의를 어디서나(Anki 포함) 볼 수 있습니다. 앞으로 더 깊이 연동할 예정입니다.
- **카메라 번역**: 주변의 텍스트에 카메라를 비추면 번역을 실시간으로 읽을 수 있고, 화면을 멈춘 뒤 단어를 탭해 찾아볼 수도 있습니다.
- **텍스트 기록**: 캡처한 문장을 기록으로 남깁니다. 기본적으로 꺼져 있습니다.

## 설치 방법

1. [여기를 눌러 최신 릴리스를 다운로드하세요](https://github.com/dominostars/playtranslate/releases/latest)
2. Android 기기에서 브라우저나 파일 관리자가 출처를 알 수 없는 앱을 설치할 수 있도록 허용하세요. **설정 → 앱 → 특별한 앱 액세스 → 출처를 알 수 없는 앱 설치**를 열고 해당 앱을 선택한 다음 **이 출처 허용**을 켜면 됩니다. APK를 처음 열 때 Android가 이 설정 화면으로 이동할지 묻기도 합니다
3. APK 파일을 열고 “설치”를 탭하세요
4. 처음 실행하면 화면의 안내에 따라 필요한 권한을 허용하세요

### 설치가 안 되나요?

일부 Android 기기에서는 **Google Play 프로텍트**가 Play 스토어 밖에서 받은 APK를 차단하고 “앱이 설치되지 않았습니다”나 “유해한 앱” 같은 모호한 경고를 표시합니다. 이럴 때는 다음과 같이 검사를 잠시 끄세요.

1. **Play 스토어**를 여세요
2. 오른쪽 상단의 **프로필 아이콘**을 탭하세요
3. **Play 프로텍트**를 탭하세요
4. 오른쪽 상단의 **톱니바퀴 아이콘**을 탭하세요
5. **Play 프로텍트로 앱 검사**를 끄세요

APK를 설치한 뒤에는 다른 앱도 계속 검사할 수 있도록 Play 프로텍트를 다시 켜 두세요.

### 접근성을 켤 수 없나요?

몇몇 고급 기능(단축키를 길게 눌러 미리 보기 등)을 사용하려면 접근성 권한을 켜라는 안내가 표시됩니다. 일부 Android 제조사는 Play 스토어 밖에서 설치한 앱이 접근성 권한을 받지 못하도록 기본적으로 막아 두기 때문에, 설정의 스위치가 회색으로 비활성화되어 있거나 “제한된 설정” 메시지가 표시될 수 있습니다. 차단을 해제하려면 다음과 같이 하세요.

1. **설정 → 앱 → PlayTranslate**를 여세요
2. 오른쪽 상단의 **⋮** 메뉴를 탭하세요
3. **제한된 설정 허용**을 탭하세요
4. 인증 요청이 표시되면 인증하세요

이제 접근성 설정에서 PlayTranslate를 사용 설정할 수 있습니다.

## 지원

문제를 신고하거나 도움을 받거나 기능을 요청하려면 [Discord 서버](https://discord.gg/DVCj6p7MUC)에 참여해 주세요.

[Ko-fi](https://ko-fi.com/playtranslate)에서 PlayTranslate를 후원할 수 있습니다.

## 지원 언어

PlayTranslate는 **게임 언어 26개**(화면에서 읽어 들이는 텍스트의 언어)를 **번역 언어 59개**(사용자에게 보여 주는 언어)로 번역합니다. 두 표 모두 전 세계 사용 인구가 많은 순서로 정렬되어 있습니다.

### 게임 언어(화면에서 읽는 언어)

| 언어 | 원어 표기 | 코드 |
|----------|------------------|---------|
| 영어       | English          | en      |
| 중국어(간체) | 简体中文             | zh      |
| 중국어(번체) | 繁體中文             | zh-Hant |
| 힌디어      | हिन्दी           | hi      |
| 스페인어     | Español          | es      |
| 아랍어      | العربية          | ar      |
| 프랑스어     | Français         | fr      |
| 포르투갈어    | Português        | pt      |
| 러시아어     | Русский          | ru      |
| 인도네시아어   | Bahasa Indonesia | id      |
| 독일어      | Deutsch          | de      |
| 일본어      | 日本語              | ja      |
| 튀르키예어    | Türkçe           | tr      |
| 베트남어     | Tiếng Việt       | vi      |
| 한국어      | 한국어              | ko      |
| 이탈리아어    | Italiano         | it      |
| 태국어      | ไทย              | th      |
| 네덜란드어    | Nederlands       | nl      |
| 루마니아어    | Română           | ro      |
| 헝가리어     | Magyar           | hu      |
| 스웨덴어     | Svenska          | sv      |
| 카탈로니아어   | Català           | ca      |
| 덴마크어     | Dansk            | da      |
| 핀란드어     | Suomi            | fi      |
| 노르웨이어    | Norsk            | no      |
| 폴란드어     | Polski           | pl      |

### 번역 언어(번역해서 보여 주는 언어)

| 언어 | 원어 표기 | 코드 |
|----------|------------------|------|
| 영어       | English          | en   |
| 중국어      | 中文               | zh   |
| 힌디어      | हिन्दी           | hi   |
| 스페인어     | Español          | es   |
| 아랍어      | العربية          | ar   |
| 프랑스어     | Français         | fr   |
| 벵골어      | বাংলা            | bn   |
| 포르투갈어    | Português        | pt   |
| 러시아어     | Русский          | ru   |
| 우르두어     | اردو             | ur   |
| 인도네시아어   | Bahasa Indonesia | id   |
| 스와힐리어    | Kiswahili        | sw   |
| 독일어      | Deutsch          | de   |
| 일본어      | 日本語              | ja   |
| 마라티어     | मराठी            | mr   |
| 텔루구어     | తెలుగు           | te   |
| 튀르키예어    | Türkçe           | tr   |
| 베트남어     | Tiếng Việt       | vi   |
| 한국어      | 한국어              | ko   |
| 타밀어      | தமிழ்            | ta   |
| 페르시아어    | فارسی            | fa   |
| 이탈리아어    | Italiano         | it   |
| 태국어      | ไทย              | th   |
| 구자라트어    | ગુજરાતી          | gu   |
| 폴란드어     | Polski           | pl   |
| 우크라이나어   | Українська       | uk   |
| 타갈로그어    | Tagalog          | tl   |
| 말레이어     | Bahasa Melayu    | ms   |
| 칸나다어     | ಕನ್ನಡ            | kn   |
| 네덜란드어    | Nederlands       | nl   |
| 루마니아어    | Română           | ro   |
| 헝가리어     | Magyar           | hu   |
| 그리스어     | Ελληνικά         | el   |
| 체코어      | Čeština          | cs   |
| 스웨덴어     | Svenska          | sv   |
| 벨라루스어    | Беларуская       | be   |
| 히브리어     | עברית            | he   |
| 불가리아어    | Български        | bg   |
| 카탈로니아어   | Català           | ca   |
| 슬로바키아어   | Slovenčina       | sk   |
| 아이티어     | Kreyòl Ayisyen   | ht   |
| 크로아티아어   | Hrvatski         | hr   |
| 덴마크어     | Dansk            | da   |
| 핀란드어     | Suomi            | fi   |
| 노르웨이어    | Norsk            | no   |
| 알바니아어    | Shqip            | sq   |
| 갈리시아어    | Galego           | gl   |
| 슬로베니아어   | Slovenščina      | sl   |
| 리투아니아어   | Lietuvių         | lt   |
| 라트비아어    | Latviešu         | lv   |
| 아프리칸스어   | Afrikaans        | af   |
| 마케도니아어   | Македонски       | mk   |
| 에스토니아어   | Eesti            | et   |
| 조지아어     | ქართული          | ka   |
| 웨일스어     | Cymraeg          | cy   |
| 몰타어      | Malti            | mt   |
| 아이슬란드어   | Íslenska         | is   |
| 아일랜드어    | Gaeilge          | ga   |
| 에스페란토어   | Esperanto        | eo   |

## 온라인 번역 서비스(선택 사항)

기본 번역에는 [Lingva](https://github.com/thedaviddelta/lingva-translate)를 사용하고, 온라인 번역을 사용할 수 없을 때는 오프라인에서 작동하는 ML Kit 번역으로 대체합니다. 더 높은 품질의 번역이 필요하면 **설정 → 번역 서비스**에서 아래 서비스 중 원하는 서비스의 API 키를 등록하세요. 서비스는 원하는 만큼 추가할 수 있습니다. 각 서비스가 목록에 개별 항목으로 표시되므로 여러 서비스를 설정해 두고 순서를 바꿔 가장 먼저 번역할 서비스를 정할 수 있습니다.

- **DeepL**: [deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer)(무료 요금제 제공)
- **OpenAI**: [platform.openai.com](https://platform.openai.com/api-keys)(모델은 앱에서 선택)
- **Gemini**: [aistudio.google.com](https://aistudio.google.com/app/apikey)(모델은 앱에서 선택)
- **DeepSeek**: [platform.deepseek.com](https://platform.deepseek.com/api_keys)(모델은 앱에서 선택)
- **Mistral**: [console.mistral.ai](https://console.mistral.ai/api-keys)(모델은 앱에서 선택)
- **Groq**: [console.groq.com](https://console.groq.com/keys)(모델은 앱에서 선택)
- **OpenRouter**: [openrouter.ai](https://openrouter.ai/keys)(모델은 앱에서 선택)
- **Claude**: [platform.claude.com](https://platform.claude.com/settings/keys)(모델은 앱에서 선택)
- **사용자 지정**: 그 밖의 OpenAI 호환 엔드포인트(“사용자 지정 URL”에 사용할 백엔드 URL을 입력)

## Anki 플래시카드(선택 사항)

[AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki)를 설치하고 설정에서 PlayTranslate에 AnkiDroid 접근 권한을 허용하면 카드를 덱으로 바로 내보낼 수 있습니다.

## 크레딧 및 라이선스

라이브러리, 모델, 언어 데이터의 크레딧과 GPL 3.0 라이선스는 영어 README의 [크레딧](https://github.com/dominostars/playtranslate#credits)과 [라이선스](https://github.com/dominostars/playtranslate/blob/main/LICENSE) 항목에서 확인할 수 있습니다.
