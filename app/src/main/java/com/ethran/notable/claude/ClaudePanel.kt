package com.ethran.notable.claude

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.PageView
import com.ethran.notable.ui.noRippleClickable
import java.text.DateFormat
import java.util.Date

private val PANEL_BODY_HEIGHT = 150.dp
private val TEXT_SIZE = 15.sp

/**
 * Everything Claude in the editor: the docked feedback box at the bottom (above a bottom
 * toolbar) and, when opened, the sheet with the chat, uploaded files and instructions.
 *
 * [onPanelHeightPx] reports the docked box's height so pen input can leave it alone;
 * [onSheetOpenChanged] lets the editor turn pen input off while the sheet covers the page.
 */
@Composable
fun ClaudeOverlay(
    viewModel: ClaudeViewModel,
    page: PageView,
    bottomInset: Dp,
    onPanelHeightPx: (Int) -> Unit,
    onSheetOpenChanged: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isPanelVisible) { if (!state.isPanelVisible) onPanelHeightPx(0) }
    LaunchedEffect(state.isSheetOpen) { onSheetOpenChanged(state.isSheetOpen) }

    if (state.isPanelVisible && !state.isSheetOpen) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            DockedPanel(
                state = state,
                viewModel = viewModel,
                page = page,
                modifier = Modifier.onSizeChanged { onPanelHeightPx(it.height) },
            )
            Spacer(Modifier.height(bottomInset))
        }
    }

    if (state.isSheetOpen) {
        Column(Modifier.fillMaxSize()) {
            // The page stays visible above the sheet, but it does not take pen input meanwhile.
            Spacer(Modifier.weight(0.35f))
            ClaudeSheet(
                state = state,
                viewModel = viewModel,
                page = page,
                modifier = Modifier.weight(0.65f),
            )
            Spacer(Modifier.height(bottomInset))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Docked panel
// ---------------------------------------------------------------------------------------------

@Composable
private fun DockedPanel(
    state: ClaudeUiState,
    viewModel: ClaudeViewModel,
    page: PageView,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .blockTouchesBelow()
            .background(Color.White)
            .border(1.dp, Color.Black)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Claude", fontWeight = FontWeight.Bold, fontSize = TEXT_SIZE)
            Spacer(Modifier.width(10.dp))
            Text(
                statusLine(state),
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (state.isBusy) {
                PanelButton("Cancel") { viewModel.cancel() }
            } else {
                PanelButton("Check page") { viewModel.checkPage(page) }
                PanelButton("Hint") { viewModel.askHint(page) }
            }
            PanelButton("Chat") { viewModel.openSheet(ClaudeSheetTab.Chat) }
            PanelButton("✕") { viewModel.hidePanel() }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.Black))
        Box(
            Modifier
                .fillMaxWidth()
                .height(PANEL_BODY_HEIGHT)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            PanelBody(state, viewModel)
        }
    }
}

@Composable
private fun PanelBody(state: ClaudeUiState, viewModel: ClaudeViewModel) {
    val scroll = rememberScrollState()
    val partial = state.conversation?.partial.orEmpty()
    val latest = state.latest

    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        when {
            state.error != null -> {
                Text(state.error, fontSize = TEXT_SIZE, color = Color.Black, fontWeight = FontWeight.Bold)
                if (state.bridgeUnreachable) {
                    Spacer(Modifier.height(6.dp))
                    BridgeHelp(viewModel)
                } else {
                    Spacer(Modifier.height(6.dp))
                    PanelButton("OK") { viewModel.dismissError() }
                }
            }

            partial.isNotBlank() -> Text(renderMarkdown(partial), fontSize = TEXT_SIZE)

            latest != null -> {
                Text(
                    renderMarkdown(latest.text),
                    fontSize = TEXT_SIZE,
                    fontWeight = if (latest.role == "error") FontWeight.Bold else FontWeight.Normal,
                )
            }

            else -> Text(
                "Write on the page, then tap “Check page” (or the Claude button in the toolbar) " +
                        "to get feedback. Set how Claude should answer under Chat → Instructions.",
                fontSize = TEXT_SIZE,
                fontStyle = FontStyle.Italic,
            )
        }
    }
    // New text replaces the old: start reading at the top.
    LaunchedEffect(latest?.id) { scroll.scrollTo(0) }
}

