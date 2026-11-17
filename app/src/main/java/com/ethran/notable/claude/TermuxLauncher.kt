package com.ethran.notable.claude

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.ethran.notable.data.datastore.GlobalAppSettings
import io.shipbook.shipbooksdk.ShipBook

private val log = ShipBook.getLogger("TermuxLauncher")

/**
 * Starts the bridge through Termux's RUN_COMMAND intent
 * (https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). Needs, once:
 * - `allow-external-apps = true` in ~/.termux/termux.properties, and
 * - the "Run commands in Termux environment" permission granted to Notable
 *   (Android settings → Apps → Notable → Permissions → Additional permissions).
 */
object TermuxLauncher {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Returns null when the start command was handed to Termux, else what went wrong. */
    fun startBridge(context: Context): String? {
        if (!isTermuxInstalled(context)) return "Termux is not installed."
        if (!hasPermission(context)) {
            return "Notable may not run commands in Termux yet. Grant \"Run commands in Termux " +
                    "environment\" in Android's app settings for Notable."
        }
        val command = GlobalAppSettings.current.claudeTermuxCommand.ifBlank { ClaudeDefaults.TERMUX_COMMAND }
        // A login shell, so PATH (node, claude) and ~ resolve as in a Termux session. The bridge
        // runs as a background task of Termux; its output goes to ~/.notable-claude/bridge.log.
        val script = "mkdir -p ~/.notable-claude && " +
                "command -v termux-wake-lock >/dev/null && termux-wake-lock; " +
                "exec $command >> ~/.notable-claude/bridge.log 2>&1"
        val intent = Intent()
            .setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            .setAction("com.termux.RUN_COMMAND")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        return try {
            context.startService(intent)
            null
        } catch (e: Exception) {
            log.e("Starting the bridge in Termux failed", e)
            "Termux refused the command: ${e.message}. Is allow-external-apps = true set in " +
                    "~/.termux/termux.properties?"
        }
    }
}
