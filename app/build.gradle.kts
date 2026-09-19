// Modified from Phone Whisper by kafkasl for DictAI; see repository NOTICE.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localFormatPrototype = providers.gradleProperty("localFormatPrototype").orNull == "true"
val gemma270Pilot = providers.gradleProperty("gemma270Pilot").orNull == "true"
val effectiveLocalFormatPrototype = localFormatPrototype || gemma270Pilot

android {
    namespace = "com.kafkasl.phonewhisper"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    androidResources { noCompress += listOf("gguf", "litertlm") }
    // Gemma is installed once from inside the app. The old bundled 350M is not packaged.
    // The pilot needs the llama.cpp JNI libraries; only the old E2B prototype
    // excludes them because it does not use the CPU runtime.
    if (localFormatPrototype && !gemma270Pilot) {
        packaging.jniLibs.excludes += setOf("**/libdictai_llm.so", "**/libdictai_llm_arm82.so")
    }

    // The pilot asset directory is opt-in so a normal APK never packages the GGUF.
    if (gemma270Pilot) sourceSets.getByName("main").assets.srcDir("src/gemma270Pilot/assets")

    defaultConfig {
        applicationId = "com.uhama.whisperpin"
        minSdk = 30
        targetSdk = 34
        buildConfigField("boolean", "LOCAL_FORMAT_PROTOTYPE", effectiveLocalFormatPrototype.toString())
        buildConfigField("boolean", "GEMMA270_PILOT", gemma270Pilot.toString())
        versionCode = if (gemma270Pilot) 36 else 35
        versionName = when {
            gemma270Pilot -> "0.9.7-gemma270-v3-test"
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
