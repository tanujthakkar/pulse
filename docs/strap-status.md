# Strap status and the five-minute sync

## Background sync

History still syncs on connect and when the app comes to the foreground. The
gap between pulls is five minutes.

WorkManager periodic work cannot be shorter than fifteen minutes, so the
fallback is a unique one-time request named `pulse-history-chain`. App start
and boot enqueue it with KEEP when no request is already pending. That first
delay starts with the process. When the launch pull finishes, the pending
request is replaced so the next five minutes are measured from the finish.
Each worker run appends the next request when it returns, so the delay is
measured from the end of that run as well. The append uses
APPEND_OR_REPLACE. A failure result marks that appended request FAILED
without running it, so a cooldown, a skip, and an already-running sync
return success and still append. A link that is not ready returns retry,
and the request's linear five-minute backoff is the reschedule. The worker
and the in-process timer may start another pull one minute after the
previous attempt finished, which is shorter than the chain delay. The old
periodic name `pulse-history-sync` is cancelled on enqueue.

The request requires the battery not to be low and does not require a
network. It is not a foreground service and it does not post a notification.
Doze, app standby, and the battery constraint can run the chain later than
five minutes. Command 23 is unchanged: it is still sent only after a chunk
is saved, and only while the user's acknowledgement switch is on.

## Charging

The icon shows a bolt only after an event frame whose number (byte 10) is 7,
and clears it on event 8. Those numbers are the catalogue names "charging on"
and "charging off" in `docs/protocol-spec.md`. They were not captured on
firmware 50.43.1.0, so a strap on that build may stay without a bolt.
Command 26 and Battery Level `2A19` are a percent only. A percent does not
turn the bolt on.

The simulated strap's Charging scenario emits event 7 once, after the first
command, and reports 64% from command 26. Connected is the normal scenario
after hello. Disconnected is the strap off, or the disconnect scenario, which drops once and then
refuses the next connect so the icon stays red. Syncing is the history
attempt itself: the icon draws a spinning arc and the number of chunks
received on this attempt, including chunks that were already stored. The
strap does not send a total, so the arc is not a percentage.

Event bytes are copied off the GATT callback and decoded on `pulse-charge`.
The home icon collects link phase, the sync attempt, and that flag on
`Dispatchers.Default`.
