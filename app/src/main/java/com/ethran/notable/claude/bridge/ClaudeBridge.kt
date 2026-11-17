package com.ethran.notable.claude.bridge

import com.ethran.notable.claude.ClaudeProblem
import com.ethran.notable.claude.ClaudeProblemException
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

private val log = ShipBook.getLogger("ClaudeBridge")

/** Starts the bridge inside Termux. [TermuxBridgeLauncher] in the app, fakes in tests. */
interface BridgeLauncher {
    fun termuxStatus(): TermuxStatus

    /** Hands the start command to Termux; returns the id of the [TermuxResult] it will send. */
    fun launch(): Int

    /** Results of commands Termux ran (including our bridge when it ends). */
    val results: Flow<TermuxResult>
}

sealed interface BridgeState {
    /** Not known to be running; the next request starts it. */
    data object Stopped : BridgeState
    data object Starting : BridgeState
    data class Running(val health: BridgeHealth) : BridgeState
    data class Failed(val problem: ClaudeProblem) : BridgeState
}

/**
 * Keeps a bridge of the right version running whenever the app needs one.
 *
 * [use] checks the bridge, starts it through Termux when it isn't running (or is an older
 * version, or was started with another token), waits until it answers, and runs the request.
 * If the bridge disappears during the request (it exits when idle, or Android stopped Termux),
 * it is started again and the request repeated once — requests carry ids, so the bridge never
 * runs a repeated one twice. Failures become [ClaudeProblemException]s that say what to fix.
 */
class ClaudeBridge(
    private val client: BridgeClient,
    private val launcher: BridgeLauncher,
    private val expectedVersion: String,
    private val startTimeoutMs: Long = 20_000,
    private val pollIntervalMs: Long = 300,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<BridgeState>(BridgeState.Stopped)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    /**
     * Runs [block] against a running bridge, starting it first if needed. Once the bridge is
     * known to run, requests go straight to it; only a failed one checks it again.
     */
    suspend fun <T> use(block: suspend (BridgeClient) -> T): T {
        if (_state.value !is BridgeState.Running) ensureRunning()
        return try {
            block(client)
        } catch (e: IOException) {
            val gone = e is BridgeUnreachableException || (e is BridgeHttpException && e.code == 401)
            if (!gone) throw e
            log.w("Bridge vanished during a request, starting it again")
            _state.value = BridgeState.Stopped
            ensureRunning()
            block(client)
        }
    }

    suspend fun ensureRunning(): BridgeHealth = mutex.withLock {
        when (val probe = probe()) {
            is Probe.Up -> if (probe.health.version == expectedVersion) {
                return@withLock running(probe.health)
            } else log.i("Bridge ${probe.health.version.take(8)} is outdated, replacing it")
            Probe.Unauthorized -> log.i("Bridge answers with another token, replacing it")
            Probe.Down -> Unit
        }
        start()
    }

    /** Asks a running bridge to exit (no-op when none is running). */
    suspend fun stop() = mutex.withLock {
        runCatching { client.shutdown() }
        _state.value = BridgeState.Stopped
    }

    private suspend fun start(): BridgeHealth {
        when (launcher.termuxStatus()) {
            TermuxStatus.NotInstalled -> fail(ClaudeProblem.TermuxNotInstalled)
            TermuxStatus.PermissionNeeded -> fail(ClaudeProblem.PermissionNeeded)
            TermuxStatus.Ready -> Unit
        }
        _state.value = BridgeState.Starting
        val executionId = try {
            launcher.launch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e("Termux refused the start command", e)
            fail(ClaudeProblem.TermuxRefused(e.message ?: e::class.java.simpleName))
        }

        val started = System.nanoTime()
        return coroutineScope {
            // Termux reports back when the command ends; during startup that means it failed.
            val ended = async { launcher.results.first { it.executionId == executionId } }
            try {
                while (true) {
                    if (ended.isCompleted) fail(problemFor(ended.await()))
                    val probe = probe()
                    if (probe is Probe.Up && probe.health.version == expectedVersion) {
                        log.i("Bridge up after ${(System.nanoTime() - started) / 1_000_000} ms")
                        return@coroutineScope running(probe.health)
                    }
                    if ((System.nanoTime() - started) / 1_000_000 > startTimeoutMs) {
                        fail(ClaudeProblem.StartTimeout)
                    }
                    delay(pollIntervalMs)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } finally {
                ended.cancel()
            }
        }
    }

    private fun running(health: BridgeHealth): BridgeHealth {
        _state.value = BridgeState.Running(health)
        return health
    }

    private fun fail(problem: ClaudeProblem): Nothing {
        _state.value = BridgeState.Failed(problem)
        throw ClaudeProblemException(problem)
    }

    private sealed interface Probe {
        data class Up(val health: BridgeHealth) : Probe
        data object Unauthorized : Probe
        data object Down : Probe
    }

    private suspend fun probe(): Probe = try {
        Probe.Up(client.health())
    } catch (e: BridgeHttpException) {
        if (e.code == 401) Probe.Unauthorized else Probe.Down
    } catch (e: BridgeUnreachableException) {
        Probe.Down
    }

    companion object {
        /** What the user should know when the start command ended before the bridge answered. */
        fun problemFor(result: TermuxResult): ClaudeProblem {
            if (result.termuxError) {
                val message = result.errmsg.orEmpty()
                return if (message.contains("allow-external-apps", ignoreCase = true)) {
                    ClaudeProblem.ExternalAppsDisabled
                } else ClaudeProblem.TermuxRefused(message.trim())
            }
            val output = result.stderr.ifBlank { result.stdout }.trim().takeLast(800)
            return when {
                result.exitCode == BridgeLaunch.EXIT_NO_PYTHON -> ClaudeProblem.PythonMissing
                result.exitCode == BridgeLaunch.EXIT_CANNOT_WRITE -> ClaudeProblem.BridgeFailed(
                    "Could not write the helper into Termux's home folder.\n$output".trim()
                )
                output.contains("Address already in use") -> ClaudeProblem.BridgeFailed(
                    "Another app is using port ${com.ethran.notable.claude.ClaudeDefaults.BRIDGE_PORT}."
                )
                else -> ClaudeProblem.BridgeFailed(
                    output.ifBlank { "It exited with code ${result.exitCode ?: "?"}." }
                )
            }
        }
    }
}
