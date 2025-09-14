package com.example.wheels_on_motion

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class State(
    val isRecording: Boolean = false,
)

class MainActivityViewModel: ViewModel() {


    private val _state = MutableStateFlow(State())
    val uiState: StateFlow<State> = _state.asStateFlow()

    fun setRecording(isRecording: Boolean) {
        _state.update { state -> state.copy(isRecording = isRecording) }
    }

}