package com.telepix

import android.app.Application
import com.telepix.di.AppContainer
import com.telepix.di.DefaultAppContainer

/**
 * Application entrypoint that owns the manual dependency [AppContainer].
 */
class TelepixApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
