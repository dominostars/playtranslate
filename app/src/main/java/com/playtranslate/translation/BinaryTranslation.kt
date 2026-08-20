package com.playtranslate.translation

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Detects whether this process's ARM native code executes under a binary
 * translator (Intel Houdini / NetEase libnb / Google ndk_translation) instead
 * of real ARM silicon. The APK ships no x86_64 slice, so x86_64 hosts — MuMu,
 * BlueStacks, Google Play Games on PC, Intel Chromebooks — install the arm64
 * slice and translate every instruction of our .so files at runtime.
 *
 * Why anyone gates on this: translators are not faithful CPUs. Field case
 * (2026-08, MuMu Player's Android 12 image): Houdini mis-resolves TLSDESC —
 * the bionic resolver call returns an offset that makes `thread_pointer +
 * offset` wrap to exactly 0 — so the first C++ `thread_local` access in
 * libbergamot_jni.so (slimt's SentenceStream ctor, Splitter.cc) dereferences
 * null and SIGSEGVs the process before any try/catch can run. The same APK is
 * correct on real ARM hardware and on MuMu's Android 15 image (newer
 * translator), so the engine gates itself off translated environments rather
 * than chase per-translator bugs; the backend waterfall falls through to
 * ML Kit, whose libs predate TLSDESC-emitting toolchains and run there.
 *
 * Detection is fail-open: every signal unreadable ⇒ "not translated", so a
 * real device can never lose Bergamot to a detection failure.
 */
object BinaryTranslation {
    private const val TAG = "BinaryTranslation"

    @Volatile private var cached: Boolean? = null

    /** True when this process runs an ARM native slice on a non-ARM host. */
    fun isTranslated(context: Context): Boolean =
        cached ?: compute(context.applicationContext).also { cached = it }

    private fun compute(context: Context): Boolean {
        // Which slice the installer picked for us — the path suffix is derived
        // from primaryCpuAbi by the platform (armeabi*→"arm", arm64-v8a→
        // "arm64", x86*→itself) independent of extractNativeLibs. Exempt ONLY
        // a definite x86 slice: if an x86_64 slice ever ships (the build
        // comment reserves it for emulator testing), that process runs native
        // x86 code and must not be gated even though the image's bridge
        // properties are still set. Anything else — arm*, a null dir, an
        // OEM-mangled shape — falls through to the environment checks below,
        // so an unrecognized path can never silently disable the gate (on
        // real ARM hardware those checks are all negative anyway).
        val slice = (context.applicationInfo.nativeLibraryDir ?: "").substringAfterLast('/')
        if (slice.startsWith("x86")) return false

        // Decide ONLY on ISA-specific signals — both true exactly when arm64
        // is a translated guest on this image:
        //   • ro.dalvik.vm.isa.arm64 is set (to the host ISA, e.g. "x86_64")
        //     iff the arm64 ISA is remapped;
        //   • /system/lib64/arm64 is the translator's guest-ARM system-lib
        //     dir (reflection-free fallback) — real ARM devices never have it.
        // ro.dalvik.vm.native.bridge is deliberately NOT a trigger: it only
        // says the image ships a translator for SOME guest ISA. An ARM-native
        // host carrying a bridge for a foreign guest (or an OEM leaving the
        // prop set) must not cost a real device its Bergamot tier (Codex P2).
        val translated = systemProperty("ro.dalvik.vm.isa.arm64").isNotEmpty() ||
            File("/system/lib64/arm64").isDirectory
        if (translated) {
            val bridge = systemProperty("ro.dalvik.vm.native.bridge")
            Log.i(TAG, "Native slice '$slice' runs under binary translation (bridge='$bridge')")
        }
        return translated
    }

    // SystemProperties is unlisted-API (warns, never blocked); any failure
    // reads as "" so the property contributes no signal instead of a gate.
    private fun systemProperty(name: String): String = try {
        @SuppressLint("PrivateApi")
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
    } catch (t: Throwable) {
        ""
    }
}
