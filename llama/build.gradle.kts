plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 34

    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 30

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // arm64-v8a only — minSdk 30 = 64-bit-only world (Play Store + most Android 11+ devices).
            // Saves ~5–8 MB per APK split vs. shipping armeabi-v7a too.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                // Don't compile for the host CPU's specific feature set — we want a portable
                // arm64-v8a binary. The dynamic backend loader picks the best ggml-cpu-XXX.so
                // at runtime based on the device's actual feature flags.
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"

                // OpenCL backend for Adreno GPU acceleration (Snapdragon 8 Gen 2/3/Elite).
                // Requires Khronos OpenCL headers + libOpenCL.so in the NDK sysroot — see
                // docs/backend/OPENCL.md for the one-time setup. The backend produces an
                // additional libggml-opencl.so that GGML_BACKEND_DL loads at runtime
                // alongside the CPU variants. Falls through to CPU on non-Adreno hardware.
                arguments += "-DGGML_OPENCL=ON"
                arguments += "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON"

                // LLaMaFile (mmap-tricks for x86) is irrelevant on Android arm64.
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
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
