# Pulse

Pulse is a local-only Android companion for a WHOOP 5.0 strap the user already owns. The display name is Pulse. Nothing in this app is sent off the phone: the manifest has no `INTERNET` permission, and CI fails if one is added.

This revision is the Milestone 1 kickoff only:

- Gradle skeleton (Kotlin 2.1, Jetpack Compose Material 3, Hilt, minSdk 31, compile/target SDK 35)
- Modules `:wire` (pure JVM), `:ble`, `:link`, `:sync`, `:data`, `:metrics`, `:feature:connect`, `:feature:live`, `:feature:sync`, `:feature:debug`, `:app`, `:testing:fakestrap`, `:testing:golden`
- [`docs/protocol-spec.md`](docs/protocol-spec.md) v0, with a source, firmware, and confidence on every fact
- Clean-room rules in [`CONTRIBUTING.md`](CONTRIBUTING.md)

The frame codec is not started. Screens are placeholders.

## Check

```sh
export ANDROID_HOME="$HOME/android-sdk"   # platforms;android-35 and build-tools;35.0.0
./gradlew check
```

`check` runs unit tests, Android lint, a scan that rejects `android.permission.INTERNET`, and a check that `:wire` stays a pure JVM module.
