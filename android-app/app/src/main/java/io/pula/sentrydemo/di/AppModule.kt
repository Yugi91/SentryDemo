package io.pula.sentrydemo.di

import io.pula.sentrydemo.data.repository.PhotoWorkflowRepositoryImpl
import io.pula.sentrydemo.domain.repository.PhotoWorkflowRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindPhotoWorkflowRepository(
        impl: PhotoWorkflowRepositoryImpl,
    ): PhotoWorkflowRepository
}
