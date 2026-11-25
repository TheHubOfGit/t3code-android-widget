package app.t3widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ClassifierTest {
    private val now = Instant.parse("2026-11-25T14:30:00Z")

    private fun load(name: String): ShellSnapshotDto {
        val text = javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()
        return T3Json.decodeFromString(ShellSnapshotDto.serializer(), text)
    }

    private val snapshot by lazy { load("shell_synthetic.json") }
    /** Uncapped, so every visible thread can be inspected. */
    private val all by lazy { Classifier.build(snapshot, now, limit = 100) }
    private fun row(id: String) = all.rows.firstOrNull { it.threadId == id }

    @Test fun `widget shows three rows - needs-you pinned first, oldest request first`() {
        val rows = Classifier.build(snapshot, now).rows
        assertEquals(3, rows.size)
        assertEquals(listOf("t-perm-old", "t-perm-new", "t-weird-kind"), rows.map { it.threadId })
    }

    @Test fun `after needs-you, the rest are newest first regardless of state`() {
        val rest = all.rows.drop(3).map { it.threadId }
        assertEquals(
            listOf(
                "t-run", "t-async-q", "t-future", "t-done-recent", "t-done-settled",
                "t-fail", "t-err-obj", "t-wait", "t-snooze-over", "t-done-old",
            ),
            rest,
        )
    }

    @Test fun `with nothing waiting, the latest three are shown`() {
        val quiet = snapshot.copy(threads = snapshot.threads.map { it.copy(pendingRuntimeRequest = null) })
        val rows = Classifier.build(quiet, now).rows.map { it.threadId }
        assertEquals(listOf("t-perm-new", "t-run", "t-async-q"), rows)
    }

    @Test fun `needs-you details`() {
        assertEquals("r1", row("t-perm-old")!!.requestId)
        assertEquals("permission", row("t-perm-old")!!.requestKind)
        assertEquals("needs command approval", row("t-perm-new")!!.detail)
    }

    @Test fun `non-blocking request does not count as needs-you`() {
        assertEquals(ThreadState.RUNNING, row("t-async-q")!!.state)
        assertNull(row("t-async-q")!!.requestId)
    }

    @Test fun `activityRunStatus alone marks running`() {
        assertEquals(ThreadState.RUNNING, row("t-run")!!.state)
        assertEquals("Updating middleware…", row("t-run")!!.detail)
    }

    @Test fun `failed by status or by lastError, including object-shaped errors`() {
        assertEquals(ThreadState.FAILED, row("t-fail")!!.state)
        assertEquals("Provider crashed", row("t-fail")!!.detail)
        assertEquals(ThreadState.FAILED, row("t-err-obj")!!.state)
        assertEquals("boom", row("t-err-obj")!!.detail)
    }

    @Test fun `waiting without request is waiting`() {
        assertEquals(ThreadState.WAITING, row("t-wait")!!.state)
    }

    @Test fun `unknown status and kind still render`() {
        assertEquals(ThreadState.DONE, row("t-future")!!.state)
        assertEquals("teleporting", row("t-future")!!.rawStatus)
        val weird = row("t-weird-kind")!!
        assertEquals("needs telepathy", weird.detail)
        assertEquals("Untitled thread", weird.title)
        assertEquals("", weird.projectTitle)
    }

    @Test fun `archived, deleted, snoozed and id-less threads are hidden`() {
        listOf("t-archived", "t-snoozed").forEach { assertNull(it, row(it)) }
        assertTrue(all.rows.none { it.title == "No id" })
        assertEquals(ThreadState.RUNNING, row("t-snooze-over")!!.state)
    }

    @Test fun `counts`() {
        assertEquals(Counts(needsYou = 3, running = 3, failed = 2, waiting = 1), all.counts)
    }

    @Test fun `project title falls back to workspace folder name`() {
        assertEquals("api-server", row("t-fail")!!.projectTitle)
    }

    @Test fun `project icons map from T3 Code icon kinds`() {
        fun icon(projectId: String, title: String) =
            Classifier.projectIcon(snapshot.projects.first { it.id == projectId }, projectId, title)
        val emoji = icon("p3", "Emoji Proj")
        assertEquals("emoji" to "⚡", emoji.kind to emoji.value)
        assertEquals("blue", emoji.color) // no color given → T3's title-derived color

        val lucide = icon("p4", "Lucide Proj")
        assertEquals(Triple("lucide", "rocket", "sky"), Triple(lucide.kind, lucide.value, lucide.color))
        assertEquals("LP", lucide.fallbackMonogram)

        val mono = icon("p5", "Mono Proj")
        assertEquals(Triple("monogram", "XY", "rose"), Triple(mono.kind, mono.value, mono.color))

        // Unknown kinds fall through to favicon / auto monogram, as in T3 Code.
        val unknown = icon("p6", "Future Proj")
        assertEquals(Triple("auto", "FP", "orange"), Triple(unknown.kind, unknown.value, unknown.color))

        // No icon set: favicon if the server has one, else T3's auto monogram and color.
        val run = row("t-run")!!
        assertEquals(Triple("auto", "MA", "orange"), Triple(run.icon!!.kind, run.icon!!.value, run.icon!!.color))
        assertEquals("/src/my-app", run.workspaceRoot)
    }

    /** Expected values come from running T3 Code's own projectMonogram / projectMonogramColor. */
    @Test fun `monograms and colors match T3 Code`() {
        val expected = listOf(
            Triple("my-app", "MA", "orange"),
            Triple("docs", "DS", "blue"),
            Triple("t3code android_widget", "T3", "pink"),
            Triple("", "PR", "fuchsia"),
            Triple("Visual Git", "VG", "purple"),
            Triple("Pixel Watch Mod", "PM", "emerald"),
            Triple("Codex Account Switcher", "CS", "amber"),
            Triple("~", "PR", "gray"),
            Triple("東京 app", "東", "green"),
            Triple("Proj 2go", "P2", "violet"),
            Triple("a", "AA", "emerald"),
        )
        for ((title, mono, color) in expected) {
            assertEquals("monogram of '$title'", mono, Classifier.monogram(title))
            assertEquals("color of '$title'", color, Classifier.monogramColor(title))
        }
    }

    @Test fun `real recorded snapshot parses and yields three rows`() {
        val snap = load("shell_real.json")
        assertEquals(2, snap.schemaVersion)
        val result = Classifier.build(snap, Instant.parse("2026-11-25T12:48:00Z"))
        assertEquals(3, result.rows.size)
        assertTrue(result.counts.running >= 1)
        assertTrue(result.rows.all { it.projectTitle.isNotEmpty() && it.icon != null })
    }
}
