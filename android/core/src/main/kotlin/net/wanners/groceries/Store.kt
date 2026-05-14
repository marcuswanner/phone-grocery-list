package net.wanners.groceries

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.SecureRandom

@Serializable
data class Item(
    val id: String,
    val text: String,
    val done: Boolean = false,
)

@Serializable
sealed interface Change {
    @Serializable
    data class Added(val item: Item) : Change

    @Serializable
    data class Updated(val item: Item) : Change

    @Serializable
    data class Removed(val id: String) : Change

    @Serializable
    data class Reordered(val ids: List<String>) : Change
}

class Store(
    private val file: File,
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
) {
    private val mutex = Mutex()
    private val items: MutableList<Item> = mutableListOf()
    // DROP_OLDEST so a slow/stalled SSE subscriber can never wedge `emit` and block writers.
    // A laggy client misses intermediate events but `refresh()` on reconnect re-syncs.
    private val _changes = MutableSharedFlow<Change>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    // Single-step trash for undo. Replaced by every destructive op.
    private data class TrashEntry(val item: Item, val originalIndex: Int)
    private var lastDeleted: List<TrashEntry> = emptyList()

    init {
        load()
    }

    fun snapshot(): List<Item> = synchronized(items) { items.toList() }

    suspend fun add(text: String): Item {
        val clean = sanitize(text)
        require(clean.isNotBlank()) { "text required" }
        val item = Item(id = newId(), text = clean, done = false)
        mutex.withLock {
            val size = synchronized(items) { items.size }
            require(size < maxItems) { "list_full" }
            synchronized(items) { items.add(item) }
            persist()
        }
        _changes.emit(Change.Added(item))
        return item
    }

    suspend fun update(id: String, text: String? = null, done: Boolean? = null): Item? {
        val cleanedText = text?.let { sanitize(it) }?.takeIf { it.isNotEmpty() }
        val updated: Item? = mutex.withLock {
            val current = synchronized(items) {
                val idx = items.indexOfFirst { it.id == id }
                if (idx < 0) return@withLock null
                val merged = items[idx].copy(
                    text = cleanedText ?: items[idx].text,
                    done = done ?: items[idx].done,
                )
                items[idx] = merged
                merged
            }
            persist()
            current
        }
        if (updated != null) _changes.emit(Change.Updated(updated))
        return updated
    }

    suspend fun remove(id: String): Boolean {
        val removed: Boolean = mutex.withLock {
            val idx = synchronized(items) { items.indexOfFirst { it.id == id } }
            if (idx < 0) return@withLock false
            val item = synchronized(items) { items.removeAt(idx) }
            lastDeleted = listOf(TrashEntry(item, idx))
            persist()
            true
        }
        if (removed) _changes.emit(Change.Removed(id))
        return removed
    }

    suspend fun removeDone(): List<String> {
        val removedIds = mutex.withLock {
            val trash = synchronized(items) {
                items.mapIndexedNotNull { idx, item -> if (item.done) TrashEntry(item, idx) else null }
            }
            if (trash.isNotEmpty()) {
                synchronized(items) { items.removeAll { it.done } }
                lastDeleted = trash.takeLast(MAX_TRASH)
                persist()
            }
            trash.map { it.item.id }
        }
        for (id in removedIds) _changes.emit(Change.Removed(id))
        return removedIds
    }

    suspend fun clear(): List<String> {
        val removedIds = mutex.withLock {
            val trash = synchronized(items) { items.mapIndexed { idx, item -> TrashEntry(item, idx) } }
            if (trash.isNotEmpty()) {
                synchronized(items) { items.clear() }
                lastDeleted = trash.takeLast(MAX_TRASH)
                persist()
            }
            trash.map { it.item.id }
        }
        for (id in removedIds) _changes.emit(Change.Removed(id))
        return removedIds
    }

    suspend fun undo(): List<Item> {
        val result = mutex.withLock {
            val toRestore = lastDeleted
            if (toRestore.isEmpty()) return@withLock Triple(emptyList<Item>(), emptyList<String>(), false)
            val sorted = toRestore.sortedBy { it.originalIndex }
            for (entry in sorted) {
                val currentSize = synchronized(items) { items.size }
                val clamped = entry.originalIndex.coerceIn(0, currentSize)
                synchronized(items) { items.add(clamped, entry.item) }
            }
            lastDeleted = emptyList()
            persist()
            val finalOrder = synchronized(items) { items.map { it.id } }
            Triple(sorted.map { it.item }, finalOrder, true)
        }
        val (restored, finalOrder, did) = result
        if (did) {
            for (item in restored) _changes.emit(Change.Added(item))
            // Tell subscribers the final order, since Added events alone don't carry position.
            _changes.emit(Change.Reordered(finalOrder))
        }
        return restored
    }

    suspend fun reorder(newOrder: List<String>): Boolean {
        val finalOrder: List<String>? = mutex.withLock {
            val byId = synchronized(items) { items.associateBy { it.id } }
            // Must reference exactly the current set of IDs — no adds, no drops.
            if (byId.keys != newOrder.toSet() || newOrder.size != byId.size) return@withLock null
            val reordered = newOrder.map { byId[it]!! }
            synchronized(items) {
                items.clear()
                items.addAll(reordered)
            }
            persist()
            newOrder.toList()
        }
        if (finalOrder != null) {
            _changes.emit(Change.Reordered(finalOrder))
            return true
        }
        return false
    }

    private fun persist() {
        val snapshot = synchronized(items) { items.toList() }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(LIST_SERIALIZER, snapshot))
        if (!tmp.renameTo(file)) {
            // Don't do a non-atomic fallback rewrite: if rename failed, the disk or parent
            // dir is broken, and a partial overwrite would risk destroying the existing
            // good file. Let the caller see the failure.
            tmp.delete()
            throw IOException("could not atomically replace ${file.name}")
        }
    }

    private fun load() {
        if (!file.exists()) return
        // Don't swallow IOException — surface broken storage to the caller instead
        // of starting empty (and then overwriting the bad file with an empty list).
        val text = file.readText()
        if (text.isBlank()) return
        val parsed = try {
            json.decodeFromString(LIST_SERIALIZER, text)
        } catch (e: SerializationException) {
            // Move the bad file aside so the next persist() doesn't overwrite it.
            val parent = file.parentFile
            if (parent != null) {
                file.renameTo(File(parent, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }
            throw IllegalStateException("items.json corrupted; moved aside", e)
        }
        synchronized(items) {
            items.clear()
            items.addAll(parsed)
        }
    }

    // Strip ASCII C0 (sans tab), C1, and bidi overrides (U+202A–U+202E, U+2066–U+2069).
    // Keeps log lines clean and prevents RTL spoofing in the rendered list.
    private fun sanitize(s: String): String = buildString(s.length) {
        for (c in s) {
            val code = c.code
            val isC0 = code in 0x00..0x1F && code != 0x09
            val isC1 = code in 0x80..0x9F
            val isBidi = code in 0x202A..0x202E || code in 0x2066..0x2069
            if (!isC0 && !isC1 && !isBidi) append(c)
        }
    }.trim()

    companion object {
        const val DEFAULT_MAX_ITEMS = 5_000
        const val MAX_TRASH = 5_000
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val LIST_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(Item.serializer())
        private val rng = SecureRandom()
        private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun newId(): String {
            val buf = CharArray(10)
            for (i in buf.indices) buf[i] = ID_ALPHABET[rng.nextInt(ID_ALPHABET.length)]
            return String(buf)
        }
    }
}
