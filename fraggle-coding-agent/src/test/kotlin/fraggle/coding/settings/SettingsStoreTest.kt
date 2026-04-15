package fraggle.coding.settings

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {

    @Nested
    inner class NoFiles {
        @Test
        fun `no files returns defaults`() {
            val merged = SettingsStore.load(globalFile = null, projectFile = null)
            val s = merged.effective
            assertEquals("ask", s.supervision)
            assertEquals(CodingSettingsDefaults.contextWindowTokens, s.contextWindowTokens)
            assertEquals(CodingSettingsDefaults.compactionTriggerRatio, s.compactionTriggerRatio)
            assertEquals(CodingSettingsDefaults.compactionKeepRecentMessages, s.compactionKeepRecentMessages)
            assertEquals(CodingSettingsDefaults.maxIterations, s.maxIterations)
            assertFalse(merged.sources.globalFileExists)
            assertFalse(merged.sources.projectFileExists)
        }

        @Test
        fun `pointing at non-existent files is the same as null`(@TempDir dir: Path) {
            val merged = SettingsStore.load(
                globalFile = dir.resolve("no-such-global.json"),
                projectFile = dir.resolve("no-such-project.json"),
            )
            assertEquals("ask", merged.effective.supervision)
            assertFalse(merged.sources.globalFileExists)
            assertFalse(merged.sources.projectFileExists)
        }
    }

    @Nested
    inner class GlobalOnly {
        @Test
        fun `global file overrides defaults`(@TempDir dir: Path) {
            val global = dir.resolve("global.json")
            global.writeText(
                """
                {
                  "supervision": "none",
                  "contextWindowTokens": 32000,
                  "maxIterations": 50
                }
                """.trimIndent(),
            )
            val merged = SettingsStore.load(globalFile = global, projectFile = null)
            assertEquals("none", merged.effective.supervision)
            assertEquals(32000, merged.effective.contextWindowTokens)
            assertEquals(50, merged.effective.maxIterations)
            // Unset fields still come from defaults
            assertEquals(CodingSettingsDefaults.compactionTriggerRatio, merged.effective.compactionTriggerRatio)
            assertTrue(merged.sources.globalFileExists)
        }
    }

    @Nested
    inner class ProjectOverridesGlobal {
        @Test
        fun `project fields win over global`(@TempDir dir: Path) {
            val global = dir.resolve("global.json")
            global.writeText(
                """
                {"supervision": "none", "contextWindowTokens": 8000, "maxIterations": 5}
                """.trimIndent(),
            )
            val project = dir.resolve("project.json")
            project.writeText(
                """
                {"supervision": "ask", "contextWindowTokens": 64000}
                """.trimIndent(),
            )
            val merged = SettingsStore.load(globalFile = global, projectFile = project)

            // Project wins for fields it sets
            assertEquals("ask", merged.effective.supervision)
            assertEquals(64000, merged.effective.contextWindowTokens)
            // Global wins for fields it sets but project does not
            assertEquals(5, merged.effective.maxIterations)

            assertTrue(merged.sources.globalFileExists)
            assertTrue(merged.sources.projectFileExists)
        }

        @Test
        fun `project can undo global only by setting explicit values`(@TempDir dir: Path) {
            val global = dir.resolve("global.json")
            global.writeText("""{"supervision": "none"}""")
            val project = dir.resolve("project.json")
            project.writeText("""{}""")  // empty project — global still wins
            val merged = SettingsStore.load(globalFile = global, projectFile = project)
            assertEquals("none", merged.effective.supervision)
        }
    }

    @Nested
    inner class Resilience {
        @Test
        fun `malformed global file does not crash — falls back to defaults`(@TempDir dir: Path) {
            val global = dir.resolve("broken.json")
            global.writeText("{ this is not json")
            val merged = SettingsStore.load(globalFile = global, projectFile = null)
            assertEquals("ask", merged.effective.supervision, "should fall back to default")
        }

        @Test
        fun `unknown fields in JSON are ignored`(@TempDir dir: Path) {
            val global = dir.resolve("forward.json")
            global.writeText(
                """
                {"supervision": "ask", "futureField": 123, "anotherNewThing": "hello"}
                """.trimIndent(),
            )
            val merged = SettingsStore.load(globalFile = global, projectFile = null)
            assertEquals("ask", merged.effective.supervision)
        }
    }
}
