package io.pula.sentrydemo.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.pula.sentrydemo.domain.model.WorkflowReport
import io.pula.sentrydemo.domain.usecase.RunPhotoWorkflowUseCase
import io.pula.sentrydemo.domain.usecase.SimulateDelayUseCase
import io.pula.sentrydemo.domain.usecase.TriggerAnrUseCase
import io.pula.sentrydemo.domain.usecase.TriggerCrashUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DemoUiState(
    val busy: Boolean = false,
    val lastAction: String? = null,
    val lastReport: WorkflowReport? = null,
    val statusLog: List<String> = emptyList(),
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val simulateDelay: SimulateDelayUseCase,
    private val triggerCrash: TriggerCrashUseCase,
    private val triggerAnr: TriggerAnrUseCase,
    private val runPhotoWorkflow: RunPhotoWorkflowUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DemoUiState())
    val state: StateFlow<DemoUiState> = _state.asStateFlow()

    fun onSimulateDelay() {
        if (_state.value.busy) return
        viewModelScope.launch {
            log("delay_action: started (2 s)")
            _state.update { it.copy(busy = true, lastAction = "delay_action") }
            runCatching { simulateDelay(durationMs = 2_000L) }
                .onSuccess { log("delay_action: finished") }
                .onFailure { log("delay_action: failed (${it.message})") }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Synchronous on the main thread, by design — Sentry's uncaught handler ships it. */
    fun onTriggerCrash() {
        log("crash_action: about to throw RuntimeException…")
        triggerCrash()
    }

    /** Must be called on the main thread; blocks for 8 s to trip the ANR watchdog. */
    fun onTriggerAnr() {
        log("anr_action: blocking main thread for 8 s…")
        triggerAnr()
        log("anr_action: returned — ANR event already in flight")
    }

    fun onRunWorkflow(forceFailure: Boolean) {
        if (_state.value.busy) return
        viewModelScope.launch {
            log("photo_workflow: started${if (forceFailure) " (force fail)" else ""}")
            _state.update { it.copy(busy = true, lastAction = "photo_workflow", lastReport = null) }
            val report = runPhotoWorkflow(forceFailure = forceFailure)
            report.steps.forEach { step ->
                log("  • ${step.name} ${step.status} (${step.durationMs}ms)${step.errorMessage?.let { " — $it" } ?: ""}")
            }
            log("photo_workflow: ${if (report.ok) "ok" else "FAILED"}")
            _state.update { it.copy(busy = false, lastReport = report) }
        }
    }

    private fun log(line: String) {
        _state.update { current ->
            current.copy(statusLog = (current.statusLog + line).takeLast(120))
        }
    }
}
