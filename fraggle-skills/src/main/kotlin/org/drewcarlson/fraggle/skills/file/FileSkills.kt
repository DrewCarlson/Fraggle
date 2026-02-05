package org.drewcarlson.fraggle.skills.file

import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.sandbox.SandboxResult
import org.drewcarlson.fraggle.skill.Skill
import org.drewcarlson.fraggle.skill.SkillResult
import org.drewcarlson.fraggle.skill.skill

/**
 * File operation skills using the sandbox for safe execution.
 */
object FileSkills {

    /**
     * Create all file skills with the given sandbox.
     */
    fun create(sandbox: Sandbox): List<Skill> {
        return listOf(
            readFile(sandbox),
            writeFile(sandbox),
            listFiles(sandbox),
            searchFiles(sandbox),
            fileExists(sandbox),
            deleteFile(sandbox),
            appendFile(sandbox),
        )
    }

    /**
     * Skill to read file contents.
     */
    fun readFile(sandbox: Sandbox) = skill("read_file") {
        description = "Read the contents of a file. Returns the file content as text."

        parameter<String>("path") {
            description = "Path to the file to read (relative to workspace or absolute)"
            required = true
        }

        parameter<Int>("max_lines") {
            description = "Maximum number of lines to read. Defaults to 1000."
            default = 1000
        }

        execute { params ->
            val path = params.get<String>("path")
            val maxLines = params.getOrDefault("max_lines", 1000)

            when (val result = sandbox.readFile(path, maxLines)) {
                is SandboxResult.Success -> SkillResult.Success(result.value)
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to write content to a file.
     */
    fun writeFile(sandbox: Sandbox) = skill("write_file") {
        description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does."

        parameter<String>("path") {
            description = "Path to the file to write (relative to workspace or absolute)"
            required = true
        }

        parameter<String>("content") {
            description = "Content to write to the file"
            required = true
        }

        execute { params ->
            val path = params.get<String>("path")
            val content = params.get<String>("content")

            when (val result = sandbox.writeFile(path, content)) {
                is SandboxResult.Success -> SkillResult.Success("File written successfully: $path")
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to append content to a file.
     */
    fun appendFile(sandbox: Sandbox) = skill("append_file") {
        description = "Append content to a file. Creates the file if it doesn't exist."

        parameter<String>("path") {
            description = "Path to the file to append to"
            required = true
        }

        parameter<String>("content") {
            description = "Content to append to the file"
            required = true
        }

        execute { params ->
            val path = params.get<String>("path")
            val content = params.get<String>("content")

            when (val result = sandbox.appendFile(path, content)) {
                is SandboxResult.Success -> SkillResult.Success("Content appended to: $path")
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to list files in a directory.
     */
    fun listFiles(sandbox: Sandbox) = skill("list_files") {
        description = "List files and directories in a given path."

        parameter<String>("path") {
            description = "Directory path to list (relative to workspace or absolute)"
            required = true
        }

        parameter<Boolean>("recursive") {
            description = "Whether to list files recursively. Defaults to false."
            default = false
        }

        execute { params ->
            val path = params.get<String>("path")
            val recursive = params.getOrDefault("recursive", false)

            when (val result = sandbox.listFiles(path, recursive)) {
                is SandboxResult.Success -> {
                    val files = result.value
                    if (files.isEmpty()) {
                        SkillResult.Success("Directory is empty: $path")
                    } else {
                        val listing = files.joinToString("\n") { file ->
                            val prefix = if (file.isDirectory) "[DIR] " else "      "
                            val size = if (file.isDirectory) "" else " (${formatSize(file.size)})"
                            "$prefix${file.name}$size"
                        }
                        SkillResult.Success("Contents of $path:\n$listing")
                    }
                }
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to search for files matching a pattern.
     */
    fun searchFiles(sandbox: Sandbox) = skill("search_files") {
        description = "Search for files matching a pattern in a directory."

        parameter<String>("path") {
            description = "Directory path to search in"
            required = true
        }

        parameter<String>("pattern") {
            description = "Pattern to match (supports * and ? wildcards)"
            required = true
        }

        execute { params ->
            val path = params.get<String>("path")
            val pattern = params.get<String>("pattern")

            when (val result = sandbox.listFiles(path, recursive = true)) {
                is SandboxResult.Success -> {
                    val regex = pattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", ".")
                        .toRegex()

                    val matches = result.value.filter { file ->
                        !file.isDirectory && regex.matches(file.name)
                    }

                    if (matches.isEmpty()) {
                        SkillResult.Success("No files found matching '$pattern' in $path")
                    } else {
                        val listing = matches.joinToString("\n") { it.path }
                        SkillResult.Success("Found ${matches.size} file(s) matching '$pattern':\n$listing")
                    }
                }
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to check if a file or directory exists.
     */
    fun fileExists(sandbox: Sandbox) = skill("file_exists") {
        description = "Check if a file or directory exists at the given path."

        parameter<String>("path") {
            description = "Path to check"
            required = true
        }

        execute { params ->
            val path = params.get<String>("path")

            when (val result = sandbox.exists(path)) {
                is SandboxResult.Success -> {
                    if (result.value) {
                        SkillResult.Success("Path exists: $path")
                    } else {
                        SkillResult.Success("Path does not exist: $path")
                    }
                }
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
        }
    }

    /**
     * Skill to delete a file.
     */
    fun deleteFile(sandbox: Sandbox) = skill("delete_file") {
        description = "Delete a file at the given path."

        parameter<String>("path") {
            description = "Path to the file to delete"
            required = true
        }

        execute { params ->
            val path = params.get<String>("path")

            when (val result = sandbox.deleteFile(path)) {
                is SandboxResult.Success -> SkillResult.Success("File deleted: $path")
                is SandboxResult.Denied -> SkillResult.Error("Access denied: ${result.reason}")
                is SandboxResult.Error -> SkillResult.Error(result.message)
            }
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
