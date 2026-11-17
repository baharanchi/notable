package com.ethran.notable.claude.bridge

import com.ethran.notable.claude.ClaudeProblem
import com.ethran.notable.claude.ClaudeProblemException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClaudeBridgeTest {

    private val version = "v-current"

    /** A bridge that is down until [startsAfterProbes] health checks after a launch. */
    private class FakeClient(var health: () -> BridgeHealth) : BridgeClient {
        var conversationCalls = 0
        var failNextConversation = false

        override suspend fun health(): BridgeHealth = health.invoke()
        override suspend fun conversation(key: String): ClaudeConversation {
            conversationCalls++
            if (failNextConversation) {
                failNextConversation = false
                throw BridgeUnreachableException(java.io.IOException("gone"))
            }
            return ClaudeConversation(key = key)
        }
        override suspend fun send(key: String, request: ClaudeMessageRequest) = Unit
        override suspend fun upload(key: String, id: String, name: String, bytes: ByteArray) = Unit
        override suspend fun deleteFile(key: String, name: String) = Unit
        override suspend fun cancel(key: String) = Unit
        override suspend fun reset(key: String, keepFiles: Boolean) = Unit
        override suspend fun shutdown() = Unit
    }

    private class FakeLauncher(var status: TermuxStatus = TermuxStatus.Ready) : BridgeLauncher {
        var launches = 0
        var onLaunch: (Int) -> Unit = {}
        override val results = MutableSharedFlow<TermuxResult>(replay = 8)
        override fun termuxStatus() = status
        override fun launch(): Int {
            launches++
            onLaunch(launches)
            return launches
        }
    }

    private fun up(v: String = version) = BridgeHealth(ok = true, version = v, pid = 1)
    private fun down(): Nothing = throw BridgeUnreachableException(java.io.IOException("refused"))

    private fun bridge(client: FakeClient, launcher: FakeLauncher, timeoutMs: Long = 2_000) =
        ClaudeBridge(client, launcher, version, startTimeoutMs = timeoutMs, pollIntervalMs = 10)

    private fun problemOf(block: suspend () -> Unit): ClaudeProblem = runBlocking {
        try {
            block()
            fail("expected a problem")
            throw AssertionError()
        } catch (e: ClaudeProblemException) {
            e.problem
        }
    }

    @Test
    fun `a running bridge of the right version is used as is`() = runBlocking {
        val launcher = FakeLauncher()
        bridge(FakeClient { up() }, launcher).ensureRunning()
        assertEquals(0, launcher.launches)
    }

    @Test
    fun `a stopped bridge is started and waited for`() = runBlocking {
        var started = false
        var probesAfterStart = 0
        val client = FakeClient { if (started && ++probesAfterStart > 3) up() else down() }
        val launcher = FakeLauncher().apply { onLaunch = { started = true } }
        val b = bridge(client, launcher)
        b.ensureRunning()
        assertEquals(1, launcher.launches)
        assertTrue(b.state.value is BridgeState.Running)
    }

    @Test
    fun `an outdated or foreign bridge is replaced`() = runBlocking {
        var started = false
        val client = FakeClient { if (started) up() else up("v-old") }
        val launcher = FakeLauncher().apply { onLaunch = { started = true } }
        bridge(client, launcher).ensureRunning()
        assertEquals(1, launcher.launches)

        var restarted = false
        val foreign = FakeClient { if (restarted) up() else throw BridgeHttpException(401, "wrong token") }
        val launcher2 = FakeLauncher().apply { onLaunch = { restarted = true } }
        bridge(foreign, launcher2).ensureRunning()
        assertEquals(1, launcher2.launches)
    }

    @Test
    fun `missing Termux or permission is reported without launching`() {
        val launcher = FakeLauncher(TermuxStatus.NotInstalled)
        assertSame(ClaudeProblem.TermuxNotInstalled, problemOf { bridge(FakeClient { down() }, launcher).ensureRunning() })
        launcher.status = TermuxStatus.PermissionNeeded
        assertSame(ClaudeProblem.PermissionNeeded, problemOf { bridge(FakeClient { down() }, launcher).ensureRunning() })
        assertEquals(0, launcher.launches)
    }

    @Test
    fun `a start command that ends early explains why`() {
        val launcher = FakeLauncher()
        launcher.onLaunch = { id ->
            launcher.results.tryEmit(TermuxResult(id, BridgeLaunch.EXIT_NO_PYTHON, "", "no python", -1, null))
        }
        val b = bridge(FakeClient { down() }, launcher)
        assertSame(ClaudeProblem.PythonMissing, problemOf { b.ensureRunning() })
        assertTrue(b.state.value is BridgeState.Failed)
    }

    @Test
    fun `a result for another command is ignored`() = runBlocking {
        var started = false
        var probes = 0
        val launcher = FakeLauncher()
        launcher.onLaunch = { id ->
            started = true
            launcher.results.tryEmit(TermuxResult(id + 100, 1, "", "unrelated", -1, null))
        }
        bridge(FakeClient { if (started && ++probes > 2) up() else down() }, launcher).ensureRunning()
        assertEquals(1, launcher.launches)
    }

    @Test
    fun `a bridge that never answers times out`() {
        assertSame(
            ClaudeProblem.StartTimeout,
            problemOf { bridge(FakeClient { down() }, FakeLauncher(), timeoutMs = 100).ensureRunning() },
        )
    }

    @Test
    fun `a request is repeated once after the bridge vanished`() = runBlocking {
        val client = FakeClient { up() }.apply { failNextConversation = true }
        val launcher = FakeLauncher()
        bridge(client, launcher).use { it.conversation("nb") }
        assertEquals(2, client.conversationCalls)
    }

    @Test
    fun `once running, requests skip the health check until one fails`() = runBlocking {
        var healthChecks = 0
        val client = FakeClient { healthChecks++; up() }
        val b = bridge(client, FakeLauncher())
        repeat(3) { b.use { it.conversation("nb") } }
        assertEquals(1, healthChecks)
        client.failNextConversation = true
        b.use { it.conversation("nb") }
        assertEquals(2, healthChecks)
    }

    @Test
    fun `termux results map to what the user should do`() {
        fun result(exit: Int?, stderr: String = "", err: Int? = -1, errmsg: String? = null) =
            TermuxResult(1, exit, "", stderr, err, errmsg)

        assertSame(
            ClaudeProblem.ExternalAppsDisabled,
            ClaudeBridge.problemFor(result(null, err = 2, errmsg = "RunCommandService requires allow-external-apps property to be set to true"))
        )
        assertTrue(ClaudeBridge.problemFor(result(null, err = 2, errmsg = "bootstrap missing")) is ClaudeProblem.TermuxRefused)
        assertSame(ClaudeProblem.PythonMissing, ClaudeBridge.problemFor(result(BridgeLaunch.EXIT_NO_PYTHON)))
        assertTrue(
            ClaudeBridge.problemFor(result(1, stderr = "OSError: [Errno 98] Address already in use")).message
                .contains("port")
        )
        val crash = ClaudeBridge.problemFor(result(1, stderr = "Traceback: boom"))
        assertTrue(crash is ClaudeProblem.BridgeFailed && crash.message.contains("boom"))
        // err = -1 with a message is not a Termux error.
        assertTrue(ClaudeBridge.problemFor(result(3, err = -1, errmsg = "x")) is ClaudeProblem.BridgeFailed)
    }
}
