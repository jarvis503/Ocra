package com.orca.browser

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.orca.browser.adblocker.AdBlocker
import com.orca.browser.browser.MemoryWatchdog
import com.orca.browser.data.db.OrcaDatabase
import com.orca.browser.data.prefs.PrefsManager

class OrcaApp : Application() {

    val database: OrcaDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OrcaDatabase.getInstance(this)
    }

    lateinit var prefs: PrefsManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PrefsManager.getInstance(this)
        registerComponentCallbacks(MemoryWatchdog)

        Handler(Looper.getMainLooper()).post {
            AdBlocker.initialize(this)
        }
    }

    companion object {
        @Volatile
        lateinit var instance: OrcaApp
            private set
    }
}
