package com.ethran.notable.claude

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import javax.inject.Inject

private val log = ShipBook.getLogger("ClaudeViewModel")

enum class ClaudeSheetTab { Chat, Files, Instructions }

data class ClaudeUiState(
    /** The docked feedback box at the bottom of the editor. */
    val isPanelVisible: Boolean = false,
    /** The full chat sheet (chat, files, instructions). Pen input is off while it is open. */
    val isSheetOpen: Boolean = false,
    val sheetTab: ClaudeSheetTab = ClaudeSheetTab.Chat,

    /** One conversation per notebook; loose pages get their own. */
    val conversationKey: String? = null,
    val title: String = "",
    val pageInfo: String = "",

    val conversation: ClaudeConversation? = null,
    /** Set while the app is capturing/uploading, before the bridge reports busy. */
    val isSending: Boolean = false,
    /** Last failure talking to the bridge, shown in the panel. */
    val error: String? = null,
    val bridgeUnreachable: Boolean = false,

    /** This notebook's own instructions; empty means the default from Settings applies. */
    val notebookInstructions: String = "",
) {
    val isBusy: Boolean get() = isSending || conversation?.busy == true

    val effectiveInstructions: String
        get() = notebookInstructions.ifBlank { GlobalAppSettings.current.claudeDefaultInstructions }

    /** What the docked panel shows: the reply being written, else the latest reply or error. */
    val latest: ClaudeEntry?
        get() = conversation?.history?.lastOrNull { it.role == "assistant" || it.role == "error" }
}

/**
 * Drives the Claude panel: sends page snapshots, chat messages and files to the Termux bridge
 * ([ClaudeBridgeClient]) and polls it for replies. The bridge does the actual work and keeps the
 * conversation, so a reply that arrives while the editor is closed is there when it reopens.
 */
