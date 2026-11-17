package com.ethran.notable.claude

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.claude.bridge.TermuxStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupStep(
    val name: String,
    val status: Status = Status.Waiting,
    val detail: String = "",
    val problem: ClaudeProblem? = null,
) {
    enum class Status { Waiting, Running, Ok, Failed }
}

/**
 * Settings → Claude's setup check: goes through everything Claude needs, in order, and stops at
 * the first thing that's missing, with how to fix it.
 */
@HiltViewModel
class ClaudeSetupViewModel @Inject constructor(
    private val connection: ClaudeConnection,
) : ViewModel() {

    private val initial = listOf(
        SetupStep("Termux is installed"),
        SetupStep("Notable may run commands in Termux"),
        SetupStep("Claude's helper starts in Termux"),
        SetupStep("Claude Code works"),
    )

    private val _steps = MutableStateFlow(initial)
    val steps: StateFlow<List<SetupStep>> = _steps.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    fun run() {
        job?.cancel()
        _steps.value = initial
        job = viewModelScope.launch {
            _running.value = true
            try {
                check(0) {
                    if (connection.termux.status() == TermuxStatus.NotInstalled) {
                        throw ClaudeProblemException(ClaudeProblem.TermuxNotInstalled)
                    }
                    ""
                }
                check(1) {
                    if (connection.termux.status() == TermuxStatus.PermissionNeeded) {
                        throw ClaudeProblemException(ClaudeProblem.PermissionNeeded)
                    }
                    ""
                }
                check(2) {
                    val health = connection.bridge.ensureRunning()
                    "Running (process ${health.pid})"
                }
                check(3) {
                    // The helper checks `claude --version` in the background when it starts.
                    var waited = 0
                    while (true) {
                        val claude = connection.bridge.use { it.health() }.claude
                        if (claude.checked) {
                            claude.error?.let { throw ClaudeProblemException(ClaudeProblem.ClaudeUnavailable(it)) }
                            return@check "${claude.version.orEmpty()}\n${claude.path.orEmpty()}".trim()
                        }
                        if (waited > 120_000) {
                            throw ClaudeProblemException(
                                ClaudeProblem.ClaudeUnavailable("`claude --version` didn't finish within 2 minutes.")
                            )
                        }
                        delay(500)
                        waited += 500
                    }
                    @Suppress("UNREACHABLE_CODE")
                    ""
                }
            } catch (_: StepFailed) {
                // Shown on the failed step.
            } finally {
                _running.value = false
            }
        }
    }

    /** Asks the helper to exit now (it also exits by itself when idle). */
    fun stopHelper() {
        viewModelScope.launch { connection.bridge.stop() }
    }

    private class StepFailed : Exception()

    private suspend fun check(index: Int, block: suspend () -> String) {
        update(index) { it.copy(status = SetupStep.Status.Running) }
        try {
            val detail = block()
            update(index) { it.copy(status = SetupStep.Status.Ok, detail = detail) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ClaudeProblemException) {
            update(index) { it.copy(status = SetupStep.Status.Failed, problem = e.problem) }
            throw StepFailed()
        } catch (e: Exception) {
            update(index) {
                it.copy(status = SetupStep.Status.Failed, problem = ClaudeProblem.Unexpected(e.message ?: e.toString()))
            }
            throw StepFailed()
        }
    }

    private fun update(index: Int, change: (SetupStep) -> SetupStep) =
        _steps.update { steps -> steps.mapIndexed { i, s -> if (i == index) change(s) else s } }
}
