# Pulse

Pulse is a local-only Android companion for a WHOOP 5.0 strap the user already owns. The display name is Pulse. Nothing in this app is sent off the phone: the manifest has no `INTERNET` permission, and CI fails if one is added.

Milestone 1 includes the project skeleton and the format-1 frame codec in `:wire`:

- Gradle skeleton (Kotlin 2.1, Jetpack Compose Material 3, Hilt, minSdk 31, compile/target SDK 35)
- Modules `:wire` (pure JVM), `:ble`, `:link`, `:sync`, `:data`, `:metrics`, `:feature:connect`, `:feature:live`, `:feature:sync`, `:feature:debug`, `:app`, `:testing:fakestrap`, `:testing:golden`
- [`docs/protocol-spec.md`](docs/protocol-spec.md) v0, with a source, firmware, and confidence on every fact
- Clean-room rules in [`CONTRIBUTING.md`](CONTRIBUTING.md)

`:wire` encodes and decodes format-1 frames, reassembles split notifications, correlates command replies (including a pending result), and builds the documented client hello. Screens are still placeholders. BLE, bonding, sync, and the deny-list sender are later work.

## Check

```sh
export ANDROID_HOME="$HOME/android-sdk"   # platforms;android-35 and build-tools;35.0.0
./gradlew check
```

`check` runs unit tests, Android lint, a scan that rejects `android.permission.INTERNET`, a check that `:wire` stays a pure JVM module, and a line-coverage check of at least 90% on the `:wire` codec package.
