package com.ethran.notable.claude.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger

private val log = ShipBook.getLogger("Termux")

enum class TermuxStatus { NotInstalled, PermissionNeeded, Ready }

/**
 * What Termux reports when a command it ran for us ends: its exit code and output, or, when it
 * could not run it at all, [err]/[errmsg] (for example when external apps are not allowed).
 */
data class TermuxResult(
    val executionId: Int,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val err: Int?,
    val errmsg: String?,
) {
    /** Termux could not run the command (as opposed to the command failing). */
    val termuxError: Boolean get() = !errmsg.isNullOrBlank() && err != null && err != ERR_NONE

    companion object {
        /** Termux's "no error" code (Errno.ERRNO_SUCCESS). */
        const val ERR_NONE = -1
    }
}

/** Results of commands Termux ran for us, published by [TermuxResultReceiver]. */
object TermuxResults {
    private val _results = MutableSharedFlow<TermuxResult>(replay = 8, extraBufferCapacity = 16)
    val results: SharedFlow<TermuxResult> = _results.asSharedFlow()

    fun publish(result: TermuxResult) {
        log.i("Termux result #${result.executionId}: exit=${result.exitCode} err=${result.err} ${result.errmsg ?: ""}")
        _results.tryEmit(result)
    }
}

/** Receives the PendingIntent Termux sends back once a command we started ends. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra("result")
        TermuxResults.publish(
            TermuxResult(
                executionId = id,
                exitCode = bundle?.takeIf { it.containsKey("exitCode") }?.getInt("exitCode"),
                stdout = bundle?.getString("stdout").orEmpty(),
                stderr = bundle?.getString("stderr").orEmpty(),
                err = bundle?.takeIf { it.containsKey("err") }?.getInt("err"),
                errmsg = bundle?.getString("errmsg"),
            )
        )
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "com.ethran.notable.claude.EXECUTION_ID"
    }
}

/**
 * Runs commands in Termux through its RUN_COMMAND intent
 * (https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). Needs Termux installed, the
 * "Run commands in Termux environment" permission, and `allow-external-apps = true` in
 * ~/.termux/termux.properties.
 */
class Termux(private val context: Context) {

    private val nextId = AtomicInteger((System.currentTimeMillis() % 1_000_000).toInt())

    fun status(): TermuxStatus = when {
        !isInstalled() -> TermuxStatus.NotInstalled
        ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED ->
            TermuxStatus.PermissionNeeded
        else -> TermuxStatus.Ready
    }

    private fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Starts [arguments] with Termux's bash as a background task and returns the id its
     * [TermuxResult] will carry. Throws if Android refuses to hand the command to Termux.
     */
    fun runInBackground(arguments: Array<String>, label: String): Int {
        val id = nextId.incrementAndGet()
        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
            .putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, id)
        // Mutable: Termux adds the result to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(context, id, resultIntent, flags)

        val intent = Intent()
            .setClassName(PACKAGE, "$PACKAGE.app.RunCommandService")
            .setAction("$PACKAGE.RUN_COMMAND")
            .putExtra("$PACKAGE.RUN_COMMAND_PATH", BASH)
            .putExtra("$PACKAGE.RUN_COMMAND_ARGUMENTS", arguments)
            .putExtra("$PACKAGE.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("$PACKAGE.RUN_COMMAND_BACKGROUND", true)
            .putExtra("$PACKAGE.RUN_COMMAND_COMMAND_LABEL", label)
            .putExtra("$PACKAGE.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        // Termux may not be running: its service must be allowed to start in the foreground.
        context.startForegroundService(intent)
        log.i("Started Termux command #$id ($label)")
        return id
    }

    companion object {
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val HOME = "/data/data/com.termux/files/home"
    }
}
