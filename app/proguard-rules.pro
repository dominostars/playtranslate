# ── PlayTranslate-specific keeps ──────────────────────────────────────────────

# Data classes passed as TranslationResult / DictionaryResponse through callbacks
-keep class com.playtranslate.model.** { *; }

# DeepL response is parsed by Gson reflection — field names must survive
# obfuscation. DeepLResponse is private-nested in DeepLTranslator; Translation
# is nested one level deeper inside DeepLResponse (so the JVM class name is
# DeepLTranslator$DeepLResponse$Translation, NOT DeepLTranslator$Translation).
-keep class com.playtranslate.DeepLTranslator$DeepLResponse { *; }
-keep class com.playtranslate.DeepLTranslator$DeepLResponse$Translation { *; }

# Language pack catalog + manifest — Gson reflection-parsed, field names must
# survive R8 obfuscation.
-keep class com.playtranslate.language.LanguagePackCatalog { *; }
-keep class com.playtranslate.language.CatalogEntry { *; }
-keep class com.playtranslate.language.LanguagePackManifest { *; }
-keep class com.playtranslate.language.ManifestFile { *; }
-keep class com.playtranslate.language.ManifestLicense { *; }

# ── ML Kit ────────────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── Kuromoji (Japanese tokeniser) ─────────────────────────────────────────────
# Kuromoji loads dictionaries and factories by class name via reflection
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

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

# ── OkHttp / Okio (bundled rules handle most cases; add dontwarn for extras) ──
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
