package app.pulse.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * One strap this phone has said hello to.
 *
 * Identity blocks from the hello body are not columns. History and sync rows
 * are a later milestone.
 */
@Entity(tableName = "device")
data class DeviceRow(
    @PrimaryKey val address: String,
    val helloFirmware: String?,
    val informationFirmware: String?,
    val commandBatteryPercent: Long?,
    val serviceBatteryPercent: Int?,
    val updatedAtMillis: Long,
)

@Dao
interface DeviceDao {
    @Upsert
    fun upsert(row: DeviceRow)

    @Query("SELECT * FROM device ORDER BY updatedAtMillis DESC LIMIT 1")
    fun latest(): DeviceRow?
}

@Database(entities = [DeviceRow::class], version = 1, exportSchema = false)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun devices(): DeviceDao
}

interface DeviceDirectory {
    fun save(row: DeviceRow)

    fun latest(): DeviceRow?
}

class RoomDeviceDirectory(
    private val database: PulseDatabase,
) : DeviceDirectory {
    override fun save(row: DeviceRow) {
        database.devices().upsert(row)
    }

    override fun latest(): DeviceRow? = database.devices().latest()
}
