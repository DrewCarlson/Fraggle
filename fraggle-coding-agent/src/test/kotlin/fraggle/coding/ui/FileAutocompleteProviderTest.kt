package fraggle.coding.ui

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileAutocompleteProviderTest {

    /** Mutable clock for TTL tests. */
    private class MutableClock(var now: Long = 0L) {
        fun get(): Long = now
    }

    private fun touch(p: Path, content: String = "") {
        if (!p.parent.toFile().exists()) p.parent.toFile().mkdirs()
        p.writeText(content)
    }

    @Nested
    inner class TriggerHandling {
        @Test
        fun `handlesTrigger is true only for at sign`(@TempDir tmp: Path) {
            val p = FileAutocompleteProvider(root = tmp)
            assertTrue(p.handlesTrigger('@'))
            assertFalse(p.handlesTrigger('/'))
            assertFalse(p.handlesTrigger('#'))
            assertFalse(p.handlesTrigger('a'))
            assertFalse(p.handlesTrigger(' '))
        }

        @Test
        fun `suggest with non-handled trigger returns empty`(@TempDir tmp: Path) {
            touch(tmp.resolve("a.txt"))
            val p = FileAutocompleteProvider(root = tmp)
            assertEquals(emptyList(), p.suggest('/', "", 10))
        }
    }

    @Nested
    inner class Walking {
        @Test
        fun `returns entries for a simple tree`(@TempDir tmp: Path) {
            touch(tmp.resolve("alpha.kt"))
            touch(tmp.resolve("beta.kt"))
            touch(tmp.resolve("gamma.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 20)

            val labels = results.map { it.label }
            assertTrue("alpha.kt" in labels, "missing alpha.kt; got $labels")
            assertTrue("beta.kt" in labels, "missing beta.kt; got $labels")
            assertTrue("gamma.kt" in labels, "missing gamma.kt; got $labels")
        }

        @Test
        fun `files inside dot-git are not suggested`(@TempDir tmp: Path) {
            touch(tmp.resolve("keep.kt"))
            val git = tmp.resolve(".git")
            git.createDirectory()
            touch(git.resolve("HEAD"))
            touch(git.resolve("config"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 100)

            val labels = results.map { it.label }
            assertFalse(labels.any { it.startsWith(".git") || it.contains("/HEAD") || it == "HEAD" },
                "Should not include .git contents; got $labels")
            assertTrue("keep.kt" in labels, "keep.kt should survive; got $labels")
        }

        @Test
        fun `files inside node_modules are not suggested`(@TempDir tmp: Path) {
            touch(tmp.resolve("keep.kt"))
            val nm = tmp.resolve("node_modules/pkg").apply { createDirectories() }
            touch(nm.resolve("index.js"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 100)

            val labels = results.map { it.label }
            assertFalse(labels.any { it.startsWith("node_modules") }, "got $labels")
            assertFalse("index.js" in labels, "got $labels")
        }

        @Test
        fun `files inside build are not suggested`(@TempDir tmp: Path) {
            touch(tmp.resolve("keep.kt"))
            val build = tmp.resolve("build/classes").apply { createDirectories() }
            touch(build.resolve("Foo.class"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 100)

            val labels = results.map { it.label }
            assertFalse(labels.any { it.startsWith("build") || it == "Foo.class" }, "got $labels")
        }

        @Test
        fun `nested ignored dirs also blocked (build inside src)`(@TempDir tmp: Path) {
            val nested = tmp.resolve("src/build").apply { createDirectories() }
            touch(nested.resolve("x.kt"))
            touch(tmp.resolve("src/ok.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 100)

            val labels = results.map { it.label }
            assertFalse(labels.any { it.startsWith("src/build") }, "got $labels")
            assertTrue("src/ok.kt" in labels, "ok.kt should survive; got $labels")
        }

        @Test
        fun `hidden dot-dirs are skipped`(@TempDir tmp: Path) {
            touch(tmp.resolve("visible.kt"))
            val hidden = tmp.resolve(".hidden").apply { createDirectory() }
            touch(hidden.resolve("secret.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 100)

            val labels = results.map { it.label }
            assertFalse(labels.any { it.startsWith(".hidden") || it == "secret.kt" },
                "hidden dir should not be suggested; got $labels")
            assertTrue("visible.kt" in labels, "got $labels")
        }

        @Test
        fun `dot-fraggle dir IS included -- exception to hidden-dir skip`(@TempDir tmp: Path) {
            touch(tmp.resolve("top.kt"))
            val f = tmp.resolve(".fraggle").apply { createDirectory() }
            touch(f.resolve("skill.md"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 100)

            val labels = results.map { it.label }
            assertTrue(labels.any { it.startsWith(".fraggle/") },
                ".fraggle subtree should be included; got $labels")
            assertTrue(labels.any { it == ".fraggle/skill.md" || it.endsWith("skill.md") },
                "skill.md should appear; got $labels")
        }

        @Test
        fun `non-existent root yields empty list`(@TempDir tmp: Path) {
            val ghost = tmp.resolve("does-not-exist")
            val p = FileAutocompleteProvider(root = ghost)
            assertEquals(emptyList(), p.suggest('@', "", 10))
        }
    }

    @Nested
    inner class EmptyPrefix {
        @Test
        fun `empty prefix returns shallowest paths first`(@TempDir tmp: Path) {
            touch(tmp.resolve("root.kt"))
            touch(tmp.resolve("a/one.kt").also { it.parent.toFile().mkdirs() })
            touch(tmp.resolve("a/b/two.kt").also { it.parent.toFile().mkdirs() })
            touch(tmp.resolve("a/b/c/three.kt").also { it.parent.toFile().mkdirs() })

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 50)

            // First result should be a zero-depth path (no '/' in relative).
            assertNotNull(results.firstOrNull())
            val first = results.first().label
            assertFalse('/' in first.trimEnd('/').let {
                // Directories end in "/"; check depth excluding trailing.
                if (first.endsWith("/")) it else first
            }, "shallowest entry expected first, got $first")

            // Find depths of root.kt vs three.kt.
            val depths = results.map { it.label to it.label.count { ch -> ch == '/' } }
            val rootDepth = depths.first { it.first == "root.kt" }.second
            val deepDepth = depths.first { it.first == "a/b/c/three.kt" }.second
            assertTrue(rootDepth < deepDepth, "root must be shallower than three; got $depths")
            // And order: any zero-depth file must come before any 3-depth file.
            val rootIdx = results.indexOfFirst { it.label == "root.kt" }
            val deepIdx = results.indexOfFirst { it.label == "a/b/c/three.kt" }
            assertTrue(rootIdx < deepIdx, "root.kt (idx=$rootIdx) should come before three.kt (idx=$deepIdx)")
        }
    }

    @Nested
    inner class Scoring {
        @Test
        fun `filename-start outranks substring match`(@TempDir tmp: Path) {
            // Both files have "foo" in them, but only one has a name starting with it.
            touch(tmp.resolve("notfoo.kt")) // substring at end of name → only substring match
            touch(tmp.resolve("foobar.kt")) // filename-start match

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "foo", 10)

            val labels = results.map { it.label }
            assertTrue(labels.isNotEmpty(), "expected some results, got $labels")
            val fooIdx = labels.indexOf("foobar.kt")
            val notIdx = labels.indexOf("notfoo.kt")
            assertTrue(fooIdx >= 0, "foobar.kt missing from $labels")
            // If notfoo.kt also matched (it contains "foo"), foobar.kt must outrank it.
            if (notIdx >= 0) {
                assertTrue(fooIdx < notIdx,
                    "foobar.kt (idx=$fooIdx) should rank above notfoo.kt (idx=$notIdx); got $labels")
            }
        }

        @Test
        fun `path-start match is a valid hit`(@TempDir tmp: Path) {
            val dir = tmp.resolve("source").apply { createDirectory() }
            touch(dir.resolve("Main.kt"))
            touch(tmp.resolve("unrelated.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "sour", 10)

            val labels = results.map { it.label }
            assertTrue(labels.any { it.startsWith("source") },
                "expected source/... match for prefix 'sour', got $labels")
        }

        @Test
        fun `segment-start match inside path works`(@TempDir tmp: Path) {
            touch(tmp.resolve("pkg/util/Foo.kt").also { it.parent.toFile().mkdirs() })
            touch(tmp.resolve("pkg/other/Bar.kt").also { it.parent.toFile().mkdirs() })

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "util", 20)

            val labels = results.map { it.label }
            assertTrue(labels.any { it.contains("/util/") || it.startsWith("util") },
                "prefix 'util' should find util segment; got $labels")
        }

        @Test
        fun `non-matching prefix returns empty list`(@TempDir tmp: Path) {
            touch(tmp.resolve("alpha.kt"))
            touch(tmp.resolve("beta.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "zzz-nope-xyz", 10)
            assertEquals(emptyList(), results)
        }
    }

    @Nested
    inner class CompletionShape {
        @Test
        fun `directory entries come with continueCompletion and no trailing space`(@TempDir tmp: Path) {
            tmp.resolve("src").createDirectory()
            touch(tmp.resolve("src/Foo.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "src", 20)

            val dirEntry = results.firstOrNull { it.label == "src/" }
            assertNotNull(dirEntry, "expected src/ entry in $results")
            assertTrue(dirEntry.continueCompletion, "directory should continueCompletion")
            assertFalse(dirEntry.trailingSpace, "directory should not add trailing space")
        }

        @Test
        fun `file entries come with trailing space and no continueCompletion`(@TempDir tmp: Path) {
            touch(tmp.resolve("alpha.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "alpha", 20)

            val fileEntry = results.firstOrNull { it.label == "alpha.kt" }
            assertNotNull(fileEntry, "expected alpha.kt entry in $results")
            assertTrue(fileEntry.trailingSpace, "file should add trailing space")
            assertFalse(fileEntry.continueCompletion, "file should not continueCompletion")
        }

        @Test
        fun `replacement equals label`(@TempDir tmp: Path) {
            touch(tmp.resolve("onlyone.kt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 10)

            val entry = results.first { it.label == "onlyone.kt" }
            assertEquals(entry.label, entry.replacement)
        }
    }

    @Nested
    inner class Limits {
        @Test
        fun `suggest honors the limit arg`(@TempDir tmp: Path) {
            for (i in 0 until 20) touch(tmp.resolve("file$i.txt"))

            val p = FileAutocompleteProvider(root = tmp)
            val results = p.suggest('@', "", 5)

            assertEquals(5, results.size)
        }

        @Test
        fun `walker respects cap when the tree is larger`(@TempDir tmp: Path) {
            // Create more files than the cap.
            for (i in 0 until 30) touch(tmp.resolve("f$i.txt"))

            val p = FileAutocompleteProvider(root = tmp, cap = 5)
            val results = p.suggest('@', "", 100)

            // Only capped number of entries were walked + available to suggest.
            // (The limit arg is 100, so the only bound is the cap.)
            assertTrue(results.size <= 5, "expected <=5 entries due to cap, got ${results.size}")
        }
    }

    @Nested
    inner class TtlCaching {
        @Test
        fun `second query within TTL window does NOT rewalk`(@TempDir tmp: Path) {
            touch(tmp.resolve("first.kt"))

            val clock = MutableClock(now = 1_000L)
            val p = FileAutocompleteProvider(
                root = tmp,
                refreshTtlMs = 2_000L,
                clock = clock::get,
            )

            // First query: walks + caches.
            val first = p.suggest('@', "", 50)
            assertTrue(first.any { it.label == "first.kt" })

            // Add a new file — but don't let time advance past the TTL yet.
            touch(tmp.resolve("second.kt"))
            clock.now = 2_500L // still inside 2000ms window from 1000ms

            val midWindow = p.suggest('@', "", 50)
            assertTrue(midWindow.any { it.label == "first.kt" })
            assertFalse(midWindow.any { it.label == "second.kt" },
                "new file should NOT appear while cache is still fresh; got ${midWindow.map { it.label }}")
        }

        @Test
        fun `cache expires after TTL and re-walks`(@TempDir tmp: Path) {
            touch(tmp.resolve("first.kt"))

            val clock = MutableClock(now = 1_000L)
            val p = FileAutocompleteProvider(
                root = tmp,
                refreshTtlMs = 2_000L,
                clock = clock::get,
            )

            // First query at t=1000 — populates cache.
            p.suggest('@', "", 50)

            touch(tmp.resolve("second.kt"))
            // t=3001 — TTL (2000ms) elapsed from 1000.
            clock.now = 3_001L

            val fresh = p.suggest('@', "", 50)
            val labels = fresh.map { it.label }
            assertTrue("second.kt" in labels,
                "new file should appear after TTL expiry; got $labels")
        }
    }
}
