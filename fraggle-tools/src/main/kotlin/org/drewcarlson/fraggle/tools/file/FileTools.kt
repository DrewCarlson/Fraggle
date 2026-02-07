package org.drewcarlson.fraggle.tools.file

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.sandbox.SandboxResult

class ReadFileTool(private val sandbox: Sandbox) : SimpleTool<ReadFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "read_file",
    description = "Read the contents of a file. Returns the file content as text.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Path to the file to read (relative to workspace or absolute)")
        val path: String,
        @LLMDescription("Maximum number of lines to read. Defaults to 1000.")
        val max_lines: Int = 1000,
    )

    override suspend fun execute(args: Args): String {
        return when (val result = sandbox.readFile(args.path, args.max_lines)) {
            is SandboxResult.Success -> result.value
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }
}

class WriteFileTool(private val sandbox: Sandbox) : SimpleTool<WriteFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "write_file",
    description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Path to the file to write (relative to workspace or absolute)")
        val path: String,
        @LLMDescription("Content to write to the file")
        val content: String,
    )

    override suspend fun execute(args: Args): String {
        return when (val result = sandbox.writeFile(args.path, args.content)) {
            is SandboxResult.Success -> "File written successfully: ${args.path}"
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }
}

class AppendFileTool(private val sandbox: Sandbox) : SimpleTool<AppendFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "append_file",
    description = "Append content to a file. Creates the file if it doesn't exist.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Path to the file to append to")
        val path: String,
        @LLMDescription("Content to append to the file")
        val content: String,
    )

    override suspend fun execute(args: Args): String {
        return when (val result = sandbox.appendFile(args.path, args.content)) {
            is SandboxResult.Success -> "Content appended to: ${args.path}"
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }
}

class ListFilesTool(private val sandbox: Sandbox) : SimpleTool<ListFilesTool.Args>(
    argsSerializer = Args.serializer(),
    name = "list_files",
    description = "List files and directories in a given path.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Directory path to list (relative to workspace or absolute)")
        val path: String,
        @LLMDescription("Whether to list files recursively. Defaults to false.")
        val recursive: Boolean = false,
    )

    override suspend fun execute(args: Args): String {
        return when (val result = sandbox.listFiles(args.path, args.recursive)) {
            is SandboxResult.Success -> {
                val files = result.value
                if (files.isEmpty()) {
                    "Directory is empty: ${args.path}"
                } else {
                    val listing = files.joinToString("\n") { file ->
                        val prefix = if (file.isDirectory) "[DIR] " else "      "
                        val size = if (file.isDirectory) "" else " (${formatSize(file.size)})"
                        "$prefix${file.name}$size"
                    }
                    "Contents of ${args.path}:\n$listing"
                }
            }
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

class SearchFilesTool(private val sandbox: Sandbox) : SimpleTool<SearchFilesTool.Args>(
    argsSerializer = Args.serializer(),
    name = "search_files",
    description = "Search for files matching a pattern in a directory.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Directory path to search in")
        val path: String,
        @LLMDescription("Pattern to match (supports * and ? wildcards)")
        val pattern: String,
    )

    override suspend fun execute(args: Args): String {
        return when (val result = sandbox.listFiles(args.path, recursive = true)) {
            is SandboxResult.Success -> {
                val regex = args.pattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                    .toRegex()

                val matches = result.value.filter { file ->
                    !file.isDirectory && regex.matches(file.name)
                }

                if (matches.isEmpty()) {
                    "No files found matching '${args.pattern}' in ${args.path}"
                } else {
                    val listing = matches.joinToString("\n") { it.path }
                    "Found ${matches.size} file(s) matching '${args.pattern}':\n$listing"
                }
            }
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }
}

class FileExistsTool(private val sandbox: Sandbox) : SimpleTool<FileExistsTool.Args>(
    argsSerializer = Args.serializer(),
    name = "file_exists",
    description = "Check if a file or directory exists at the given path.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Path to check")
        val path: String,
    )

    override suspend fun execute(args: Args): String {
        return when (val result = sandbox.exists(args.path)) {
            is SandboxResult.Success -> {
                if (result.value) "Path exists: ${args.path}" else "Path does not exist: ${args.path}"
            }
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }
}

class DeleteFileTool(private val sandbox: Sandbox) : SimpleTool<DeleteFileTool.Args>(
    argsSerializer = Args.serializer(),
    name = "delete_file",
    description = "Delete a file at the given path.",
) {
    @Serializable
    data class Args(
        @LLMDescription("Path to the file to delete")
        val path: String,
    )

    override suspend fun execute(args: Args): String {
        return when (val result = sandbox.deleteFile(args.path)) {
            is SandboxResult.Success -> "File deleted: ${args.path}"
            is SandboxResult.Denied -> "Error: Access denied: ${result.reason}"
            is SandboxResult.Error -> "Error: ${result.message}"
        }
    }
}
