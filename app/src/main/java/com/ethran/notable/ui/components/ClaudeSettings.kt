package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.claude.ClaudeDefaults
import com.ethran.notable.claude.ClaudeSetupViewModel
import com.ethran.notable.claude.InkTextField
import com.ethran.notable.claude.PanelButton
import com.ethran.notable.claude.ProblemCard
import com.ethran.notable.claude.SetupStep
import com.ethran.notable.claude.copyText
import com.ethran.notable.data.datastore.AppSettings

/**
 * Settings → Claude: a setup check that walks through everything Claude needs in Termux, and
 * how Claude gives feedback by default. Text fields are drafts saved with one button, so typing
 * doesn't write the settings on every key.
 */
@Composable
fun ClaudeSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    setup: ClaudeSetupViewModel = hiltViewModel(),
) {
    var instructions by remember { mutableStateOf(settings.claudeDefaultInstructions) }
    var checkPrompt by remember { mutableStateOf(settings.claudeCheckPrompt) }
    var hintPrompt by remember { mutableStateOf(settings.claudeHintPrompt) }
    var command by remember { mutableStateOf(settings.claudeCommand) }

    val dirty = instructions != settings.claudeDefaultInstructions ||
            checkPrompt != settings.claudeCheckPrompt || hintPrompt != settings.claudeHintPrompt ||
            command != settings.claudeCommand

    fun save() = onSettingsChange(
        settings.copy(
            claudeDefaultInstructions = instructions.trim(),
            claudeCheckPrompt = checkPrompt.trim().ifEmpty { ClaudeDefaults.CHECK_PROMPT },
            claudeHintPrompt = hintPrompt.trim().ifEmpty { ClaudeDefaults.HINT_PROMPT },
            claudeCommand = command.trim().ifEmpty { ClaudeDefaults.CLAUDE_COMMAND },
        )
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        SectionTitle("Set up")
        Text(
            "Claude runs as Claude Code inside Termux, on this tablet. Notable starts it when " +
                    "needed and stops it when idle. Once, in Termux, run:",
            style = MaterialTheme.typography.body2,
        )
        SetupCommand()
        SetupCheck(setup)
        SettingsDivider()

        SectionTitle("Feedback")
        SelectorRow(
            label = "What the snapshot shows",
            options = listOf(
                AppSettings.ClaudeCaptureMode.Visible to "Visible screen",
                AppSettings.ClaudeCaptureMode.FullPage to "Whole page",
            ),
            value = settings.claudeCaptureMode,
            onValueChange = { onSettingsChange(settings.copy(claudeCaptureMode = it)) },
        )
        SelectorRow(
            label = "Model",
            options = ClaudeDefaults.MODELS,
            value = settings.claudeModel,
            onValueChange = { onSettingsChange(settings.copy(claudeModel = it)) },
        )

        Label("Default instructions (notebooks without their own; set those in the Claude panel → Chat → Instructions)")
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Presets:", fontSize = 13.sp)
            ClaudeDefaults.PRESETS.forEach { (name, text) -> PanelButton(name) { instructions = text } }
        }
        InkTextField(
            instructions, { instructions = it }, "How should Claude give feedback?",
            singleLine = false, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        )
        Label("Message sent by “Check page” and the toolbar button")
        InkTextField(checkPrompt, { checkPrompt = it }, ClaudeDefaults.CHECK_PROMPT, singleLine = false,
            modifier = Modifier.fillMaxWidth())
        Label("Message sent by “Hint”")
        InkTextField(hintPrompt, { hintPrompt = it }, ClaudeDefaults.HINT_PROMPT, singleLine = false,
            modifier = Modifier.fillMaxWidth())
        Label("Claude Code command in Termux (a name on PATH, or a full path)")
        InkTextField(command, { command = it }, ClaudeDefaults.CLAUDE_COMMAND, singleLine = true,
            modifier = Modifier.fillMaxWidth())

        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton(if (dirty) "Save" else "Saved", enabled = dirty) { save() }
            PanelButton("Reset to defaults") {
                instructions = ClaudeDefaults.INSTRUCTIONS
                checkPrompt = ClaudeDefaults.CHECK_PROMPT
                hintPrompt = ClaudeDefaults.HINT_PROMPT
                command = ClaudeDefaults.CLAUDE_COMMAND
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupCommand() {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Text(
        ClaudeDefaults.TERMUX_SETUP_COMMAND,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 6.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        PanelButton(if (copied) "Copied" else "Copy command") {
            copyText(context, ClaudeDefaults.TERMUX_SETUP_COMMAND)
            copied = true
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "It installs Python, Node and Claude Code, lets Notable start commands, and opens " +
                    "Claude so you can log in. Install Termux from F-Droid, not the Play Store.",
            style = MaterialTheme.typography.caption,
        )
    }
}

@Composable
private fun SetupCheck(setup: ClaudeSetupViewModel) {
    val steps by setup.steps.collectAsStateWithLifecycle()
    val running by setup.running.collectAsStateWithLifecycle()

    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelButton(if (running) "Checking…" else "Check setup", enabled = !running) { setup.run() }
        PanelButton("Stop Claude's helper", enabled = !running) { setup.stopHelper() }
    }
    steps.forEach { step ->
        val mark = when (step.status) {
            SetupStep.Status.Waiting -> "○"
            SetupStep.Status.Running -> "…"
            SetupStep.Status.Ok -> "✓"
            SetupStep.Status.Failed -> "✗"
        }
        Row(Modifier.padding(top = 6.dp)) {
            Text(mark, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
            Column {
                Text(step.name, style = MaterialTheme.typography.body2)
                if (step.detail.isNotBlank()) {
                    Text(step.detail, style = MaterialTheme.typography.caption, fontFamily = FontFamily.Monospace)
                }
                step.problem?.let {
                    ProblemCard(it, onRetry = { setup.run() }, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.h6, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}
