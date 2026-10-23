# Hardware acceptance

`./gradlew check` runs the `:ble` unit tests against an in-memory radio. The checks below need a strap the tester owns. They are for task T14 and are not part of CI.

Bonding, live heart rate, the client hello, and history sync are out of scope here.

Logcat tag: `PulseGatt`.

## T4 scan

1. Put the strap on so it is advertising.
2. Start `StrapScanner`. The only filter is service `fd4b0001-cce1-4033-93ce-002d5875f58a`. Do not match on the advertised name.
3. Pass: a sighting for that strap is delivered within 10 seconds of scan start (`sighting <address> rssi=<n>`).
4. Stop the scan (`scan stop`). The scan must not keep running.

## T4 connect and disconnect

1. `GattSession.open` connects, discovers services, requests MTU 247, and writes the notify descriptor on `fd4b0003`, `fd4b0004`, `fd4b0005`, and `fd4b0007`.
2. Each operation is one pair of lines, `BEGIN <op>` then `END <op> status=<n>`. A second `BEGIN` before `END` is a failure. `NOTIFY` lines are not operations.
3. Disconnect. The log contains `CLOSE`. That line means `BluetoothGatt.close()` ran. The client must not stay registered.
4. Repeat open and disconnect for 20 cycles, using a new session each cycle.
5. Pass: all 20 cycles log `CLOSE`, no `BEGIN`/`END` pair overlaps, and no GATT client is left open afterward.

A granted MTU below 247 still passes. 247 is the phone's request, not a strap requirement. A cycle that times out (10 seconds with no callback, `END <op> status=-1` and `CLOSE`) does not count as one of the 20.
