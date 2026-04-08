package com.messageapp

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.provider.ContactsContract
import android.net.Uri
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.bridge.WritableNativeArray

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

    private fun getContactName(phoneNumber: String?): String? {
        if (phoneNumber == null) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
        var contactName: String? = null
        try {
            val cursor = reactApplicationContext.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    contactName = cursor.getString(0)
                }
                cursor.close()
            }
        } catch (e: Exception) {
            // Ignore contact resolution errors
        }
        return contactName
    }

    @ReactMethod
    fun getMessages(promise: Promise) {
        fetchMessagesFallback(promise) // Use fallback as primary for better grouping and unread status
    }

    private fun fetchMessagesFallback(promise: Promise) {
        val messages = WritableNativeArray()
        val seenAddresses = mutableSetOf<String>()
        try {
            val uri = Uri.parse("content://sms/")
            // We fetch all messages and pick the first one (latest) for each address
            val cursor = reactApplicationContext.contentResolver.query(uri, null, null, null, "date DESC")
            if (cursor != null) {
                val indexAddress = cursor.getColumnIndex("address")
                val indexBody = cursor.getColumnIndex("body")
                val indexDate = cursor.getColumnIndex("date")
                val indexId = cursor.getColumnIndex("_id")
                val indexRead = cursor.getColumnIndex("read")
                val indexThreadId = cursor.getColumnIndex("thread_id")
                
                while (cursor.moveToNext()) {
                    val address = cursor.getString(indexAddress) ?: "Unknown"
                    if (!seenAddresses.contains(address)) {
                        val map = WritableNativeMap()
                        map.putString("id", cursor.getString(indexId))
                        map.putString("threadId", cursor.getString(indexThreadId))
                        map.putString("address", address)
                        map.putString("body", cursor.getString(indexBody))
                        map.putDouble("date", cursor.getDouble(indexDate))
                        map.putInt("read", cursor.getInt(indexRead))
                        map.putString("contactName", getContactName(address))
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
        val history = WritableNativeArray()
        try {
            val uri = Uri.parse("content://sms/")
            val cursor = reactApplicationContext.contentResolver.query(
                uri, 
                null, 
                "address=?", 
                arrayOf(address), 
                "date ASC"
            )
            
            if (cursor != null) {
                val indexId = cursor.getColumnIndex("_id")
                val indexBody = cursor.getColumnIndex("body")
                val indexDate = cursor.getColumnIndex("date")
                val indexType = cursor.getColumnIndex("type") // 1 = Inbox, 2 = Sent

                while (cursor.moveToNext()) {
                    val map = WritableNativeMap()
                    map.putString("id", cursor.getString(indexId))
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
            values.put("read", 1)
            reactApplicationContext.contentResolver.insert(Uri.parse("content://sms/sent"), values)
            
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR_SEND_SMS", e.message)
        }
    }

    @ReactMethod
    fun deleteConversation(address: String, promise: Promise) {
        try {
            val uri = Uri.parse("content://sms/")
            val deletedRows = reactApplicationContext.contentResolver.delete(
                uri, 
                "address=?", 
                arrayOf(address)
            )
            promise.resolve(deletedRows > 0)
        } catch (e: Exception) {
            promise.reject("DELETE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun deleteMessage(id: String, promise: Promise) {
        try {
            val uri = Uri.parse("content://sms/")
            val deletedRows = reactApplicationContext.contentResolver.delete(
                uri, 
                "_id=?", 
                arrayOf(id)
            )
            promise.resolve(deletedRows > 0)
        } catch (e: Exception) {
            promise.reject("DELETE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun markAsRead(address: String, promise: Promise) {
        try {
            val values = android.content.ContentValues()
            values.put("read", 1)
            val uri = Uri.parse("content://sms/inbox")
            reactApplicationContext.contentResolver.update(
                uri, 
                values, 
                "address=? AND read=0", 
                arrayOf(address)
            )
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("READ_ERROR", e.message)
        }
    }
}
