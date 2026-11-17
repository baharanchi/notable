package com.ethran.notable.claude.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ---- Wire format of assets/claude/bridge.py ----

@Serializable
data class ClaudeEntry(
    val id: String = "",
    /** "user", "assistant", "event" (uploads, retries, cancellations) or "error". */
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
    /** What Claude is doing right now ("Read files/sheet.pdf…"). */
    val status: String = "",
    /** The reply so far, while Claude is still working on it. */
    val partial: String = "",
    val history: List<ClaudeEntry> = emptyList(),
    val files: List<ClaudeFile> = emptyList(),
    /** The conversation's folder in Termux; lets the user resume it by hand. */
    val folder: String = "",
)

@Serializable
data class ClaudeInfo(
    val path: String? = null,
    val version: String? = null,
    val error: String? = null,
    val checked: Boolean = false,
)

@Serializable
data class BridgeHealth(
    val ok: Boolean = false,
    val version: String = "",
    val pid: Int = 0,
    val claude: ClaudeInfo = ClaudeInfo(),
)

@Serializable
data class ClaudeImage(val mime: String, val data: String, val label: String)

@Serializable
data class ClaudeMessageRequest(
    /** Lets the bridge recognise a retried request, so a message never runs twice. */
    val id: String,
    val text: String,
    val instructions: String,
    val title: String,
    /** Extra context appended to the prompt (which page of which notebook). */
    val context: String? = null,
    val kind: String? = null,
    val model: String? = null,
    val images: List<ClaudeImage> = emptyList(),
)

@Serializable
private data class UploadRequest(val id: String, val name: String, val data: String)

@Serializable
private data class ResetRequest(@SerialName("keep_files") val keepFiles: Boolean)

@Serializable
private data class ErrorBody(val error: String? = null)

@Serializable
private class Ack

/** The bridge is not running (nothing answered on its port). */
class BridgeUnreachableException(cause: Throwable) : IOException("The Claude bridge is not running", cause)

/** The bridge answered with an error; [message] is fit to show. */
class BridgeHttpException(val code: Int, message: String) : IOException(message)

/** What the app needs from the bridge; [BridgeApi] over HTTP, fakes in tests. */
interface BridgeClient {
    suspend fun health(): BridgeHealth
    suspend fun conversation(key: String): ClaudeConversation
    suspend fun send(key: String, request: ClaudeMessageRequest)
    suspend fun upload(key: String, id: String, name: String, bytes: ByteArray)
    suspend fun deleteFile(key: String, name: String)
    suspend fun cancel(key: String)
    suspend fun reset(key: String, keepFiles: Boolean)
    suspend fun shutdown()
}

/** HTTP client for the bridge on 127.0.0.1:[port]; every request carries [token]. */
class BridgeApi(private val port: Int, private val token: () -> String) : BridgeClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** For the startup wait: answers fast or not at all. */
    private val quick = http.newBuilder()
        .connectTimeout(700, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override suspend fun health(): BridgeHealth =
        call(quick, "GET", listOf("health"), null, BridgeHealth.serializer())

    override suspend fun conversation(key: String): ClaudeConversation =
        call(http, "GET", listOf("conversations", key), null, ClaudeConversation.serializer())

    override suspend fun send(key: String, request: ClaudeMessageRequest) {
        call(http, "POST", listOf("conversations", key, "messages"),
            json.encodeToString(ClaudeMessageRequest.serializer(), request), Ack.serializer())
    }

    override suspend fun upload(key: String, id: String, name: String, bytes: ByteArray) {
        val body = UploadRequest(id, name, java.util.Base64.getEncoder().encodeToString(bytes))
        call(http, "POST", listOf("conversations", key, "files"),
            json.encodeToString(UploadRequest.serializer(), body), Ack.serializer())
    }

    override suspend fun deleteFile(key: String, name: String) {
        call(http, "DELETE", listOf("conversations", key, "files", name), "{}", Ack.serializer())
    }

    override suspend fun cancel(key: String) {
        call(http, "POST", listOf("conversations", key, "cancel"), "{}", Ack.serializer())
    }

    override suspend fun reset(key: String, keepFiles: Boolean) {
        call(http, "POST", listOf("conversations", key, "reset"),
            json.encodeToString(ResetRequest.serializer(), ResetRequest(keepFiles)), Ack.serializer())
    }

    override suspend fun shutdown() {
        call(quick, "POST", listOf("shutdown"), "{}", Ack.serializer())
    }

    private suspend fun <T> call(
        client: OkHttpClient,
        verb: String,
        path: List<String>,
        body: String?,
        serializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port)
            .apply { path.forEach { addPathSegment(it) } }.build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${token()}")
            .apply {
                if (verb == "GET") get() else method(verb, (body ?: "{}").toRequestBody(jsonType))
            }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw BridgeUnreachableException(e)
        }
        response.use {
            val text = try {
                it.body.string()
            } catch (e: IOException) {
                throw BridgeUnreachableException(e)
            }
            if (!it.isSuccessful) {
                val reason = runCatching { json.decodeFromString(ErrorBody.serializer(), text).error }
                    .getOrNull() ?: "HTTP ${it.code}"
                throw BridgeHttpException(it.code, reason)
            }
            try {
                json.decodeFromString(serializer, text)
            } catch (e: Exception) {
                // Something else is listening on the port.
                throw BridgeHttpException(it.code, "Unexpected answer on port $port: ${e.message}")
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
