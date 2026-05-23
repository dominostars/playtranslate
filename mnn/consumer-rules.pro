-keep class com.playtranslate.mnn.* { *; }
-keep class com.playtranslate.mnn.internal.* { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class kotlin.Metadata { *; }
