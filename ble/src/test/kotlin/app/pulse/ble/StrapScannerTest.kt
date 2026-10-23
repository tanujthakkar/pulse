package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class StrapScannerTest {
    @Test
    fun filterIsTheProprietaryServiceAndTheNameIsNotAKey() {
        val port = FakeScanPort()
        val seen = mutableListOf<StrapSighting>()
        val scanner = StrapScanner(port, object : ScanEvents {
            override fun onSighting(sighting: StrapSighting) {
                seen += sighting
            }
        })

        scanner.start()
        port.emit(
            StrapSighting(
                address = "11:22:33:44:55:66",
                rssi = -42,
                advertisedName = "not-a-filter",
            ),
        )

        assertEquals(StrapGatt.SERVICE, port.service)
        assertEquals("fd4b0001-cce1-4033-93ce-002d5875f58a", port.service.toString())
        assertEquals(1, seen.size)
        assertEquals("11:22:33:44:55:66", seen[0].address)
        assertEquals(-42, seen[0].rssi)
        assertEquals("not-a-filter", seen[0].advertisedName)
        assertTrue(port.scanning)
    }

    @Test
    fun stopDropsALateSighting() {
        val port = FakeScanPort()
        val seen = mutableListOf<StrapSighting>()
        val scanner = StrapScanner(port, object : ScanEvents {
            override fun onSighting(sighting: StrapSighting) {
                seen += sighting
            }
        })
        scanner.start()
        scanner.stop()
        port.emit(StrapSighting("11:22:33:44:55:66", -20, null))

        assertFalse(port.scanning)
        assertEquals(1, port.stopCount)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun restartStopsThePreviousScan() {
        val port = FakeScanPort()
        val scanner = StrapScanner(port, object : ScanEvents {})
        scanner.start()
        scanner.start()
        assertEquals(1, port.stopCount)
        assertTrue(port.scanning)
        assertEquals(StrapGatt.SERVICE, port.service)
    }

    @Test
    fun scanFailureIsDeliveredAndEndsTheScan() {
        val port = FakeScanPort()
        val failures = mutableListOf<Int>()
        val seen = mutableListOf<StrapSighting>()
        val scanner = StrapScanner(port, object : ScanEvents {
            override fun onSighting(sighting: StrapSighting) {
                seen += sighting
            }

            override fun onScanFailed(code: Int) {
                failures += code
            }
        })
        scanner.start()
        port.fail(2)
        port.emit(StrapSighting("11:22:33:44:55:66", -20, "ignored"))

        assertEquals(listOf(2), failures)
        assertTrue(seen.isEmpty())
        assertNull(port.onSighting)
    }

    @Test
    fun stopBeforeStartDoesNotTouchThePort() {
        val port = FakeScanPort()
        StrapScanner(port, object : ScanEvents {}).stop()
        assertEquals(0, port.stopCount)
        assertNull(port.service)
    }
}

private class FakeScanPort : ScanPort {
    var service: UUID? = null
    var scanning: Boolean = false
    var stopCount: Int = 0
    var onSighting: ((StrapSighting) -> Unit)? = null

    private var onFailed: ((Int) -> Unit)? = null

    override fun start(
        service: UUID,
        onSighting: (StrapSighting) -> Unit,
        onFailed: (Int) -> Unit,
    ) {
        this.service = service
        this.onSighting = onSighting
        this.onFailed = onFailed
        scanning = true
    }

    override fun stop() {
        scanning = false
        stopCount += 1
        onSighting = null
        onFailed = null
    }

    fun emit(sighting: StrapSighting) {
        onSighting?.invoke(sighting)
    }

    fun fail(code: Int) {
        onFailed?.invoke(code)
    }
}
