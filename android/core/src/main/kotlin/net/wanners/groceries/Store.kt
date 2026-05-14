package net.wanners.groceries

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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

class Store(private val file: File) {
    private val mutex = Mutex()
    private val items: MutableList<Item> = mutableListOf()
    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    // Single-step trash for undo. Replaced by every destructive op.
    private data class TrashEntry(val item: Item, val originalIndex: Int)
    private var lastDeleted: List<TrashEntry> = emptyList()

    init {
        load()
    }

    fun snapshot(): List<Item> = synchronized(items) { items.toList() }

    suspend fun add(text: String): Item {
        require(text.isNotBlank()) { "text required" }
        val item = Item(id = newId(), text = text.trim(), done = false)
        mutex.withLock {
            synchronized(items) { items.add(item) }
            persist()
        }
        _changes.emit(Change.Added(item))
        return item
    }

    suspend fun update(id: String, text: String? = null, done: Boolean? = null): Item? {
        val updated: Item? = mutex.withLock {
            val current = synchronized(items) {
                val idx = items.indexOfFirst { it.id == id }
                if (idx < 0) return@withLock null
                val merged = items[idx].copy(
                    text = text?.trim()?.takeIf { it.isNotEmpty() } ?: items[idx].text,
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
                lastDeleted = trash
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
                lastDeleted = trash
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
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun load() {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text.isBlank()) return
        val parsed = runCatching { json.decodeFromString(LIST_SERIALIZER, text) }.getOrNull() ?: return
        synchronized(items) {
            items.clear()
            items.addAll(parsed)
        }
    }

    companion object {
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
