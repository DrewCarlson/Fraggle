package fraggle.coding.session

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.useLines

/**
 * Append-only JSONL reader/writer for a single session file.
 *
 * The file is a sequence of one JSON object per line, each parseable as a
 * [SessionEntry]. New entries are appended atomically (one `Files.write` call
 * per entry with [StandardOpenOption.APPEND]). The file never rewrites past
 * entries — branches are expressed via `parentId` pointers, not by mutating
 * history.
 */
class SessionFile(val path: Path) {

    /**
     * Append [entry] as a new line. Creates the file and parent directories
     * if they don't exist yet. Refuses to write future-versioned entries.
     */
    fun append(entry: SessionEntry) {
        require(entry.schemaVersion <= SessionEntry.CURRENT_SCHEMA_VERSION) {
            "Refusing to write future schema version ${entry.schemaVersion} (current is ${SessionEntry.CURRENT_SCHEMA_VERSION})"
        }
        val line = json.encodeToString(SessionEntry.serializer(), entry) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)

        if (!path.exists()) {
            path.createParentDirectories()
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } else {
            Files.write(path, bytes, StandardOpenOption.APPEND)
        }
    }

    /**
     * Read every entry from the file in write order. Returns an empty list
     * if the file doesn't exist. Blank lines are skipped. Unknown future
     * schema versions surface as [SessionReadException].
     */
    fun readAll(): List<SessionEntry> {
        if (!path.exists()) return emptyList()
        val entries = mutableListOf<SessionEntry>()
        path.useLines(Charsets.UTF_8) { lines ->
            var lineNumber = 0
            for (line in lines) {
                lineNumber++
                if (line.isBlank()) continue
                val entry = try {
                    json.decodeFromString(SessionEntry.serializer(), line)
                } catch (e: Exception) {
                    throw SessionReadException("Failed to parse line $lineNumber of $path: ${e.message}", e)
                }
                if (entry.schemaVersion > SessionEntry.CURRENT_SCHEMA_VERSION) {
                    throw SessionReadException(
                        "Session file $path line $lineNumber uses schema version ${entry.schemaVersion}; " +
                            "this build supports up to ${SessionEntry.CURRENT_SCHEMA_VERSION}. Upgrade fraggle or use a different session.",
                    )
                }
                entries += entry
            }
        }
        return entries
    }

    companion object {
        internal val json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            classDiscriminator = "kind"
            encodeDefaults = true
        }
    }
}

class SessionReadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
