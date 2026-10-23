package app.pulse.link

import app.pulse.ble.StrapGatt
import app.pulse.data.DeviceDirectory
import app.pulse.data.DeviceRow
import app.pulse.wire.codec.BatteryCommand
import app.pulse.wire.codec.CommandFrames
import app.pulse.wire.codec.CommandResult
import app.pulse.wire.codec.FrameCodec
import app.pulse.wire.codec.HelloBody
import app.pulse.wire.codec.HelloCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StrapHandshakeTest {
    @Test
    fun pendingThenFinalHelloParsesFirmwareAndRequestsBattery() {
        val fx = Fixture()
        fx.handshake.start()

        assertArrayEquals(HelloCommand.bytes(), fx.writes.single())
        assertEquals(listOf(StrapGatt.FIRMWARE_REVISION, StrapGatt.BATTERY_LEVEL), fx.reads)

        fx.handshake.onRead(StrapGatt.FIRMWARE_REVISION, status = 0, value = "50.42.1.0".encodeToByteArray())
        fx.handshake.onRead(StrapGatt.BATTERY_LEVEL, status = 0, value = byteArrayOf(81))
        assertEquals("50.42.1.0", fx.reports.last().informationFirmware)
        assertEquals(81, fx.reports.last().serviceBatteryPercent)
        assertFalse(fx.reports.last().settled)

        val pending = response(
            command = HelloCommand.NUMBER,
            origin = HelloCommand.DOCUMENTED_SEQUENCE,
            result = CommandResult.PENDING,
            body = ByteArray(HelloBody.FINAL_LENGTH).also { it[0] = 1.toByte() },
        )
        fx.handshake.onNotification(StrapGatt.RESPONSE_NOTIFY, pending.copyOfRange(0, 8))
        assertFalse(fx.reports.last().settled)
        fx.handshake.onNotification(StrapGatt.RESPONSE_NOTIFY, pending.copyOfRange(8, pending.size))
        assertFalse(fx.reports.any { it.settled })
        assertEquals(1, fx.writes.size)

        fx.clock.advance(StrapHandshake.HELLO_WAIT_MS - 1)
        assertFalse(fx.reports.any { it.settled })

        val body = helloBody()
        fx.handshake.onNotification(
            StrapGatt.RESPONSE_NOTIFY,
            response(
                command = HelloCommand.NUMBER,
                origin = HelloCommand.DOCUMENTED_SEQUENCE,
                result = CommandResult.SUCCESS,
                body = body,
            ),
        )

        val settled = fx.reports.last { it.settled }
        assertTrue(settled.succeeded)
        assertEquals("50.42.1.0", settled.helloFirmware)
        assertEquals("50.42.1.0", settled.informationFirmware)
        assertTrue(fx.logs.any { it.contains("identity=redacted") && it.contains("version=50.42.1.0") })
        assertTrue(fx.logs.none { it.contains("SECRET") })
        assertEquals(2, fx.writes.size)
        val battery = FrameCodec.decode(fx.writes[1])
        val batteryFrame = (battery as app.pulse.wire.codec.DecodeResult.Ok).frame
        assertEquals(BatteryCommand.NUMBER, batteryFrame.command)
        assertArrayEquals(
            CommandFrames.request(BatteryCommand.NUMBER, sequence = 2, body = BatteryCommand.REQUEST_BODY),
            fx.writes[1],
        )

        fx.handshake.onNotification(
            StrapGatt.RESPONSE_NOTIFY,
            response(
                command = BatteryCommand.NUMBER,
                origin = 2,
                result = CommandResult.SUCCESS,
                body = byteArrayOf(80, 0, 0, 0),
            ),
        )
        assertEquals(80L, fx.reports.last().commandBatteryPercent)
        assertEquals(80L, fx.devices.latest()?.commandBatteryPercent)
        assertEquals("50.42.1.0", fx.devices.latest()?.helloFirmware)
        assertEquals("AA:BB:CC:DD:EE:FF", fx.devices.latest()?.address)
        assertTrue(fx.logs.none { it.contains("SECRET") })
    }

    @Test
    fun helloFailureKeepsTheReportAndDoesNotSendBattery() {
        val fx = Fixture(heartRateLive = true)
        fx.handshake.start()
        fx.handshake.onWriteFinished(StrapGatt.COMMAND, status = 0)
        fx.handshake.onNotification(
            StrapGatt.RESPONSE_NOTIFY,
            response(
                command = HelloCommand.NUMBER,
                origin = HelloCommand.DOCUMENTED_SEQUENCE,
                result = CommandResult.FAILURE,
                body = ByteArray(4),
            ),
        )
        val settled = fx.reports.last()
        assertTrue(settled.settled)
        assertFalse(settled.succeeded)
        assertTrue(settled.heartRateLive)
        assertEquals(1, fx.writes.size)
        assertTrue(fx.logs.any { it.contains("identity=redacted") })
        assertTrue(fx.logs.none { it.contains("SECRET") })
    }

    @Test
    fun helloWriteFailureAndSilenceBothSettleAsFailure() {
        val failedWrite = Fixture()
        failedWrite.handshake.start()
        failedWrite.handshake.onWriteFinished(StrapGatt.COMMAND, status = 5)
        assertTrue(failedWrite.reports.last().settled)
        assertFalse(failedWrite.reports.last().succeeded)
        assertEquals(1, failedWrite.writes.size)

        val silent = Fixture()
        silent.handshake.start()
        silent.clock.advance(StrapHandshake.HELLO_WAIT_MS - 1)
        assertTrue(silent.reports.none { it.settled })
        silent.clock.advance(1)
        assertTrue(silent.reports.last().settled)
        assertFalse(silent.reports.last().succeeded)
    }

    @Test
    fun batteryFailureDoesNotInventAPercent() {
        val fx = Fixture()
        fx.handshake.start()
        fx.handshake.onNotification(
            StrapGatt.RESPONSE_NOTIFY,
            response(
                command = HelloCommand.NUMBER,
                origin = HelloCommand.DOCUMENTED_SEQUENCE,
                result = CommandResult.SUCCESS,
                body = helloBody(),
            ),
        )
        fx.handshake.onNotification(
            StrapGatt.RESPONSE_NOTIFY,
            response(
                command = BatteryCommand.NUMBER,
                origin = 2,
                result = CommandResult.FAILURE,
                body = byteArrayOf(0, 0, 0, 0),
            ),
        )
        assertEquals(null, fx.reports.last().commandBatteryPercent)
        assertEquals(null, fx.devices.latest()?.commandBatteryPercent)
    }

    private fun helloBody(): ByteArray {
        val body = ByteArray(HelloBody.FINAL_LENGTH)
        body[0] = HelloCommand.REVISION.toByte()
        "SECRET-IDENTITY-BLOCK-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345".encodeToByteArray()
            .copyInto(body, destinationOffset = 14)
        body[HelloBody.VERSION_OFFSET] = 50.toByte()
        body[HelloBody.VERSION_OFFSET + 1] = 42.toByte()
        body[HelloBody.VERSION_OFFSET + 2] = 1.toByte()
        return body
    }

    private fun response(command: Int, origin: Int, result: Int, body: ByteArray): ByteArray {
        return FrameCodec.encodeCommandResponse(
            sequence = 9,
            command = command,
            originSequence = origin,
            result = result,
            body = body,
        )
    }

    private class Fixture(
        heartRateLive: Boolean = false,
    ) {
        val writes = mutableListOf<ByteArray>()
        val reads = mutableListOf<java.util.UUID>()
        val reports = mutableListOf<DeviceReport>()
        val logs = mutableListOf<String>()
        val devices = MemoryDevices()
        val clock = ManualClock()
        val handshake = StrapHandshake(
            address = "AA:BB:CC:DD:EE:FF",
            knows = { true },
            write = { bytes -> writes += bytes.copyOf() },
            read = { reads += it },
            clock = clock,
            devices = devices,
            heartRateLive = { heartRateLive },
            report = { reports += it },
            nowMillis = { 42L },
            log = { logs += it },
        )
    }
}

private class MemoryDevices : DeviceDirectory {
    private val rows = mutableListOf<DeviceRow>()

    override fun save(row: DeviceRow) {
        rows.removeAll { it.address == row.address }
        rows += row
    }

    override fun latest(): DeviceRow? = rows.maxByOrNull { it.updatedAtMillis }
}

private class ManualClock : LinkClock {
    private var now = 0L
    private val tasks = mutableListOf<Scheduled>()

    private class Scheduled(val due: Long, val task: () -> Unit) {
        var cancelled = false
    }

    override fun after(delayMs: Long, task: () -> Unit): LinkAlarm {
        val scheduled = Scheduled(now + delayMs, task)
        tasks += scheduled
        return LinkAlarm { scheduled.cancelled = true }
    }

    fun advance(ms: Long) {
        now += ms
        val due = tasks.filter { !it.cancelled && it.due <= now }
        tasks.removeAll(due.toSet())
        due.forEach { if (!it.cancelled) it.task() }
    }
}
