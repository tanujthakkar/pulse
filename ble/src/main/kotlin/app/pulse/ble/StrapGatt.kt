package app.pulse.ble

import java.util.UUID

/**
 * GATT map from protocol spec §2 and the project rules in §14.
 *
 * Scans filter on [SERVICE] only. Command payloads are written to [COMMAND]
 * with a response. A session subscribes [NOTIFY] in that order. [REQUESTED_MTU]
 * is the phone's request after connect, not a strap requirement.
 */
object StrapGatt {
    val SERVICE: UUID = UUID.fromString("fd4b0001-cce1-4033-93ce-002d5875f58a")
    val COMMAND: UUID = UUID.fromString("fd4b0002-cce1-4033-93ce-002d5875f58a")
    val RESPONSE_NOTIFY: UUID = UUID.fromString("fd4b0003-cce1-4033-93ce-002d5875f58a")
    val EVENT_NOTIFY: UUID = UUID.fromString("fd4b0004-cce1-4033-93ce-002d5875f58a")
    val DATA_NOTIFY: UUID = UUID.fromString("fd4b0005-cce1-4033-93ce-002d5875f58a")
    val EXTRA_NOTIFY: UUID = UUID.fromString("fd4b0007-cce1-4033-93ce-002d5875f58a")

    /** Spec §2 session subscribe set, in the documented order. `fd4b0007` has no assigned role yet. */
    val NOTIFY: List<UUID> = listOf(
        RESPONSE_NOTIFY,
        EVENT_NOTIFY,
        DATA_NOTIFY,
        EXTRA_NOTIFY,
    )

    /** Client Characteristic Configuration descriptor, Bluetooth SIG base UUID. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Notifications enabled. Stored little-endian in [CCCD]. */
    val ENABLE_NOTIFICATION: ByteArray = byteArrayOf(0x01, 0x00)

    const val REQUESTED_MTU: Int = 247
}
