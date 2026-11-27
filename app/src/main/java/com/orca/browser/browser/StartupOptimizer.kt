package com.orca.browser.browser

import android.app.Activity
import com.orca.browser.adblocker.AdBlocker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object StartupOptimizer {

    fun deferredInit(activity: Activity, onComplete: () -> Unit) {
        activity.window.decorView.post {
            CoroutineScope(Dispatchers.IO).launch {
                AdBlocker.initialize(activity.applicationContext)
            }
            onComplete()
        }
    }
}
