package com.ethran.notable.claude.bridge

import java.security.MessageDigest
import java.util.Base64

/**
 * The bridge script Notable ships (assets/claude/bridge.py). Every launch hands it to Termux,
 * so Termux always runs the copy that matches this app; [version] is what the running bridge
 * reports from /health, to spot an older one left running from a previous app version.
 */
class BridgeScript(bytes: ByteArray) {
    val version: String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    val base64: String = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val ASSET_PATH = "claude/bridge.py"
    }
}

/**
 * The shell command Termux runs to (re)start the bridge. Run as `bash -lc SCRIPT name ARGS`:
 * $1 is the bridge script in base64, the rest are the bridge's own arguments. It stops a bridge
 * still running from before (an older version, or one with a stale token), writes the script,
 * and replaces itself with Python running it.
 */
object BridgeLaunch {
    const val EXIT_CANNOT_WRITE = 90
    const val EXIT_NO_PYTHON = 91

    val SCRIPT = """
        d="${'$'}HOME/.notable-claude"
        mkdir -p "${'$'}d" || exit $EXIT_CANNOT_WRITE
        old=${'$'}(cat "${'$'}d/bridge.pid" 2>/dev/null)
        if [ -n "${'$'}old" ] && grep -qa bridge.py "/proc/${'$'}old/cmdline" 2>/dev/null; then
          kill "${'$'}old" 2>/dev/null
        fi
        if ! printf '%s' "${'$'}1" | base64 -d > "${'$'}d/bridge.py.new" 2>/dev/null; then
          echo "Could not write ${'$'}d/bridge.py" >&2; exit $EXIT_CANNOT_WRITE
        fi
        mv -f "${'$'}d/bridge.py.new" "${'$'}d/bridge.py" || exit $EXIT_CANNOT_WRITE
        shift
        py=${'$'}(command -v python3 || command -v python) || {
          echo "Python is not installed in Termux. Run: pkg install python" >&2; exit $EXIT_NO_PYTHON
        }
        exec "${'$'}py" "${'$'}d/bridge.py" "${'$'}@"
    """.trimIndent()

    /** Arguments for `bash`: everything after the executable. */
    fun arguments(
        script: BridgeScript,
        port: Int,
        token: String,
        claudeCommand: String,
        idleSeconds: Int,
    ): Array<String> = arrayOf(
        "-lc", SCRIPT, "notable-claude-bridge", script.base64,
        "--port", port.toString(),
        "--token", token,
        "--claude", claudeCommand,
        "--idle-timeout", idleSeconds.toString(),
    )
}
