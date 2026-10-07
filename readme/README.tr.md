# PlayTranslate

<!-- l10n-header -->
<div align="center">

[English](../README.md) | [简体中文](./README.zh-CN.md) | [繁體中文](./README.zh-HK.md) | [Español](./README.es.md) | [العربية](./README.ar.md) | [Français](./README.fr.md) | [Português (Brasil)](./README.pt-BR.md) | [Русский](./README.ru.md) | [Deutsch](./README.de.md) | [日本語](./README.ja.md) | Türkçe | [Tiếng Việt](./README.vi.md) | [한국어](./README.ko.md) | [ไทย](./README.th.md)

[![Downloads](https://img.shields.io/github/downloads/dominostars/playtranslate/total)](https://github.com/dominostars/playtranslate/releases)
[![Stars](https://img.shields.io/github/stars/dominostars/playtranslate?style=flat)](https://github.com/dominostars/playtranslate/stargazers)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
[![License](https://img.shields.io/github/license/dominostars/playtranslate)](https://github.com/dominostars/playtranslate/blob/main/LICENSE)

</div>
<!-- /l10n-header -->

Oyunlar, görsel romanlar, mangalar ve ekrandaki her türlü metin için gerçek zamanlı çeviri yapan, dil öğrenmenize de yardımcı olan bir Android uygulaması. 26 oyun dilini ve 59 çeviri dilini destekler!

[En son sürümü indirmek için buraya tıklayın](https://github.com/dominostars/playtranslate/releases/latest)

Sorun bildirmek, destek almak veya istekte bulunmak için lütfen [Discord sunucumuza](https://discord.gg/DVCj6p7MUC) katılın.

[Persona 3 Reload'da PlayTranslate](https://github.com/user-attachments/assets/e89c2c6e-92f3-41d2-8e51-5483beaca612)

## Özellikler

- **Tek dokunuşla çeviri**: Oyun ekranını yakalayıp üzerindeki metni tek dokunuşla çevirin
- **Otomatik Çeviri modu**: Diyalog değiştikçe otomatik olarak çevirir; dokunmanıza gerek yoktur
- **Sözcük arama**: Kayan büyüteci herhangi bir sözcüğün üzerine getirdiğinizde sözlük tanımları anında görünür
- **Çevrimdışı kullanım**: Metin tanıma (OCR) ve sözlük aramaları internet bağlantısı olmadan çalışır; isterseniz çevrimdışı çeviri modellerini de indirebilirsiniz
- **Furigana/Pinyin modu**: Karakterlerin okunuşunu gerçek zamanlı olarak üzerlerinde gösterir
- **Kısayol tuşları**: Fiziksel bir tuşu, basılı tuttuğunuz sürece çevirileri veya furiganayı gösterecek şekilde ayarlayın; ek tuşları olan el konsolları için ideal
- **Çift ekran ve bölünmüş ekran**: Ayn Thor gibi çift ekranlı cihazlarda iki ekranda birden çalışır; Android'in bölünmüş ekran modunda, pencere modundaki oyunların yanında da kullanılabilir
- **Yakalama bölgeleri**: Yakalamayı yalnızca diyalog kutusuyla, altyazılarla veya istediğiniz herhangi bir alanla sınırlayın
- **Metin okuma**: Metinleri sesli dinleyin. Varsayılan sesi Ayarlardan değiştirebilirsiniz
- **Anki'ye aktarma**: Cümleleri orijinal metin, çeviri, sözcük listesi, hedef sözcükler, metin okuma sesi ve ekran görüntüsüyle birlikte AnkiDroid'e kaydedin. Oyun sesini bile kaydedip karta ekleyebilirsiniz! Kart türünü siz seçersiniz; popüler desteler için hazır ayarlar da vardır.
- **Yomitan desteği**: Yomitan sözlükleri uygulamaya sorunsuzca bütünleşir; perde vurgusu, sıklık etiketleri, ek kanji bilgileri ve birleştirilmiş madde tanımları her yerde (Anki dahil) karşınıza çıkar. Bu bütünleşme ileride daha da derinleşecek
- **Kamerayla çeviri**: Kameranızı çevrenizdeki bir metne doğrultup çevirisini canlı olarak okuyun ya da anlık görüntü alıp sözcüklere dokunarak tanımlarına bakın.
- **Metin geçmişi**: Yakalanan cümlelerin kaydını tutar. Varsayılan olarak kapalıdır.

## Kurulum

1. [En son sürümü indirmek için buraya tıklayın](https://github.com/dominostars/playtranslate/releases/latest)
2. Android cihazınızda tarayıcınızın veya dosya yöneticinizin bilinmeyen uygulamaları yüklemesine izin verin: **Ayarlar → Uygulamalar → Özel uygulama erişimi → Bilinmeyen uygulamaları yükle** yolunu izleyin, uygulamayı seçin ve **Bu kaynaktan izin ver** seçeneğini açın. APK dosyasını ilk kez açtığınızda Android de sizi bu ayara götürmeyi önerir
3. APK dosyasını açın ve Yükle düğmesine dokunun
4. Uygulamayı ilk kez açtığınızda ekrandaki adımları izleyerek gerekli izinleri verin

### Yüklenmiyor mu?

Bazı Android cihazlarda **Google Play Protect**, Play Store dışından yüklenen APK dosyalarını engeller ve “Uygulama yüklenmedi” ya da “Zararlı uygulama tespit edildi” gibi belirsiz bir uyarı gösterir. Böyle bir durumda taramayı geçici olarak kapatın:

1. **Play Store** uygulamasını açın
2. Sağ üstteki **profil simgenize** dokunun
3. **Play Protect** seçeneğine dokunun
4. Sağ üstteki **dişli simgesine** dokunun
5. **Uygulamaları Play Protect ile tara** seçeneğini kapatın

APK dosyasını yükleyin, ardından diğer uygulamalarınızın taranmaya devam etmesi için Play Protect'i yeniden açın.

### Erişilebilirlik açılmıyor mu?

Bazı gelişmiş özellikler (örneğin kısayol tuşuyla basılı tutarak önizleme) sizden Erişilebilirlik iznini açmanızı ister. Bazı Android cihaz üreticileri, Play Store dışından yüklenen uygulamaların Erişilebilirlik izni almasını varsayılan olarak engeller; bu durumda Ayarlardaki düğme gri görünebilir veya “Kısıtlı ayar” mesajı çıkabilir. Engeli kaldırmak için:

1. **Ayarlar → Uygulamalar → PlayTranslate** sayfasını açın
2. Sağ üstteki **⋮** menüsüne dokunun
3. **Kısıtlı ayarlara izin ver** seçeneğine dokunun
4. İstendiğinde kimliğinizi doğrulayın

Artık PlayTranslate için Erişilebilirlik iznini açabilirsiniz.

## Destek

Sorun bildirmek, destek almak veya istekte bulunmak için lütfen [Discord sunucumuza](https://discord.gg/DVCj6p7MUC) katılın.

PlayTranslate'i Ko-fi üzerinden destekleyebilirsiniz: https://ko-fi.com/playtranslate

## Desteklenen diller

PlayTranslate, **26 oyun dilinden** (ekrandan okuyabildiği metinlerin dili) **59 çeviri diline** (size gösterilen dil) çeviri yapar. İki tablo da dünya genelindeki toplam konuşan sayısına göre sıralanmıştır.

### Oyun dilleri (ekrandan okunanlar)

| Dil                      | Yerel adı        | Kod     |
|--------------------------|------------------|---------|
| İngilizce                | English          | en      |
| Çince (Basitleştirilmiş) | 简体中文             | zh      |
| Çince (Geleneksel)       | 繁體中文             | zh-Hant |
| Hintçe                   | हिन्दी           | hi      |
| İspanyolca               | Español          | es      |
| Arapça                   | العربية          | ar      |
| Fransızca                | Français         | fr      |
| Portekizce               | Português        | pt      |
| Rusça                    | Русский          | ru      |
| Endonezce                | Bahasa Indonesia | id      |
| Almanca                  | Deutsch          | de      |
| Japonca                  | 日本語              | ja      |
| Türkçe                   | Türkçe           | tr      |
| Vietnamca                | Tiếng Việt       | vi      |
| Korece                   | 한국어              | ko      |
| İtalyanca                | Italiano         | it      |
| Tayca                    | ไทย              | th      |
| Felemenkçe               | Nederlands       | nl      |
| Rumence                  | Română           | ro      |
| Macarca                  | Magyar           | hu      |
| İsveççe                  | Svenska          | sv      |
| Katalanca                | Català           | ca      |
| Danca                    | Dansk            | da      |
| Fince                    | Suomi            | fi      |
| Norveççe                 | Norsk            | no      |
| Lehçe                    | Polski           | pl      |

### Çeviri dilleri (sizin için çevrilenler)

| Dil           | Yerel adı        | Kod  |
|---------------|------------------|------|
| İngilizce     | English          | en   |
| Çince         | 中文               | zh   |
| Hintçe        | हिन्दी           | hi   |
| İspanyolca    | Español          | es   |
| Arapça        | العربية          | ar   |
| Fransızca     | Français         | fr   |
| Bengalce      | বাংলা            | bn   |
| Portekizce    | Português        | pt   |
| Rusça         | Русский          | ru   |
| Urduca        | اردو             | ur   |
| Endonezce     | Bahasa Indonesia | id   |
| Svahili dili  | Kiswahili        | sw   |
| Almanca       | Deutsch          | de   |
| Japonca       | 日本語              | ja   |
| Marathi dili  | मराठी            | mr   |
| Telugu dili   | తెలుగు           | te   |
| Türkçe        | Türkçe           | tr   |
| Vietnamca     | Tiếng Việt       | vi   |
| Korece        | 한국어              | ko   |
| Tamilce       | தமிழ்            | ta   |
| Farsça        | فارسی            | fa   |
| İtalyanca     | Italiano         | it   |
| Tayca         | ไทย              | th   |
| Güceratça     | ગુજરાતી          | gu   |
| Lehçe         | Polski           | pl   |
| Ukraynaca     | Українська       | uk   |
| Tagalogca     | Tagalog          | tl   |
| Malayca       | Bahasa Melayu    | ms   |
| Kannada dili  | ಕನ್ನಡ            | kn   |
| Felemenkçe    | Nederlands       | nl   |
| Rumence       | Română           | ro   |
| Macarca       | Magyar           | hu   |
| Yunanca       | Ελληνικά         | el   |
| Çekçe         | Čeština          | cs   |
| İsveççe       | Svenska          | sv   |
| Belarusça     | Беларуская       | be   |
| İbranice      | עברית            | he   |
| Bulgarca      | Български        | bg   |
| Katalanca     | Català           | ca   |
| Slovakça      | Slovenčina       | sk   |
| Haiti Kreyolu | Kreyòl Ayisyen   | ht   |
| Hırvatça      | Hrvatski         | hr   |
| Danca         | Dansk            | da   |
| Fince         | Suomi            | fi   |
| Norveççe      | Norsk            | no   |
| Arnavutça     | Shqip            | sq   |
| Galiçyaca     | Galego           | gl   |
| Slovence      | Slovenščina      | sl   |
| Litvanca      | Lietuvių         | lt   |
| Letonca       | Latviešu         | lv   |
| Afrikaanca    | Afrikaans        | af   |
| Makedonca     | Македонски       | mk   |
| Estonca       | Eesti            | et   |
| Gürcüce       | ქართული          | ka   |
| Galce         | Cymraeg          | cy   |
| Maltaca       | Malti            | mt   |
| İzlandaca     | Íslenska         | is   |
| İrlandaca     | Gaeilge          | ga   |
| Esperanto     | Esperanto        | eo   |

## İsteğe bağlı: Çevrimiçi çeviri hizmetleri

Varsayılan olarak çeviri için [Lingva](https://github.com/thedaviddelta/lingva-translate) kullanılır; çevrimiçi çeviri kullanılamadığında ise çevrimdışı yedek olarak ML Kit devreye girer. Daha kaliteli çeviriler için **Ayarlar → Çeviri hizmetleri** bölümünde aşağıdakilerden herhangi birinin API anahtarını girebilirsiniz. İstediğiniz kadar hizmet ekleyebilirsiniz. Her hizmet listede ayrı bir satır olarak yer alır; böylece birkaçını birden yapılandırıp sıralarını değiştirerek önce hangisinin çevireceğini seçebilirsiniz:

- **DeepL**: ücretsiz plan için [deepl.com/en/pro#developer](https://www.deepl.com/en/pro#developer)
- **OpenAI**: [platform.openai.com](https://platform.openai.com/api-keys) (modeli uygulama içinden seçin)
- **Gemini**: [aistudio.google.com](https://aistudio.google.com/app/apikey) (modeli uygulama içinden seçin)
- **DeepSeek**: [platform.deepseek.com](https://platform.deepseek.com/api_keys) (modeli uygulama içinden seçin)
- **Mistral**: [console.mistral.ai](https://console.mistral.ai/api-keys) (modeli uygulama içinden seçin)
- **Groq**: [console.groq.com](https://console.groq.com/keys) (modeli uygulama içinden seçin)
- **OpenRouter**: [openrouter.ai](https://openrouter.ai/keys) (modeli uygulama içinden seçin)
- **Claude**: [platform.claude.com](https://platform.claude.com/settings/keys) (modeli uygulama içinden seçin)
- **Özel**: OpenAI uyumlu başka herhangi bir uç nokta (**Özel URL** alanına sunucunuzun URL'sini girin)

## İsteğe bağlı: Anki Bilgi Kartları

Kartları doğrudan destelerinize aktarmak için [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) uygulamasını yükleyin ve Ayarlardan PlayTranslate uygulamasının AnkiDroid'e erişmesine izin verin.

## Teşekkürler ve lisans

PlayTranslate'in yararlandığı kütüphanelere, modellere ve dil verilerine yönelik teşekkürler [İngilizce README dosyasında](https://github.com/dominostars/playtranslate#credits) yer alır. PlayTranslate, [GPL 3.0](https://github.com/dominostars/playtranslate/blob/main/LICENSE) lisansı altında dağıtılır.
