package dev.sasha.clauderemarks.store

import com.intellij.configurationStore.ComponentSerializationUtil
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
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
            startColumn = 5,
            endColumn = 9,
            text = "why is this synchronized?",
            asksForAnswer = true,
            status = RemarkStatus.PUBLISHED,
            createdAt = 1_700_000_000_000L,
            readAt = 1_700_000_500_000L,
            textHash = "abcdef0123456789",
            contextBefore = "line a\nline b",
            contextAfter = "line c\nline d",
            commit = "0123456789abcdef0123456789abcdef01234567",
            phrase = "is this synchronized",
        )
        val state = RemarkStore.RemarksState()
        state.addRemark(original)

        val restored = roundTrip(state)

        assertEquals(1, restored.remarks.size)
        assertEquals(asXml(original), asXml(restored.remarks.single()))
    }

    /**
     * A remark an older build wrote, carrying `severity` and `tag`, must still load. Those two
     * properties are no longer declared on RemarkState, so the deserializer skips them as unknown,
     * and everything else has to come back untouched.
     *
     * This is the only guard that an existing user's workspace.xml still loads after phase 11 deleted
     * the two fields, so it asserts what had to survive — the id, the path, the text and the status —
     * and not only that one element came back. An unknown property is skipped unconditionally, so a
     * count on its own passes against a RemarkState that dropped every one of those four.
     *
     * ⚠️ **Written the way BaseState actually stores a property: an `<option name= value=/>` child,
     * not an XML attribute.** This test, and the three "stored before X existed" tests below, all
     * used attribute form until phase 11's review. Attribute form parses into a RemarkState with
     * every property still at its default, so each of those tests passed against any RemarkState at
     * all — they were checking a shape no workspace.xml has ever held. Anything added here has to
     * keep the option form or it stops testing migration.
     */
    @Test
    fun `a remark stored with the old severity and tag options still loads`() {
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(
                """
                <RemarksState><remarks><RemarkState>
                  <option name="id" value="r-1" />
                  <option name="path" value="src/Foo.kt" />
                  <option name="severity" value="MUST" />
                  <option name="tag" value="BUG" />
                  <option name="text" value="old remark" />
                  <option name="status" value="PUBLISHED" />
                </RemarkState></remarks></RemarksState>
                """.trimIndent()
            ),
            RemarkStore.RemarksState::class.java,
        )

        assertEquals(1, restored.remarks.size)
        val remark = restored.remarks.single()
        assertEquals("r-1", remark.id)
        assertEquals("src/Foo.kt", remark.path)
        assertEquals("old remark", remark.text)
        assertEquals(RemarkStatus.PUBLISHED, remark.status)
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
    fun `editing a remark changes its text and marks the state as changed`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old"))
        val before = state.modificationCount

        assertTrue(state.editRemark("r-1", "new"))

        assertEquals("new", state.snapshot().single().text)
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `editing an id that is not there changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old"))
        val before = state.modificationCount

        assertFalse(state.editRemark("no-such-id", "new"))

        assertEquals("old", state.snapshot().single().text)
        assertEquals(before, state.modificationCount)
    }

    @Test
    fun `marking published only touches the ids given`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.addRemark(remark(id = "r-2"))
        val before = state.modificationCount

        assertEquals(1, state.markPublished(setOf("r-1")))

        assertEquals(RemarkStatus.PUBLISHED, state.snapshot().first { it.id == "r-1" }.status)
        assertEquals(RemarkStatus.PENDING, state.snapshot().first { it.id == "r-2" }.status)
        // markPublished writes a FIELD on a remark that is already in the list, which is not the
        // same as adding or removing a list element. Whether that alone would reach the outer
        // state's modification count is not settled, and if it does not, the PUBLISHED flag is lost
        // on restart with nothing logged. So the count is pinned here.
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `marking a remark published twice does not change it a second time`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.markPublished(setOf("r-1"))
        val before = state.modificationCount

        assertEquals(0, state.markPublished(setOf("r-1")))

        assertEquals(before, state.modificationCount)
    }

    /**
     * `markPublished` filters on `status != PUBLISHED`, not on `status == PENDING`: Publish
     * Selected exists exactly to re-publish something already handed over, so a READ remark has to
     * move back to PUBLISHED, not be skipped as already handled.
     */
    @Test
    fun `marking published moves a read remark back to published`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", status = RemarkStatus.READ))

        assertEquals(1, state.markPublished(setOf("r-1")))

        assertEquals(RemarkStatus.PUBLISHED, state.snapshot().single().status)
    }

    @Test
    fun `removing handed over keeps the pending ones`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", status = RemarkStatus.PUBLISHED))
        state.addRemark(remark(id = "r-2", status = RemarkStatus.PENDING))
        val before = state.modificationCount

        assertEquals(1, state.removeHandedOver())

        assertEquals(listOf("r-2"), state.snapshot().map { it.id })
        assertTrue(state.modificationCount > before)
    }

    /** `removeHandedOver` takes out READ remarks too, not only PUBLISHED ones. */
    @Test
    fun `removing handed over takes out read remarks too`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", status = RemarkStatus.READ))
        state.addRemark(remark(id = "r-2", status = RemarkStatus.PENDING))

        assertEquals(1, state.removeHandedOver())

        assertEquals(listOf("r-2"), state.snapshot().map { it.id })
    }

    @Test
    fun `removing handed over when there are none changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val before = state.modificationCount

        assertEquals(0, state.removeHandedOver())

        assertEquals(before, state.modificationCount)
    }

    @Test
    fun `clear removes everything`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.addRemark(remark(id = "r-2", status = RemarkStatus.PUBLISHED))
        val before = state.modificationCount

        assertEquals(2, state.clear())

        assertEquals(0, state.snapshot().size)
        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `published and read both survive the round trip`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", status = RemarkStatus.PUBLISHED))
        state.addRemark(remark(id = "r-2", status = RemarkStatus.READ))

        val restored = roundTrip(state).remarks

        assertEquals(RemarkStatus.PUBLISHED, restored.first { it.id == "r-1" }.status)
        assertEquals(RemarkStatus.READ, restored.first { it.id == "r-2" }.status)
    }

    @Test
    fun `an edited remark survives the round trip through xml`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old"))
        state.editRemark("r-1", "new")
        state.markPublished(setOf("r-1"))

        val restored = roundTrip(state).remarks.single()

        assertEquals("new", restored.text)
        assertEquals(RemarkStatus.PUBLISHED, restored.status)
    }

    /**
     * The storage half of "a remark records the selected columns": startColumn/endColumn are
     * ordinary BaseState int properties, same as startLine/endLine, but this pins that they
     * actually reach workspace.xml and come back rather than being dropped somewhere in the
     * XmlSerializer round trip.
     */
    @Test
    fun `startColumn and endColumn survive the round trip`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", startColumn = 3, endColumn = 7))

        val restored = roundTrip(state).remarks.single()

        assertEquals(3, restored.startColumn)
        assertEquals(7, restored.endColumn)
    }

    /**
     * Every remark stored before this feature existed has neither attribute in its XML element at
     * all: BaseState omits a property still at its default when it serializes. Both must come back
     * as 0, which is exactly the "no sub-line range, whole lines" meaning the field's KDoc pins —
     * not a null and not a crash, so an old remark keeps rendering exactly as it did before.
     */
    @Test
    fun `a remark stored before columns existed loads with both at 0`() {
        val restored = deserializeOne(
            """<option name="id" value="r-1" /><option name="path" value="src/Foo.kt" />"""
        )

        assertEquals("r-1", restored.id)
        assertEquals(0, restored.startColumn)
        assertEquals(0, restored.endColumn)
    }

    /** The storage half of "a sub-line remark stores the words it points at". */
    @Test
    fun `the phrase survives the round trip`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", phrase = "why is this synchronized"))

        assertEquals("why is this synchronized", roundTrip(state).remarks.single().phrase)
    }

    /**
     * Every remark stored before this field existed, and every whole-line remark since, has no
     * phrase attribute in its XML element at all: BaseState omits a property still at its default.
     * Null must come back, not an empty string, so the anchor for those remarks stays unchanged.
     */
    @Test
    fun `a remark with no phrase round-trips as null`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", phrase = null))

        assertNull(roundTrip(state).remarks.single().phrase)
    }

    /**
     * The accepted reset, pinned so it is a decision and not a surprise: a remark an older build
     * wrote as "SENT" does not parse against the new enum — which has no SENT constant — and comes
     * back at the delegate's default, PENDING. Nothing is lost but the colour; the remark had
     * already been handed over once.
     */
    @Test
    fun `a remark stored as SENT by an older build loads as pending`() {
        val restored = deserializeOne(
            """<option name="id" value="r-1" /><option name="path" value="src/Foo.kt" />""" +
                """<option name="status" value="SENT" />"""
        )

        assertEquals("r-1", restored.id)
        assertEquals(RemarkStatus.PENDING, restored.status)
    }


    /**
     * A remark stored by an older build carrying `<option name="bucket" value="x"/>` must still
     * load, the same migration guard phase 11 pinned for `tag` and `severity` above. `bucket` is no
     * longer declared on RemarkState, so the deserializer skips it as unknown, and the option is
     * gone from the XML the next time this remark is saved.
     *
     * ⚠️ Written in `<option>` form, the way BaseState actually stores a property — see the note on
     * the severity and tag test above for why attribute form tests nothing at all.
     */
    @Test
    fun `a remark stored with the old bucket option still loads and drops it on the next save`() {
        val restored = deserializeOne(
            """
            <option name="id" value="r-1" />
            <option name="path" value="src/Foo.kt" />
            <option name="bucket" value="auth refactor" />
            <option name="text" value="old remark" />
            """.trimIndent()
        )

        assertEquals("r-1", restored.id)
        assertEquals("src/Foo.kt", restored.path)
        assertEquals("old remark", restored.text)
        assertFalse(asXml(restored), asXml(restored).contains("bucket"))
    }

    /**
     * Every remark stored before this field existed has no `readAt` attribute in its XML element
     * at all: BaseState omits a property still at its default when it serializes. 0 must come
     * back — "never read" — the same no-migration shape [phrase] and the columns above already
     * use, and it is what lets Done fall back to `createdAt` for a remark read before this phase.
     */
    @Test
    fun `a remark stored before readAt existed loads with it at 0`() {
        val restored = deserializeOne(
            """<option name="id" value="r-1" /><option name="path" value="src/Foo.kt" />"""
        )

        assertEquals("r-1", restored.id)
        assertEquals(0L, restored.readAt)
    }

    /**
     * The migration half of the asks-for-an-answer flag, in the opposite direction to the severity
     * one above: this field is arriving rather than leaving. False is the default, so BaseState omits
     * it from the XML altogether, which is what makes every remark stored before the field existed
     * load as an ordinary remark with nothing to migrate. If the attribute ever started being written
     * at its default the omission argument would be quietly untrue, so it is asserted rather than
     * assumed.
     */
    @Test
    fun `asksForAnswer defaults to false and is left out of the xml`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))

        val stored = state.snapshot().single()

        assertFalse(stored.asksForAnswer)
        assertFalse(asXml(stored), asXml(stored).contains("asksForAnswer"))
    }

    /** A remark written by a build that had no such field at all: no attribute, and false. */
    @Test
    fun `a remark stored before asksForAnswer existed loads as false`() {
        val restored = deserializeOne(
            """<option name="id" value="r-1" /><option name="path" value="src/Foo.kt" />"""
        )

        assertEquals("r-1", restored.id)
        assertFalse(restored.asksForAnswer)
    }

    /** The other half: once it is true it has to reach workspace.xml and come back. */
    @Test
    fun `asksForAnswer survives the round trip when it is set`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", asksForAnswer = true))

        assertTrue(roundTrip(state).remarks.single().asksForAnswer)
    }

    /** The storage half of "readAt is stamped once and stays put": once it is non-zero it has to
     *  reach workspace.xml and come back, the same as every other timestamp on this class. */
    @Test
    fun `readAt survives the round trip when it is set`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", readAt = 1_700_000_500_000L))

        assertEquals(1_700_000_500_000L, roundTrip(state).remarks.single().readAt)
    }

    @Test
    fun `setting asksForAnswer writes it and clearing it writes false`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))

        assertEquals(1, state.setAsksForAnswer(setOf("r-1"), true))
        assertTrue(state.snapshot().single().asksForAnswer)

        assertEquals(1, state.setAsksForAnswer(setOf("r-1"), false))
        assertFalse(state.snapshot().single().asksForAnswer)
    }

    /** The toggle acts on several rows at once, and only the ids it was given. */
    @Test
    fun `setting asksForAnswer touches only the ids given`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.addRemark(remark(id = "r-2"))
        state.addRemark(remark(id = "r-3"))

        assertEquals(2, state.setAsksForAnswer(setOf("r-1", "r-2"), true))

        assertTrue(state.snapshot().first { it.id == "r-1" }.asksForAnswer)
        assertTrue(state.snapshot().first { it.id == "r-2" }.asksForAnswer)
        assertFalse(state.snapshot().first { it.id == "r-3" }.asksForAnswer)
    }

    @Test
    fun `setting asksForAnswer to what it already is changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", asksForAnswer = true))
        val before = state.modificationCount

        assertEquals(0, state.setAsksForAnswer(setOf("r-1"), true))

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
        state.addRemark(remark(id = "r-1", text = "old"))
        val snapshot = state.snapshot()

        state.editRemark("r-1", "new")
        state.markPublished(setOf("r-1"))

        assertEquals("old", snapshot.single().text)
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
     * omits a property still at its default when it serializes, so leaving commit alone made the
     * comparison pass even if the copy dropped it — which is what this test is cited as proof
     * against, in RemarkStore.snapshot()'s own doc.
     */
    @Test
    fun `a snapshot carries every field a remark is stored with`() {
        val state = RemarkStore.RemarksState()
        val original = remark(
            id = "r-1",
            path = "src/main/kotlin/Foo.kt",
            startLine = 10,
            endLine = 12,
            startColumn = 5,
            endColumn = 9,
            text = "why is this synchronized?",
            asksForAnswer = true,
            status = RemarkStatus.PUBLISHED,
            createdAt = 1_700_000_000_000L,
            readAt = 1_700_000_500_000L,
            textHash = "abcdef0123456789",
            contextBefore = "line a\nline b",
            contextAfter = "line c\nline d",
            commit = "0123456789abcdef0123456789abcdef01234567",
            phrase = "is this synchronized",
        )
        state.addRemark(original)

        val copy = state.snapshot().single()

        assertNotSame(original, copy)
        assertEquals(asXml(original), asXml(copy))
    }

    /**
     * The answers half of the deep copy, and it exists for the same reason the remark half above
     * does: a reader must not share an object with the live state. The tree, the gutter and the
     * resolver all walk answers on a pooled thread long after they have left the lock, so a shallow
     * `answers.toList()` would hand them the live objects.
     *
     * Compared as serialized XML rather than field by field, so a field added to AnswerState later is
     * covered with no edit here — and, as in the remark twin, every field is set away from its
     * default, because BaseState omits a property still at its default and a dropped field would
     * otherwise compare equal.
     */
    @Test
    fun `a snapshot carries every field an answer is stored with`() {
        val state = RemarkStore.RemarksState()
        val original = answer(
            id = "a-1",
            remarkId = "r-7",
            question = "why is this synchronized?",
            markdown = "# Because\n\ntwo threads write it.",
            answeredAt = 1_700_000_000_000L,
            path = "src/main/kotlin/Foo.kt",
            startLine = 10,
            endLine = 12,
            startColumn = 5,
            endColumn = 9,
            textHash = "abcdef0123456789",
            contextBefore = "line a\nline b",
            contextAfter = "line c\nline d",
            phrase = "is this synchronized",
            commit = "0123456789abcdef0123456789abcdef01234567",
        )
        state.putAnswer(original)

        val copy = state.answersSnapshot().single()

        assertNotSame(original, copy)
        assertEquals(asXml(original), asXml(copy))
    }

    /**
     * The race the deep copy closes, probed the same way the modification-count race below is: not
     * a deterministic reproduction, a bounded loop in which a bad pair may simply never appear.
     *
     * This used to pair `text` against `tag`, because `editRemark` wrote both. Phase 11 took the tag
     * off a remark, so there is no second field left to catch half written, and the pair test would
     * pass no matter what `snapshot()` did. What is still true, and is what the deep copy actually
     * buys, is this: a remark a reader is holding must not change under it. A reader walks these
     * objects on a pooled thread for as long as rendering a whole prompt takes, and a shallow copy
     * hands it the live object, so the same field read twice comes back with two answers and the
     * prompt is rendered from a remark that was never in one piece. Proven by mutation: with
     * `snapshot()` back to `remarks.toList()` this fails almost immediately.
     */
    @Test(timeout = 5_000)
    fun `a remark inside a snapshot does not change under its reader`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1", text = "old"))

        val stopAt = System.nanoTime() + 300_000_000L // 300ms of racing, same as the probe below
        val mixed = AtomicReference<String>()

        val writer = Thread {
            while (System.nanoTime() < stopAt) {
                state.editRemark("r-1", "new")
                state.editRemark("r-1", "old")
            }
        }
        val reader = Thread {
            while (System.nanoTime() < stopAt) {
                val seen = state.snapshot().single()
                val first = seen.text
                // A window for the writer to land an edit. With a shallow copy `seen` IS the live
                // remark, so the second read comes back with the other text; with a deep copy the
                // object the reader holds is its own and cannot move.
                Thread.yield()
                if (seen.text != first) mixed.set("text went from $first to ${seen.text}")
            }
        }

        writer.start()
        reader.start()
        writer.join()
        reader.join()

        mixed.get()?.let { fail("a remark changed under the reader holding it: $it") }
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

    /**
     * getState() builds a fresh RemarksState by hand, so a second list is a second line to write and
     * a second line to forget. Forgetting it loses every answer on the next save with nothing logged,
     * which is the same silent loss the missing @get:XCollection would cause and looks identical from
     * the outside.
     */
    @Test
    fun `what getState hands the serializer carries the answers too`() {
        val store = RemarkStore()
        store.putAnswer(answer(id = "a-1", remarkId = "r-1"))

        assertEquals(listOf("a-1"), store.getState().answers.map { it.id })
    }

    /** The answers half of the guard above it: the serializer never iterates a list anyone can
     *  still mutate. */
    @Test
    fun `every call to getState hands out its own answers list instance`() {
        val store = RemarkStore()
        store.putAnswer(answer(id = "a-1", remarkId = "r-1"))

        assertNotSame(store.getState().answers, store.getState().answers)
    }

    /** The half with teeth: an answer added after the serializer was handed its state must not
     *  appear in what it is holding. */
    @Test
    fun `the state handed to the serializer does not change when an answer is added afterwards`() {
        val store = RemarkStore()
        store.putAnswer(answer(id = "a-1", remarkId = "r-1"))
        val handedOut = store.getState()

        store.putAnswer(answer(id = "a-2", remarkId = "r-2"))

        assertEquals(listOf("a-1"), handedOut.answers.map { it.id })
        assertEquals(listOf("a-1", "a-2"), store.getState().answers.map { it.id })
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

    /**
     * One stored remark, written the way BaseState really writes one — `<option name= value=/>`
     * children — and read back. [options] is the property list, already in that form.
     *
     * A helper rather than the XML spelled out per test, because getting the shape wrong is silent:
     * anything the deserializer does not recognise leaves every property at its default, so a test
     * built on the wrong shape asserts nothing at all. That is exactly what the attribute-form XML
     * these tests used to carry did.
     */
    private fun deserializeOne(options: String): RemarkState = XmlSerializer.deserialize(
        JDOMUtil.load("<RemarksState><remarks><RemarkState>$options</RemarkState></remarks></RemarksState>"),
        RemarkStore.RemarksState::class.java,
    ).remarks.single()

    private fun asXml(remark: RemarkState) = JDOMUtil.write(XmlSerializer.serialize(remark))

    private fun asXml(answer: AnswerState) = JDOMUtil.write(XmlSerializer.serialize(answer))

    private fun roundTrip(state: RemarkStore.RemarksState) = XmlSerializer.deserialize(
        JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(state))),
        RemarkStore.RemarksState::class.java,
    )
}
