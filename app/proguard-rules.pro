# ── PlayTranslate-specific keeps ──────────────────────────────────────────────

# Data classes passed as TranslationResult / DictionaryResponse through callbacks
-keep class com.playtranslate.model.** { *; }

# The translation backends, language-pack catalog/manifest, the Yomitan
# registry + index.json, and the Tatoeba API DTOs were migrated from Gson to
# kotlinx.serialization — its serializers are generated at compile time, so R8
# sees them and they need NO keep rules (this is the whole point of the
# migration). Gson remains a dependency only for the reflection-free streaming
# Yomitan bank parsers (FreqData/TermEntry/TermGlossary), which never needed
# keeps. If you add a new reflective Gson DTO, prefer @Serializable over adding
# a keep rule here.

# ── ML Kit ────────────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── Sudachi (Japanese morphological analysis) ─────────────────────────────────
# Sudachi instantiates its plugins reflectively by class name from the bundled
# sudachi.json — input-text (DefaultInputTextPlugin, ProlongedSoundMark…), OOV
# (MeCabOovProviderPlugin, SimpleOovProviderPlugin), path-rewrite (JoinNumeric,
# JoinKatakanaOov), formatter — so R8 can't see those references. Keep the whole
# package plus jdartsclone (the double-array trie it reads the system dict
# through). Without this, a minified release build silently loses JA
# tokenization/furigana (create() throws → analyze degrades to empty).
-keep class com.worksap.nlp.sudachi.** { *; }
-keep class com.worksap.nlp.dartsclone.** { *; }
-dontwarn com.worksap.nlp.sudachi.**
-dontwarn com.worksap.nlp.dartsclone.**

# JSON-P: Sudachi parses sudachi.json with javax.json. The glassfish provider is
# resolved by class-name string — javax.json 1.1.4 ships no META-INF/services,
# so JsonProvider.provider() falls back to
# Class.forName("org.glassfish.json.JsonProviderImpl") — invisible to R8.
-keep class javax.json.** { *; }
-keep class org.glassfish.json.** { *; }
-dontwarn javax.json.**
-dontwarn org.glassfish.json.**

# ── Lucene + Snowball (English/Latin stemmer) ────────────────────────────────
# Lucene analyzers-common ships with reflection-loaded filters and
# META-INF/services entries. Phase 3 only uses the Snowball EnglishStemmer
# directly, but keeping the analyzer package + the Snowball classes is
# defensive against future refactors that might use AnalysisSPI-based loading.
-keep class org.apache.lucene.analysis.** { *; }
-keep class org.tartarus.snowball.** { *; }
-dontwarn org.apache.lucene.**
-dontwarn org.tartarus.**

# Lucene FST + DataInput/Output wrappers (target gloss pack reader).
# Outputs.getSingleton() resolves singleton fields reflectively; FST
# constructors and Util.get touch internal classes through method handles.
-keep class org.apache.lucene.util.fst.** { *; }
-keep class org.apache.lucene.store.** { *; }

# ── KOMORAN (Korean morphological analyzer) ──────────────────────────────────
# KOMORAN loads its bundled model via classloader resource lookup from
# `kr.co.shineware.nlp.komoran.*`. The models, dictionary data, and helper
# classes (kr.co.shineware.util, kr.co.shineware.common) are referenced by
# reflection and class name; keeping both namespaces avoids R8 stripping.
-keep class kr.co.shineware.** { *; }
-dontwarn kr.co.shineware.**

# ── HanLP (Chinese CRF segmenter) ────────────────────────────────────────────
# HanLP loads models, dictionaries, and nature-enum mappings by reflection.
-keep class com.hankcs.hanlp.** { *; }
-dontwarn com.hankcs.hanlp.**

# ── OpenCC (opencc4j: Simplified⇄Traditional Chinese) ─────────────────────────
# opencc4j reads its bundled conversion dictionaries from classpath resources
# and resolves segment/format components reflectively; it also pulls the
# com.github.houbb.heaven util lib. Keep both namespaces so a minified release
# build doesn't strip the converter or its dictionary data — otherwise render-
# time Traditional Chinese conversion silently falls back to Simplified.
-keep class com.github.houbb.opencc4j.** { *; }
-keep class com.github.houbb.heaven.** { *; }
-dontwarn com.github.houbb.**

# ── OkHttp / Okio (bundled rules handle most cases; add dontwarn for extras) ──
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
