package com.ethran.notable.claude

import com.ethran.notable.data.datastore.GlobalAppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// ---- Wire format of tools/claude-bridge/notable_claude_bridge.py ----

@Serializable
data class ClaudeEntry(
    val id: String = "",
    /** "user", "assistant", "event" (uploads, cancellations) or "error". */
    val role: String = "",
    val text: String = "",
    val time: Long = 0,
    /** Snapshot paths (relative to the conversation folder) sent with a user message. */
    val images: List<String> = emptyList(),
    val kind: String? = null,
    @SerialName("cost_usd") val costUsd: Double? = null,
)

@Serializable
data class ClaudeFile(val name: String, val size: Long = 0, val time: Long = 0)

@Serializable
data class ClaudeConversation(
    val key: String = "",
    val title: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    val busy: Boolean = false,
    val status: String = "",
    /** The reply so far, while Claude is still writing it. */
    val partial: String = "",
    val history: List<ClaudeEntry> = emptyList(),
    val files: List<ClaudeFile> = emptyList(),
    /** Where the conversation lives inside Termux; lets the user resume it by hand. */
    val folder: String = "",
)

@Serializable
data class ClaudeHealth(
    val ok: Boolean = false,
    val version: Int = 0,
    val claude: String? = null,
    @SerialName("claude_version") val claudeVersion: String? = null,
)

@Serializable
data class ClaudeImage(val mime: String, val data: String, val label: String)

@Serializable
data class ClaudeMessageRequest(
    val text: String,
    val instructions: String,
    val title: String,
    /** Extra context appended to the prompt (which page of which notebook). */
    val context: String? = null,
    val kind: String? = null,
    val images: List<ClaudeImage> = emptyList(),
)

@Serializable
private data class ClaudeUpload(val name: String, val data: String)

@Serializable
private data class ClaudeReset(@SerialName("keep_files") val keepFiles: Boolean)

@Serializable
private data class ClaudeErrorBody(val error: String? = null)

@Serializable
private class Empty

/** A failure talking to the bridge, with a message fit to show the user. */
class ClaudeBridgeException(message: String, val unreachable: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * HTTP client for the Termux bridge. Reads the URL and token from [GlobalAppSettings] on every
 * call, so settings changes apply immediately. All calls run on [Dispatchers.IO].
 */
@Singleton
class ClaudeBridgeClient @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun health(): ClaudeHealth = call("GET", listOf("health"), null, ClaudeHealth.serializer())

    suspend fun conversation(key: String): ClaudeConversation =
        call("GET", listOf("conversations", key), null, ClaudeConversation.serializer())

    suspend fun send(key: String, request: ClaudeMessageRequest) {
        call(
            "POST", listOf("conversations", key, "messages"),
            json.encodeToString(ClaudeMessageRequest.serializer(), request), Empty.serializer()
        )
    }

    suspend fun upload(key: String, name: String, bytes: ByteArray) {
        val body = ClaudeUpload(name, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        call(
            "POST", listOf("conversations", key, "files"),
            json.encodeToString(ClaudeUpload.serializer(), body), Empty.serializer()
        )
    }

    suspend fun deleteFile(key: String, name: String) {
        call("DELETE", listOf("conversations", key, "files", name), null, Empty.serializer())
    }

    suspend fun cancel(key: String) {
        call("POST", listOf("conversations", key, "cancel"), "{}", Empty.serializer())
    }

    suspend fun reset(key: String, keepFiles: Boolean) {
        call(
            "POST", listOf("conversations", key, "reset"),
            json.encodeToString(ClaudeReset.serializer(), ClaudeReset(keepFiles)), Empty.serializer()
        )
    }

    private suspend fun <T> call(
        verb: String,
        path: List<String>,
        body: String?,
        serializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val settings = GlobalAppSettings.current
        val base = settings.claudeBridgeUrl.trim().ifEmpty { ClaudeDefaults.BRIDGE_URL }
        val url = base.toHttpUrlOrNull()?.newBuilder()?.apply { path.forEach { addPathSegment(it) } }?.build()
            ?: throw ClaudeBridgeException("Invalid bridge URL: $base")

        val request = Request.Builder().url(url).apply {
            if (settings.claudeBridgeToken.isNotBlank()) {
                header("Authorization", "Bearer ${settings.claudeBridgeToken.trim()}")
            }
            when (verb) {
                "GET" -> get()
                "DELETE" -> delete()
                else -> method(verb, (body ?: "{}").toRequestBody(jsonType))
            }
        }.build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ClaudeBridgeException(
                "Can't reach the Claude bridge at $base. Is it running in Termux?",
                unreachable = true, cause = e
            )
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                val reason = runCatching { json.decodeFromString(ClaudeErrorBody.serializer(), text).error }
                    .getOrNull() ?: "HTTP ${it.code}"
                throw ClaudeBridgeException(reason)
            }
            try {
                json.decodeFromString(serializer, text)
            } catch (e: Exception) {
                throw ClaudeBridgeException("Unexpected answer from the bridge: ${e.message}", cause = e)
            }
        }
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
