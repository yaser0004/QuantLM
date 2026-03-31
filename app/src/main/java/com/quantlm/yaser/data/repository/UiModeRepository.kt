package com.quantlm.yaser.data.repository

import com.quantlm.yaser.domain.ui.UiMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiModeRepository @Inject constructor(
    private val dataStoreRepository: DataStoreRepository
) {

    val uiMode: Flow<UiMode> = dataStoreRepository.getAppSettings().map { settings ->
        if (settings.useModernUi) UiMode.MODERN else UiMode.CLASSIC
    }

    suspend fun setUiMode(mode: UiMode) {
        dataStoreRepository.setUseModernUi(mode == UiMode.MODERN)
    }
}
