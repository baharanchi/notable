package com.ethran.notable.claude

import android.content.Context
import com.ethran.notable.claude.bridge.BridgeApi
import com.ethran.notable.claude.bridge.ClaudeConversation
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val log = ShipBook.getLogger("ClaudeConversationCache")

/**
 * The last known state of each conversation, kept in the app. The bridge in Termux holds the
 * real conversation but only runs while it is needed; this lets the panel show the chat at once,
 * without starting it. A cached conversation that was still busy tells the panel to reconnect
 * and collect the reply.
 */
@Singleton
class ClaudeConversationCache @Inject constructor(@ApplicationContext context: Context) {

    private val dir = File(context.filesDir, "claude")

    suspend fun load(key: String): ClaudeConversation? = withContext(Dispatchers.IO) {
        val file = fileFor(key)
        if (!file.exists()) return@withContext null
        try {
            BridgeApi.json.decodeFromString(ClaudeConversation.serializer(), file.readText())
        } catch (e: Exception) {
            log.w("Unreadable cached conversation $key: ${e.message}")
            null
        }
    }

    suspend fun save(key: String, conversation: ClaudeConversation) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${fileFor(key).name}.tmp")
            tmp.writeText(BridgeApi.json.encodeToString(ClaudeConversation.serializer(), conversation))
            tmp.renameTo(fileFor(key))
        } catch (e: Exception) {
            log.w("Could not cache conversation $key: ${e.message}")
        }
    }

    private fun fileFor(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
}
