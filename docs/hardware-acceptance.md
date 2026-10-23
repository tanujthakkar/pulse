# Hardware acceptance

`./gradlew check` runs the `:ble` unit tests against an in-memory radio. The checks below need a strap the tester owns. They are for task T14 and are not part of CI.

The client hello and history sync are out of scope here. Live heart rate has its own section below. The chest-strap comparison in that section stays unchecked until a tester runs it.

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

When Heart Rate Service `180D` is present, the session writes the `2A37` notify descriptor after discover and before the MTU request. That write is the live heart-rate subscription. It is not one of the four proprietary descriptors above.

## T5 bond

`./gradlew check` covers the state machine with a fake session. The checks below need a strap the tester owns.

Logcat tag: `PulseGatt`. Lines look like `link BONDING bond=1 connect=0 address=<addr>`.

### Clean strap

1. The strap is not bonded to another phone. Put it in pairing mode: firm taps until the LEDs flash blue.
2. Open Connect.
3. Pass: the screen reaches **Bonded and connected** and the log shows at most two `bond=` values before `link LINKED`. Two attempts is the cap. A third bond request is a failure.

### Still bonded to the official app

1. Leave the strap bonded to the official app. Do not put it in pairing mode.
2. Open Connect.
3. Pass: within 15 seconds the screen shows **Release the other bond** and the status **The strap is still bonded to another phone**.
4. Pass: after that screen is up, the log does not keep increasing the bond attempt on its own. A later attempt happens only after Try again.

## T6 live heart rate

`./gradlew check` parses Heart Rate Measurement fixtures and checks that a discovered `2A37` is subscribed before the MTU request. The rows below need a strap the tester owns. They are not part of CI.

Heart rate notifications do not require the proprietary bond. On the first sighting the session discovers services, writes the `2A37` notify descriptor, and can deliver bpm before bonding finishes. The four proprietary notify descriptors wait until the bond is present. Milestone 1 does not store the samples.

- [ ] Wear the strap and open Connect so Pulse links. The Live screen shows bpm and R-R, updating about once a second.
- [ ] Compare that bpm with a chest strap worn at the same time. Pass when Pulse stays within ±2 bpm. Leave this unchecked until the comparison is run.
