# JNI entry points are resolved by name from native code — keep them.
-keepclasseswithmembernames,includedescriptorclasses class com.playtranslate.bergamot.** {
    native <methods>;
}
-keep class com.playtranslate.bergamot.BergamotNative { *; }
