package app.pulse

import android.content.Context
import app.pulse.data.DeviceDirectory
import app.pulse.link.LinkAssembly
import app.pulse.link.LiveHeartRateFeed
import app.pulse.link.StrapCommandPort
import app.pulse.link.StrapLink
import app.pulse.link.StrapLinks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LinkBindings {
    @Provides
    @Singleton
    fun linkAssembly(
        @ApplicationContext context: Context,
        devices: DeviceDirectory,
    ): LinkAssembly = StrapLinks.create(context, devices)

    @Provides
    fun strapLink(assembly: LinkAssembly): StrapLink = assembly.strapLink

    @Provides
    fun heartRate(assembly: LinkAssembly): LiveHeartRateFeed = assembly.heartRate

    @Provides
    fun strapCommands(assembly: LinkAssembly): StrapCommandPort = assembly.commands
}
