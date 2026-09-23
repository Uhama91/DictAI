// Modified from Phone Whisper by kafkasl for DictAI; see repository NOTICE.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val gemma4FineTunedPilot = providers.gradleProperty("gemma4FineTunedPilot").orNull == "true"
val gemma3RepairPilot = providers.gradleProperty("gemma3RepairPilot").orNull == "true"
require(!(gemma4FineTunedPilot && gemma3RepairPilot)) {
    "Gemma 3 repair and Gemma 4 pilots are mutually exclusive."
}
val localFormatPrototype = providers.gradleProperty("localFormatPrototype").orNull == "true" ||
    gemma4FineTunedPilot || gemma3RepairPilot

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    androidResources { noCompress += listOf("gguf", "litertlm") }
    if (gemma3RepairPilot) {
        sourceSets.getByName("main").assets.srcDir("src/gemma3RepairPilot/assets")
    }
    // Gemma is installed once from inside the app. The old bundled 350M is not packaged.
    if (localFormatPrototype && !gemma4FineTunedPilot && !gemma3RepairPilot) {
        packaging.jniLibs.excludes += setOf("**/libdictai_llm.so", "**/libdictai_llm_arm82.so")
    }

    defaultConfig {
        applicationId = "com.uhama.whisperpin"
        minSdk = 30
        targetSdk = 34
        buildConfigField("boolean", "LOCAL_FORMAT_PROTOTYPE", localFormatPrototype.toString())
        buildConfigField("boolean", "GEMMA4_FINE_TUNED_PILOT", gemma4FineTunedPilot.toString())
        buildConfigField("boolean", "GEMMA3_REPAIR_PILOT", gemma3RepairPilot.toString())
        versionCode = when {
            gemma3RepairPilot -> 41
            gemma4FineTunedPilot -> 39
            else -> 35
        }
        versionName = when {
            gemma3RepairPilot -> "0.9.12-dictai-gemma3-test"
            gemma4FineTunedPilot -> "0.9.10-dictai-latency-test"
            localFormatPrototype -> "0.9.6-dictai-gemma-test"
            else -> "0.9.6-dictai"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += "arm64-v8a" }

    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.ibm.icu:icu4j:78.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
