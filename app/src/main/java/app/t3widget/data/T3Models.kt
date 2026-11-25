package app.t3widget.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire DTOs. Deliberately tiny: we read ~10 of the ~70 thread fields, every field is
 * nullable/defaulted, and enums are kept as raw strings (HANDOFF.md §8, rules 1-2).
 */

val T3Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    isLenient = true
}

@Serializable
data class EnvironmentDto(
    val environmentId: String? = null,
    val label: String? = null,
    val serverVersion: String? = null,
    val orchestrationProtocolVersion: Int? = null,
)

@Serializable
data class ShellSnapshotDto(
    val schemaVersion: Int? = null,
    val snapshotSequence: Long? = null,
    val projects: List<ProjectDto> = emptyList(),
    val threads: List<ThreadDto> = emptyList(),
)

@Serializable
data class ProjectDto(
    val id: String? = null,
    val title: String? = null,
    val workspaceRoot: String? = null,
    val archivedAt: String? = null,
    val projectIcon: ProjectIconDto? = null,
)

/**
 * The icon a user picked for a project in T3 Code. Observed kinds: `monogram` {text, color},
 * `emoji` {emoji}, `lucide` {name, color}. Colors are Tailwind-style names ("violet", "sky", …).
 * Repo favicons are not in the snapshot (they need file-read access), so they are not supported.
 */
@Serializable
data class ProjectIconDto(
    val kind: String? = null,
    val text: String? = null,
    val emoji: String? = null,
    val name: String? = null,
    val color: String? = null,
)

@Serializable
data class ThreadDto(
    val id: String? = null,
    val projectId: String? = null,
    val title: String? = null,
    val status: String? = null,
    val activityRunStatus: String? = null,
    val pendingRuntimeRequest: PendingRequestDto? = null,
    /** Kept as a raw element: observed as null so far; tolerate a string or an object. */
    val lastError: JsonElement? = null,
    val latestVisibleMessage: MessageDto? = null,
    val updatedAt: String? = null,
    val archivedAt: String? = null,
    val deletedAt: String? = null,
    val settledAt: String? = null,
    val snoozedUntil: String? = null,
) {
    val lastErrorText: String?
        get() = when (val e = lastError) {
            null, JsonNull -> null
            is JsonPrimitive -> e.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> (e["message"] as? JsonPrimitive)?.contentOrNull ?: e.toString()
            else -> e.toString()
        }
}

@Serializable
data class PendingRequestDto(
    val id: String? = null,
    val kind: String? = null,
    val createdAt: String? = null,
    val blocking: Boolean? = null,
    val userOnly: Boolean? = null,
)

@Serializable
data class MessageDto(
    val id: String? = null,
    val role: String? = null,
    val text: String? = null,
    val updatedAt: String? = null,
)
