package fraggle.coding.session

/**
 * Richer view of a session file for the resume picker.
 *
 * [SessionSummary] (returned by [SessionManager.list]) is deliberately cheap —
 * it's just directory metadata, no file parsing. For the picker we need more
 * context per row: how many messages, what model, and a preview of the first
 * user message so the user can recognise the session. [loadPreviews] opens
 * each file once and extracts that information in a resilient way; a
 * malformed session file yields a best-effort preview rather than aborting
 * the whole listing.
 */
data class SessionPreview(
    /** The underlying summary (id, file path, last-modified time). */
    val summary: SessionSummary,
    /** The first [SessionEntry.Payload.User] `text` on the latest branch, or null if none. */
    val firstUserMessage: String?,
    /** Number of entries on the latest branch, including the root. */
    val messageCount: Int,
    /** Model id from the session's root entry, or null if the file was unreadable. */
    val model: String?,
)

/**
 * Open and inspect every session in [SessionManager.list]'s result, producing
 * one [SessionPreview] per session. The order is preserved — most-recent
 * first.
 *
 * Sessions that fail to parse (corrupt JSONL, missing root, schema mismatch)
 * still appear in the list with a null [SessionPreview.firstUserMessage] and
 * a null [SessionPreview.model] so the user can at least see them by
 * timestamp and try to open them directly via `--session`.
 */
fun SessionManager.loadPreviews(): List<SessionPreview> =
    list().map { summary -> buildPreview(summary) }

private fun SessionManager.buildPreview(summary: SessionSummary): SessionPreview {
    return try {
        val session = open(summary.file)
        val branch = session.tree.currentBranch()
        val firstUserMessage = branch
            .firstNotNullOfOrNull { entry ->
                (entry.payload as? SessionEntry.Payload.User)?.text
            }
        val rootModel = (session.tree.root?.payload as? SessionEntry.Payload.Root)?.model
        SessionPreview(
            summary = summary,
            firstUserMessage = firstUserMessage,
            messageCount = branch.size,
            model = rootModel,
        )
    } catch (_: Throwable) {
        SessionPreview(
            summary = summary,
            firstUserMessage = null,
            messageCount = 0,
            model = null,
        )
    }
}
