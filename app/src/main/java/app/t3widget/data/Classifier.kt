package app.t3widget.data

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** Widget-facing state, ordered by how urgently it needs the user. */
@Serializable
enum class ThreadState { NEEDS_YOU, FAILED, RUNNING, WAITING, DONE }

/** What to draw inside a row's state shape. */
@Serializable
data class ProjectIcon(
    /**
     * "monogram", "emoji" or "lucide" when the project sets an icon; "auto" when it doesn't, in
     * which case T3 shows the repo favicon if the server finds one, else the monogram in [value].
     */
    val kind: String,
    /** Monogram text, the emoji, or the Lucide icon name. */
    val value: String,
    /** T3 Code color name, e.g. "violet"; never null (derived from the project id if unset). */
    val color: String,
    /** Monogram to use if a Lucide icon is not bundled. */
    val fallbackMonogram: String,
)

@Serializable
data class ThreadRow(
    val threadId: String,
    val projectTitle: String,
    val title: String,
    val state: ThreadState,
    /** Raw `status` string, kept so unknown values can still be shown. */
    val rawStatus: String,
    /** e.g. "permission", "command"; null unless [state] is NEEDS_YOU. */
    val requestKind: String? = null,
    val requestId: String? = null,
    val detail: String? = null,
    val updatedAt: String? = null,
    val icon: ProjectIcon? = null,
    /** Project folder on the server; the key for its favicon. */
    val workspaceRoot: String? = null,
)

@Serializable
data class Counts(val needsYou: Int = 0, val running: Int = 0, val failed: Int = 0, val waiting: Int = 0)

/** Pure classification of a shell snapshot (HANDOFF.md §5.4). No Android dependencies. */
object Classifier {
    private val RUNNING_STATUSES = setOf("preparing", "queued", "starting", "running")
    private val RUNNING_ACTIVITY = setOf("preparing", "starting", "running")

    /** The widget shows this many threads. */
    const val MAX_ROWS = 3

    /** T3 Code's project icon palette; used to pick a stable color for projects without one. */
    /** Same order as T3 Code's `ProjectIconColor` literals; the auto color indexes into it. */
    val ICON_COLORS = listOf(
        "gray", "red", "orange", "amber", "yellow", "lime", "green", "emerald", "teal", "cyan",
        "sky", "blue", "indigo", "violet", "purple", "fuchsia", "pink", "rose",
    )

    fun classify(thread: ThreadDto): ThreadState {
        val status = thread.status
        val request = thread.pendingRuntimeRequest
        val needsYou = request != null && request.blocking != false
        val running = status in RUNNING_STATUSES || thread.activityRunStatus in RUNNING_ACTIVITY
        return when {
            needsYou -> ThreadState.NEEDS_YOU
            status == "failed" -> ThreadState.FAILED
            running -> ThreadState.RUNNING
            thread.lastErrorText != null && thread.settledAt == null -> ThreadState.FAILED
            status == "waiting" -> ThreadState.WAITING
            else -> ThreadState.DONE
        }
    }

    fun isHidden(thread: ThreadDto, now: Instant): Boolean {
        if (thread.id.isNullOrBlank()) return true
        if (thread.archivedAt != null || thread.deletedAt != null) return true
        val snoozed = parseInstant(thread.snoozedUntil)
        return snoozed != null && snoozed.isAfter(now)
    }

    data class Result(val rows: List<ThreadRow>, val counts: Counts)

    /**
     * Rows = threads waiting on the user (oldest request first), then the most recently updated
     * of everything else, capped at [limit].
     */
    fun build(snapshot: ShellSnapshotDto, now: Instant = Instant.now(), limit: Int = MAX_ROWS): Result {
        val projects = snapshot.projects.filter { it.id != null }.associateBy { it.id }

        val classified = snapshot.threads
            .filterNot { isHidden(it, now) }
            .map { it to classify(it) }

        val counts = Counts(
            needsYou = classified.count { it.second == ThreadState.NEEDS_YOU },
            running = classified.count { it.second == ThreadState.RUNNING },
            failed = classified.count { it.second == ThreadState.FAILED },
            waiting = classified.count { it.second == ThreadState.WAITING },
        )

        val (needsYou, rest) = classified.partition { it.second == ThreadState.NEEDS_YOU }
        val ordered = needsYou.sortedBy { it.first.pendingRuntimeRequest?.createdAt ?: "" } +
            rest.sortedByDescending { it.first.updatedAt ?: "" }

        val rows = ordered.take(limit).map { (t, s) -> toRow(t, s, projects[t.projectId]) }
        return Result(rows, counts)
    }

