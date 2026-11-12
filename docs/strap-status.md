# Strap status and the five-minute sync

## Background sync

History still syncs on connect and when the app comes to the foreground. The
gap between pulls is five minutes.

WorkManager periodic work cannot be shorter than fifteen minutes, so the
fallback is a unique one-time request named `pulse-history-chain`. App start
and boot enqueue it with KEEP when no request is already pending. Each run,
after it finishes or fails, enqueues the next request with a five-minute
delay. That follow-up uses APPEND_OR_REPLACE so the worker that is still
running is not cancelled, and a second follow-up replaces the pending one
instead of stacking. The old periodic name `pulse-history-sync` is cancelled
on enqueue.

The request requires the battery not to be low and does not require a
network. It is not a foreground service and it does not post a notification.
Doze, app standby, and the battery constraint can run the chain later than
five minutes. A scheduled run that finds a history attempt already going
returns without starting another one. The worker reports success only after
that attempt reaches metadata complete. Command 23 is unchanged: it is still
sent only after a chunk is saved, and only while the user's acknowledgement
switch is on.

## Charging

The icon shows a bolt only after an event frame whose number (byte 10) is 7,
and clears it on event 8. Those numbers are the catalogue names "charging on"
and "charging off" in `docs/protocol-spec.md`. They were not captured on
firmware 50.43.1.0, so a strap on that build may stay without a bolt.
Command 26 and Battery Level `2A19` are a percent only. A percent does not
turn the bolt on.

The simulated strap's Charging scenario emits event 7 once, after the first
command, and reports 64% from command 26. Connected is the normal scenario
after hello. Disconnected is the strap off, or the disconnect scenario after
the drop. Syncing is the history attempt itself: the icon draws a spinning
arc and, once chunks have been stored, that count. The strap does not send a
total, so the arc is not a percentage.

Event bytes are copied off the GATT callback and decoded on `pulse-charge`.
The home icon collects link phase, the sync attempt, and that flag on
`Dispatchers.Default`.
