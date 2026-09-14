pluginManagement {
    buildscript {
        repositories { google(); mavenCentral() }
        // LiteRT-LM 0.17 uses Kotlin 2.4. Override AGP's older bundled D8/R8.
        dependencies { classpath("com.android.tools:r8:9.1.43") }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Modified from Phone Whisper by kafkasl for DictAI; see repository NOTICE.

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DictAI"
include(":app")
