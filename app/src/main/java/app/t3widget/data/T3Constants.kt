package app.t3widget.data

/**
 * Every point of coupling to T3 Code's internal API lives here (HANDOFF.md §8, rule 6).
 * A protocol bump should be a change to this file plus model fixes.
 */
object T3Constants {
    const val ORCHESTRATION_PROTOCOL_HEADER = "x-t3-orchestration-protocol"
    const val ORCHESTRATION_PROTOCOL_VERSION = 2

    const val ENVIRONMENT_PATH = "/.well-known/t3/environment"
    const val SHELL_PATH = "/api/orchestration/shell"

    /** Never add `resumeFrom`: the server may then answer `{"resumable": true}` instead of a snapshot. */
    val SHELL_QUERY = mapOf(
        "omitIdleSubagents" to "true",
        "omitOlderSettled" to "true",
    )

    const val THREAD_LINK_PREFIX = "https://app.t3.codes/threads/"
    val OFFICIAL_APP_PACKAGES = listOf("com.t3tools.t3code", "com.t3tools.t3code.preview", "com.t3tools.t3code.dev")

    const val DEFAULT_PORT = 3773
}
