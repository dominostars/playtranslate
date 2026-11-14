package com.playtranslate.tts

import com.playtranslate.language.SourceLangId

/**
 * What a voice says when it is tapped on the voice page, so the user can hear
 * it: one short sentence in the game language, "Hello. This is how this voice
 * sounds."
 *
 * A sentence, and in a script that leaves no doubt which language it is. The
 * sample was once the language's own name, one word: fine for the device
 * engine, which is told the language, and wrong for a voice service, which is
 * told nothing and has to take the language from the text. Asked to say 日本語
 * and nothing else, one service voice read it as Chinese and another said it
 * with an English accent, and neither was a sample of the voice speaking
 * Japanese. So the Japanese sentence is written with kana, which no other
 * language's reading can claim.
 *
 * An exhaustive `when`, not a map with a default: a language added to
 * [SourceLangId] does not compile until it has a sample of its own.
 *
 * Not user-interface text: it is spoken in the game language whatever
 * language the app is shown in, so it lives here and not in the string
 * resources.
 */
fun voiceSampleText(lang: SourceLangId): String = when (lang) {
    SourceLangId.JA -> "こんにちは。この声はこのように聞こえます。"
    SourceLangId.EN -> "Hello. This is how this voice sounds."
    SourceLangId.ZH -> "你好。这个声音听起来是这样的。"
    SourceLangId.ZH_HANT -> "你好。這個聲音聽起來是這樣的。"
    SourceLangId.ES -> "Hola. Así suena esta voz."
    SourceLangId.FR -> "Bonjour. Voici comment sonne cette voix."
    SourceLangId.DE -> "Hallo. So klingt diese Stimme."
    SourceLangId.IT -> "Ciao. Ecco come suona questa voce."
    SourceLangId.PT -> "Olá. É assim que esta voz soa."
    SourceLangId.NL -> "Hallo. Zo klinkt deze stem."
    SourceLangId.TR -> "Merhaba. Bu ses böyle duyulur."
    SourceLangId.VI -> "Xin chào. Giọng nói này nghe như thế này."
    SourceLangId.ID -> "Halo. Beginilah suara ini terdengar."
    SourceLangId.SV -> "Hej. Så här låter den här rösten."
    SourceLangId.DA -> "Hej. Sådan lyder denne stemme."
    SourceLangId.NO -> "Hei. Slik høres denne stemmen ut."
    SourceLangId.FI -> "Hei. Tältä tämä ääni kuulostaa."
    SourceLangId.HU -> "Szia. Így hangzik ez a hang."
    SourceLangId.RO -> "Bună. Așa sună această voce."
    SourceLangId.CA -> "Hola. Així sona aquesta veu."
    SourceLangId.KO -> "안녕하세요. 이 목소리는 이렇게 들립니다."
    SourceLangId.RU -> "Здравствуйте. Так звучит этот голос."
    SourceLangId.AR -> "مرحبا. هكذا يبدو هذا الصوت."
    SourceLangId.TH -> "สวัสดี เสียงนี้ฟังดูเป็นแบบนี้"
    SourceLangId.HI -> "नमस्ते। यह आवाज़ ऐसी सुनाई देती है।"
    SourceLangId.PL -> "Cześć. Tak brzmi ten głos."
}
