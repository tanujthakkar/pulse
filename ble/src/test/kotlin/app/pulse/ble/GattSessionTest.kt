package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GattSessionTest {
    @Test
    fun connectDiscoverMtuCccdAndNotificationStaySerialized() {
        val fixture = SessionFixture()
        val payload = byteArrayOf(0x0A, 0x0B)
        fixture.session.open(STRAP_ADDRESS)
        fixture.session.write(StrapGatt.COMMAND, byteArrayOf(0x11))
        fixture.openAndReady()

        assertEquals(STRAP_ADDRESS, fixture.radio.connectAddress)
        assertEquals(StrapGatt.REQUESTED_MTU, fixture.radio.requestedMtu)
        assertEquals(StrapGatt.REQUESTED_MTU, fixture.events.readyMtu)
        assertEquals(false, fixture.events.writeVisibleAtReady)
        assertEquals(StrapGatt.NOTIFY, fixture.radio.cccdWrites.map { it.first })
        fixture.radio.cccdWrites.forEach { (_, value) ->
            assertArrayEquals(byteArrayOf(0x01, 0x00), value)
        }
        assertTrue(fixture.radio.writes.all { it.withResponse })
        assertArrayEquals(byteArrayOf(0x11), fixture.radio.writes.single().value)
        assertFalse(fixture.radio.started.any { it.startsWith("write:") && it != "write:${StrapGatt.COMMAND}" })

        fixture.radio.succeedWrite()
        fixture.radio.emit(StrapGatt.RESPONSE_NOTIFY, payload)
        payload[0] = 0x7F
        fixture.session.disconnect()
        fixture.radio.completeDisconnect()

        val lines = fixture.session.opLogSnapshot()
        assertSerialized(lines)
        assertEquals(0, fixture.session.overlapCount())
        assertEquals(1, fixture.radio.maxInFlight)
        assertEquals(1, fixture.radio.closeCount)
        assertEquals(SessionPhase.CLOSED, fixture.session.phase)
        assertEquals(CloseReason.REQUESTED, fixture.session.closeReason)
        assertNull(fixture.session.terminalStatus)
        assertEquals(listOf(CloseReason.REQUESTED), fixture.events.closed)
        assertTrue(fixture.clock.closed)
        assertEquals(1, fixture.events.notifications.size)
        assertEquals(StrapGatt.RESPONSE_NOTIFY, fixture.events.notifications[0].first)
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), fixture.events.notifications[0].second)
        assertTrue(lines.contains("NOTIFY ${StrapGatt.RESPONSE_NOTIFY} bytes=2"))
        assertEquals("CLOSE", lines.last())
        assertEquals(
            listOf(
                "connect",
                "discover",
                "requestMtu",
            ) + StrapGatt.NOTIFY.flatMap { uuid ->
                listOf("setNotify:$uuid:true", "cccd:$uuid")
            } + listOf("write:${StrapGatt.COMMAND}", "disconnect"),
            fixture.radio.started,
        )
    }

    @Test
    fun lowerGrantedMtuStillSubscribes() {
        val fixture = SessionFixture()
        fixture.openAndReady(mtu = 185)
        assertEquals(185, fixture.events.readyMtu)
        assertEquals(247, fixture.radio.requestedMtu)
        assertEquals(4, fixture.radio.cccdWrites.size)
    }

    @Test
    fun notificationDuringMtuDoesNotFinishThatOp() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet(),
        )
        fixture.radio.emit(StrapGatt.DATA_NOTIFY, byteArrayOf(1, 2, 3))

        val lines = fixture.session.opLogSnapshot()
        assertTrue(lines.any { it == "BEGIN requestMtu" })
        assertTrue(lines.none { it.startsWith("END requestMtu") })
        assertEquals(1, fixture.events.notifications.size)
        assertEquals(0, fixture.session.overlapCount())
        assertNull(fixture.events.readyMtu)

        fixture.radio.succeedMtu(247)
        repeat(4) { fixture.radio.succeedCccd() }
        assertSerialized(fixture.session.opLogSnapshot())
        assertEquals(247, fixture.events.readyMtu)
    }

    @Test
    fun disconnectClosesOnceAndASecondCallDoesNothing() {
        val fixture = SessionFixture()
        fixture.openAndReady()
        fixture.session.disconnect()
        fixture.radio.completeDisconnect()
        fixture.session.disconnect()
        fixture.radio.emit(StrapGatt.EVENT_NOTIFY, byteArrayOf(1))

        assertEquals(1, fixture.radio.closeCount)
        assertEquals(1, fixture.radio.disconnectCount)
        assertEquals(0, fixture.events.notifications.size)
        assertEquals(SessionPhase.CLOSED, fixture.session.phase)
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun disconnectBeforeTheLinkIsUpSkipsDiscoverAndStillCloses() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.session.disconnect()
        fixture.radio.succeedConnect()
        fixture.radio.completeDisconnect()

        assertFalse(fixture.radio.started.contains("discover"))
        assertEquals(1, fixture.radio.closeCount)
        assertEquals(CloseReason.REQUESTED, fixture.session.closeReason)
        assertEquals(1, fixture.radio.maxInFlight)
        assertEquals(0, fixture.session.overlapCount())
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun disconnectDuringADescriptorWriteWaitsForThatWrite() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet(),
        )
        fixture.radio.succeedMtu(247)
        assertEquals(0, fixture.radio.disconnectCount)

        fixture.session.disconnect()
        assertEquals(0, fixture.radio.disconnectCount)
        fixture.radio.succeedCccd()
        assertEquals(1, fixture.radio.disconnectCount)
        fixture.radio.completeDisconnect()

        assertEquals(CloseReason.REQUESTED, fixture.session.closeReason)
        assertEquals(1, fixture.radio.closeCount)
        assertEquals(1, fixture.radio.maxInFlight)
        assertFalse(
            fixture.session.opLogSnapshot().any { it.contains("setNotify:${StrapGatt.EVENT_NOTIFY}") },
        )
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun connectFailureClosesWithoutALiveClient() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.failConnect(status = 133)

        assertEquals(1, fixture.radio.closeCount)
        assertEquals(0, fixture.radio.disconnectCount)
        assertEquals(CloseReason.FAILED, fixture.session.closeReason)
        assertEquals(133, fixture.session.terminalStatus)
        assertEquals(SessionPhase.CLOSED, fixture.session.phase)
        assertTrue(fixture.clock.closed)
        assertEquals(0, fixture.session.overlapCount())
    }

    @Test
    fun connectThatDoesNotStartStillCloses() {
        val fixture = SessionFixture()
        fixture.radio.failConnectStart = true
        fixture.session.open(STRAP_ADDRESS)

        assertEquals(SessionPhase.CLOSED, fixture.session.phase)
        assertEquals(CloseReason.FAILED, fixture.session.closeReason)
        assertEquals(1, fixture.radio.closeCount)
        assertEquals(0, fixture.radio.maxInFlight)
        assertTrue(fixture.session.opLogSnapshot().contains("END connect status=${GattStatus.START_FAILED}"))
        assertEquals("CLOSE", fixture.session.opLogSnapshot().last())
    }

    @Test
    fun missingProprietaryServiceClosesAfterDisconnect() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(services = emptySet(), characteristics = emptySet())
        fixture.radio.completeDisconnect()

        assertEquals(CloseReason.FAILED, fixture.session.closeReason)
        assertEquals(1, fixture.radio.closeCount)
        assertTrue(
            fixture.session.opLogSnapshot().contains("END discover status=${GattStatus.MISSING}"),
        )
        assertEquals(0, fixture.session.overlapCount())
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun descriptorFailureDisconnectsAndCloses() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet(),
        )
        fixture.radio.succeedMtu(247)
        fixture.radio.failCccd(status = 5)
        fixture.radio.completeDisconnect()

        assertEquals(CloseReason.FAILED, fixture.session.closeReason)
        assertEquals(5, fixture.session.terminalStatus)
        assertEquals(1, fixture.radio.closeCount)
        assertEquals(1, fixture.radio.maxInFlight)
        assertNull(fixture.events.readyMtu)
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun spontaneousDropClosesWithoutAnotherOperation() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet(),
        )
        fixture.radio.dropLink(status = 8)

        assertEquals(CloseReason.LINK_LOST, fixture.session.closeReason)
        assertEquals(0, fixture.radio.disconnectCount)
        assertEquals(1, fixture.radio.closeCount)
        assertEquals(1, fixture.radio.maxInFlight)
        assertEquals(0, fixture.session.overlapCount())
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun stuckConnectClosesOnTimeoutWithoutASecondOp() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.clock.fire()

        assertEquals(SessionPhase.CLOSED, fixture.session.phase)
        assertEquals(CloseReason.TIMED_OUT, fixture.session.closeReason)
        assertEquals(0, fixture.radio.disconnectCount)
        assertEquals(1, fixture.radio.closeCount)
        assertTrue(fixture.clock.closed)
        assertTrue(fixture.session.opLogSnapshot().contains("END connect status=${GattStatus.TIMEOUT}"))
        assertFalse(fixture.session.opLogSnapshot().any { it.startsWith("BEGIN disconnect") })
        assertEquals(0, fixture.session.overlapCount())
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun twentyCyclesEachCloseTheClient() {
        repeat(20) {
            val fixture = SessionFixture()
            fixture.openAndReady()
            fixture.session.disconnect()
            fixture.radio.completeDisconnect()

            assertEquals(1, fixture.radio.closeCount)
            assertEquals(0, fixture.session.overlapCount())
            assertEquals(1, fixture.radio.maxInFlight)
            assertEquals(SessionPhase.CLOSED, fixture.session.phase)
            assertTrue(fixture.clock.closed)
            assertTrue(fixture.session.opLogSnapshot().contains("CLOSE"))
            assertFalse(fixture.radio.started.any { it.startsWith("write:") })
            assertSerialized(fixture.session.opLogSnapshot())
        }
    }

    @Test
    fun concurrentWritesDoNotOverlap() {
        val fixture = SessionFixture()
        fixture.openAndReady()
        val pool = Executors.newFixedThreadPool(4)
        val finished = CountDownLatch(8)
        repeat(8) { index ->
            pool.submit {
                fixture.session.write(StrapGatt.COMMAND, byteArrayOf(index.toByte()))
                finished.countDown()
            }
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        repeat(8) { fixture.radio.succeedWrite() }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(8, fixture.radio.writes.size)
        assertTrue(fixture.radio.writes.all { it.withResponse })
        assertEquals(1, fixture.radio.maxInFlight)
        assertEquals(0, fixture.session.overlapCount())
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun secondOpenAfterCloseDoesNotConnectAgain() {
        val fixture = SessionFixture()
        fixture.openAndReady()
        fixture.session.disconnect()
        fixture.radio.completeDisconnect()
        val connects = fixture.radio.started.count { it == "connect" }
        fixture.session.open(STRAP_ADDRESS)
        assertEquals(connects, fixture.radio.started.count { it == "connect" })
        assertEquals(1, fixture.radio.closeCount)
    }

    @Test
    fun heartRateIsSubscribedBeforeMtuAndParsedBeforeReady() {
        val fixture = SessionFixture(nowMillis = { 1_700_000_000_123L })
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE, StrapGatt.HEART_RATE_SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet() +
                setOf(StrapGatt.COMMAND, StrapGatt.HEART_RATE_MEASUREMENT),
        )

        val hr = StrapGatt.HEART_RATE_MEASUREMENT
        assertEquals(
            listOf("connect", "discover", "setNotify:$hr:true", "cccd:$hr"),
            fixture.radio.started,
        )
        assertTrue(fixture.session.heartRate.replayCache.isEmpty())

        fixture.radio.succeedCccd()
        assertEquals("requestMtu", fixture.radio.started.last())
        assertNull(fixture.events.readyMtu)

        // flags 0x16: u8 HR, contact detected, R-R present. 72 bpm, 1024 ticks -> 1000 ms.
        fixture.radio.emit(hr, byteArrayOf(0x16, 0x48, 0x00, 0x04))
        val sample = fixture.session.heartRate.replayCache.single()
        assertEquals(1_700_000_000_123L, sample.tsMillis)
        assertEquals(72, sample.bpm)
        assertEquals(listOf(1000), sample.rrMs)
        assertEquals(SensorContact.DETECTED, sample.contact)
        assertNull(fixture.events.readyMtu)

        fixture.radio.emit(hr, byteArrayOf(0x00))
        fixture.radio.emit(StrapGatt.RESPONSE_NOTIFY, byteArrayOf(0x10, 0x48, 0x00, 0x04))
        assertEquals(1, fixture.session.heartRate.replayCache.size)
        assertEquals(72, fixture.session.heartRate.replayCache.single().bpm)

        fixture.radio.succeedMtu(247)
        repeat(StrapGatt.NOTIFY.size) { fixture.radio.succeedCccd() }
        assertEquals(247, fixture.events.readyMtu)
        assertEquals(5, fixture.radio.cccdWrites.size)
        assertEquals(hr, fixture.radio.cccdWrites.first().first)
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun deferredProprietaryNotifiesLeaveHeartRateFlowing() {
        val fixture = SessionFixture(nowMillis = { 5L }, deferProprietaryNotifies = true)
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE, StrapGatt.HEART_RATE_SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet() + StrapGatt.HEART_RATE_MEASUREMENT,
        )
        fixture.radio.succeedCccd()
        fixture.radio.succeedMtu(185)
        assertTrue(fixture.radio.started.none { it.startsWith("setNotify:${StrapGatt.RESPONSE_NOTIFY}") })
        assertNull(fixture.events.readyMtu)

        fixture.radio.emit(StrapGatt.HEART_RATE_MEASUREMENT, byteArrayOf(0x00, 0x3C))
        assertEquals(60, fixture.session.heartRate.replayCache.single().bpm)
        assertEquals(5L, fixture.session.heartRate.replayCache.single().tsMillis)

        fixture.session.subscribeStrapNotifies()
        assertTrue(fixture.radio.started.any { it.startsWith("setNotify:${StrapGatt.RESPONSE_NOTIFY}") })
        repeat(StrapGatt.NOTIFY.size) { fixture.radio.succeedCccd() }
        assertEquals(185, fixture.events.readyMtu)
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun heartRateServiceWithoutMeasurementWaitsForMtu() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE, StrapGatt.HEART_RATE_SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet(),
        )
        assertEquals("requestMtu", fixture.radio.started.last())
        assertTrue(fixture.radio.cccdWrites.isEmpty())
    }

    @Test
    fun heartRateDescriptorFailureClosesBeforeMtu() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE, StrapGatt.HEART_RATE_SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet() + StrapGatt.HEART_RATE_MEASUREMENT,
        )
        fixture.radio.failCccd(status = 5)
        fixture.radio.completeDisconnect()

        assertEquals(CloseReason.FAILED, fixture.session.closeReason)
        assertFalse(fixture.radio.started.contains("requestMtu"))
        assertEquals(1, fixture.radio.closeCount)
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun disconnectBeforeOpenIsANoOp() {
        val fixture = SessionFixture()
        fixture.session.disconnect()
        assertEquals(SessionPhase.IDLE, fixture.session.phase)
        assertEquals(0, fixture.radio.closeCount)
        assertTrue(fixture.events.closed.isEmpty())
    }

    @Test
    fun responseDescriptorTimeoutWhileHeartRateIsUpMarksUnbonded() {
        val fixture = SessionFixture(deferProprietaryNotifies = true)
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE, StrapGatt.HEART_RATE_SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet() + StrapGatt.HEART_RATE_MEASUREMENT,
        )
        fixture.radio.succeedCccd()
        fixture.radio.succeedMtu(247)
        assertTrue(fixture.session.heartRateChannelUp)
        assertNull(fixture.events.readyMtu)

        fixture.session.subscribeStrapNotifies()
        assertEquals(GattSession.PROPRIETARY_CCCD_TIMEOUT_MS, fixture.clock.delays.last())
        fixture.clock.fire()

        assertTrue(fixture.session.proprietaryUnbonded)
        assertEquals(CloseReason.TIMED_OUT, fixture.session.closeReason)
        assertEquals(SessionPhase.CLOSED, fixture.session.phase)
        assertEquals(1, fixture.radio.closeCount)
        assertSerialized(fixture.session.opLogSnapshot())
    }

    @Test
    fun responseDescriptorTimeoutWithoutHeartRateIsAPlainTimeout() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(StrapGatt.SERVICE),
            characteristics = StrapGatt.NOTIFY.toSet(),
        )
        fixture.radio.succeedMtu(247)
        assertFalse(fixture.session.heartRateChannelUp)
        assertEquals(GattSession.PROPRIETARY_CCCD_TIMEOUT_MS, fixture.clock.delays.last())
        fixture.clock.fire()

        assertFalse(fixture.session.proprietaryUnbonded)
        assertEquals(CloseReason.TIMED_OUT, fixture.session.closeReason)
        assertEquals(SessionPhase.CLOSED, fixture.session.phase)
    }

    @Test
    fun readsStaySerializedAndAFailedReadLeavesTheSessionUp() {
        val fixture = SessionFixture()
        fixture.session.open(STRAP_ADDRESS)
        fixture.radio.succeedConnect()
        fixture.radio.succeedDiscover(
            services = setOf(
                StrapGatt.SERVICE,
                StrapGatt.DEVICE_INFO_SERVICE,
                StrapGatt.BATTERY_SERVICE,
            ),
            characteristics = StrapGatt.NOTIFY.toSet() +
                setOf(StrapGatt.COMMAND, StrapGatt.FIRMWARE_REVISION, StrapGatt.BATTERY_LEVEL),
        )
        fixture.radio.succeedMtu(247)
        repeat(StrapGatt.NOTIFY.size) { fixture.radio.succeedCccd() }
        assertTrue(fixture.session.knows(StrapGatt.FIRMWARE_REVISION))
        assertTrue(fixture.session.knows(StrapGatt.BATTERY_LEVEL))

        fixture.session.read(StrapGatt.FIRMWARE_REVISION)
        fixture.session.read(StrapGatt.BATTERY_LEVEL)
        assertEquals(listOf(StrapGatt.FIRMWARE_REVISION), fixture.radio.reads)

        fixture.radio.succeedRead("50.42.1.0".encodeToByteArray())
        fixture.radio.succeedRead(byteArrayOf(81), status = 5)

        assertEquals(SessionPhase.READY, fixture.session.phase)
        assertEquals(0, fixture.radio.closeCount)
        assertEquals(StrapGatt.FIRMWARE_REVISION, fixture.events.reads[0].first)
        assertEquals(0, fixture.events.reads[0].second)
        assertEquals("50.42.1.0", fixture.events.reads[0].third.decodeToString())
        assertEquals(5, fixture.events.reads[1].second)
        assertEquals(1, fixture.radio.maxInFlight)
        assertSerialized(fixture.session.opLogSnapshot())
    }
}
