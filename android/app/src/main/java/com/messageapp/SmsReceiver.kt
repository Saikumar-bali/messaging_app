package com.messageapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.facebook.react.ReactApplication
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class SmsReceiver : BroadcastReceiver() {
    private val TAG = "SmsReceiver"
    private val CHANNEL_ID = "sms_notification_channel"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val prefs = context.getSharedPreferences("SmsBlockPrefs", Context.MODE_PRIVATE)
        val isBlockingEnabled = prefs.getBoolean("blockingEnabled", false)
        val otpOnly = prefs.getBoolean("otpOnly", false)
        val forwardUrl = prefs.getString("forwardUrl", "")

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (sms in messages) {
            val body = sms.displayMessageBody
            val address = sms.displayOriginatingAddress

            Log.d(TAG, "Received SMS from $address: $body")

            val isOtp = isOtpMessage(body)
            
            var shouldBlock = false
            if (isBlockingEnabled) {
                if (otpOnly) {
                    if (isOtp) shouldBlock = true
                } else {
                    shouldBlock = true
                }
            }

            if (shouldBlock) {
                Log.d(TAG, "Blocking message from $address")
                if (!forwardUrl.isNullOrEmpty()) {
                    forwardSms(forwardUrl, address, body)
                }
                deleteSms(context, address, body)
            } else {
                Log.d(TAG, "Saving message and showing notification")
                saveToInbox(context, address, body, sms.timestampMillis)
                showNotification(context, address ?: "Unknown", body ?: "")
                notifyReactNative(context)
            }
        }
    }

    private fun isOtpMessage(body: String): Boolean {
        val keywords = listOf("otp", "code", "verification", "verify", "password", "secret")
        return keywords.any { body.contains(it, ignoreCase = true) }
    }

    private fun saveToInbox(context: Context, address: String?, body: String?, timestamp: Long) {
        try {
            val values = ContentValues()
            values.put("address", address)
            values.put("body", body)
            values.put("date", timestamp)
            values.put("read", 0)
            values.put("type", 1)
            context.contentResolver.insert(Uri.parse("content://sms/inbox"), values)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving SMS: ${e.message}")
        }
    }

    private fun showNotification(context: Context, address: String, body: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Inbound SMS", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(address)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun notifyReactNative(context: Context) {
        try {
            val reactApplication = context.applicationContext as ReactApplication
            val reactContext = reactApplication.reactNativeHost.reactInstanceManager.currentReactContext
            
            if (reactContext != null && reactContext.hasActiveCatalystInstance()) {
                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onNewMessage", null)
                Log.d(TAG, "Emitted onNewMessage event to React Native")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error notifying React Native: ${e.message}")
        }
    }

    private fun deleteSms(context: Context, address: String?, body: String?) {
        thread {
            try {
                Thread.sleep(1000)
                val uri = Uri.parse("content://sms/")
                val contentResolver = context.contentResolver
                val cursor = contentResolver.query(uri, null, "address=? AND body=?", arrayOf(address, body), "date DESC")
                if (cursor != null && cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"))
                    contentResolver.delete(Uri.parse("content://sms/$id"), null, null)
                }
                cursor?.close()
                notifyReactNative(context) // Refresh UI even after deletion if app is open
            } catch (e: Exception) {
                Log.e(TAG, "Error in deleteSms: ${e.message}")
            }
        }
    }

    private fun forwardSms(urlStr: String, address: String?, body: String?) {
        thread {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val json = "{\"address\":\"$address\", \"body\":\"$body\"}"
                conn.outputStream.use { it.write(json.toByteArray()) }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding: ${e.message}")
            }
        }
    }
}
