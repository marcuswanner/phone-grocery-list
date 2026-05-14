package net.wanners.groceries

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StoreTest {

    private fun newStore(@TempDir dir: File): Pair<Store, File> {
        val file = File(dir, "items.json")
        return Store(file) to file
    }

    @Test
    fun `add returns item with id and persists`(@TempDir dir: File) = runTest {
        val (store, file) = newStore(dir)
        val item = store.add("milk")
        assertEquals("milk", item.text)
        assertFalse(item.done)
        assertTrue(item.id.isNotEmpty())
        assertEquals(listOf(item), store.snapshot())
        assertTrue(file.exists())
    }

    @Test
    fun `add trims whitespace`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("  eggs  ")
        assertEquals("eggs", item.text)
    }

    @Test
    fun `add strips control chars and bidi overrides`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        // U+202E (RIGHT-TO-LEFT OVERRIDE), embedded NUL, and a CR are all stripped.
        val item = store.add("\u202Eevi\u0000l\rtext")
        assertEquals("eviltext", item.text)
    }

    @Test
    fun `add preserves tab as the one allowed C0`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("a\tb")
        assertEquals("a\tb", item.text)
    }

    @Test
    fun `add rejects text that is purely control chars after sanitize`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        assertThrows<IllegalArgumentException> { store.add("\u202E \u2066") }
    }

    @Test
    fun `update sanitizes text too`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("milk")
        val updated = store.update(item.id, text = "\u202Eoat milk")
        assertEquals("oat milk", updated?.text)
    }

    @Test
    fun `add rejects blank text`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        assertThrows<IllegalArgumentException> { store.add("") }
        assertThrows<IllegalArgumentException> { store.add("   ") }
    }

    @Test
    fun `update toggles done`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("milk")
        val updated = store.update(item.id, done = true)
        assertNotNull(updated)
        assertTrue(updated!!.done)
        assertEquals("milk", updated.text)
    }

    @Test
    fun `update changes text preserving done`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("milk")
        store.update(item.id, done = true)
        val updated = store.update(item.id, text = "oat milk")
        assertEquals("oat milk", updated?.text)
        assertEquals(true, updated?.done)
    }

    @Test
    fun `update returns null for unknown id`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        assertNull(store.update("nope", done = true))
    }

    @Test
    fun `remove deletes item`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("milk")
        assertTrue(store.remove(item.id))
        assertEquals(emptyList<Item>(), store.snapshot())
    }

    @Test
    fun `remove unknown id is no-op`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        assertFalse(store.remove("nope"))
    }

    @Test
    fun `removeDone clears only done items and returns their ids`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val a = store.add("milk")
        val b = store.add("eggs")
        val c = store.add("bread")
        store.update(a.id, done = true)
        store.update(c.id, done = true)
        val removed = store.removeDone()
        assertEquals(setOf(a.id, c.id), removed.toSet())
        assertEquals(listOf("eggs"), store.snapshot().map { it.text })
    }

    @Test
    fun `removeDone with no done items returns empty and emits nothing`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        store.add("milk")
        store.add("eggs")
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(300) { store.changes.first() }
        }
        val removed = store.removeDone()
        assertTrue(removed.isEmpty())
        assertEquals(2, store.snapshot().size)
        assertEquals(null, deferred.await())
    }

    @Test
    fun `clear removes all items and emits Removed for each`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val a = store.add("milk")
        val b = store.add("eggs")
        val c = store.add("bread")
        val removed = store.clear()
        assertEquals(setOf(a.id, b.id, c.id), removed.toSet())
        assertEquals(emptyList<Item>(), store.snapshot())
    }

    @Test
    fun `clear on empty store is no-op`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        assertEquals(emptyList<String>(), store.clear())
    }

    @Test
    fun `reorder rearranges items when set matches`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val a = store.add("milk")
        val b = store.add("eggs")
        val c = store.add("bread")
        val ok = store.reorder(listOf(c.id, a.id, b.id))
        assertTrue(ok)
        assertEquals(listOf("bread", "milk", "eggs"), store.snapshot().map { it.text })
    }

    @Test
    fun `reorder rejects when ids dont match current set`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val a = store.add("milk")
        val b = store.add("eggs")
        assertFalse(store.reorder(listOf(a.id))) // missing b
        assertFalse(store.reorder(listOf(a.id, b.id, "fake-id"))) // extra id
        assertEquals(listOf("milk", "eggs"), store.snapshot().map { it.text })
    }

    @Test
    fun `undo restores a single deleted item at its original index`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        store.add("milk")
        val b = store.add("eggs")
        store.add("bread")
        store.remove(b.id)
        assertEquals(listOf("milk", "bread"), store.snapshot().map { it.text })
        val restored = store.undo()
        assertEquals(1, restored.size)
        assertEquals("eggs", restored[0].text)
        assertEquals(listOf("milk", "eggs", "bread"), store.snapshot().map { it.text })
    }

    @Test
    fun `undo after clear-done restores only the previously done items at their original slots`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val a = store.add("milk")
        store.add("eggs")
        val c = store.add("bread")
        store.update(a.id, done = true)
        store.update(c.id, done = true)
        store.removeDone()
        assertEquals(listOf("eggs"), store.snapshot().map { it.text })
        store.undo()
        assertEquals(listOf("milk", "eggs", "bread"), store.snapshot().map { it.text })
    }

    @Test
    fun `undo after clear-all restores everything in original order`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        store.add("milk")
        store.add("eggs")
        store.add("bread")
        store.clear()
        assertEquals(emptyList<Item>(), store.snapshot())
        store.undo()
        assertEquals(listOf("milk", "eggs", "bread"), store.snapshot().map { it.text })
    }

    @Test
    fun `undo with empty trash is a no-op`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        store.add("milk")
        val restored = store.undo()
        assertEquals(emptyList<Item>(), restored)
        assertEquals(listOf("milk"), store.snapshot().map { it.text })
    }

    @Test
    fun `undo only restores the most recent destructive op`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val a = store.add("milk")
        val b = store.add("eggs")
        store.remove(a.id) // first delete
        store.remove(b.id) // second delete — replaces trash
        val restored = store.undo()
        assertEquals(listOf("eggs"), restored.map { it.text })
        // milk is NOT recovered
        assertEquals(listOf("eggs"), store.snapshot().map { it.text })
    }

    @Test
    fun `reorder emits Reordered event`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val a = store.add("milk")
        val b = store.add("eggs")
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { store.changes.first() }
        store.reorder(listOf(b.id, a.id))
        val ev = deferred.await()
        assertEquals(Change.Reordered(listOf(b.id, a.id)), ev)
    }

    @Test
    fun `persistence round-trip across instances`(@TempDir dir: File) = runTest {
        val file = File(dir, "items.json")
        val s1 = Store(file)
        val a = s1.add("milk")
        s1.add("eggs")
        s1.update(a.id, done = true)

        val s2 = Store(file)
        val items = s2.snapshot()
        assertEquals(2, items.size)
        val milk = items.first { it.text == "milk" }
        assertTrue(milk.done)
        val eggs = items.first { it.text == "eggs" }
        assertFalse(eggs.done)
    }

    @Test
    fun `corrupt file is moved aside and constructor throws`(@TempDir dir: File) {
        val file = File(dir, "items.json")
        file.writeText("{not valid json")
        assertThrows<IllegalStateException> { Store(file) }
        // The original bad file is renamed aside so the next persist() won't clobber it.
        assertFalse(file.exists(), "corrupt file should have been renamed away")
        val aside = dir.listFiles { _, name -> name.startsWith("items.json.corrupt-") }
        assertNotNull(aside)
        assertEquals(1, aside!!.size)
    }

    @Test
    fun `concurrent adds all persist`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        coroutineScope {
            (1..50).map { i -> async { store.add("item-$i") } }.forEach { it.await() }
        }
        val snap = store.snapshot()
        assertEquals(50, snap.size)
        assertEquals(50, snap.map { it.id }.toSet().size)
    }

    @Test
    fun `add emits Added event`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { store.changes.first() }
        val item = store.add("milk")
        assertEquals(Change.Added(item), deferred.await())
    }

    @Test
    fun `update emits Updated event`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("milk")
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { store.changes.first() }
        val updated = store.update(item.id, done = true)
        assertEquals(Change.Updated(updated!!), deferred.await())
    }

    @Test
    fun `remove emits Removed event`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val item = store.add("milk")
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { store.changes.first() }
        store.remove(item.id)
        assertEquals(Change.Removed(item.id), deferred.await())
    }

    @Test
    fun `remove of missing id emits no event`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        val deferred = async(start = CoroutineStart.UNDISPATCHED) { store.changes.first() }
        val miss = store.remove("nope")
        assertFalse(miss)
        // Now do a real mutation; we must see the Added event, not a Removed.
        val item = store.add("milk")
        assertEquals(Change.Added(item), withTimeoutOrNull(2_000) { deferred.await() })
    }

    @Test
    fun `ids have no collisions across many generations`() {
        val ids = (1..100_000).map { Store.newId() }.toSet()
        assertEquals(100_000, ids.size)
    }

    @Test
    fun `ids are url-safe`() {
        val pattern = Regex("^[A-Za-z0-9]+$")
        repeat(1000) {
            assertTrue(pattern.matches(Store.newId()))
        }
    }

    @Test
    fun `add returns promptly even when a subscriber never drains`(@TempDir dir: File) = runTest {
        val (store, _) = newStore(dir)
        coroutineScope {
            val sub = launch {
                store.changes.collect {
                    // Simulate a stalled SSE client that never advances.
                    kotlinx.coroutines.delay(Long.MAX_VALUE)
                }
            }
            // 200 adds is well past the 64-slot buffer. With DROP_OLDEST these all complete.
            for (i in 1..200) store.add("item-$i")
            sub.cancel()
        }
        assertEquals(200, store.snapshot().size)
    }

    @Test
    fun `add rejects once max items is reached`(@TempDir dir: File) = runTest {
        val store = Store(File(dir, "items.json"), maxItems = 3)
        store.add("a"); store.add("b"); store.add("c")
        val e = assertThrows<IllegalArgumentException> { store.add("overflow") }
        assertEquals("list_full", e.message)
    }
}
