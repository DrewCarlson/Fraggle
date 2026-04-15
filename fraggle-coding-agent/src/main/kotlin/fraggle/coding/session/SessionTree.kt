package fraggle.coding.session

/**
 * In-memory view of a session file as a parentId graph.
 *
 * A session file is append-only but logically a tree: each entry has an
 * optional [SessionEntry.parentId] pointing at another entry in the same file.
 * Roots have `parentId = null` (there should be exactly one root, but this
 * class tolerates multiples for defensive reasons). Multiple children of the
 * same parent create branches — different lines of conversation that share
 * a prefix.
 *
 * The tree is immutable; [append] returns a new instance so callers can hold
 * onto an old snapshot safely.
 */
class SessionTree private constructor(
    val entries: List<SessionEntry>,
) {
    private val byId: Map<String, SessionEntry> = entries.associateBy { it.id }
    private val childrenByParent: Map<String?, List<SessionEntry>> =
        entries.groupBy { it.parentId }

    /** The single root entry, or null if the tree is empty. Throws if there's more than one root. */
    val root: SessionEntry? get() {
        val roots = childrenByParent[null].orEmpty()
        return when (roots.size) {
            0 -> null
            1 -> roots.single()
            else -> error("Session tree has ${roots.size} roots; expected exactly one")
        }
    }

    /** Entries in write-order (the order they appear in the file). */
    val size: Int get() = entries.size

    /** Returns the entry with the given id, or null if not present. */
    fun find(id: String): SessionEntry? = byId[id]

    /** Direct children of [entry]. */
    fun childrenOf(entry: SessionEntry): List<SessionEntry> =
        childrenByParent[entry.id].orEmpty()

    /** Direct children of the entry with id [parentId]. */
    fun childrenOf(parentId: String?): List<SessionEntry> =
        childrenByParent[parentId].orEmpty()

    /**
     * Walk from [leaf] back to the root, returning the branch in root→leaf
     * order (so index 0 is the root, last element is the leaf). Throws if
     * [leaf] is not in this tree, or if the chain is broken (dangling parentId).
     */
    fun branchTo(leaf: SessionEntry): List<SessionEntry> {
        val result = ArrayDeque<SessionEntry>()
        var current: SessionEntry? = byId[leaf.id]
            ?: error("Entry ${leaf.id} is not in this session tree")
        while (current != null) {
            result.addFirst(current)
            val parentId = current.parentId ?: break
            current = byId[parentId]
                ?: error("Broken parentId chain: ${current.id} points at missing parent $parentId")
        }
        return result.toList()
    }

    /**
     * The "current" branch — the longest chain that follows the most recently
     * appended leaf. When there are no branches, this is just the entire file.
     * This is what a `fraggle code -c` resume loads into the agent state.
     */
    fun currentBranch(): List<SessionEntry> {
        if (entries.isEmpty()) return emptyList()
        // The last entry written is the tip of whichever branch the user was
        // working on when they stopped. Walk back from there to root.
        return branchTo(entries.last())
    }

    /**
     * Return a new tree with [entry] appended. The caller is responsible for
     * making sure [entry]'s parentId refers to an existing entry (or null
     * for the root). Violations are surfaced at [branchTo] walk time, not here.
     */
    fun append(entry: SessionEntry): SessionTree = SessionTree(entries + entry)

    /** All leaf entries (those with no children). Useful for `/tree` branch selection. */
    fun leaves(): List<SessionEntry> =
        entries.filter { childrenByParent[it.id].isNullOrEmpty() }

    companion object {
        val EMPTY: SessionTree = SessionTree(emptyList())

        /** Build a tree from an already-loaded list of entries. */
        fun of(entries: List<SessionEntry>): SessionTree = SessionTree(entries.toList())
    }
}
