package com.th3cavalry.androidllm

import android.app.Application
import com.google.gson.Gson

class App : Application() {

    companion object {
        lateinit var instance: App
            private set

        val gson: Gson by lazy { Gson() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
