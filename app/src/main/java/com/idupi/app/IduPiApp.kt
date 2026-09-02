package com.idupi.app

import android.app.Application

class IduPiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: IduPiApp
            private set
    }
}
