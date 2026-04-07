package com.messageapp

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap

class SmsBlockModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "SmsBlockModule"
    }

    private val prefs = reactContext.getSharedPreferences("SmsBlockPrefs", Context.MODE_PRIVATE)

    @ReactMethod
    fun isDefaultSmsApp(promise: Promise) {
        val packageName = reactApplicationContext.packageName
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(reactApplicationContext)
        promise.resolve(packageName == defaultSmsPackage)
    }

    @ReactMethod
    fun requestDefaultSmsApp(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = reactApplicationContext.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                        promise.resolve(true)
                        return
                    }
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    reactApplicationContext.currentActivity?.startActivityForResult(intent, 123)
                    promise.resolve(true)
                } else {
                    promise.reject("ROLE_SMS_NOT_AVAILABLE", "Role SMS is not available")
                }
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, reactApplicationContext.packageName)
                reactApplicationContext.currentActivity?.startActivity(intent)
                promise.resolve(true)
            }
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun setBlockingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("blockingEnabled", enabled).apply()
    }

    @ReactMethod
    fun setOtpOnly(enabled: Boolean) {
        prefs.edit().putBoolean("otpOnly", enabled).apply()
    }

    @ReactMethod
    fun setForwardUrl(url: String) {
        prefs.edit().putString("forwardUrl", url).apply()
    }

    @ReactMethod
    fun getSettings(promise: Promise) {
        val map = WritableNativeMap()
        map.putBoolean("blockingEnabled", prefs.getBoolean("blockingEnabled", false))
        map.putBoolean("otpOnly", prefs.getBoolean("otpOnly", false))
        map.putString("forwardUrl", prefs.getString("forwardUrl", ""))
        promise.resolve(map)
    }

    @ReactMethod
    fun getMessages(promise: Promise) {
        val messages = com.facebook.react.bridge.WritableNativeArray()
        try {
            // Querying 'content://sms/conversations' is more efficient for grouping
            val uri = android.net.Uri.parse("content://sms/")
            // We use a subquery/grouping trick to get the latest message per address
            val cursor = reactApplicationContext.contentResolver.query(
                uri, 
                arrayOf("address", "body", "date", "_id", "thread_id"), 
                "address IS NOT NULL) GROUP BY (address", 
                null, 
                "date DESC"
            )
            
            if (cursor != null) {
                val indexAddress = cursor.getColumnIndex("address")
                val indexBody = cursor.getColumnIndex("body")
                val indexDate = cursor.getColumnIndex("date")
                val indexId = cursor.getColumnIndex("_id")
                val indexThreadId = cursor.getColumnIndex("thread_id")

                while (cursor.moveToNext()) {
                    val map = WritableNativeMap()
                    map.putString("id", cursor.getString(indexId))
                    map.putString("threadId", cursor.getString(indexThreadId))
                    map.putString("address", cursor.getString(indexAddress))
                    map.putString("body", cursor.getString(indexBody))
                    map.putDouble("date", cursor.getDouble(indexDate))
                    messages.pushMap(map)
                }
                cursor.close()
            }
            promise.resolve(messages)
        } catch (e: Exception) {
            // Fallback for some Android versions that don't allow GROUP BY in query
            fetchMessagesFallback(promise)
        }
    }

    private fun fetchMessagesFallback(promise: Promise) {
        val messages = com.facebook.react.bridge.WritableNativeArray()
        val seenAddresses = mutableSetOf<String>()
        try {
            val uri = android.net.Uri.parse("content://sms/")
            val cursor = reactApplicationContext.contentResolver.query(uri, null, null, null, "date DESC")
            if (cursor != null) {
                val indexAddress = cursor.getColumnIndex("address")
                val indexBody = cursor.getColumnIndex("body")
                val indexDate = cursor.getColumnIndex("date")
                val indexId = cursor.getColumnIndex("_id")
                
                while (cursor.moveToNext()) {
                    val address = cursor.getString(indexAddress) ?: "Unknown"
                    if (!seenAddresses.contains(address)) {
                        val map = WritableNativeMap()
                        map.putString("id", cursor.getString(indexId))
                        map.putString("address", address)
                        map.putString("body", cursor.getString(indexBody))
                        map.putDouble("date", cursor.getDouble(indexDate))
                        messages.pushMap(map)
                        seenAddresses.add(address)
                    }
                }
                cursor.close()
            }
            promise.resolve(messages)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun getChatHistory(address: String, promise: Promise) {
        val history = com.facebook.react.bridge.WritableNativeArray()
        try {
            val uri = android.net.Uri.parse("content://sms/")
            val cursor = reactApplicationContext.contentResolver.query(
                uri, 
                null, 
                "address=?", 
                arrayOf(address), 
                "date ASC"
            )
            
            if (cursor != null) {
                val indexBody = cursor.getColumnIndex("body")
                val indexDate = cursor.getColumnIndex("date")
                val indexType = cursor.getColumnIndex("type") // 1 = Inbox, 2 = Sent

                while (cursor.moveToNext()) {
                    val map = WritableNativeMap()
                    map.putString("body", cursor.getString(indexBody))
                    map.putDouble("date", cursor.getDouble(indexDate))
                    map.putInt("type", cursor.getInt(indexType))
                    history.pushMap(map)
                }
                cursor.close()
            }
            promise.resolve(history)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun sendSms(address: String, message: String, promise: Promise) {
        try {
            val smsManager: android.telephony.SmsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                reactApplicationContext.getSystemService(android.telephony.SmsManager::class.java)
            } else {
                android.telephony.SmsManager.getDefault()
            }
            
            smsManager.sendTextMessage(address, null, message, null, null)
            
            // Also save the sent message to the system "Sent" folder
            val values = android.content.ContentValues()
            values.put("address", address)
            values.put("body", message)
            values.put("date", System.currentTimeMillis())
            values.put("type", 2) // 2 = Sent
            reactApplicationContext.contentResolver.insert(android.net.Uri.parse("content://sms/sent"), values)
            
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR_SEND_SMS", e.message)
        }
    }
}