@Composable
private fun BridgeHelp(viewModel: ClaudeViewModel) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        message = if (granted) viewModel.startBridgeInTermux() ?: "Starting… tap Retry in a few seconds."
        else "Permission denied. You can also start the bridge yourself in Termux."
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PanelButton("Start bridge in Termux") {
            if (!TermuxLauncher.hasPermission(context) && TermuxLauncher.isTermuxInstalled(context)) {
                requestPermission.launch(TermuxLauncher.PERMISSION)
            } else {
                message = viewModel.startBridgeInTermux() ?: "Starting… tap Retry in a few seconds."
            }
        }
        PanelButton("Retry") { message = null; viewModel.retry() }
    }
    Text(
        message ?: "Or run in Termux:  ${GlobalAppSettings.current.claudeTermuxCommand}",
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun statusLine(state: ClaudeUiState): String {
    val conv = state.conversation
    return when {
        state.isSending -> "Sending…"
        conv?.busy == true -> conv.status.ifBlank { "Claude is thinking…" }
        state.error != null -> "Problem"
        else -> state.title
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet: chat, files, instructions
// ---------------------------------------------------------------------------------------------

@Composable
private fun ClaudeSheet(
    state: ClaudeUiState,
    viewModel: ClaudeViewModel,
    page: PageView,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .blockTouchesBelow()
            .background(Color.White)
            .border(1.dp, Color.Black)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Claude · ${state.title}",
                fontWeight = FontWeight.Bold,
                fontSize = TEXT_SIZE,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ClaudeSheetTab.entries.forEach { tab ->
                PanelButton(tab.name, selected = state.sheetTab == tab) { viewModel.selectTab(tab) }
            }
            Spacer(Modifier.width(8.dp))
            PanelButton("Close") { viewModel.closeSheet() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
            when (state.sheetTab) {
                ClaudeSheetTab.Chat -> ChatTab(state, viewModel, page)
                ClaudeSheetTab.Files -> FilesTab(state, viewModel)
                ClaudeSheetTab.Instructions -> InstructionsTab(state, viewModel)
            }
        }
    }
}

@Composable
private fun ChatTab(state: ClaudeUiState, viewModel: ClaudeViewModel, page: PageView) {
    var draft by rememberSaveable { mutableStateOf("") }
    var attachPage by rememberSaveable { mutableStateOf(true) }
    val history = state.conversation?.history.orEmpty()
    val partial = state.conversation?.partial.orEmpty()
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        // Input on top: the on-screen keyboard covers the bottom of the screen.
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Message Claude… (e.g. “give me a hint”)",
                singleLine = false,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 120.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column {
                PanelButton(if (attachPage) "☑ page" else "☐ page") { attachPage = !attachPage }
                if (state.isBusy) {
                    PanelButton("Cancel") { viewModel.cancel() }
                } else {
                    PanelButton("Send", enabled = draft.isNotBlank() || attachPage) {
                        viewModel.send(page, draft, attachPage)
                        draft = ""
                    }
                }
            }
        }
        state.error?.let {
            Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            if (state.bridgeUnreachable) BridgeHelp(viewModel)
        }
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                statusLine(state).takeIf { state.isBusy }.orEmpty(),
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.weight(1f),
            )
            PanelButton("New conversation", enabled = !state.isBusy && history.isNotEmpty()) {
                viewModel.newConversation(keepFiles = true)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.Black))

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (history.isEmpty() && partial.isBlank()) {
                item {
                    Text(
                        "No messages yet. Claude sees a snapshot of the page when “☑ page” is on.",
                        fontSize = TEXT_SIZE,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(history, key = { it.id.ifEmpty { it.time.toString() } }) { entry -> ChatEntry(entry) }
            if (partial.isNotBlank()) {
                item(key = "partial") {
                    ChatEntry(ClaudeEntry(role = "assistant", text = partial))
                }
            }
        }
    }
    LaunchedEffect(history.size, partial.length) {
        // Same items as the list above: the placeholder, the history, the reply being written.
        val count = history.size + (if (partial.isNotBlank()) 1 else 0) +
                (if (history.isEmpty() && partial.isBlank()) 1 else 0)
        if (count > 0) listState.scrollToItem(count - 1)
    }
}

@Composable
private fun ChatEntry(entry: ClaudeEntry) {
    val context = LocalContext.current
    val time = if (entry.time > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.time)) else ""
    when (entry.role) {
        "event" -> Text(
            "— ${entry.text} $time",
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        else -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            val who = when (entry.role) {
                "user" -> "You"
                "error" -> "Error"
                else -> "Claude"
            }
            val attachments = if (entry.images.isNotEmpty()) " · 📄 page snapshot" else ""
            Text("$who  $time$attachments", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val body = entry.text.ifBlank { if (entry.images.isNotEmpty()) "(page only)" else "" }
            Text(
                renderMarkdown(body),
                fontSize = TEXT_SIZE,
                modifier = Modifier
                    .then(
                        if (entry.role == "assistant") Modifier.noRippleClickable { copyText(context, entry.text) }
                        else Modifier
                    ),
            )
        }
    }
}

