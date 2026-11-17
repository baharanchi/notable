package com.ethran.notable.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.claude.ClaudeBridgeClient
import com.ethran.notable.claude.ClaudeDefaults
import com.ethran.notable.claude.InkTextField
import com.ethran.notable.claude.PanelButton
import com.ethran.notable.claude.TermuxLauncher
import com.ethran.notable.data.datastore.AppSettings
import kotlinx.coroutines.launch

/**
 * Settings → Claude: where the Termux bridge is, and how Claude gives feedback by default.
 * Text fields are drafts saved with one button, so typing doesn't write the settings per key.
 */
@Composable
fun ClaudeSettings(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ClaudeBridgeClient() }

    var url by remember { mutableStateOf(settings.claudeBridgeUrl) }
    var token by remember { mutableStateOf(settings.claudeBridgeToken) }
    var command by remember { mutableStateOf(settings.claudeTermuxCommand) }
    var instructions by remember { mutableStateOf(settings.claudeDefaultInstructions) }
    var checkPrompt by remember { mutableStateOf(settings.claudeCheckPrompt) }
    var hintPrompt by remember { mutableStateOf(settings.claudeHintPrompt) }
    var connection by remember { mutableStateOf<String?>(null) }

    val dirty = url != settings.claudeBridgeUrl || token != settings.claudeBridgeToken ||
            command != settings.claudeTermuxCommand ||
            instructions != settings.claudeDefaultInstructions ||
            checkPrompt != settings.claudeCheckPrompt || hintPrompt != settings.claudeHintPrompt

    fun save() = onSettingsChange(
        settings.copy(
            claudeBridgeUrl = url.trim().ifEmpty { ClaudeDefaults.BRIDGE_URL },
            claudeBridgeToken = token.trim(),
            claudeTermuxCommand = command.trim().ifEmpty { ClaudeDefaults.TERMUX_COMMAND },
            claudeDefaultInstructions = instructions.trim(),
            claudeCheckPrompt = checkPrompt.trim().ifEmpty { ClaudeDefaults.CHECK_PROMPT },
            claudeHintPrompt = hintPrompt.trim().ifEmpty { ClaudeDefaults.HINT_PROMPT },
        )
    )

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        connection = if (granted) TermuxLauncher.startBridge(context) ?: "Start command sent to Termux."
        else "Permission denied."
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            "Notable talks to Claude Code running in Termux through a small bridge script " +
                    "(tools/claude-bridge in this repository). Install it once in Termux:",
            style = MaterialTheme.typography.body2,
        )
        Text(
            "pkg install python nodejs\n" +
                    "npm install -g @anthropic-ai/claude-code   # then run `claude` once to log in\n" +
                    "cd ~ && curl -LO https://github.com/baharanchi/notable/releases/download/" +
                    "claude-build-1/notable_claude_bridge.py\n" +
                    "python ~/notable_claude_bridge.py",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        SectionTitle("Bridge")
        Label("Bridge URL")
        InkTextField(url, { url = it }, ClaudeDefaults.BRIDGE_URL, singleLine = true, modifier = Modifier.fillMaxWidth())
        Label("Token (only if the bridge was started with --token)")
        InkTextField(token, { token = it }, "", singleLine = true, modifier = Modifier.fillMaxWidth())
        Label("Termux command that starts the bridge")
        InkTextField(command, { command = it }, ClaudeDefaults.TERMUX_COMMAND, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(
            Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelButton("Test connection") {
                if (dirty) save()
                connection = "Testing…"
                scope.launch {
                    connection = try {
                        val health = client.health()
                        if (health.claude == null) "Bridge is running, but it can't find the claude command."
                        else "Connected. Claude Code ${health.claudeVersion ?: ""}".trim()
                    } catch (e: Exception) {
                        e.message ?: "Failed"
                    }
                }
            }
            PanelButton("Start bridge in Termux") {
                if (dirty) save()
                if (TermuxLauncher.isTermuxInstalled(context) && !TermuxLauncher.hasPermission(context)) {
                    requestPermission.launch(TermuxLauncher.PERMISSION)
                } else {
                    connection = TermuxLauncher.startBridge(context) ?: "Start command sent to Termux."
                }
            }
        }
        connection?.let { Text(it, style = MaterialTheme.typography.body2, fontWeight = FontWeight.Bold) }
        Text(
            "Starting from Notable needs allow-external-apps = true in ~/.termux/termux.properties.",
            style = MaterialTheme.typography.caption,
        )
        SettingsDivider()

        SelectorRow(
            label = "What the snapshot shows",
            options = listOf(
                AppSettings.ClaudeCaptureMode.Visible to "Visible screen",
                AppSettings.ClaudeCaptureMode.FullPage to "Whole page",
            ),
            value = settings.claudeCaptureMode,
            onValueChange = { onSettingsChange(settings.copy(claudeCaptureMode = it)) },
        )

        SectionTitle("Default feedback instructions")
        Text(
            "Used by every notebook that has no instructions of its own (set those in the " +
                    "Claude panel → Chat → Instructions).",
            style = MaterialTheme.typography.caption,
        )
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Presets:", fontSize = 13.sp)
            ClaudeDefaults.PRESETS.forEach { (name, text) -> PanelButton(name) { instructions = text } }
        }
        InkTextField(
            instructions, { instructions = it }, "How should Claude give feedback?",
            singleLine = false, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        )
        Label("Message sent by “Check page” / the toolbar button")
        InkTextField(checkPrompt, { checkPrompt = it }, ClaudeDefaults.CHECK_PROMPT, singleLine = false, modifier = Modifier.fillMaxWidth())
        Label("Message sent by “Hint”")
        InkTextField(hintPrompt, { hintPrompt = it }, ClaudeDefaults.HINT_PROMPT, singleLine = false, modifier = Modifier.fillMaxWidth())

        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelButton(if (dirty) "Save" else "Saved", enabled = dirty) { save() }
            PanelButton("Reset to defaults") {
                url = ClaudeDefaults.BRIDGE_URL
                command = ClaudeDefaults.TERMUX_COMMAND
                instructions = ClaudeDefaults.INSTRUCTIONS
                checkPrompt = ClaudeDefaults.CHECK_PROMPT
                hintPrompt = ClaudeDefaults.HINT_PROMPT
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.h6,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
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
