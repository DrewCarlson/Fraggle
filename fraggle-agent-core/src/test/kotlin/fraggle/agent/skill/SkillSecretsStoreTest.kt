package fraggle.agent.skill

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillSecretsStoreTest {

    @Nested
    inner class SetAndGet {

        @Test
        fun `set and get a secret`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "API_KEY", "secret-123")
            assertEquals("secret-123", store.get("my-skill", "API_KEY"))
        }

        @Test
        fun `overwrite existing secret`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "API_KEY", "old")
            store.set("my-skill", "API_KEY", "new")
            assertEquals("new", store.get("my-skill", "API_KEY"))
        }

        @Test
        fun `get returns null for unconfigured secret`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            assertNull(store.get("my-skill", "MISSING"))
        }

        @Test
        fun `get returns null for unconfigured skill`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            assertNull(store.get("no-such-skill", "KEY"))
        }

        @Test
        fun `preserves empty string value`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "EMPTY", "")
            assertEquals("", store.get("my-skill", "EMPTY"))
        }

        @Test
        fun `preserves multiline value`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            val multiline = "line1\nline2\nline3"
            store.set("my-skill", "CERT", multiline)
            assertEquals(multiline, store.get("my-skill", "CERT"))
        }
    }

    @Nested
    inner class IsConfigured {

        @Test
        fun `returns true when secret exists`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "KEY", "val")
            assertTrue(store.isConfigured("my-skill", "KEY"))
        }

        @Test
        fun `returns false when secret missing`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            assertFalse(store.isConfigured("my-skill", "KEY"))
        }
    }

    @Nested
    inner class ListConfigured {

        @Test
        fun `lists configured variable names`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "KEY_A", "a")
            store.set("my-skill", "KEY_B", "b")
            assertEquals(setOf("KEY_A", "KEY_B"), store.listConfigured("my-skill"))
        }

        @Test
        fun `returns empty set for unconfigured skill`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            assertEquals(emptySet(), store.listConfigured("no-such-skill"))
        }
    }

    @Nested
    inner class LoadEnvVars {

        @Test
        fun `loads all secrets as map`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "KEY_A", "val-a")
            store.set("my-skill", "KEY_B", "val-b")
            assertEquals(
                mapOf("KEY_A" to "val-a", "KEY_B" to "val-b"),
                store.loadEnvVars("my-skill"),
            )
        }

        @Test
        fun `returns empty map for unconfigured skill`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            assertEquals(emptyMap(), store.loadEnvVars("no-such-skill"))
        }
    }

    @Nested
    inner class Remove {

        @Test
        fun `removes a single secret`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "KEY", "val")
            assertTrue(store.remove("my-skill", "KEY"))
            assertNull(store.get("my-skill", "KEY"))
        }

        @Test
        fun `remove returns false when secret does not exist`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            assertFalse(store.remove("my-skill", "NOPE"))
        }

        @Test
        fun `removeAll clears all secrets for a skill`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("my-skill", "A", "1")
            store.set("my-skill", "B", "2")
            store.removeAll("my-skill")
            assertEquals(emptySet(), store.listConfigured("my-skill"))
        }

        @Test
        fun `removeAll is no-op for unconfigured skill`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.removeAll("no-such-skill") // should not throw
        }
    }

    @Nested
    inner class Isolation {

        @Test
        fun `secrets are isolated between skills`(@TempDir tmp: Path) {
            val store = SkillSecretsStore(tmp)
            store.set("skill-a", "KEY", "a-val")
            store.set("skill-b", "KEY", "b-val")
            assertEquals("a-val", store.get("skill-a", "KEY"))
            assertEquals("b-val", store.get("skill-b", "KEY"))
        }
    }
}
