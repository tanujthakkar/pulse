package app.pulse.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataBindings {
    @Provides
    @Singleton
    fun pulseDatabase(@ApplicationContext context: Context): PulseDatabase {
        // One short row. Callers may read it on the main thread. Sync commits
        // are a later milestone and must not reuse this shortcut.
        return Room.databaseBuilder(context, PulseDatabase::class.java, "pulse.db")
            .allowMainThreadQueries()
            .build()
    }

    @Provides
    fun deviceDirectory(database: PulseDatabase): DeviceDirectory = RoomDeviceDirectory(database)
}
