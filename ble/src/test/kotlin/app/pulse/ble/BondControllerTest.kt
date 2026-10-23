package app.pulse.ble

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BondControllerTest {
    @Test
    fun absentDeviceStartsABond() {
        val port = FakeBondPort()
        val events = RecordingBondEvents()
        val controller = BondController(port, events)

        val begin = controller.begin(ADDRESS)

        assertEquals(BondBegin.STARTED, begin)
        assertEquals(listOf(ADDRESS), port.created)
        assertTrue(events.updates.isEmpty())
    }

    @Test
    fun createBondFalseDoesNotPretendItStarted() {
        val port = FakeBondPort(createResult = false)
        val controller = BondController(port, RecordingBondEvents())

        assertEquals(BondBegin.NOT_STARTED, controller.begin(ADDRESS))
        assertEquals(listOf(ADDRESS), port.created)
    }

    @Test
    fun presentBondDoesNotCreateAnother() {
        val port = FakeBondPort(state = DeviceBond.PRESENT)
        val controller = BondController(port, RecordingBondEvents())

        assertEquals(BondBegin.ALREADY_PRESENT, controller.begin(ADDRESS))
        assertTrue(port.created.isEmpty())
    }

    @Test
    fun inProgressBondWaitsForTheCallback() {
        val port = FakeBondPort(state = DeviceBond.BONDING)
        val controller = BondController(port, RecordingBondEvents())

        assertEquals(BondBegin.STARTED, controller.begin(ADDRESS))
        assertTrue(port.created.isEmpty())
    }

    @Test
    fun platformUpdatesAreForwarded() {
        val port = FakeBondPort()
        val events = RecordingBondEvents()
        BondController(port, events)

        port.emit(ADDRESS, DeviceBond.BONDING, refused = false)
        port.emit(ADDRESS, DeviceBond.ABSENT, refused = true)

        assertEquals(
            listOf(
                ADDRESS to DeviceBond.BONDING to false,
                ADDRESS to DeviceBond.ABSENT to true,
            ),
            events.updates.map { (address, bond, refused) -> address to bond to refused },
        )
    }

    @Test
    fun cancelForgetsTheActiveRequest() {
        val port = FakeBondPort()
        val controller = BondController(port, RecordingBondEvents())
        controller.begin(ADDRESS)
        controller.cancel()
        controller.cancel()

        assertEquals(listOf(ADDRESS), port.cancelled)
    }
}

internal class FakeBondPort(
    var state: DeviceBond = DeviceBond.ABSENT,
    private val createResult: Boolean = true,
) : BondPort {
    override var onUpdate: ((address: String, bond: DeviceBond, refused: Boolean) -> Unit)? = null
    val created = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override fun state(address: String): DeviceBond = state

    override fun createBond(address: String): Boolean {
        created += address
        return createResult
    }

    override fun cancel(address: String) {
        cancelled += address
    }

    fun emit(address: String, bond: DeviceBond, refused: Boolean) {
        onUpdate?.invoke(address, bond, refused)
    }
}

internal class RecordingBondEvents : BondEvents {
    val updates = mutableListOf<Triple<String, DeviceBond, Boolean>>()

    override fun onChanged(address: String, bond: DeviceBond, refused: Boolean) {
        updates += Triple(address, bond, refused)
    }
}

private const val ADDRESS: String = "AA:BB:CC:DD:EE:FF"
