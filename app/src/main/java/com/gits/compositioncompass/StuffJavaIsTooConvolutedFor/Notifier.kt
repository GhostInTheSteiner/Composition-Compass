package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat.getSystemService
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import com.gits.compositioncompass.R
import java.util.*

class Notifier(private val options: CompositionCompassOptions, private val activity: Activity) {

    private var id: String

    init {
        id = createNotificationChannel(UUID.randomUUID().toString())
    }
    
    fun post(e: Exception) {
        val mBuilder: NotificationCompat.Builder = NotificationCompat.Builder(activity!!, id)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("An exception occured ;(") // title
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(e.stackTraceToString()))
            .setContentText(
                e.message + System.lineSeparator() + System.lineSeparator() +
                        e.stackTraceToString()) // body message
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        NotificationManagerCompat.from(activity!!).notify(Random().nextInt(Int.MAX_VALUE), mBuilder.build())
    }

    private fun createNotificationChannel(id: String): String {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = options.appName
            val descriptionText = "Notifications for ${options.appName}"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(id, name, importance).apply {
                description = descriptionText
            }

            // Register the channel with the system
            val notificationManager: NotificationManager =
                activity!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        return id
    }
}