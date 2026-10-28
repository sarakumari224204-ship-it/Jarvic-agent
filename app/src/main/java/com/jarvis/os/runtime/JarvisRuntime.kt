package com.jarvis.os.runtime

import com.jarvis.os.domain.agent.AgentStatus
import com.jarvis.os.domain.agent.AgentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder so the ForegroundService can publish agent/listening state
 * and the Compose UI can observe it without binding.
 */
object JarvisRuntime {
    private val _agentStatus = MutableStateFlow(AgentStatus())
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialSpeech = MutableStateFlow("")
    val partialSpeech: StateFlow<String> = _partialSpeech.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    fun updateAgentStatus(status: AgentStatus) {
        _agentStatus.value = status
    }

    fun updateListening(listening: Boolean) {
        _isListening.value = listening
    }

    fun updatePartial(text: String) {
        _partialSpeech.value = text
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) {
            _isListening.value = false
            _partialSpeech.value = ""
            _agentStatus.value = AgentStatus(state = AgentState.IDLE)
        }
    }
}
