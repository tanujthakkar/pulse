package app.pulse.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import app.pulse.metrics.LoggedActivity

/**
 * One workout the person entered.
 *
 * Sleep is not a row here. [startUnix] is inclusive and [endUnix] is exclusive.
 */
@Entity(tableName = "manual_activity")
data class ManualActivityRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    @ColumnInfo(name = "start_unix") val startUnix: Long,
    @ColumnInfo(name = "end_unix") val endUnix: Long,
)

@Dao
interface ManualActivityDao {
    @Query("SELECT * FROM manual_activity ORDER BY start_unix ASC, id ASC")
    fun all(): List<ManualActivityRow>

    @Insert
    fun insert(row: ManualActivityRow): Long

    @Update
    fun update(row: ManualActivityRow)

    @Query("DELETE FROM manual_activity WHERE id = :id")
    fun delete(id: Long)
}

interface ActivityLog {
    fun all(): List<LoggedActivity>

    fun save(id: Long?, type: String, startUnix: Long, endUnix: Long)

    fun delete(id: Long)
}

class RoomActivityLog(
    private val database: PulseDatabase,
) : ActivityLog {
    override fun all(): List<LoggedActivity> = database.activities().all().map { row ->
        LoggedActivity(id = row.id, type = row.type, startUnix = row.startUnix, endUnix = row.endUnix)
    }

    override fun save(id: Long?, type: String, startUnix: Long, endUnix: Long) {
        val row = ManualActivityRow(id = id ?: 0L, type = type, startUnix = startUnix, endUnix = endUnix)
        if (id == null) database.activities().insert(row) else database.activities().update(row)
    }

    override fun delete(id: Long) {
        database.activities().delete(id)
    }
}
