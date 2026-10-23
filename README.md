# Pulse

Pulse is a local-only Android companion for a WHOOP 5.0 strap the user already owns. The display name is Pulse. Nothing in this app is sent off the phone: the manifest has no `INTERNET` permission, and CI fails if one is added.

Milestone 1 includes the project skeleton, the format-1 frame codec in `:wire`, and the BLE scanner and GATT session in `:ble`:

- Gradle skeleton (Kotlin 2.1, Compose Material 3, Hilt, minSdk 31, compile/target SDK 35)
- Modules `:wire` (pure JVM), `:ble`, `:link`, `:sync`, `:data`, `:metrics`, `:feature:connect`, `:feature:live`, `:feature:sync`, `:feature:debug`, `:app`, `:testing:fakestrap`, `:testing:golden`
- [`docs/protocol-spec.md`](docs/protocol-spec.md) v0, with a source, firmware, and confidence on every fact
- Clean-room rules in [`CONTRIBUTING.md`](CONTRIBUTING.md)

`:wire` encodes and decodes format-1 frames, reassembles split notifications, correlates command replies (including a pending result), and builds the documented client hello. `:ble` scans for service `fd4b0001-cce1-4033-93ce-002d5875f58a` and runs one GATT operation at a time: connect, discover, MTU 247, notify subscriptions, notifications, and a disconnect that closes the client. The Connect screen asks for Bluetooth scan and connect permission, plus notification permission on Android 13 and later. It does not ask for location. If permission is denied, Connect explains why and opens this app's settings. If Bluetooth is off, Connect asks to turn it on. Pairing stays closed until those grants are in place and the radio is on. Once they are, `:link` bonds through that session: at most two attempts, then it stops. If the strap is still bonded to another phone, Connect shows pairing help within 15 seconds instead of retrying. The Live screen shows Bluetooth Heart Rate Measurement (service `180D`, characteristic `2A37`) as soon as that session discovers services, including while bonding is still unfinished. Those samples stay in memory. The hello handshake and sync are later work.

Strap-in-hand checks for this transport are in [`docs/hardware-acceptance.md`](docs/hardware-acceptance.md).

## Check

```sh
export ANDROID_HOME="$HOME/android-sdk"   # platforms;android-35 and build-tools;35.0.0
./gradlew check
```

`check` runs unit tests, Android lint, a scan that rejects `android.permission.INTERNET` and location permissions, a check that `:wire` stays a pure JVM module, and a line-coverage check of at least 90% on the `:wire` codec package.
