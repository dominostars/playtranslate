plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.playtranslate.bergamot"
    compileSdk = 36

    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // arm64-v8a only. slimt's int8 path uses gemmology (xsimd/NEON) and
            // the tuned kernels are aarch64-only. BergamotBackend.isUsable()
            // gates on Process.is64Bit() so the 32-bit slice never loads this
            // (same pattern as :mnn).
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // Self-contained libc++ so libbergamot_jni.so doesn't share an
                // STL with the app's other native libs (MNN).
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DANDROID_ARM_NEON=TRUE"
                // 16K page-size alignment for Android 15+ devices (matches :mnn).
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                // Build ONLY our JNI lib + its static dependency chain. Without
                // this, AGP builds slimt's whole CMake "all" set — slimt_cli,
                // the spm_* / sentencepiece_train tools, cpuinfo dump utilities,
                // and the SHARED libsentencepiece.so (which fails to link
                // __android_log_write). We link sentencepiece-static, so none of
                // that is needed; restricting here also cuts build time sharply.
                targets += "bergamot_jni"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            // 3.22.1 ships with the Android SDK CMake (same as :mnn).
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
