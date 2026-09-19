# BUILD-NOTES — Ultrasonic for Pebble 0.1.0

What was verified, how, and against what; what was not; and the decisions that shaped it.

## Verified

Every row under *Build, emulator and tests* was executed on this release's source, from a
clean extraction of its tree. The rows under *On hardware* ran on a pre-release build whose
app code differs from this release only in the companion's package id, the watchapp UUID and
the companion URL (every other app source file compared identical).

### Build, emulator and tests

| Claim | How |
|---|---|
| `flint` = Pebble 2 Duo, `emery` = Pebble Time 2 | SDK 4.33.1 template README and `pebble_sdk_platform.py` |
| Watch C builds for both with 0 compiler warnings | a fresh build compiles **12/12** core objects (6 files x 2 platforms) with **0 compiler warnings**. The SDK passes `-Wall -Wextra -Werror` but also `-Wno-error` for several classes (unused variable, unused function, format truncation among them) and exits 0 with those present, so `build.sh` and the emulator suite count warnings themselves |
| That warning count can fail | an unused variable injected into `main.c`: **2** warnings counted (one per platform); source restored, sha256 equal to the original; recount **0** |
| RAM | flint 26,840 / 65,536 B; emery 26,844 / 131,072 B |
| Every screen renders on both | the emulator suite captures 11 states per platform; every frame was inspected. `store-assets/screenshots/` holds 5 of them per platform |
| Watch <-> companion protocol | `tools/fake_companion.py`: **29/29 on flint, 29/29 on emery**; every watch->phone command asserted by value, stale-REQ header and watchdog checked by pixel diff |
| The suite can fail | `tools/mutate_watch.py`: **6/6 mutants caught at the intended step**, clean suite after restore |
| Companion unit tests | **29/29** (JVM): wire contract parsed from `package.json` and `protocol.h`, UTF-8 truncation, list registry, now-playing mapping, Ultrasonic id flags |
| Companion tests can fail | `tools/mutate_companion.py`: **7/7 mutants caught, each at its named test** (key drift, command drift, NUL budget, UTF-8 width, list-id wrap, heart precedence, Play All flags), clean 29/29 after restore |
| Debug hook is debug-only | `aapt2` and `dexdump`: InjectCommandReceiver declared 1 / in dex 1 in the debug APK, 0 / 0 in the release APK; a positive-control class is present in both |

### On hardware (Pebble Time 2, Core Devices app 1.12.0.1, Ultrasonic 4.8.0)

| Claim | How |
|---|---|
| Watch sideload via the Core app | Core app log: PutBytes binary + resources, `AppRunStateStart(<uuid>)` on the paired watch |
| Watch -> phone -> watch | Core app log: watchapp HELLO in, companion reply out 66 ms later, watch ACK |
| Companion against Ultrasonic 4.8.0 | `tools/device_matrix.sh`: **15/15** — root, Albums (large library, first 40 shown), an album's tracks, stale id refused, Love -> Dislike -> Love, repeat 0 -> 2 -> 1 -> 0, next/previous, volume +/-, state restored |
| Play / pause | a matrix run with `--with-play`: PLAYING then PAUSED (2 s of audio) |

## Not verified

- **Starting playback of a browsed item on hardware.** `PLAY_ITEM` replaces
  Ultrasonic's queue; a controller cannot put a many-track queue back, so it
  was not run on a phone in active use. The watch half is emulator-verified;
  the companion half (`setMediaItem` with Ultrasonic's media id, resolved by
  its `onAddLegacyAutoItems`) is untested end-to-end.
- **Pebble 2 Duo hardware.** No `flint` watch was paired for this milestone;
  flint is emulator-only.
- **Physical buttons on the real Pebble Time 2.** Button -> command mapping
  is verified in the emery emulator with the same source; on hardware only
  HELLO travelled from the watch. Commands on hardware were injected into
  the companion's own command path via the debug-only receiver.
- **Cold start of Ultrasonic.** The 15 s connect timeout is headroom, not a
  measurement.

## Findings on hardware that the emulator could not show

Measured with the pre-release build described above.

1. **Ultrasonic sends album rows with no browsable/playable metadata.** The
   watch ignores a row with neither flag, so albums were inert. The companion
   now derives flags from Ultrasonic's media-id grammar (`UltrasonicIds`,
   both tables copied from its 4.8.0 `onLoadChildren` /
   `onAddLegacyAutoItems`), but trusts metadata whenever it asserts anything,
   because *Play All* shares the album's id kind and must play, not re-open.
2. **`dumpsys media_session` does not refresh Ultrasonic's repeat button
   name** after REPEAT_MODE; the controller-side `repeatMode` does. Repeat
   is checked on the controller.
3. **Aggressive OEM app freezers** (Samsung Freecess and similar) freeze a
   backgrounded companion between broadcasts, stalling commands mid-flight.
   The hardware matrix foregrounds the status screen while it runs. In real
   use the Pebble app keeps the service bound, so the process should not be
   frozen.

## Decisions

- **Companion, not PebbleKit JS.** PKJS has no media API; only an Android
  component can reach Ultrasonic's session. The Pebble's built-in Music app
  already covers bare play/pause.
- **PebbleKit 2 client 1.2.0.** 1.3.x declares `minCompileSdk=37`; this
  project targets 36 to keep the Android SDK requirement one level lower.
  Cost: 1.3.2's "cancellability of suspend operation" fix is absent. To
  upgrade, install `platforms;android-37` and set `compileSdk = 37`.
- **Unit tests run on JDK 21** via a Gradle toolchain: PebbleKit 2's
  pure-JVM jars are Java 21 bytecode (class file 65). The APK is unaffected
  (D8).
- **Explicit message-key numbers** (`package.json` object form), so the
  companion's constants are declared, not derived from declaration order.
- **Debug build, debug key.** The bundled APK is signed with the local
  debug keystore; a later release-signed build cannot update this install
  in place.
