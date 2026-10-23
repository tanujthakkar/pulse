package app.pulse.feature.connect

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConnectBindings {
    @Provides
    @Singleton
    fun radioProbe(@ApplicationContext context: Context): RadioProbe = AndroidRadioProbe(context)

    @Provides
    @Singleton
    fun permissionPromptMemory(
        @ApplicationContext context: Context,
    ): PermissionPromptMemory = SharedPreferencesPermissionPromptMemory(context)
}
