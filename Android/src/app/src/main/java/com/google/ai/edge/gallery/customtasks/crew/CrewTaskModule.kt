package com.google.ai.edge.gallery.customtasks.crew

import android.content.Context
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal object CrewTaskModule {
  @Provides
  @IntoSet
  fun provideCrewTask(@ApplicationContext context: Context): CustomTask = CrewTask(context)
}
