package com.kafkasl.phonewhisper

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class DictAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeModeController.apply(this)
    }
}
