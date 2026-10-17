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

## Building locally

Requires JDK 17 and the Android SDK (API 34): `gradle assembleDebug`.
The effect lives in `MonsterVoice.kt` and has JVM unit tests: `gradle testDebugUnitTest`.
