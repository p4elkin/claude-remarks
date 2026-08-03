package dev.sasha.clauderemarks.store

import com.intellij.configurationStore.ComponentSerializationUtil
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The nested state class RemarkStore.RemarksState, on its own: what survives a write and read
 * cycle through XML, and what its three mutators do. Then the store's own contract with the
 * platform — getState, loadState and the state class the platform resolves — which needs no
 * project, so a plain RemarkStore() is enough. The service wiring is RemarkStoreServiceTest.
 */
class RemarkStoreStateTest {

    /**
     * Compared as serialized XML rather than field by field, the same way `a snapshot carries every
     * field a remark is stored with` does. The eleven hand-written assertions this replaces named
     * every field except severity, bucket and commit — so the test's name kept getting less true as
     * phase 5 added fields, and would have again on the next one.
     */
    @Test
    fun `every field survives a write and read cycle`() {
        val original = remark(
            id = "r-1",
            path = "src/main/kotlin/Foo.kt",
            startLine = 10,
            endLine = 12,
            text = "why is this synchronized?",
            tag = RemarkTag.QUESTION,
            status = RemarkStatus.SENT,
            createdAt = 1_700_000_000_000L,
            textHash = "abcdef0123456789",
            contextBefore = "line a\nline b",
            contextAfter = "line c\nline d",
            severity = RemarkSeverity.MUST,
            bucket = "auth refactor",
            commit = "0123456789abcdef0123456789abcdef01234567",
        )
        val state = RemarkStore.RemarksState()
        state.addRemark(original)

        val restored = roundTrip(state)

        assertEquals(1, restored.remarks.size)
        assertEquals(asXml(original), asXml(restored.remarks.single()))
    }

    @Test
    fun `a remark with no tag round-trips as null`() {
        val original = RemarkStore.RemarksState()
        original.addRemark(remark(id = "r-2", tag = null))

        val restored = roundTrip(original)

        assertNull(restored.remarks.single().tag)
        assertEquals(RemarkStatus.PENDING, restored.remarks.single().status)
    }

    /**
     * The joined context carries a leading newline as its marker, and XML attribute values are
     * whitespace-normalized by the parser unless the writer escapes them. This is the check
     * that JDOM writes it as a character reference, so the marker comes back.
     */
    @Test
    fun `one blank line of context survives the round trip through xml`() {
        val original = RemarkStore.RemarksState()
        original.addRemark(
            remark(id = "r-1").also {
                it.contextBefore = joinContext(listOf(""))
                it.contextAfter = joinContext(listOf("", "tail"))
            }
        )

        val restored = roundTrip(original).remarks.single()

        assertEquals(listOf(""), splitContext(restored.contextBefore))
        assertEquals(listOf("", "tail"), splitContext(restored.contextAfter))
    }

    @Test
    fun `several remarks survive in the order they were added`() {
        val original = RemarkStore.RemarksState()
        original.addRemark(remark(id = "r-1"))
        original.addRemark(remark(id = "r-2"))
        original.addRemark(remark(id = "r-3"))

        assertEquals(listOf("r-1", "r-2", "r-3"), roundTrip(original).remarks.map { it.id })
    }

    @Test
    fun `an empty list round-trips as an empty list`() {
        assertEquals(0, roundTrip(RemarkStore.RemarksState()).remarks.size)
    }

    @Test
    fun `adding a remark marks the state as changed`() {
        val state = RemarkStore.RemarksState()
        val before = state.modificationCount

        state.addRemark(remark(id = "r-1"))

        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `removing a remark marks the state as changed`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val before = state.modificationCount

        assertTrue(state.removeRemark("r-1"))

        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `removing an id that is not there changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val before = state.modificationCount

        assertFalse(state.removeRemark("no-such-id"))

        assertEquals(before, state.modificationCount)
        assertEquals(1, state.remarks.size)
    }

    @Test
    fun `removing an id takes out every remark carrying it`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "dup"))
        state.addRemark(remark(id = "dup"))

        assertTrue(state.removeRemark("dup"))

        assertEquals(0, state.remarks.size)
    }

    @Test
    fun `editing a remark changes its text and tag and marks the state as changed`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old", tag = null))
        val before = state.modificationCount

        assertTrue(state.editRemark("r-1", "new", RemarkTag.BUG))

        assertEquals("new", state.snapshot().single().text)
        assertEquals(RemarkTag.BUG, state.snapshot().single().tag)
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `editing an id that is not there changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old"))
        val before = state.modificationCount

        assertFalse(state.editRemark("no-such-id", "new", null))

        assertEquals("old", state.snapshot().single().text)
        assertEquals(before, state.modificationCount)
    }

