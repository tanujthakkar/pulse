package app.pulse.data

import android.content.Context
import androidx.room.Room
import app.pulse.sync.SyncStore
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
        // The device row is one short read. Callers may read it on the main
        // thread. Sync commits go through RoomSyncStore, which does not use
        // this shortcut.
        return Room.databaseBuilder(context, PulseDatabase::class.java, "pulse.db")
            .addMigrations(
                PulseMigrations.FROM_1_TO_2,
                PulseMigrations.FROM_2_TO_3,
                PulseMigrations.FROM_3_TO_4,
            )
            .allowMainThreadQueries()
            .build()
    }

    @Provides
    fun deviceDirectory(database: PulseDatabase): DeviceDirectory = RoomDeviceDirectory(database)

    @Provides
    @Singleton
    fun roomSyncStore(database: PulseDatabase): RoomSyncStore = RoomSyncStore(database)

    @Provides
    fun syncStore(store: RoomSyncStore): SyncStore = store
}
