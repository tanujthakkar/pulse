package app.pulse

import android.content.Context
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
    fun strapLink(@ApplicationContext context: Context): StrapLink = StrapLinks.create(context)
}
