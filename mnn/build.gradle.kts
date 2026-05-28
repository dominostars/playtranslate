plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.playtranslate.mnn"
    compileSdk = 34

    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 30

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // arm64-v8a only. MNN's tuned LLM kernels (ARMv8.2 fp16 + i8mm +
            // transformer-fuse weight-dequant GEMM) are aarch64-only, and on
            // armeabi-v7a MNN would fall back to plain C++ paths and run
            // 5–10× slower — effectively unusable for our LLM tier. The
            // :app module ships armeabi-v7a (so the app installs on a 32-bit
            // device and ML Kit translation works), but :mnn deliberately
            // doesn't, so the 32-bit slice has no MNN libs. The runtime
            // hardware gate in OnDeviceLlmBackend.supportsRequiredAbi() is
            // what hides the MNN backend rows in Settings on 32-bit.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"

                // MNN-LLM build — the Llm runtime that drives Qwen 2.5 here.
                // Flag set follows the official MnnLlmChat Android app's README,
                // restricted to the Qwen-only subset: no vision/audio/diffusion
                // since we don't ship multimodal LLMs through MNN (the TG-4B
                // path is blocked upstream by alibaba/MNN#4463). Default
                // MNN_SEP_BUILD → separate libMNN.so / libllm.so /
                // libMNN_Express.so packaged into the APK. OpenCL backend
                // dropped after a Thor benchmark — see :mnn's CMakeLists.txt
                // for the numbers.
                arguments += "-DMNN_BUILD_LLM=ON"
                arguments += "-DMNN_LOW_MEMORY=ON"
                arguments += "-DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON"
                arguments += "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON"
                arguments += "-DMNN_ARM82=ON"

                // Route MNN_PRINT through Android logcat (under the app's UID)
                // instead of stdout — necessary for in-app log capture and for
                // surfacing MNN's own diagnostics in tombstones.
                arguments += "-DMNN_USE_LOGCAT=ON"

                // 16K page-size alignment for Android 15+ devices. Linker
                // pads .so segments so the dynamic loader can map them on
                // hosts where the OS uses 16 KB pages.
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            // Matches :llama. 3.22.1 ships with the Android SDK CMake;
            // a clean clone gets the build tool without a separate install.
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
