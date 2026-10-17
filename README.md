# Monster Voice

A live voice changer for Android that turns your voice into a deep monster voice
as you talk. Made for Halloween 2026.

## Using it

1. Plug in headphones or a small speaker (wired or Bluetooth). If the phone's own
   speaker plays the voice, the microphone hears it and it squeals.
2. Open **Monster Voice** and press **START**. Allow the microphone the first time.
3. Pick a monster (Grumbly, Beast, Giant, Demon) or use the sliders:
   - **Deepness**: how far down the voice goes (up to one octave).
   - **Growl**: rumble and grit.
   - **Volume**: up to 200%.
4. The voice keeps running with the screen off. Stop it from the app or the notification.

## Installing on the phone

Every push to `main` builds the app on GitHub Actions and attaches `MonsterVoice.apk`
to the **latest** release.

1. On the phone, sign in to GitHub in the browser, open this repo's **Releases**, and
   download `MonsterVoice.apk` from the **latest** release.
2. Tap the downloaded file. Android asks to allow installs from the browser the first
   time; allow it, then tap **Install**.

New builds install over the old one, because they're all signed with the same committed
debug key (`app/debug.keystore`).

## Testing on a Linux PC

1. Install Android Studio (`sudo snap install android-studio --classic`, or the tarball
   from developer.android.com/studio) and run it once so it installs the Android SDK.
2. **File > Open** this folder and wait for the Gradle sync to finish.
3. Pick a device and press **Run**:
   - **A real phone (best):** on the phone, open Settings > About phone and tap
     *Build number* 7 times. Then turn on Developer options > *USB debugging*,
     plug it in over USB, and accept the prompt.
   - **Emulator:** Device Manager > create a phone (e.g. Pixel 7, API 34). Once it's
     running, open the emulator's *...* menu > Microphone and turn on
     *Virtual microphone uses host audio input*. Wear headphones on the PC, and expect
     more delay than on a real phone.

From a terminal instead: `./gradlew testDebugUnitTest` runs the voice effect tests, and
`./gradlew installDebug` builds and installs onto a plugged-in phone (needs `ANDROID_HOME`
set to the SDK, usually `~/Android/Sdk`).
