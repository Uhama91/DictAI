package com.kafkasl.phonewhisper

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class DictAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
