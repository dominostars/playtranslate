# ── GameLens-specific keeps ───────────────────────────────────────────────────

# Data classes passed as TranslationResult / DictionaryResponse through callbacks
-keep class com.gamelens.model.** { *; }

# DeepL response is parsed by Gson reflection — field names must survive obfuscation
-keep class com.gamelens.DeepLTranslator$DeepLResponse { *; }
-keep class com.gamelens.DeepLTranslator$Translation { *; }

# ── ML Kit ────────────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── Kuromoji (Japanese tokeniser) ─────────────────────────────────────────────
# Kuromoji loads dictionaries and factories by class name via reflection
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

# ── OkHttp / Okio (bundled rules handle most cases; add dontwarn for extras) ──
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
