package ru.ecubz.aiunblock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TunnelState {
    data object Off : TunnelState
    data object Starting : TunnelState
    data class On(val gatewaySummary: String) : TunnelState
    data class Error(val message: String) : TunnelState
}

object TunnelStateStore {
    private val mutableState = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state = mutableState.asStateFlow()

    fun update(value: TunnelState) {
        mutableState.value = value
    }
}

