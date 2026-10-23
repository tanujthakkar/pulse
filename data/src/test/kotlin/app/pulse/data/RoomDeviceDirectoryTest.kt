package app.pulse.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomDeviceDirectoryTest {
    @Test
    fun upsertsOneDeviceRowWithoutIdentityColumns() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PulseDatabase::class.java,
        ).allowMainThreadQueries().build()
        val directory = RoomDeviceDirectory(database)
        assertNull(directory.latest())

        directory.save(
            DeviceRow(
                address = "AA:BB:CC:DD:EE:FF",
                helloFirmware = "50.42.1.0",
                informationFirmware = null,
                commandBatteryPercent = null,
                serviceBatteryPercent = 81,
                updatedAtMillis = 10,
            ),
        )
        directory.save(
            DeviceRow(
                address = "AA:BB:CC:DD:EE:FF",
                helloFirmware = "50.42.1.0",
                informationFirmware = "50.42.1.0",
                commandBatteryPercent = 80,
                serviceBatteryPercent = 81,
                updatedAtMillis = 11,
            ),
        )

        val stored = directory.latest()
        assertEquals("AA:BB:CC:DD:EE:FF", stored?.address)
        assertEquals("50.42.1.0", stored?.helloFirmware)
        assertEquals("50.42.1.0", stored?.informationFirmware)
        assertEquals(80L, stored?.commandBatteryPercent)
        assertEquals(81, stored?.serviceBatteryPercent)
        assertEquals(11L, stored?.updatedAtMillis)
        database.close()
    }
}
