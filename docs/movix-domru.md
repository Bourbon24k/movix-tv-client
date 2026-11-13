# Movix / Dom.ru setup and recovery

This guide is for an Android TV or Google TV device that already has a valid Movix/Dom.ru service. It uses ADB for installation and diagnostics. It does not root the device, remove system packages, bypass DRM, or make a channel available without operator entitlement.

## Before you start

- Confirm that the device is Android TV/Google TV and that network debugging works.
- Record the model, Android version, firmware build, and the current Home package.
- Keep the stock launcher enabled until this APK has opened successfully as Home.
- Use a device-specific package list if you later debloat the box. Do not copy a list from another model.
- Never use `pm uninstall` for the stock launcher or input/remote services. A recovery path must remain available.

## Build and install

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
./build.sh

TV='<tv-ip>:5555'
adb connect "$TV"
adb -s "$TV" shell getprop ro.build.version.sdk
adb -s "$TV" install -r build/tvos-home.apk
adb -s "$TV" shell am start -n com.techgenius.tvosmovix/.MainActivity
adb -s "$TV" shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
```

The expected Home activity is `com.techgenius.tvosmovix/.MainActivity`. Confirm it on the real TV with the physical remote before changing the stock launcher state.

## Movix session

The player obtains a device token for the operator API. If an authenticated session is supplied through the app's setup flow, it is stored in the app's private data. Do not commit session files, tokens, device IDs, screenshots containing account information, or ADB keys.

The player supports live HLS, official catch-up URLs, EPG, archive seeking and audio/subtitle tracks. A channel can still fail because the account lacks entitlement, the programme has expired, the operator changed the catalogue, or the stream is unavailable.

## Resume behavior

Playback saves the current programme and position in private preferences. On the next player launch, when a valid saved position is available, the video is paused and a choice is shown:

- **Continue from last position** opens the official archive at the saved position.
- **Return to live TV** discards neither the saved record nor the account; it starts the current live stream.

This choice is deliberately made before playback continues, so a user does not lose part of the live stream while deciding.

## Recovery

If Home does not return after an experiment, use ADB while the device is reachable:

```sh
adb -s "$TV" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s "$TV" shell pm enable '<stock-home-package>'
adb -s "$TV" shell pm set-home-activity '<stock-home-package>/<stock-home-activity>'
```

The stock package and activity are device-specific. Obtain them from a fresh `dumpsys` result; do not guess. If the device is no longer reachable, stop and use the manufacturer's remote recovery path rather than repeatedly disabling packages.

## Evidence boundaries

A successful APK install proves only that Android accepted the package. A focused Home activity proves only launcher routing. Live playback, archive playback, EPG, mobile network behavior, DRM formats, and every channel require separate tests on the target device and account.
