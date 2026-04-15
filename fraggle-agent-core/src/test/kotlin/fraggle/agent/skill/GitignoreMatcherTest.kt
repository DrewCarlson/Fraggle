package fraggle.agent.skill

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitignoreMatcherTest {

    private fun matcher(vararg lines: String, prefix: String = ""): GitignoreMatcher =
        GitignoreMatcher().also { it.add(lines.toList(), prefix) }

    @Nested
    inner class BasicPatterns {

        @Test
        fun `plain name matches basename at root`() {
            val m = matcher("fixtures")
            assertTrue(m.isIgnored("fixtures", isDirectory = true))
            assertTrue(m.isIgnored("fixtures/SKILL.md", isDirectory = false))
        }

        @Test
        fun `plain name matches basename at any depth`() {
            val m = matcher("fixtures")
            assertTrue(m.isIgnored("a/b/fixtures", isDirectory = true))
            assertTrue(m.isIgnored("a/b/fixtures/SKILL.md", isDirectory = false))
        }

        @Test
        fun `anchored pattern only matches at prefix root`() {
            val m = matcher("/fixtures")
            assertTrue(m.isIgnored("fixtures", isDirectory = true))
            assertFalse(m.isIgnored("nested/fixtures", isDirectory = true))
        }

        @Test
        fun `directory-only pattern skips file matches`() {
            val m = matcher("fixtures/")
            assertTrue(m.isIgnored("fixtures", isDirectory = true))
            assertFalse(m.isIgnored("fixtures", isDirectory = false))
        }

        @Test
        fun `path pattern with slash is implicitly anchored`() {
            val m = matcher("a/b")
            assertTrue(m.isIgnored("a/b", isDirectory = true))
            assertFalse(m.isIgnored("x/a/b", isDirectory = true))
        }
    }

    @Nested
    inner class Wildcards {

        @Test
        fun `star matches basename segment`() {
            val m = matcher("*.log")
            assertTrue(m.isIgnored("foo.log", isDirectory = false))
            assertTrue(m.isIgnored("a/b/foo.log", isDirectory = false))
        }

        @Test
        fun `star does not cross slash`() {
            val m = matcher("/*.log")
            assertTrue(m.isIgnored("foo.log", isDirectory = false))
            assertFalse(m.isIgnored("nested/foo.log", isDirectory = false))
        }

        @Test
        fun `double star matches across slashes`() {
            val m = matcher("/build/**/cache")
            assertTrue(m.isIgnored("build/cache", isDirectory = true))
            assertTrue(m.isIgnored("build/x/y/cache", isDirectory = true))
        }
    }

    @Nested
    inner class Negation {

        @Test
        fun `later negation reinstates a path`() {
            val m = GitignoreMatcher()
            m.add(listOf("*.md", "!keep.md"), prefix = "")
            assertTrue(m.isIgnored("foo.md", isDirectory = false))
            assertFalse(m.isIgnored("keep.md", isDirectory = false))
        }

        @Test
        fun `earlier negation without later ignore has no effect`() {
            val m = GitignoreMatcher()
            m.add(listOf("!keep.md", "*.md"), prefix = "")
            assertTrue(m.isIgnored("keep.md", isDirectory = false))
        }
    }

    @Nested
    inner class CommentsAndBlanks {

        @Test
        fun `comments and blanks are skipped`() {
            val m = matcher("", "# this is a comment", "fixtures")
            assertTrue(m.isIgnored("fixtures", isDirectory = true))
        }

        @Test
        fun `escaped hash is literal`() {
            val m = matcher("\\#hash")
            assertTrue(m.isIgnored("#hash", isDirectory = false))
        }
    }

    @Nested
    inner class PrefixScoping {

        @Test
        fun `prefix confines rules to a subtree`() {
            val m = GitignoreMatcher()
            m.add(listOf("cache"), prefix = "a/")
            assertTrue(m.isIgnored("a/cache", isDirectory = true))
            assertTrue(m.isIgnored("a/deeper/cache", isDirectory = true))
            assertFalse(m.isIgnored("b/cache", isDirectory = true))
            assertFalse(m.isIgnored("cache", isDirectory = true))
        }

        @Test
        fun `anchored pattern under prefix matches only at that level`() {
            val m = GitignoreMatcher()
            m.add(listOf("/cache"), prefix = "a/")
            assertTrue(m.isIgnored("a/cache", isDirectory = true))
            assertFalse(m.isIgnored("a/deeper/cache", isDirectory = true))
        }
    }
}
