# Ultrasonic for Pebble

A Pebble watchapp that controls **Ultrasonic** (`org.moire.ultrasonic`, the open-source
Subsonic/Navidrome client for Android) from the wrist, plus the small Android companion it
needs.

Targets the current Core Devices hardware:

| Watch | SDK platform | Screen |
|---|---|---|
| Pebble 2 Duo | `flint` | 144 x 168, black and white, 64 KB app RAM |
| Pebble Time 2 | `emery` | 200 x 228, 64-colour, 128 KB app RAM |

Platform facts are from the SDK's own table (`pebble_sdk_platform.py`, SDK 4.33.1).

## What it does

**Now playing** (the app's home screen): title, artist, album, a progress bar ticking
locally between updates, and flags for loved / shuffle / repeat.

| Button | Click | Hold |
|---|---|---|
| Up | Previous | Volume up |
| Select | Play / pause | Actions menu |
| Down | Next | Volume down |

**Actions menu:** Browse library, Love / Unlove (stars the track on the Navidrome server
through Ultrasonic), Shuffle queue, Repeat (cycles None / All / One).

**Browse:** Ultrasonic's own library tree (Media Library, Artists, Albums, Playlists and
their children). Select opens a folder or album; select on a track, or on *Play All*, plays
it; hold select on an album plays the whole album. Up to 40 rows per list and 6 levels deep;
a longer list's title gets a "(40+)" suffix.

**Not yet verified on hardware:** starting playback of a browsed item. It replaces
Ultrasonic's play queue, so it was not run on a phone in daily use; the watch side of it is
verified in the emulators. See `BUILD-NOTES.md`.

## How it works

```
Pebble (flint/emery)                Phone
 watchapp (C) <--BLE--> Core Devices Pebble app <--PebbleKit 2--> companion APK
                                                                    |  media3 MediaBrowser
                                                                    v
                                                         Ultrasonic PlaybackService
```

The companion drives **Ultrasonic's own media session**. It holds no Navidrome server
address and no credentials: Ultrasonic keeps authenticating, keeps the queue, and stars
tracks the way its own notification does. There is no PebbleKit JS.

## Install

Requirements on the phone: Ultrasonic (tested 4.8.0), the Core Devices Pebble app (tested
1.12.0.1) paired to the watch.

The easiest path is a **Release**: grab `ultrasonic-pebble-0.1.1.pbw` and
`ultrasonic-for-pebble-0.1.1-debug.apk` from the GitHub Releases page.

To build from source:

1. `bash build.sh` produces both artifacts under `dist/`. It expects
   `pebble` (SDK 4.33.1) and JDK 17 on PATH, and an Android SDK with
   build-tools 36 and platform 36.
2. Install the companion: `adb install -r dist/ultrasonic-for-pebble-0.1.1-debug.apk`.
3. Open `dist/ultrasonic-pebble-0.1.1.pbw` with the Pebble app on the phone
   (or, over adb: push it to `/sdcard/Download`, media-scan it, and
   `am start -a android.intent.action.VIEW` its `content://media/external/downloads/<id>`
   URI with `-n coredevices.coreapp/.MainActivity` — the Pebble app then
   installs it to the watch without a prompt).
4. Open the companion once; its status screen shows whether Ultrasonic and
   the Pebble app are found and whether Ultrasonic's session answers.

The watchapp's `package.json` names the companion in `companionApp`; the
Pebble app only lets that package talk to the watchapp.

## Layout

| Path | What |
|---|---|
| `watchapp/` | C watchapp (`src/c/`), `package.json` (message keys, companion whitelist) |
| `companion/` | Android companion (Kotlin, AGP 9.2 / Kotlin 2.3.21 / Gradle 9.4.1) |
| `tools/emulator_suite.sh` | build + drive both emulators with a fake companion |
| `tools/fake_companion.py` | AppMessage scenario: every watch command asserted by value |
| `tools/mutate_watch.py`, `tools/mutate_companion.py` | prove the suites can fail |
| `tools/device_matrix.sh` | hardware matrix against the real Ultrasonic, state restored |
| `BUILD-NOTES.md` | what was verified and how, what was not, and why |

## Licence

Copyright (c) 2026 PiSCES.

Source code is licensed under the Mozilla Public License 2.0. See `LICENSE`.
Documentation is licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).

## Trademarks

*Ultrasonic* is a trademark of its authors (the `org.moire.ultrasonic`
project). This project is not affiliated with Ultrasonic or with Core
Devices; it interoperates with them.