    @Test
    fun `marking sent only touches the ids given`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.addRemark(remark(id = "r-2"))
        val before = state.modificationCount

        assertEquals(1, state.markSent(setOf("r-1")))

        assertEquals(RemarkStatus.SENT, state.snapshot().first { it.id == "r-1" }.status)
        assertEquals(RemarkStatus.PENDING, state.snapshot().first { it.id == "r-2" }.status)
        // markSent writes a FIELD on a remark that is already in the list, which is not the same
        // as adding or removing a list element. Whether that alone would reach the outer state's
        // modification count is not settled, and if it does not, the SENT flag is lost on restart
        // with nothing logged. So the count is pinned here.
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `marking a remark sent twice does not change it a second time`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.markSent(setOf("r-1"))
        val before = state.modificationCount

        assertEquals(0, state.markSent(setOf("r-1")))

        assertEquals(before, state.modificationCount)
    }

    @Test
    fun `removing sent keeps the pending ones`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", status = RemarkStatus.SENT))
        state.addRemark(remark(id = "r-2", status = RemarkStatus.PENDING))
        val before = state.modificationCount

        assertEquals(1, state.removeSent())

        assertEquals(listOf("r-2"), state.snapshot().map { it.id })
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `removing sent when there are none changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val before = state.modificationCount

        assertEquals(0, state.removeSent())

        assertEquals(before, state.modificationCount)
    }

    @Test
    fun `clear removes everything`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.addRemark(remark(id = "r-2", status = RemarkStatus.SENT))
        val before = state.modificationCount

        assertEquals(2, state.clear())

        assertEquals(0, state.snapshot().size)
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `an edited remark survives the round trip through xml`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old", tag = null))
        state.editRemark("r-1", "new", RemarkTag.REFACTOR)
        state.markSent(setOf("r-1"))

        val restored = roundTrip(state).remarks.single()

        assertEquals("new", restored.text)
        assertEquals(RemarkTag.REFACTOR, restored.tag)
        assertEquals(RemarkStatus.SENT, restored.status)
    }

    @Test
    fun `severity and bucket survive the round trip`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", severity = RemarkSeverity.MUST, bucket = "auth refactor"))

        val restored = roundTrip(state).remarks.single()

        assertEquals(RemarkSeverity.MUST, restored.severity)
        assertEquals("auth refactor", restored.bucket)
    }

    @Test
    fun `a remark with no bucket round-trips as null`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", bucket = null))

        assertNull(roundTrip(state).remarks.single().bucket)
    }

    /**
     * Every remark already in someone's workspace.xml was written before the severity field
     * existed, so its element has no severity attribute at all. The default has to come back, not
     * null: a null severity would reach the renderer and the tree, both of which read it without a
     * null check, and the failure would be a crash on the first copy after upgrading.
     */
    @Test
    fun `a remark stored before severity existed loads with the default`() {
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load("""<RemarksState><remarks><RemarkState id="r-1" path="src/Foo.kt" /></remarks></RemarksState>"""),
            RemarkStore.RemarksState::class.java,
        )

        assertEquals(RemarkSeverity.SHOULD, restored.remarks.single().severity)
    }

    /**
     * The name no longer claims to guard `setSeverity`'s own `incrementModificationCount()` call,
     * because it cannot: writing `severity` on a child RemarkState already bumps that child's count,
     * and ListStoredProperty surfaces it through the outer state — so the count rises with the
     * explicit call deleted. The call is left in place, matching markSent and removeSent; only the
     * name is corrected. The same is true of setBucket.
     */
    @Test
    fun `setting the severity only touches the ids given`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.addRemark(remark(id = "r-2"))
        val before = state.modificationCount

        assertEquals(1, state.setSeverity(setOf("r-1"), RemarkSeverity.MUST))

        assertEquals(RemarkSeverity.MUST, state.snapshot().first { it.id == "r-1" }.severity)
        assertEquals(RemarkSeverity.SHOULD, state.snapshot().first { it.id == "r-2" }.severity)
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `setting the severity to what it already is changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", severity = RemarkSeverity.MUST))
        val before = state.modificationCount

        assertEquals(0, state.setSeverity(setOf("r-1"), RemarkSeverity.MUST))

        assertEquals(before, state.modificationCount)
    }

    @Test
    fun `setting the bucket writes it and clearing it writes null`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))

        assertEquals(1, state.setBucket(setOf("r-1"), "auth refactor"))
        assertEquals("auth refactor", state.snapshot().single().bucket)

        assertEquals(1, state.setBucket(setOf("r-1"), null))
        assertNull(state.snapshot().single().bucket)
    }

    @Test
    fun `setting the bucket to what it already is changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", bucket = "b"))
        val before = state.modificationCount

        assertEquals(0, state.setBucket(setOf("r-1"), "b"))

        assertEquals(before, state.modificationCount)
    }

    @Test
    fun `the commit survives the round trip and is null when there was none`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", commit = "0123456789abcdef0123456789abcdef01234567"))
        state.addRemark(remark(id = "r-2", commit = null))

        val restored = roundTrip(state).remarks

        assertEquals("0123456789abcdef0123456789abcdef01234567", restored.first { it.id == "r-1" }.commit)
        assertNull(restored.first { it.id == "r-2" }.commit)
    }

    @Test
    fun `a snapshot does not change when a remark is added afterwards`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val snapshot = state.snapshot()

        state.addRemark(remark(id = "r-2"))

        assertEquals(1, snapshot.size)
        assertEquals(2, state.snapshot().size)
    }

    /**
     * The deep half of the copy, and the reason it exists.
     *
     * A remark handed to a reader must not change under it. `resolveAll` and `collectForPrompt`
     * walk these objects on a pooled thread for as long as rendering a whole prompt takes, and an
     * edit landing in that window used to reach them field by field.
     */
    @Test
    fun `a snapshot does not see an edit that lands after it was taken`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old", tag = null))
        val snapshot = state.snapshot()

        state.editRemark("r-1", "new", RemarkTag.BUG)
        state.markSent(setOf("r-1"))

        assertEquals("old", snapshot.single().text)
        assertNull(snapshot.single().tag)
        assertEquals(RemarkStatus.PENDING, snapshot.single().status)
    }

    /**
     * What makes the deep copy safe to keep. `BaseState.copyFrom` walks the property list the class
     * registers for itself, so a field added to RemarkState later is copied with no edit to
     * snapshot(). This compares the serialized form rather than listing fields, so it covers every
     * stored field by name and value including any added afterwards: a field the copy dropped would
     * disappear from workspace.xml with nothing logged.
     *
     * Every field is set to something OTHER than its default, and that is load-bearing. BaseState
     * omits a property still at its default when it serializes, so leaving severity, bucket and
     * commit alone made the comparison pass even if the copy dropped all three — which is what this
     * test is cited as proof against, in RemarkStore.snapshot()'s own doc.
     */
    @Test
    fun `a snapshot carries every field a remark is stored with`() {
        val state = RemarkStore.RemarksState()
        val original = remark(
            id = "r-1",
            path = "src/main/kotlin/Foo.kt",
            startLine = 10,
            endLine = 12,
            text = "why is this synchronized?",
            tag = RemarkTag.QUESTION,
            status = RemarkStatus.SENT,
            createdAt = 1_700_000_000_000L,
            textHash = "abcdef0123456789",
            contextBefore = "line a\nline b",
            contextAfter = "line c\nline d",
            severity = RemarkSeverity.MUST,
            bucket = "auth refactor",
            commit = "0123456789abcdef0123456789abcdef01234567",
        )
        state.addRemark(original)

        val copy = state.snapshot().single()

        assertNotSame(original, copy)
        assertEquals(asXml(original), asXml(copy))
    }

    /**
     * The race the deep copy closes, probed the same way the modification-count race below is: not
     * a deterministic reproduction, a bounded loop in which a bad pair may simply never appear.
     *
     * `editRemark` writes `text` and then `tag`, both under the lock. A reader holding the live
     * objects reads them at some later moment of its own, outside that lock, so it can catch the
     * new text next to the old tag — a prompt rendered with an edit half applied, and no later pass
     * fixes a prompt that already went to the clipboard. Proven by mutation: with `snapshot()` back
     * to `remarks.toList()` this fails almost immediately.
     */
    @Test(timeout = 5_000)
    fun `a reader never sees an edit half applied`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old", tag = null))

        val stopAt = System.nanoTime() + 300_000_000L // 300ms of racing, same as the probe below
        val mixed = AtomicReference<String>()

        val writer = Thread {
            while (System.nanoTime() < stopAt) {
                state.editRemark("r-1", "new", RemarkTag.BUG)
                state.editRemark("r-1", "old", null)
            }
        }
        val reader = Thread {
            while (System.nanoTime() < stopAt) {
                val seen = state.snapshot().single()
                val text = seen.text
                val tag = seen.tag
                if ((text == "new") != (tag == RemarkTag.BUG)) mixed.set("text=$text tag=$tag")
            }
        }

        writer.start()
        reader.start()
        writer.join()
        reader.join()

        mixed.get()?.let { fail("a reader saw an edit half applied: $it") }
    }

    /**
     * The guard for the whole reason RemarkStore implements PersistentStateComponent by hand.
     * The platform's serializer reads the object getState() returns, off the EDT and without
     * taking the store's lock, so that object must not be reachable by anything that mutates.
     */
    @Test
    fun `the state handed to the serializer does not change when a remark is added afterwards`() {
        val store = RemarkStore()
        store.add(remark(id = "r-1"))
        val handedOut = store.getState()

        store.add(remark(id = "r-2"))

        assertEquals(listOf("r-1"), handedOut.remarks.map { it.id })
        assertEquals(listOf("r-1", "r-2"), store.getState().remarks.map { it.id })
    }

    @Test
    fun `every call to getState hands out its own list instance`() {
        val store = RemarkStore()
        store.add(remark(id = "r-1"))

        assertNotSame(store.getState().remarks, store.getState().remarks)
    }

    @Test
    fun `what getState hands the serializer comes back through loadState`() {
        val store = RemarkStore()
        store.add(remark(id = "r-1"))
        store.add(remark(id = "r-2"))

        val restored = RemarkStore()
        restored.loadState(roundTrip(store.getState()))

        assertEquals(listOf("r-1", "r-2"), restored.all().map { it.id })
    }

    /**
     * The platform digs the state class out of the component's generic signature to know what to
     * deserialize workspace.xml into. It used to find it on the SimplePersistentStateComponent
     * superclass; now it has to find it on an implemented interface. If that ever stops working
     * the symptom is every stored remark vanishing on restart, with nothing logged.
     */
    @Test
    fun `the platform resolves RemarksState as the state class of the store`() {
        assertEquals(
            RemarkStore.RemarksState::class.java,
            ComponentSerializationUtil.getStateClass<RemarkStore.RemarksState>(RemarkStore::class.java),
        )
    }

    /**
     * The regression guard for the ConcurrentModificationException race: getStateModificationCount()
     * used to read `liveState.modificationCount` directly, bypassing every lock the mutators take.
     * BaseState.getModificationCount() walks the `remarks` list (through
     * ListStoredProperty.getModificationCount()) with no lock of its own, so a platform save
     * landing mid-walk while a remark was being added on another thread could throw. This is not a
     * deterministic reproduction of the race itself — it is a probe: one thread adds in a loop,
     * another reads stateModificationCount in a loop, for a short bounded time, and no exception
     * may escape. The list starts pre-populated so each read has enough to iterate over that a
     * concurrent add has a real chance of landing mid-walk.
     *
     * Proven by mutation: reverting getStateModificationCount() to read
     * `liveState.modificationCount` directly makes this fail with ConcurrentModificationException,
     * reliably, well inside the timeout below.
     */
    @Test(timeout = 5_000)
    fun `reading the modification count while adding does not throw`() {
        val store = RemarkStore()
        repeat(20_000) { store.add(remark(id = "seed-$it")) }

        val stopAt = System.nanoTime() + 300_000_000L // 300ms of racing is enough to trigger it
        val failure = AtomicReference<Throwable>()

        val writer = Thread {
            var i = 0
            while (System.nanoTime() < stopAt) {
                store.add(remark(id = "extra-${i++}"))
            }
        }
        val reader = Thread {
            try {
                while (System.nanoTime() < stopAt) {
                    store.stateModificationCount
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        writer.start()
        reader.start()
        writer.join()
        reader.join()

        failure.get()?.let { throw AssertionError("stateModificationCount read raced unsafely", it) }
    }

    private fun asXml(remark: RemarkState) = JDOMUtil.write(XmlSerializer.serialize(remark))

    private fun roundTrip(state: RemarkStore.RemarksState) = XmlSerializer.deserialize(
        JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(state))),
        RemarkStore.RemarksState::class.java,
    )
}
