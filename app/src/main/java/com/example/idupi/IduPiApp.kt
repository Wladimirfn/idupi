package com.example.idupi

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
