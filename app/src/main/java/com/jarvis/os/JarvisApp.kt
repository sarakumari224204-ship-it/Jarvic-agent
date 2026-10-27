package com.jarvis.os

import android.app.Application
import com.jarvis.os.di.AppContainer

class JarvisApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
