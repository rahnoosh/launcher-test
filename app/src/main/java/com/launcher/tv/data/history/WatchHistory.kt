package com.launcher.tv.data.history

import android.content.Context
import com.launcher.tv.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
data class HistoryEntry(
    /** Stream URL; also the key. */
    val url: String,
    val rawTitle: String? = null,
    val title: String,
    val subtitle: String = "",
    val isEpisode: Boolean = false,
    val imageUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val lastPlayedAt: Long = 0,
    /** HTTP headers TiviMate passed along (some providers need a specific User-Agent). */
    val headers: Map<String, String> = emptyMap(),
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = progress >= 0.92f
}

/**
 * Everything played through the HomeBase player (i.e. what you watch in TiviMate),
 * newest first. Stored on the box only.
 */
class WatchHistory private constructor(context: Context) {
    private val file = File(context.filesDir, "watch_history.json")
    private val mutex = Mutex()
    private val serializer = ListSerializer(HistoryEntry.serializer())
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()
    private var loaded = false

    suspend fun load(): List<HistoryEntry> = mutex.withLock { ensureLoaded(); _entries.value }

    fun get(url: String): HistoryEntry? = _entries.value.firstOrNull { it.url == url }

    suspend fun upsert(entry: HistoryEntry) = mutex.withLock {
        ensureLoaded()
        val list = listOf(entry) + _entries.value.filter { it.url != entry.url }
        save(list.take(MAX_ENTRIES))
    }

    /** Update one entry in place (e.g. artwork found later) without moving it to the front. */
    suspend fun update(url: String, change: (HistoryEntry) -> HistoryEntry) = mutex.withLock {
        ensureLoaded()
        save(_entries.value.map { if (it.url == url) change(it) else it })
    }

    suspend fun remove(url: String) = mutex.withLock {
        ensureLoaded()
        save(_entries.value.filter { it.url != url })
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) {
            runCatching { Http.json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
        }
        loaded = true
    }

    private suspend fun save(list: List<HistoryEntry>) {
        _entries.value = list
        withContext(Dispatchers.IO) { runCatching { file.writeText(Http.json.encodeToString(serializer, list)) } }
    }

    companion object {
        private const val MAX_ENTRIES = 60

        @Volatile private var instance: WatchHistory? = null
        fun get(context: Context): WatchHistory =
            instance ?: synchronized(this) { instance ?: WatchHistory(context.applicationContext).also { instance = it } }
    }
}
