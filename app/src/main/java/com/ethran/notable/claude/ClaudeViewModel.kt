package com.ethran.notable.claude

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.claude.bridge.BridgeHttpException
import com.ethran.notable.claude.bridge.BridgeState
import com.ethran.notable.claude.bridge.BridgeUnreachableException
import com.ethran.notable.claude.bridge.ClaudeConversation
import com.ethran.notable.claude.bridge.ClaudeEntry
import com.ethran.notable.claude.bridge.ClaudeImage
import com.ethran.notable.claude.bridge.ClaudeMessageRequest
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.editor.PageView
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import javax.inject.Inject

private val log = ShipBook.getLogger("ClaudeViewModel")

enum class ClaudeSheetTab { Chat, Files, Instructions }

data class ClaudeUiState(
    /** The docked feedback box at the bottom of the editor. */
    val isPanelVisible: Boolean = false,
    /** The sheet with chat, files and instructions. Pen input is off while it is open. */
    val isSheetOpen: Boolean = false,
    val sheetTab: ClaudeSheetTab = ClaudeSheetTab.Chat,

    /** One conversation per notebook; loose pages get their own. */
    val conversationKey: String? = null,
    val title: String = "",
    val pageInfo: String = "",

    /** As last seen from the bridge (or the app's cache of it). */
    val conversation: ClaudeConversation? = null,
    /** What the app itself is doing before the bridge takes over ("Taking a snapshot…"). */
    val activity: String? = null,
    val bridgeStarting: Boolean = false,
    /** Why Claude can't be reached, with how to fix it. */
    val problem: ClaudeProblem? = null,
    /** A one-off message (the bridge refused something). */
    val notice: String? = null,

    /** This notebook's own instructions; empty means the default from Settings applies. */
    val notebookInstructions: String = "",
) {
    val isBusy: Boolean get() = activity != null || conversation?.busy == true

    val effectiveInstructions: String
        get() = notebookInstructions.ifBlank { GlobalAppSettings.current.claudeDefaultInstructions }

    /** What the docked panel shows: the latest reply or error. */
    val latest: ClaudeEntry?
        get() = conversation?.history?.lastOrNull { it.role == "assistant" || it.role == "error" }

    val statusLine: String
        get() {
            val conv = conversation
            return when {
                bridgeStarting -> "Starting Claude…"
                activity != null -> activity
                conv?.busy == true -> conv.status.ifBlank { "Claude is thinking…" }
                problem != null -> "Needs attention"
                else -> title
            }
        }
}

/**
 * Drives the Claude panel. Every operation goes through [ClaudeConnection.bridge], which starts
 * the bridge in Termux when needed; replies are collected by polling while Claude works, and the
 * conversation is cached in the app so the panel shows it without starting anything.
 *
 * A failed operation is remembered: "Try again" repeats exactly that step — a message that
 * already reached the bridge is not sent twice (its id is kept), only waited for again.
 */
