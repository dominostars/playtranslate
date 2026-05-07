import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localPropsFile.inputStream().use { localProps.load(it) }

android {
    namespace = "com.playtranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.playtranslate"
        minSdk = 30
        targetSdk = 34
        versionCode = 6
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEEPL_API_KEY",
            "\"${localProps.getProperty("deepl.api.key", "")}\"")

        // TranslateGemma toggle is feature-flagged off by default. Phase F
        // validation on Thor (2026-05-07) found latency is 8.7s median /
        // 12.8s p90 — 14× over the plan's interactive bar of 600 ms / 1.5 s.
        // Quality is excellent (0.2% catastrophic, on par with ML Kit) but
        // the speed makes interactive tap-and-wait UX unviable.
        // See memory/project_translategemma_spike.md "Phase F validation"
        // section. Flag stays false until a faster path lands (OpenCL Q4_0 or
        // smaller model). The backend itself stays registered — `isUsable`
        // returns false without a model file, so the waterfall is unchanged.
        buildConfigField("boolean", "TRANSLATEGEMMA_ENABLED", "false")
    }

    signingConfigs {
        getByName("debug") {
            // Uses default debug keystore at ~/.android/debug.keystore
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        // TranslateGemma's GGML_BACKEND_DL=ON pattern needs to dlopen
        // libggml-cpu-android_*.so files at runtime via opendir() on
        // nativeLibraryDir. Modern Android packaging keeps .so files inside
        // the APK (mmap-loaded by System.loadLibrary) which is great for size
        // but means nativeLibraryDir is empty on disk. Forcing legacy packaging
        // extracts them to /data/app/.../lib/arm64/ so the dynamic backend
        // loader can find them. This is required by ggml's backend-DL design.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/CONTRIBUTORS.md"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            // Kuromoji IPADIC binary dictionary. Now shipped inside the JA
            // source pack (see scripts/build_jmdict.py --kuromoji-jar); the
            // runtime path is PackAwareKuromojiBuilder reading from the
            // installed pack dir. Dropping these ~33 MB from every APK.
            excludes += "com/atilika/kuromoji/ipadic/*.bin"
            // KOMORAN model. Now shipped inside the KO source pack (see
            // scripts/build_latin_dict.py --komoran-jar); KoreanEngine
            // constructs Komoran(String modelPath) pointed at the
            // installed pack dir. Strip both LIGHT (~1.75 MB, what we
            // ship in the pack) and FULL (~4.2 MB, unused) models.
            excludes += "models_light/**"
            excludes += "models_full/**"
            // HanLP portable data/. Now shipped inside the ZH source
            // pack (see scripts/build_zh_dict.py --hanlp-jar);
            // ChineseEngine installs a PackAwareHanlpAdapter that
            // redirects HanLP's file reads to the pack's tokenizer/
            // dir. Strips ~23 MB of HanLP classpath resources.
            excludes += "data/dictionary/**"
            excludes += "data/model/**"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

// Provisions the JDK via Gradle's toolchain API so the build doesn't depend on
// whichever JDK the user has on PATH. Combined with the foojay-resolver plugin
// in settings.gradle.kts and `auto-download=true` in gradle.properties, Gradle
// fetches and caches a JDK 17 under ~/.gradle/jdks if one is not installed.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":llama"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // ML Kit
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition)          // Latin base SDK (Phase 3)
    implementation(libs.mlkit.text.recognition.chinese)   // Chinese OCR (Phase 4)
    implementation(libs.mlkit.text.recognition.korean)    // Korean OCR
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Japanese morphological analysis
    implementation(libs.kuromoji.ipadic)

    // Lucene Snowball stemmer (Phase 3: Latin/English stemming)
    implementation(libs.lucene.analyzers.common)

    // KOMORAN (Korean morphological analyzer — TRIE + statistical OOV).
    // Used instead of Lucene Nori because Nori's AttributeFactory touches
    // java.lang.ClassValue, which Android ART does not ship. KOMORAN is
    // pure Java and Android-compatible.
    implementation(libs.komoran)

    // HanLP CRF segmenter (Phase 4: Chinese word segmentation)
    implementation(libs.hanlp)

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")

    // On-device instrumented tests (OCR golden-set evaluation)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
