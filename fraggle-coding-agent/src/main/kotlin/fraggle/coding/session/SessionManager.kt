package fraggle.coding.session

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.notExists

/**
 * Discovers, creates, and forks session files under a shared root directory.
 *
 * Layout (matching `docs/plans/coding-agent.md`):
 * ```
 *   <sessionsRoot>/
 *     <project-hash>/                 # sha1(absolutePath(projectRoot))[:12]
 *       <session-uuid>.jsonl          # one file per session
 *       <session-uuid>.jsonl
 * ```
 *
 * The manager itself is stateless; it just composes paths and reads/writes
 * [SessionFile] instances.
 */
class SessionManager(
    private val sessionsRoot: Path,
    private val projectRoot: Path,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val uuid: () -> String = { UUID.randomUUID().toString() },
) {
    /** Directory containing all sessions for the current project. Created on demand. */
    val projectDir: Path = sessionsRoot.resolve(projectHash(projectRoot))

    /**
     * Create a new empty session and write its root entry. Returns a handle
     * wrapping the file path and the initial [SessionTree] (containing just
     * the root entry).
     */
    fun createNew(model: String): Session {
        val sessionId = uuid()
        val file = SessionFile(projectDir.resolve("$sessionId.jsonl"))
        val rootEntry = SessionEntry(
            id = uuid(),
            parentId = null,
            timestampMs = clock(),
            payload = SessionEntry.Payload.Root(
                sessionId = sessionId,
                projectRoot = projectRoot.toAbsolutePath().toString(),
                model = model,
                createdAtMs = clock(),
            ),
        )
        file.append(rootEntry)
        return Session(id = sessionId, file = file, initialTree = SessionTree.of(listOf(rootEntry)))
    }

    /**
     * Open an existing session file. The file must already exist and parse
     * as valid JSONL; otherwise [SessionReadException] surfaces.
     */
    fun open(file: Path): Session {
        require(file.exists()) { "Session file does not exist: $file" }
        val sessionFile = SessionFile(file)
        val entries = sessionFile.readAll()
        val root = entries.firstOrNull { it.parentId == null }
            ?: error("Session file $file has no root entry")
        val rootPayload = root.payload as? SessionEntry.Payload.Root
            ?: error("Session file $file root entry is not of kind=root (got ${root.payload::class.simpleName})")
        return Session(
            id = rootPayload.sessionId,
            file = sessionFile,
            initialTree = SessionTree.of(entries),
        )
    }

    /**
     * List sessions for the current project, sorted by file-modified time
     * descending (most recent first). Non-jsonl files are ignored.
     */
    fun list(): List<SessionSummary> {
        if (projectDir.notExists() || !projectDir.isDirectory()) return emptyList()
        return Files.list(projectDir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "jsonl" }
                .map { path ->
                    SessionSummary(
                        id = path.nameWithoutExtension,
                        file = path,
                        lastModifiedMs = path.getLastModifiedTime().toMillis(),
                    )
                }
                .toList()
        }.sortedByDescending { it.lastModifiedMs }
    }

    /**
     * Return the most recently modified session for this project, or null
     * if there are none. This backs `fraggle code -c` / `--continue`.
     */
    fun mostRecent(): SessionSummary? = list().firstOrNull()

    /**
     * Fork a session at [branchPoint]: create a new session file in the same
     * project directory, copy every entry from the root to (and including)
     * [branchPoint], and return a handle to the new session. The new session
     * has its own UUID; the forked entries keep their original ids (so
     * branches across files can still be cross-referenced by id if we ever
     * add that capability).
     */
    fun fork(source: Session, branchPoint: SessionEntry): Session {
        val branch = source.tree.branchTo(branchPoint)
        require(branch.isNotEmpty()) { "Branch to fork is empty" }

        val newSessionId = uuid()
        val newFile = SessionFile(projectDir.resolve("$newSessionId.jsonl"))

        // Rewrite the root entry with a new sessionId but keep the original id
        // so parentId references still line up.
        val originalRoot = branch.first()
        val originalRootPayload = originalRoot.payload as? SessionEntry.Payload.Root
            ?: error("Source session root is not of kind=root")
        val forkedRoot = originalRoot.copy(
            payload = originalRootPayload.copy(
                sessionId = newSessionId,
                createdAtMs = clock(),
            ),
        )
        newFile.append(forkedRoot)
        for (entry in branch.drop(1)) {
            newFile.append(entry)
        }

        return Session(
            id = newSessionId,
            file = newFile,
            initialTree = SessionTree.of(listOf(forkedRoot) + branch.drop(1)),
        )
    }

    /**
     * Compute a short hash of the project root path. Used to bucket session
     * files by project so `fraggle code -r` only shows sessions for the
     * working directory the user is currently in.
     */
    private fun projectHash(projectRoot: Path): String {
        val bytes = projectRoot.toAbsolutePath().toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return buildString(12) {
            for (i in 0 until 6) {
                val b = digest[i].toInt() and 0xff
                append("0123456789abcdef"[b ushr 4])
                append("0123456789abcdef"[b and 0x0f])
            }
        }
    }

    init {
        if (projectDir.notExists()) {
            projectDir.createDirectories()
        }
    }
}

/**
 * A mutable wrapper around an open session file. Mutations go through
 * [record], which appends an entry to disk AND updates the in-memory tree.
 */
class Session(
    val id: String,
    val file: SessionFile,
    initialTree: SessionTree,
) {
    private var _tree: SessionTree = initialTree
    val tree: SessionTree get() = _tree
    val path: java.nio.file.Path get() = file.path

    /**
     * Append [entry] to the file and update the in-memory tree. Returns the
     * entry's id for convenience.
     */
    fun record(entry: SessionEntry): String {
        file.append(entry)
        _tree = _tree.append(entry)
        return entry.id
    }
}

data class SessionSummary(
    val id: String,
    val file: Path,
    val lastModifiedMs: Long,
)