    private fun toRow(t: ThreadDto, s: ThreadState, project: ProjectDto?): ThreadRow {
        val request = t.pendingRuntimeRequest.takeIf { s == ThreadState.NEEDS_YOU }
        val detail = when (s) {
            ThreadState.NEEDS_YOU -> "needs ${humanKind(request?.kind)}"
            ThreadState.FAILED -> t.lastErrorText ?: "failed"
            else -> t.latestVisibleMessage?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }
        }
        val projectTitle = project?.displayName().orEmpty()
        return ThreadRow(
            threadId = t.id!!,
            projectTitle = projectTitle,
            title = t.title?.takeIf { it.isNotBlank() } ?: "Untitled thread",
            state = s,
            rawStatus = t.status ?: "unknown",
            requestKind = request?.kind,
            requestId = request?.id,
            detail = detail?.take(160),
            updatedAt = t.updatedAt,
            icon = projectIcon(project, t.projectId, projectTitle),
            workspaceRoot = project?.workspaceRoot?.takeIf { it.isNotBlank() },
        )
    }

    /** Mirrors T3 Code's `resolveProjectIconGlyph` / `projectAutoMonogram` (apps/mobile/src/lib/projectIcon.ts). */
    @Suppress("UNUSED_PARAMETER")
    fun projectIcon(project: ProjectDto?, projectId: String?, projectTitle: String): ProjectIcon {
        val icon = project?.projectIcon
        val mono = monogram(projectTitle)
        val color = icon?.color?.takeIf { it in ICON_COLORS } ?: monogramColor(projectTitle)
        return when {
            icon?.kind == "emoji" && !icon.emoji.isNullOrBlank() -> ProjectIcon("emoji", icon.emoji, color, mono)
            icon?.kind == "lucide" && !icon.name.isNullOrBlank() -> ProjectIcon("lucide", icon.name, color, mono)
            icon?.kind == "monogram" && !icon.text.isNullOrBlank() -> ProjectIcon("monogram", icon.text, color, mono)
            // No icon, or a kind this build doesn't know: favicon, else the auto monogram.
            else -> ProjectIcon("auto", mono, monogramColor(projectTitle), mono)
        }
    }

    /** Port of T3 Code's `projectMonogram` (packages/shared/src/projectMonogram.ts). */
    fun monogram(title: String): String {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(Normalizer.normalize(title, Normalizer.Form.NFKC).trim())
            .map { it.value }.toList()
        val firstWord = words.firstOrNull() ?: return "PR"
        val glyphs = firstWord.codePoints().toArray().map { String(Character.toChars(it)) }
        val first = glyphs.first()
        if (isSingleGlyphScript(first.codePointAt(0))) return first
        val second = glyphs.drop(1).firstOrNull { Character.isDigit(it.codePointAt(0)) }
            ?: (if (words.size > 1) words.last().let { String(Character.toChars(it.codePointAt(0))) } else glyphs.last())
        val combined = (first + second).uppercase(Locale.ROOT)
        return combined.codePoints().limit(2).toArray().joinToString("") { String(Character.toChars(it)) }
    }

    /** Port of T3 Code's `projectMonogramColor`: a stable color from the title. */
    fun monogramColor(title: String): String {
        val seed = Normalizer.normalize(title, Normalizer.Form.NFKC).trim().lowercase(Locale.US).ifEmpty { "project" }
        var index = 0
        seed.codePoints().forEach { cp -> index = (index * 31 + cp) % ICON_COLORS.size }
        return ICON_COLORS[index]
    }

    private fun isSingleGlyphScript(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    fun humanKind(kind: String?): String = when (kind) {
        "command" -> "command approval"
        "file-read" -> "file read approval"
        "file-change" -> "file change approval"
        "permission" -> "permission"
        "user_input" -> "your answer"
        "mcp-elicitation" -> "MCP input"
        "dynamic_tool_call" -> "tool call approval"
        "auth_refresh" -> "sign-in"
        null -> "attention"
        else -> kind.replace('_', ' ').replace('-', ' ')
    }

    private fun ProjectDto.displayName(): String =
        title?.takeIf { it.isNotBlank() }
            ?: workspaceRoot?.trimEnd('/')?.substringAfterLast('/')
            ?: ""

    fun parseInstant(value: String?): Instant? =
        value?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
