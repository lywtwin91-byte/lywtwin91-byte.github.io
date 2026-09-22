# SilentGuard

Background Android app that forces the device into total silence on
**non-Samsung / plain AOSP** hardware, where the Samsung `CSC-feature`
mute trick doesn't exist.

## How it works

Samsung's CSC-feature approach patches a Samsung-only system config. On
generic AOSP there's no equivalent hook, so this app reproduces the effect
the "existing app" you saw was using, with three layers stacked together:

1. **Exclusive call-style audio focus** (`SilentModeService.kt`)
   It opens an `AudioTrack` that continuously streams silent PCM, tagged
   with `AudioAttributes.USAGE_VOICE_COMMUNICATION` (the same usage a real
   phone call uses) and requests
   `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)`. Any
   well-behaved app (music, video, games, browsers) that listens for audio
   focus gets ducked or paused by the framework for as long as this focus
   is held — exactly like it would during an incoming call. This is the
   mechanism that made the app you saw work "like a call playing silence."

2. **Stream-volume zeroing** (`AudioMuteManager.kt`)
   Every adjustable stream (`MUSIC`, `RING`, `ALARM`, `NOTIFICATION`,
   `SYSTEM`, `DTMF`, `VOICE_CALL`) is set to its minimum volume. This
   catches apps/system sounds that don't participate in the audio-focus
   system at all.

3. **Do Not Disturb** (optional, needs the user to grant Notification
   Policy Access once): ringer mode forced to `RINGER_MODE_SILENT` and
   `setInterruptionFilter(INTERRUPTION_FILTER_NONE)`. This is what
   silences things focus/volume can't reach, like some notification
   sounds and vibration policy.

All three are re-applied every 3 seconds and on every
`RINGER_MODE_CHANGED` / `VOLUME_CHANGED` broadcast, so anything that tries
to raise volume or steal focus back gets immediately reverted — the same
"fight to stay muted" behavior the app you tested exhibited.

**Real calls are respected.** Before each re-apply, the service checks
`TelephonyManager.callState`; if a real call is active it skips muting and
audio-focus reclaiming so it never blocks or garbles an actual phone call.

## What this *can't* silence

- Camera shutter sound in regions where Android enforces it by law
  (Korea/Japan) — this is a hardware/region policy baked below the app
  layer; no public API disables it, `CSC-feature` or not.
- Apps using low-level audio (raw ALSA/HAL access) that ignore both
  `AudioManager` and audio focus entirely — very rare outside of system
  apps.

## Permissions the user must grant manually

- **Do Not Disturb access** — `MainActivity` has a button that opens
  `Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`, since this is a
  "special app access" toggle, not a manifest permission.
- **Ignore battery optimizations** — a button opens
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so the OS doesn't kill the
  foreground service to save power.
- **Notifications** (Android 13+) — requested at first launch, required to
  show the foreground-service notification.

## Building

This project was authored and reviewed for correctness in a sandbox
**without Android SDK / network access to Google's Maven repository**, so
it has not been compiled here. To build it:

```
# from android/SilentGuard
# open the folder in Android Studio (it will generate the Gradle
# wrapper and sync automatically), or, with the Android SDK + network
# access to Google's Maven repo available locally:
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

## Deployment note

Because this relies on mimicking call-priority audio focus, it is the
kind of behavior Play Store review can reject. For a fleet of AOSP
devices, sideload the APK directly or push it as a system/privileged app
via your device provisioning (AOSP `device_owner` / MDM enrollment)
instead of publishing it.
