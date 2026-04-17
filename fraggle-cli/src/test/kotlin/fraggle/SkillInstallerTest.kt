package fraggle

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillInstallerTest {

    // ---- fixtures ----

    private fun writeSkill(root: Path, name: String, description: String = "Test skill $name"): Path {
        val dir = root.resolve(name)
        dir.createDirectories()
        dir.resolve("SKILL.md").writeText(
            "---\nname: $name\ndescription: $description\n---\n\n# Body for $name\n",
        )
        return dir
    }

    private fun writeBrokenSkill(root: Path, name: String): Path {
        val dir = root.resolve(name)
        dir.createDirectories()
        // Missing description → hard error per the loader's rules.
        dir.resolve("SKILL.md").writeText("---\nname: $name\n---\n\nno description\n")
        return dir
    }

    @Nested
    inner class Install {

        @Test
        fun `installs a single skill from a skill directory`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target")

            val result = SkillInstaller(target).install(source, sourceLabel = "local:$source")

            assertEquals(1, result.installed.size)
            assertEquals("code-review", result.installed.single().name)
            assertTrue(target.resolve("code-review/SKILL.md").exists())
            assertTrue(result.diagnostics.isEmpty())
            assertTrue(result.skipped.isEmpty())
        }

        @Test
        fun `installs multiple skills from a parent directory`(@TempDir tmp: Path) {
            val source = tmp.resolve("src")
            writeSkill(source, "a")
            writeSkill(source, "b")
            writeSkill(source, "c")
            val target = tmp.resolve("target")

            val result = SkillInstaller(target).install(source, sourceLabel = "local:$source")

            assertEquals(setOf("a", "b", "c"), result.installed.map { it.name }.toSet())
            assertTrue(target.resolve("a/SKILL.md").exists())
            assertTrue(target.resolve("b/SKILL.md").exists())
            assertTrue(target.resolve("c/SKILL.md").exists())
        }

        @Test
        fun `installs from a direct SKILL_md file`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "standalone").resolve("SKILL.md")
            val target = tmp.resolve("target")

            val result = SkillInstaller(target).install(source, sourceLabel = "local:$source")

            assertEquals(listOf("standalone"), result.installed.map { it.name })
            assertTrue(target.resolve("standalone/SKILL.md").exists())
        }

        @Test
        fun `refuses when source has only invalid skills`(@TempDir tmp: Path) {
            val source = writeBrokenSkill(tmp.resolve("src"), "bogus")
            val target = tmp.resolve("target")

            val result = SkillInstaller(target).install(source, sourceLabel = "local:$source")

            assertTrue(result.installed.isEmpty())
            assertEquals(1, result.skipped.size)
            assertTrue("no valid skills" in result.skipped.single().reason)
            // Target directory should not even have been populated with the broken skill.
            assertFalse(target.resolve("bogus/SKILL.md").exists())
        }

        @Test
        fun `mixed valid and invalid installs the valid ones and reports diagnostics`(@TempDir tmp: Path) {
            val source = tmp.resolve("src")
            writeSkill(source, "good")
            writeBrokenSkill(source, "bad")
            val target = tmp.resolve("target")

            val result = SkillInstaller(target).install(source, sourceLabel = "local:$source")

            assertEquals(listOf("good"), result.installed.map { it.name })
            assertTrue(target.resolve("good/SKILL.md").exists())
            assertFalse(target.resolve("bad/SKILL.md").exists())
            assertTrue(result.diagnostics.any { "description is required" in it.message })
        }

        @Test
        fun `skips collisions by default`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target")

            val first = SkillInstaller(target).install(source, sourceLabel = "local:$source")
            assertEquals(1, first.installed.size)

            val second = SkillInstaller(target).install(source, sourceLabel = "local:$source")
            assertTrue(second.installed.isEmpty())
            assertEquals(1, second.skipped.size)
            assertTrue("already exists" in second.skipped.single().reason)
        }

        @Test
        fun `force overwrites existing destination`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review", "Original")
            val target = tmp.resolve("target")

            SkillInstaller(target).install(source, sourceLabel = "local:$source")

            // Rewrite the source with new content, then reinstall with force.
            source.resolve("SKILL.md").writeText(
                "---\nname: code-review\ndescription: Updated\n---\n\n# New body\n",
            )
            val result = SkillInstaller(target, force = true).install(source, sourceLabel = "local:$source")

            assertEquals(1, result.installed.size)
            val installedBody = target.resolve("code-review/SKILL.md").readText()
            assertTrue("Updated" in installedBody)
            assertTrue("New body" in installedBody)
        }

        @Test
        fun `source that does not exist is reported not crashed`(@TempDir tmp: Path) {
            val result = SkillInstaller(tmp.resolve("target"))
                .install(tmp.resolve("nope"), sourceLabel = "local:nope")
            assertTrue(result.installed.isEmpty())
            assertEquals(1, result.skipped.size)
            assertTrue("does not exist" in result.skipped.single().reason)
        }
    }

    @Nested
    inner class SymlinkMode {

        @Test
        fun `symlink mode creates a symlink to the source directory`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target")

            // Try the install; some CI filesystems (e.g. Windows without dev
            // mode) can't create symlinks, in which case we skip the assertion
            // rather than fail.
            val result = try {
                SkillInstaller(target, SkillInstaller.InstallMode.SYMLINK)
                    .install(source, sourceLabel = "local:$source")
            } catch (e: Exception) {
                Assumptions.assumeTrue(false, "symlinks unsupported: ${e.message}")
                return
            }

            if (result.installed.isEmpty()) {
                Assumptions.assumeTrue(false, "symlinks unsupported on this filesystem")
                return
            }

            val dest = target.resolve("code-review")
            assertTrue(dest.isSymbolicLink(), "expected $dest to be a symlink")
            assertTrue(dest.resolve("SKILL.md").exists())
        }
    }

    @Nested
    inner class ManifestBehavior {

        private val json = Json { ignoreUnknownKeys = true }

        @Test
        fun `install writes a manifest entry`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target")

            SkillInstaller(target).install(source, sourceLabel = "local:$source")

            val manifestPath = target.resolve(SkillInstaller.MANIFEST_FILENAME)
            assertTrue(manifestPath.exists())

            val manifest = json.decodeFromString<SkillsManifest>(manifestPath.readText())
            assertEquals(SkillsManifest.VERSION, manifest.version)
            assertEquals(1, manifest.skills.size)
            val entry = manifest.skills.single()
            assertEquals("code-review", entry.name)
            assertEquals("local:$source", entry.source)
            assertEquals("copy", entry.mode)
            assertNotNull(entry.installedAt)
        }

        @Test
        fun `second install merges into the existing manifest`(@TempDir tmp: Path) {
            val src1 = writeSkill(tmp.resolve("src1"), "a")
            val src2 = writeSkill(tmp.resolve("src2"), "b")
            val target = tmp.resolve("target")

            SkillInstaller(target).install(src1, sourceLabel = "local:$src1")
            SkillInstaller(target).install(src2, sourceLabel = "local:$src2")

            val manifest = SkillsManifest.read(target.resolve(SkillInstaller.MANIFEST_FILENAME))
            assertEquals(setOf("a", "b"), manifest.skills.map { it.name }.toSet())
        }

        @Test
        fun `corrupt manifest is recovered not fatal`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target").also { it.createDirectories() }
            target.resolve(SkillInstaller.MANIFEST_FILENAME).writeText("not valid json {{{")

            val result = SkillInstaller(target).install(source, sourceLabel = "local:$source")

            assertEquals(1, result.installed.size)
            // Post-install the manifest should be readable again.
            val manifest = SkillsManifest.read(target.resolve(SkillInstaller.MANIFEST_FILENAME))
            assertEquals(1, manifest.skills.size)
        }

        @Test
        fun `force reinstall updates the manifest entry`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target")

            SkillInstaller(target).install(source, sourceLabel = "local:v1")
            SkillInstaller(target, force = true).install(source, sourceLabel = "local:v2")

            val manifest = SkillsManifest.read(target.resolve(SkillInstaller.MANIFEST_FILENAME))
            assertEquals("local:v2", manifest.skills.single().source)
        }
    }

    @Nested
    inner class Uninstall {

        @Test
        fun `removes a previously installed skill and drops its manifest entry`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target")
            SkillInstaller(target).install(source, sourceLabel = "local:$source")

            val result = SkillInstaller(target).uninstall("code-review")

            assertTrue(result is SkillInstaller.UninstallResult.Removed)
            assertFalse(target.resolve("code-review/SKILL.md").exists())
            assertFalse(target.resolve("code-review").exists())
            val manifest = SkillsManifest.read(target.resolve(SkillInstaller.MANIFEST_FILENAME))
            assertTrue(manifest.skills.isEmpty())
        }

        @Test
        fun `uninstalling an untracked skill returns NotInManifest and touches nothing`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "a")
            val target = tmp.resolve("target")
            SkillInstaller(target).install(source, sourceLabel = "local:$source")

            // Drop a hand-created directory the CLI did not install.
            val rogue = target.resolve("rogue").also { it.createDirectories() }
            rogue.resolve("SKILL.md").writeText(
                "---\nname: rogue\ndescription: Not tracked.\n---\n",
            )

            val result = SkillInstaller(target).uninstall("rogue")

            assertTrue(result is SkillInstaller.UninstallResult.NotInManifest)
            // Rogue directory is left alone.
            assertTrue(rogue.resolve("SKILL.md").exists())
            // The manifest still lists the originally-installed skill.
            val manifest = SkillsManifest.read(target.resolve(SkillInstaller.MANIFEST_FILENAME))
            assertEquals(listOf("a"), manifest.skills.map { it.name })
        }

        @Test
        fun `uninstall of a symlinked skill removes only the symlink`(@TempDir tmp: Path) {
            val source = writeSkill(tmp.resolve("src"), "code-review")
            val target = tmp.resolve("target")
            val installResult = try {
                SkillInstaller(target, SkillInstaller.InstallMode.SYMLINK)
                    .install(source, sourceLabel = "local:$source")
            } catch (e: Exception) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported: ${e.message}")
                return
            }
            if (installResult.installed.isEmpty()) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported on this filesystem")
                return
            }

            val result = SkillInstaller(target).uninstall("code-review")

            assertTrue(result is SkillInstaller.UninstallResult.Removed)
            assertFalse(target.resolve("code-review").exists())
            // Original source must be untouched — a naive recursive delete through
            // the symlink would destroy it.
            assertTrue(source.resolve("SKILL.md").exists())
        }

        @Test
        fun `partial uninstall updates the manifest to reflect only the removed skill`(@TempDir tmp: Path) {
            val src = tmp.resolve("src")
            writeSkill(src, "a")
            writeSkill(src, "b")
            val target = tmp.resolve("target")
            SkillInstaller(target).install(src, sourceLabel = "local:$src")

            SkillInstaller(target).uninstall("a")

            val manifest = SkillsManifest.read(target.resolve(SkillInstaller.MANIFEST_FILENAME))
            assertEquals(listOf("b"), manifest.skills.map { it.name })
            assertFalse(target.resolve("a").exists())
            assertTrue(target.resolve("b/SKILL.md").exists())
        }
    }

    @Nested
    inner class Template {

        @Test
        fun `render produces valid frontmatter and title-cased heading`() {
            val out = SkillTemplate.render("code-review", "Review code changes.")
            assertTrue(out.startsWith("---\nname: code-review\ndescription: Review code changes.\n---"))
            assertTrue("# Code Review" in out)
        }
    }
}
