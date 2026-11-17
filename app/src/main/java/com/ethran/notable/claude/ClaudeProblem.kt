package com.ethran.notable.claude

/** One way to fix a [ClaudeProblem], offered as a button. */
sealed interface ProblemAction {
    val label: String

    data class CopyCommand(val command: String, override val label: String = "Copy command") : ProblemAction
    data object RequestPermission : ProblemAction { override val label = "Allow" }
    data object OpenTermux : ProblemAction { override val label = "Open Termux" }
    data class OpenUrl(val url: String, override val label: String) : ProblemAction
    data object Retry : ProblemAction { override val label = "Try again" }
}

/**
 * Something that keeps Notable from reaching Claude, explained for the user, with what to do.
 * Raised as [ClaudeProblemException] by [ClaudeBridge].
 */
sealed class ClaudeProblem(
    val title: String,
    val message: String,
    val actions: List<ProblemAction>,
) {
    data object TermuxNotInstalled : ClaudeProblem(
        "Termux is not installed",
        "Claude runs inside Termux. Install Termux from F-Droid (not the Play Store version, " +
                "which is outdated), open it once, then set it up with the command below.",
        listOf(
            ProblemAction.OpenUrl(ClaudeDefaults.TERMUX_DOWNLOAD_URL, "Get Termux"),
            ProblemAction.CopyCommand(ClaudeDefaults.TERMUX_SETUP_COMMAND, "Copy setup command"),
            ProblemAction.Retry,
        ),
    )

    data object PermissionNeeded : ClaudeProblem(
        "Notable needs permission to use Termux",
        "Allow Notable to run commands in Termux, so it can start Claude for you.",
        listOf(ProblemAction.RequestPermission),
    )

    data object ExternalAppsDisabled : ClaudeProblem(
        "Termux doesn't accept commands from Notable yet",
        "Open Termux and run the copied command once (it sets allow-external-apps = true).",
        listOf(
            ProblemAction.CopyCommand(ClaudeDefaults.ALLOW_EXTERNAL_APPS_COMMAND),
            ProblemAction.OpenTermux,
            ProblemAction.Retry,
        ),
    )

    class TermuxRefused(detail: String) : ClaudeProblem(
        "Termux could not run the command",
        "$detail\n\nIf Termux was just installed, open it once so it can finish setting up.",
        listOf(ProblemAction.OpenTermux, ProblemAction.Retry),
    )

    data object PythonMissing : ClaudeProblem(
        "Python is missing in Termux",
        "Notable's helper runs on Python. Install it in Termux with the copied command.",
        listOf(
            ProblemAction.CopyCommand("pkg install -y python"),
            ProblemAction.OpenTermux,
            ProblemAction.Retry,
        ),
    )

    class BridgeFailed(detail: String) : ClaudeProblem(
        "Claude's helper stopped",
        detail.ifBlank { "It ended without saying why." },
        listOf(ProblemAction.Retry),
    )

    data object StartTimeout : ClaudeProblem(
        "Claude's helper didn't start",
        "Termux didn't start it within 20 seconds. Check that Termux is set up (the copied " +
                "command does everything), that it isn't restricted by battery saving, then try again.",
        listOf(
            ProblemAction.CopyCommand(ClaudeDefaults.TERMUX_SETUP_COMMAND, "Copy setup command"),
            ProblemAction.OpenTermux,
            ProblemAction.Retry,
        ),
    )

    class ClaudeUnavailable(detail: String) : ClaudeProblem(
        "Claude Code doesn't work in Termux",
        "$detail\n\nInstall it with the copied command, then run `claude` in Termux once to log in.",
        listOf(
            ProblemAction.CopyCommand("npm install -g @anthropic-ai/claude-code && claude"),
            ProblemAction.OpenTermux,
            ProblemAction.Retry,
        ),
    )

    class Unexpected(detail: String) : ClaudeProblem(
        "Something went wrong",
        detail,
        listOf(ProblemAction.Retry),
    )
}

class ClaudeProblemException(val problem: ClaudeProblem) : Exception(problem.title)
