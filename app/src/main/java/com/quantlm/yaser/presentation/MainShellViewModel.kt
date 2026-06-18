package com.quantlm.yaser.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.repository.UiModeRepository
import com.quantlm.yaser.domain.ui.UiMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainShellViewModel @Inject constructor(
    uiModeRepository: UiModeRepository
) : ViewModel() {

    val uiMode: StateFlow<UiMode> = uiModeRepository.uiMode
        .onEach { mode ->
            AppEventLogger.info(
                component = "MainShellViewModel",
                action = "ui_mode_changed",
                details = "mode=$mode"
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            UiMode.CLASSIC
        )
}
