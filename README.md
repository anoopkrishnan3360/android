# FreeUp (Android starter)
Copies old photos/videos to a drive folder, verifies size + SHA-256, then deletes originals via the system dialog.
No INTERNET permission, no accounts, no analytics.

## Build
1. Install Android Studio (latest). File > Open > this folder. Accept any Gradle/AGP upgrade prompts.
2. Change applicationId in app/build.gradle.kts to your own (e.g. com.yourname.freeup).
3. Add an icon: right-click app > New > Image Asset, then add android:icon in the manifest.
4. Test on a real phone with a USB drive/SD card.
5. Build > Generate Signed App Bundle (AAB). Create a keystore and BACK IT UP; enrol in Play App Signing.
6. Upload the .aab in Play Console (closed testing first).

## Not done yet
Google Play Billing (isPremium stub), foreground service for long copies, laptop Wi-Fi transfer,
app lock, resume after interruption, tests. Use the prompt pack in the guide to add these.
Play Console needs: privacy policy URL, Data Safety form, photo/video permission declaration.

## Free cloud build + test (no Android Studio needed to compile)
1. Create a free GitHub account and a PRIVATE repo; upload this folder (keep .github/).
2. Every push runs unit tests + lint + builds a debug APK (Actions tab > artifact "debug-apk").
3. Install the APK on your phone to test. Or run Android Studio's free emulator.
4. For the Play bundle: add repo secrets KEYSTORE_B64 (base64 of your .jks), KEYSTORE_PASSWORD,
   KEY_ALIAS, KEY_PASSWORD, then push a tag like v1.0.0 -> artifact "release-aab" is your signed .aab.
   Generate the keystore with: keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias freeup
   Back it up offline; losing it blocks updates (use Play App Signing).
5. Optional device testing: Firebase Test Lab (free Spark quota is small) runs Robo tests on real/virtual devices.

## Added in this version
Foreground-service copy with Cancel + notification, resume (journal of verified files), Play Billing one-time
"premium_lifetime" unlock + restore, saved destination folder, privacy policy + Play Console draft answers.
Billing is client-side only (no server receipt validation). Not yet built or tested on a device: expect first-build fixes.
Still recommended: Photo Picker fallback, Android 14 partial-access handling, laptop Wi-Fi transfer, app lock, instrumented tests.
