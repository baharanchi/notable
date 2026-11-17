package com.ethran.notable.claude

import android.content.Context
import com.ethran.notable.claude.bridge.BridgeApi
import com.ethran.notable.claude.bridge.BridgeLaunch
import com.ethran.notable.claude.bridge.BridgeLauncher
import com.ethran.notable.claude.bridge.BridgeScript
import com.ethran.notable.claude.bridge.ClaudeBridge
import com.ethran.notable.claude.bridge.Termux
import com.ethran.notable.claude.bridge.TermuxResult
import com.ethran.notable.claude.bridge.TermuxResults
import com.ethran.notable.claude.bridge.TermuxStatus
import com.ethran.notable.data.datastore.GlobalAppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's one connection to Claude: Termux, the bridge script it ships, and the secret token
 * that ties the bridge to this app. The token is generated once and kept in private storage, so
 * a bridge still running from earlier keeps working with this app and nothing else.
 */
@Singleton
class ClaudeConnection @Inject constructor(@ApplicationContext private val context: Context) {

    val termux = Termux(context)

    private val script by lazy {
        BridgeScript(context.assets.open(BridgeScript.ASSET_PATH).use { it.readBytes() })
    }

    private val token: String by lazy {
        val prefs = context.getSharedPreferences("claude_bridge", Context.MODE_PRIVATE)
        prefs.getString("token", null) ?: ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString("token", it).apply() }
    }

    private val launcher = object : BridgeLauncher {
        override fun termuxStatus(): TermuxStatus = termux.status()

        override fun launch(): Int = termux.runInBackground(
            BridgeLaunch.arguments(
                script = script,
                port = ClaudeDefaults.BRIDGE_PORT,
                token = token,
                claudeCommand = GlobalAppSettings.current.claudeCommand.ifBlank { ClaudeDefaults.CLAUDE_COMMAND },
                idleSeconds = ClaudeDefaults.BRIDGE_IDLE_SECONDS,
            ),
            label = "Notable: Claude helper",
        )

        override val results: Flow<TermuxResult> = TermuxResults.results
    }

    val bridge: ClaudeBridge by lazy {
        ClaudeBridge(
            client = BridgeApi(ClaudeDefaults.BRIDGE_PORT) { token },
            launcher = launcher,
            expectedVersion = script.version,
        )
    }
}
