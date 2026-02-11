package fraggle.tools.file

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import fraggle.executor.ToolExecutor
import java.nio.file.Path
import kotlin.io.path.*

class ReadFileTool(private val toolExecutor: ToolExecutor) : SimpleTool<ReadFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "read_file",
    description = "Read the contents of a file. Returns the file content as text.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("Path to the file to read (relative to workspace or absolute)")
        val path: String,
        @SerialName("max_lines")
        @param:LLMDescription("Maximum number of lines to read. Defaults to 1000.")
        val maxLines: Int = 1000,
    )

    override suspend fun execute(args: Args): String {
        return try {
            val resolvedPath = toolExecutor.resolvePath(args.path)
            if (!resolvedPath.exists()) return "Error: File not found: ${args.path}"
            if (!resolvedPath.isRegularFile()) return "Error: Not a regular file: ${args.path}"

            withContext(Dispatchers.IO) {
                if (args.maxLines == Int.MAX_VALUE) {
                    resolvedPath.readText()
                } else {
                    resolvedPath.readLines()
                        .take(args.maxLines)
                        .joinToString("\n")
                }
            }
        } catch (e: Exception) {
            "Error: Failed to read file: ${e.message}"
        }
    }
}

class WriteFileTool(private val toolExecutor: ToolExecutor) : SimpleTool<WriteFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "write_file",
    description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("Path to the file to write (relative to workspace or absolute)")
        val path: String,
        @param:LLMDescription("Content to write to the file")
        val content: String,
    )

    override suspend fun execute(args: Args): String {
        return try {
            val resolvedPath = toolExecutor.resolvePath(args.path)
            withContext(Dispatchers.IO) {
                resolvedPath.parent?.createDirectories()
                resolvedPath.writeText(args.content)
            }
            "File written successfully: ${args.path}"
        } catch (e: Exception) {
            "Error: Failed to write file: ${e.message}"
        }
    }
}

class AppendFileTool(private val toolExecutor: ToolExecutor) : SimpleTool<AppendFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "append_file",
    description = "Append content to a file. Creates the file if it doesn't exist.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("Path to the file to append to")
        val path: String,
        @param:LLMDescription("Content to append to the file")
        val content: String,
    )

    override suspend fun execute(args: Args): String {
        return try {
            val resolvedPath = toolExecutor.resolvePath(args.path)
            withContext(Dispatchers.IO) {
                resolvedPath.parent?.createDirectories()
                resolvedPath.appendText(args.content)
            }
            "Content appended to: ${args.path}"
        } catch (e: Exception) {
            "Error: Failed to append to file: ${e.message}"
        }
    }
}

class ListFilesTool(private val toolExecutor: ToolExecutor) : SimpleTool<ListFilesTool.Args>(
    argsSerializer = Args.serializer(),
    name = "list_files",
    description = "List files and directories in a given path.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("Directory path to list (relative to workspace or absolute)")
        val path: String,
        @param:LLMDescription("Whether to list files recursively. Defaults to false.")
        val recursive: Boolean = false,
    )

    override suspend fun execute(args: Args): String {
        return try {
            val resolvedPath = toolExecutor.resolvePath(args.path)
            if (!resolvedPath.exists()) return "Error: Directory not found: ${args.path}"
            if (!resolvedPath.isDirectory()) return "Error: Not a directory: ${args.path}"

            withContext(Dispatchers.IO) {
                val tree = buildTree(resolvedPath, prefix = "", recursive = args.recursive)
                if (tree.isEmpty()) {
                    "Directory is empty: ${args.path}"
                } else {
                    "${args.path.trimEnd('/')}/\n$tree".trimEnd()
                }
            }
        } catch (e: Exception) {
            "Error: Failed to list files: ${e.message}"
        }
    }

    private fun buildTree(dir: Path, prefix: String, recursive: Boolean): String = buildString {
        val entries = dir.listDirectoryEntries()
            .sortedWith(compareBy({ !it.isDirectory() }, { it.name }))
        entries.forEachIndexed { index, entry ->
            val isLast = index == entries.lastIndex
            val connector = if (isLast) "└── " else "├── "
            val name = if (entry.isDirectory()) "${entry.name}/" else entry.name
            appendLine("$prefix$connector$name")
            if (recursive && entry.isDirectory()) {
                val childPrefix = prefix + if (isLast) "    " else "│   "
                append(buildTree(entry, childPrefix, recursive = true))
            }
        }
    }
}

class SearchFilesTool(private val toolExecutor: ToolExecutor) : SimpleTool<SearchFilesTool.Args>(
    argsSerializer = Args.serializer(),
    name = "search_files",
    description = "Search for files matching a pattern in a directory.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("Directory path to search in")
        val path: String,
        @param:LLMDescription("Pattern to match (supports * and ? wildcards)")
        val pattern: String,
    )

    override suspend fun execute(args: Args): String {
        return try {
            val resolvedPath = toolExecutor.resolvePath(args.path)
            if (!resolvedPath.exists()) return "Error: Directory not found: ${args.path}"
            if (!resolvedPath.isDirectory()) return "Error: Not a directory: ${args.path}"

            val regex = args.pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
                .toRegex()

            val matches = withContext(Dispatchers.IO) {
                resolvedPath.walk().filter { file ->
                    !file.isDirectory() && regex.matches(file.name)
                }.toList()
            }

            if (matches.isEmpty()) {
                "No files found matching '${args.pattern}' in ${args.path}"
            } else {
                val listing = matches.joinToString("\n") { it.absolutePathString() }
                "Found ${matches.size} file(s) matching '${args.pattern}':\n$listing"
            }
        } catch (e: Exception) {
            "Error: Failed to search files: ${e.message}"
        }
    }
}

class FileExistsTool(private val toolExecutor: ToolExecutor) : SimpleTool<FileExistsTool.Args>(
    argsSerializer = Args.serializer(),
    name = "file_exists",
    description = "Check if a file or directory exists at the given path.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("Path to check")
        val path: String,
    )

    override suspend fun execute(args: Args): String {
        return try {
            val resolvedPath = toolExecutor.resolvePath(args.path)
            if (resolvedPath.exists()) {
                "Path exists: ${args.path}"
            } else {
                "Path does not exist: ${args.path}"
            }
        } catch (e: Exception) {
            "Error: Failed to check path: ${e.message}"
        }
    }
}

class DeleteFileTool(private val toolExecutor: ToolExecutor) : SimpleTool<DeleteFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "delete_file",
    description = "Delete a file at the given path.",
) {
    @Serializable
    data class Args(
        @param:LLMDescription("Path to the file to delete")
        val path: String,
    )

    override suspend fun execute(args: Args): String {
        return try {
            val resolvedPath = toolExecutor.resolvePath(args.path)
            withContext(Dispatchers.IO) {
                if (resolvedPath.exists()) {
                    resolvedPath.deleteExisting()
                }
            }
            "File deleted: ${args.path}"
        } catch (e: Exception) {
            "Error: Failed to delete file: ${e.message}"
        }
    }
}
