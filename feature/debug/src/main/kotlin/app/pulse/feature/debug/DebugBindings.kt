package app.pulse.feature.debug

import app.pulse.link.LinkAssembly
import app.pulse.wire.codec.FrameRing
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DebugBindings {
    @Provides
    fun frameRing(assembly: LinkAssembly): FrameRing = assembly.frames
}
