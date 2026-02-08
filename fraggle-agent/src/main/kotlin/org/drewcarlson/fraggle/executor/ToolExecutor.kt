package org.drewcarlson.fraggle.executor

import java.nio.file.Path
import kotlin.io.path.*

/**
 * Minimal context interface for tool execution.
 * Provides workspace directory and path resolution. Tools do their own I/O directly.
 */
interface ToolExecutor {
    fun workDir(): Path

    fun resolvePath(path: String): Path {
        val p = Path(path)
        return if (p.isAbsolute) p.normalize() else workDir().resolve(p).normalize()
    }
}

/**
 * Local tool executor that resolves paths relative to a workspace directory.
 */
class LocalToolExecutor(private val workDir: Path) : ToolExecutor {
    override fun workDir(): Path = workDir
}
