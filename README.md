# TiltGuard: tilt-based privacy screen for Android

TiltGuard draws a black layer over your whole screen. The layer fades in when you
tilt the phone away from the angle you normally hold it at. It uses the
gyroscope-fused rotation sensor and falls back to the accelerometer on phones
with no gyro.

## Using it
1. Open TiltGuard and hold the phone the way you normally read it.
2. Tap the big **OFF** button. The first time, Android asks you to allow
   "Display over other apps". Turn that on, go back, and tap again.
3. The button turns green (**ON**). Tilt the phone away (sideways, forward or back)
   and the screen goes dark. Tilt it back and the screen clears.
4. **Allowed tilt** slider: move left for stronger privacy, right for more freedom.
5. **Reset viewing angle**: tap this when you change position, for example from
   sitting to lying down. You can also do it from the notification.
6. Quick toggle: swipe down → edit tiles → add the **Privacy Screen** tile.

Touches still pass through to your apps while the screen is clear. When the
screen is almost fully dark, Android blocks touches. That only happens while the
phone is tilted away from you.

## Building the APK

### Option A: GitHub (nothing to install)
1. Create a new GitHub repo and upload this whole folder, including `.github/`.
2. Open the **Actions** tab. The "Build APK" workflow runs on its own (about 3 minutes).
3. Open the finished run and download **TiltGuard-apk** under *Artifacts*.
   Unzip it to get `TiltGuard.apk`.

### Option B: Android Studio
Open this folder → *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.

### Installing
Copy the APK to your phone, open it, and allow "Install unknown apps" if Android
asks. The release build uses the debug signing key. Replace it with your own
keystore before uploading to the Play Store.