@Composable
private fun FilesTab(state: ClaudeUiState, viewModel: ClaudeViewModel) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::uploadFile)
    }
    val files = state.conversation?.files.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Files Claude can read in this notebook (exercise sheets, notes…). " +
                        "PDFs, images and text work best.",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            PanelButton(if (state.isSending) "Uploading…" else "Upload file", enabled = !state.isSending) {
                pick.launch(arrayOf("*/*"))
            }
        }
        state.error?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            if (files.isEmpty()) {
                item { Text("No files yet.", fontSize = TEXT_SIZE, fontStyle = FontStyle.Italic) }
            }
            items(files, key = { it.name }) { file ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(file.name, fontSize = TEXT_SIZE, modifier = Modifier.weight(1f))
                    Text("${(file.size + 1023) / 1024} KB", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    PanelButton("Delete") { viewModel.deleteFile(file.name) }
                }
            }
            state.conversation?.let { conv ->
                if (conv.folder.isNotBlank()) {
                    item {
                        Text(
                            "In Termux: ${conv.folder}" +
                                    (conv.sessionId?.let { "\nResume by hand: claude --resume $it" } ?: ""),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionsTab(state: ClaudeUiState, viewModel: ClaudeViewModel) {
    val default = GlobalAppSettings.current.claudeDefaultInstructions
    var draft by remember(state.conversationKey, state.notebookInstructions) {
        mutableStateOf(state.notebookInstructions.ifBlank { default })
    }
    val usesDefault = state.notebookInstructions.isBlank()
    val dirty = draft.trim() != state.effectiveInstructions.trim()

    Column(Modifier.fillMaxSize()) {
        Text(
            "How Claude should give feedback in this notebook. Sent with every message, so " +
                    "changes apply right away." +
                    if (usesDefault) " (Currently the default from Settings → Claude.)" else "",
            fontSize = 13.sp,
        )
        Row(
            Modifier.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Presets:", fontSize = 13.sp)
            ClaudeDefaults.PRESETS.forEach { (name, text) -> PanelButton(name) { draft = text } }
        }
        InkTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "e.g. Never reveal the answer. Only tell me if I'm right or wrong. " +
                    "Give hints only when I ask.",
            singleLine = false,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelButton("Save for this notebook", enabled = dirty || usesDefault) {
                viewModel.setNotebookInstructions(draft)
            }
            PanelButton("Use default", enabled = !usesDefault) {
                viewModel.setNotebookInstructions("")
                draft = default
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Small e-ink widgets
// ---------------------------------------------------------------------------------------------

@Composable
internal fun PanelButton(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(2.dp)
            .background(if (selected) Color.Black else Color.White)
            .border(1.dp, if (enabled) Color.Black else Color.Gray)
            .then(if (enabled) Modifier.noRippleClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = when {
                selected -> Color.White
                enabled -> Color.Black
                else -> Color.Gray
            },
        )
    }
}

@Composable
internal fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Color.White)
            .border(1.dp, Color.Black)
            .padding(8.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Color.Gray, fontSize = TEXT_SIZE)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = TEXT_SIZE, color = Color.Black),
            cursorBrush = SolidColor(Color.Black),
            modifier = Modifier.fillMaxWidth().then(if (singleLine) Modifier else Modifier.fillMaxHeight()),
        )
    }
}

/** Finger touches on the panel must not reach the page's gestures (swipes, scroll) below it. */
private fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Claude", text))
}

/**
 * Just enough Markdown for chat replies on e-ink: **bold**, *italic*, `code`, headings and
 * bullet lists. Everything else is shown as written.
 */
internal fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.trimEnd().lines()
    lines.forEachIndexed { index, rawLine ->
        var line = rawLine
        val heading = Regex("^#{1,6}\\s+").find(line)
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(line.substring(heading.range.last + 1)) }
        } else {
            val bullet = Regex("^(\\s*)[-*+]\\s+").find(line)
            if (bullet != null) {
                append(bullet.groupValues[1])
                append("• ")
                line = line.substring(bullet.range.last + 1)
            }
            appendInline(line)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")

private fun AnnotatedString.Builder.appendInline(line: String) {
    var last = 0
    for (match in INLINE.findAll(line)) {
        append(line.substring(last, match.range.first))
        val (bold, boldUnderscore, code, italic) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            boldUnderscore.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldUnderscore) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        last = match.range.last + 1
    }
    append(line.substring(last))
}