@HiltViewModel
class ClaudeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: ClaudeBridgeClient,
    private val snapshotter: PageSnapshotter,
    private val appRepository: AppRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ClaudeUiState())
    val state: StateFlow<ClaudeUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Called by the editor whenever the shown page (or its notebook) changes. */
    fun bind(notebookId: String?, pageId: String?, pageInfo: String) {
        val key = notebookId ?: pageId?.let { "page-$it" } ?: return
        val previous = _state.value.conversationKey
        _state.update { it.copy(pageInfo = pageInfo) }
        if (key == previous) return

        _state.update {
            it.copy(conversationKey = key, conversation = null, error = null, notebookInstructions = "")
        }
        viewModelScope.launch {
            val title = notebookId?.let {
                withContext(Dispatchers.IO) { appRepository.bookRepository.getById(it)?.title }
            } ?: "Quick page"
            val instructions = runCatching {
                appRepository.kvProxy.get(instructionsKey(key), String.serializer())
            }.getOrNull().orEmpty()
            _state.update {
                if (it.conversationKey != key) it
                else it.copy(title = title, notebookInstructions = instructions)
            }
            if (_state.value.isPanelVisible) refresh(startPolling = true)
        }
    }

    // ---- panel visibility ----

    fun showPanel() {
        _state.update { it.copy(isPanelVisible = true) }
        refresh(startPolling = true)
    }

    fun hidePanel() = _state.update { it.copy(isPanelVisible = false, isSheetOpen = false) }

    fun openSheet(tab: ClaudeSheetTab = ClaudeSheetTab.Chat) {
        _state.update { it.copy(isPanelVisible = true, isSheetOpen = true, sheetTab = tab) }
        refresh(startPolling = true)
    }

    fun closeSheet() = _state.update { it.copy(isSheetOpen = false) }

    fun selectTab(tab: ClaudeSheetTab) = _state.update { it.copy(sheetTab = tab) }

    // ---- messages ----

    /** The toolbar button: snapshot the page and ask for feedback with the check prompt. */
    fun checkPage(page: PageView) =
        send(page, GlobalAppSettings.current.claudeCheckPrompt, attachPage = true, kind = "check")

    fun askHint(page: PageView) =
        send(page, GlobalAppSettings.current.claudeHintPrompt, attachPage = true, kind = "hint")

    fun send(page: PageView?, text: String, attachPage: Boolean, kind: String = "chat") {
        val key = _state.value.conversationKey ?: return
        if (_state.value.isBusy) {
            _state.update { it.copy(isPanelVisible = true, error = "Claude is still answering — wait or cancel.") }
            return
        }
        if (text.isBlank() && !attachPage) return
        _state.update { it.copy(isPanelVisible = true, isSending = true, error = null) }

        viewModelScope.launch {
            try {
                val images = if (attachPage && page != null) {
                    snapshotter.capture(page, GlobalAppSettings.current.claudeCaptureMode)
                } else emptyList()
                val s = _state.value
                client.send(
                    key,
                    ClaudeMessageRequest(
                        text = text.trim(),
                        instructions = s.effectiveInstructions,
                        title = s.title,
                        context = if (attachPage) "Notebook “${s.title}”, page ${s.pageInfo}" else null,
                        kind = kind,
                        images = images,
                    )
                )
                _state.update { it.copy(bridgeUnreachable = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.e("Sending to Claude failed", e)
                reportError(e)
            } finally {
                _state.update { it.copy(isSending = false) }
            }
            refresh(startPolling = true)
        }
    }

    fun cancel() {
        val key = _state.value.conversationKey ?: return
        viewModelScope.launch {
            runCatching { client.cancel(key) }.onFailure(::reportError)
            refresh(startPolling = true)
        }
    }

    /** Forgets the Claude session and the chat; uploaded files stay unless [keepFiles] is false. */
    fun newConversation(keepFiles: Boolean = true) {
        val key = _state.value.conversationKey ?: return
        viewModelScope.launch {
            runCatching { client.reset(key, keepFiles) }.onFailure(::reportError)
            refresh(startPolling = false)
        }
    }

    // ---- files ----

    fun uploadFile(uri: Uri) {
        val key = _state.value.conversationKey ?: return
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val name = displayName(uri) ?: "file"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not open the file")
                    name to bytes
                }
                if (bytes.size > MAX_UPLOAD_BYTES) {
                    throw IllegalStateException("$name is too large (max ${MAX_UPLOAD_BYTES / (1024 * 1024)} MB)")
                }
                client.upload(key, name, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(e)
            } finally {
                _state.update { it.copy(isSending = false) }
            }
            refresh(startPolling = false)
        }
    }

    fun deleteFile(name: String) {
        val key = _state.value.conversationKey ?: return
        viewModelScope.launch {
            runCatching { client.deleteFile(key, name) }.onFailure(::reportError)
            refresh(startPolling = false)
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

    // ---- bridge ----

    fun startBridgeInTermux(): String? = TermuxLauncher.startBridge(context)

    fun retry() = refresh(startPolling = true)

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * Fetches the conversation; with [startPolling], keeps fetching every second or two until
     * Claude is done answering.
     */
    private fun refresh(startPolling: Boolean) {
        // Restart rather than join a running poll: its answer may predate what just changed
        // (a message sent a moment ago would look finished and stop the polling).
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val key = _state.value.conversationKey ?: return@launch
                try {
                    val conversation = client.conversation(key)
                    _state.update {
                        if (it.conversationKey != key) it
                        else it.copy(conversation = conversation, bridgeUnreachable = false)
                    }
                    if (_state.value.error?.startsWith(UNREACHABLE_PREFIX) == true) dismissError()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportError(e)
                    return@launch
                }
                if (!startPolling || !_state.value.isBusy) return@launch
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun reportError(e: Throwable) {
        val unreachable = (e as? ClaudeBridgeException)?.unreachable == true
        _state.update {
            it.copy(
                error = e.message ?: e::class.simpleName,
                bridgeUnreachable = unreachable,
            )
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private companion object {
        const val POLL_INTERVAL_MS = 1500L
        const val MAX_UPLOAD_BYTES = 40 * 1024 * 1024
        const val UNREACHABLE_PREFIX = "Can't reach"

        fun instructionsKey(conversationKey: String) = "claude_instructions:$conversationKey"
    }
}
