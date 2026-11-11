# Testing Pulse without a strap

Debug builds can talk to a simulated strap. Release builds do not include it, and it stays off until you turn it on. Simulated history is written to `pulse-sim.db`. Real strap history stays in `pulse.db`.

The series is generated in code from a fixed seed. It is not a copy of anyone's strap. A normal run is several local days of layout-18 history: one-minute daytime heart rate and steps, and three nights stored once per second so sleep, HRV, and resting heart rate have something to read. The strap answers hello with firmware `50.43.1.0`, reports a battery level, streams history in chunks with end tokens, replies to command 23 with result 1, and sends a live heart rate once a second after the heart-rate notification is enabled.

## Emulator

1. Build and install the debug APK: `./gradlew :app:assembleDebug`.
2. Start the app with the simulated strap:

```
adb shell am start -n app.pulse/.MainActivity --ez simulated_strap true
```

The process restarts once so the simulated database is the one that opens. The second launch does not need the extra. The strap is advertised as "Pulse Sim" and connects on its own. History sync, home, activities, and the calendar use the same code as a real strap.

Scenarios use the same start, with `simulated_scenario` set to `normal`, `empty`, `disconnect`, or `low_battery`:

```
adb shell am start -n app.pulse/.MainActivity --ez simulated_strap true --es simulated_scenario empty
```

`empty` completes a sync with no samples. `disconnect` saves one chunk, drops the link, and the next connection finishes the series. `low_battery` is the normal series with the battery at 8%.

The gear menu (More) has the same switch. Changing it restarts the app. Turning it off returns the app to `pulse.db`.

## Screenshots

```
./gradlew :feature:home:recordPaparazziDebug :app:recordPaparazziDebug
./gradlew :feature:home:verifyPaparazziDebug :app:verifyPaparazziDebug
```

`./gradlew check` verifies the saved images. Copies are written to `app/build/outputs/screenshots`.
