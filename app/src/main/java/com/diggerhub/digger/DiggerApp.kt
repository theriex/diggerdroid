package com.diggerhub.digger

import android.app.Application
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log

class DiggerApp : Application() {
    val digNCId = "DiggerSvcChan"
    val digNCName = "Digger Music Service"
    val digNCImp = NotificationManager.IMPORTANCE_LOW

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun createNotificationChannel() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var svcChannel = NotificationChannel(digNCId, digNCName, digNCImp)
            var svcMgr = getSystemService(NotificationManager::class.java)
            svcMgr.createNotificationChannel(svcChannel)
            Log.d("DiggerApp", "Created notification channel " + digNCId) }
    }

}
