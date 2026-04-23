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
        versionCode = 5
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEEPL_API_KEY",
            "\"${localProps.getProperty("deepl.api.key", "")}\"")
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
}
