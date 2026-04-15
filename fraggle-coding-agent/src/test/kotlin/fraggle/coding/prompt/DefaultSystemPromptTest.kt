package fraggle.coding.prompt

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DefaultSystemPromptTest {

    @Test
    fun `bundled prompt loads from resources`() {
        val prompt = DefaultSystemPrompt.load()
        assertTrue(prompt.isNotBlank(), "bundled prompt should be non-empty")
    }

    @Test
    fun `bundled prompt mentions the coding tool names it's designed for`() {
        val prompt = DefaultSystemPrompt.load()
        // These are the coding-agent's canonical tool names. If the resource
        // and the tool names drift apart, this test fails loudly so we
        // remember to keep them aligned.
        for (tool in listOf("read_file", "edit_file", "execute_command", "search_files")) {
            assertTrue(prompt.contains(tool), "bundled prompt should reference '$tool'")
        }
    }
}