@HiltViewModel
class ClaudeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connection: ClaudeConnection,
    private val snapshotter: PageSnapshotter,
    private val cache: ClaudeConversationCache,
    private val appRepository: AppRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ClaudeUiState())
    val state: StateFlow<ClaudeUiState> = _state.asStateFlow()

    private val bridge get() = connection.bridge
    private var pollJob: Job? = null
    private var retry: (suspend () -> Unit)? = null

    init {
        viewModelScope.launch {
            bridge.state.collect { s ->
                _state.update { it.copy(bridgeStarting = s is BridgeState.Starting) }
            }
        }
    }

    /** Called by the editor whenever the shown page (or its notebook) changes. */
    fun bind(notebookId: String?, pageId: String?, pageInfo: String) {
        val key = notebookId ?: pageId?.let { "page-$it" } ?: return
        _state.update { it.copy(pageInfo = pageInfo) }
        if (key == _state.value.conversationKey) return

        pollJob?.cancel()
        retry = null
        _state.update {
            it.copy(conversationKey = key, conversation = null, problem = null, notice = null,
                notebookInstructions = "", activity = null)
        }
        viewModelScope.launch {
            val title = notebookId?.let {
                withContext(Dispatchers.IO) { appRepository.bookRepository.getById(it)?.title }
            } ?: "Quick page"
            val instructions = runCatching {
                appRepository.kvProxy.get(instructionsKey(key), String.serializer())
            }.getOrNull().orEmpty()
            val cached = cache.load(key)
            if (_state.value.conversationKey != key) return@launch
            _state.update { it.copy(title = title, notebookInstructions = instructions, conversation = cached) }
            // A reply was still on its way when we last looked: go and collect it.
            if (cached?.busy == true) startPolling()
        }
    }

    // ---- panel ----

    fun showPanel() = _state.update { it.copy(isPanelVisible = true) }

    fun hidePanel() = _state.update { it.copy(isPanelVisible = false, isSheetOpen = false) }

    fun openSheet(tab: ClaudeSheetTab = ClaudeSheetTab.Chat) =
        _state.update { it.copy(isPanelVisible = true, isSheetOpen = true, sheetTab = tab) }

    fun closeSheet() = _state.update { it.copy(isSheetOpen = false) }

    fun selectTab(tab: ClaudeSheetTab) = _state.update { it.copy(sheetTab = tab) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- messages ----

    /** The toolbar button: snapshot the page and ask for feedback with the check prompt. */
    fun checkPage(page: PageView) =
        send(page, GlobalAppSettings.current.claudeCheckPrompt, attachPage = true, kind = "check")

    fun askHint(page: PageView) =
        send(page, GlobalAppSettings.current.claudeHintPrompt, attachPage = true, kind = "hint")

    fun send(page: PageView?, text: String, attachPage: Boolean, kind: String = "chat") {
        val key = _state.value.conversationKey ?: return
        if (text.isBlank() && !attachPage) return
        _state.update { it.copy(isPanelVisible = true) }
        if (_state.value.isBusy) {
            _state.update { it.copy(notice = "Claude is still answering — wait, or cancel it first.") }
            return
        }
        val id = UUID.randomUUID().toString()
        var delivered = false
        perform(if (attachPage) "Taking a snapshot…" else "Sending…") {
            if (!delivered) {
                val images: List<ClaudeImage> = if (attachPage && page != null) {
                    snapshotter.capture(page, GlobalAppSettings.current.claudeCaptureMode)
                } else emptyList()
                _state.update { it.copy(activity = "Sending…") }
                val s = _state.value
                val request = ClaudeMessageRequest(
                    id = id,
                    text = text.trim(),
                    instructions = s.effectiveInstructions,
                    title = s.title,
                    context = if (attachPage) "Notebook “${s.title}”, page ${s.pageInfo}" else null,
                    kind = kind,
                    model = GlobalAppSettings.current.claudeModel.ifBlank { null },
                    images = images,
                )
                bridge.use { it.send(key, request) }
                delivered = true
            }
            refresh(key)
            startPolling()
        }
    }

    fun cancel() {
        val key = _state.value.conversationKey ?: return
        perform("Cancelling…") {
            bridge.use { it.cancel(key) }
            refresh(key)
            startPolling()
        }
    }

    /** Forgets the Claude session and the chat; uploaded files stay unless [keepFiles] is false. */
    fun newConversation(keepFiles: Boolean = true) {
        val key = _state.value.conversationKey ?: return
        perform("Starting over…") {
            bridge.use { it.reset(key, keepFiles) }
            refresh(key)
        }
    }

    // ---- files ----

    fun uploadFile(uri: Uri) {
        val key = _state.value.conversationKey ?: return
        val id = UUID.randomUUID().toString()
        perform("Uploading…") {
            val (name, bytes) = withContext(Dispatchers.IO) {
                val name = displayName(uri) ?: "file"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not open the file")
                name to bytes
            }
            if (bytes.size > MAX_UPLOAD_BYTES) {
                throw IllegalStateException("$name is too large (max ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB)")
            }
            bridge.use { it.upload(key, id, name, bytes) }
            refresh(key)
        }
    }

    fun deleteFile(name: String) {
        val key = _state.value.conversationKey ?: return
        perform("Deleting…") {
            bridge.use { it.deleteFile(key, name) }
            refresh(key)
        }
    }

    // ---- instructions ----

    /** Saves this notebook's own instructions; blank goes back to the default from Settings. */
    fun setNotebookInstructions(text: String) {
        val key = _state.value.conversationKey ?: return
        _state.update { it.copy(notebookInstructions = text.trim()) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appRepository.kvProxy.setKv(instructionsKey(key), text.trim(), String.serializer())
            }.onFailure { log.e("Saving Claude instructions failed", it) }
        }
    }

    // ---- problems ----

    /** Repeats the step that failed (or just reconnects). */
    fun retry() {
        val step = retry ?: suspend {
            _state.value.conversationKey?.let { refresh(it) }
            startPolling()
        }
        perform("Trying again…", step)
    }

    // ---- internals ----

    /**
     * Runs [step] with [label] as the visible activity. On failure the problem or notice is shown
     * and [step] is kept for [retry].
     */
    private fun perform(label: String, step: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(activity = label, problem = null, notice = null) }
            try {
                step()
                retry = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e, step)
            } finally {
                _state.update { it.copy(activity = null) }
            }
        }
    }

    private fun fail(e: Exception, step: suspend () -> Unit) {
        log.w("Claude step failed: ${e.message}")
        when (e) {
            is ClaudeProblemException -> {
                retry = step
                _state.update { it.copy(problem = e.problem) }
            }
            is BridgeUnreachableException -> {
                retry = step
                _state.update {
                    it.copy(problem = ClaudeProblem.BridgeFailed(
                        "Claude's helper in Termux stopped answering. Android may have paused Termux " +
                                "— exempt it from battery optimisation."
                    ))
                }
            }
            is BridgeHttpException -> _state.update { it.copy(notice = e.message) }
            else -> {
                retry = step
                _state.update { it.copy(problem = ClaudeProblem.Unexpected(e.message ?: e.toString())) }
            }
        }
    }

    private suspend fun refresh(key: String) {
        val conversation = bridge.use { it.conversation(key) }
        if (_state.value.conversationKey != key) return
        _state.update { it.copy(conversation = conversation) }
        cache.save(key, conversation)
    }

    /** Fetches the conversation every 1.5 s until Claude is done. */
    private fun startPolling() {
        val key = _state.value.conversationKey ?: return
        if (_state.value.conversation?.busy != true) return
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val poll: suspend () -> Unit = {
                while (_state.value.conversationKey == key) {
                    refresh(key)
                    if (_state.value.conversation?.busy != true) break
                    delay(POLL_INTERVAL_MS)
                }
            }
            try {
                poll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e) { poll() }
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
        const val MAX_UPLOAD_BYTES = 40 * 1024 * 1024

        fun instructionsKey(conversationKey: String) = "claude_instructions:$conversationKey"
    }
}
