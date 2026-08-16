package com.fable.liteplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FableLiteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
