package fraggle.coding.prompt

/**
 * Loads the built-in default system prompt (`CODING_SYSTEM.md`) from the JAR
 * resources. Users can override this at runtime by placing their own
 * `SYSTEM.md` under `.fraggle/coding/` (project) or `$FRAGGLE_ROOT/coding/`
 * (global); the orchestrator handles that resolution and only falls through
 * to [load] when no override is present.
 */
object DefaultSystemPrompt {
    private const val RESOURCE_PATH = "/CODING_SYSTEM.md"

    /**
     * Returns the bundled prompt content as a string. Throws if the resource
     * is missing from the classpath — this is a build-time bug, not a
     * runtime condition we should try to recover from.
     */
    fun load(): String {
        val stream = DefaultSystemPrompt::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("Bundled system prompt not found at classpath resource $RESOURCE_PATH")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
