# SMS Interceptor & Blocker (React Native)

This app allows you to intercept, forward, and instantly delete incoming SMS messages. It works by setting the app as the **Default SMS Application**, which grants it the necessary permissions on Android 10+.

## Features
- **Instant Deletion**: Incoming messages are deleted from the device before notifications appear.
- **OTP Filtering**: Option to only block messages containing security keywords (OTP, code, etc.).
- **API Forwarding**: Forward message content to a custom API endpoint before deletion.
- **Modern Android Support**: Compatible with Android 10, 11, 12, 13, and 14.

## Setup Instructions

### 1. Prerequisites
- React Native development environment.
- Android device or emulator (Android 10+ recommended).

### 2. Installation
```bash
npm install
npm install @react-navigation/native @react-navigation/stack react-native-screens react-native-safe-area-context react-native-gesture-handler @react-native-masked-view/masked-view
```

### 3. Build and Run
```bash
npx react-native run-android
```

### 4. Critical Configuration (MUST DO)
For the app to work correctly, you must perform these steps:

#### A. Set as Default SMS App
1. Open the app.
2. Tap **"Set as Default SMS App"**.
3. Select this app in the system dialog and confirm.
*If this fails, go to: Settings > Apps > Default Apps > SMS App.*

#### B. Disable RCS Chat
Modern "Chat features" (RCS) bypass standard SMS broadcasts. You must disable them:
1. Open the original **Google Messages** app.
2. Tap your profile icon > **Messages settings**.
3. Tap **RCS chats**.
4. Turn **OFF** "Turn on RCS chats".

#### C. Grant Permissions
- Tap **"Request Permissions"** in the app to ensure `RECEIVE_SMS` and `READ_SMS` are granted.

## How it Works
1. When an SMS arrives, the system sends an `SMS_DELIVER` broadcast to the **default** SMS app.
2. `SmsReceiver.kt` intercepts this broadcast.
3. It checks the app settings (stored in `SharedPreferences`).
4. If blocking is enabled:
   - It checks if the message is an OTP (if "OTP Only" is enabled).
   - It forwards the data to your configured URL (if any).
   - It queries the `content://sms/` provider and deletes the message by ID.

## Testing
1. Enable **"Enable Blocking"** in the app.
2. Send an SMS to the device from another phone.
3. Monitor **Logcat** in Android Studio (filter: `SmsReceiver`) to see the interception process.

## Limitations
- **OTP Autofill**: The system's autofill service might briefly see the OTP before the app deletes it.
- **Battery Optimization**: If the app is killed by the system, forwarding might fail. Disable battery optimization for this app in system settings for 100% reliability.
