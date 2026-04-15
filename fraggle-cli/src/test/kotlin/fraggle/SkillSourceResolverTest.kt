package fraggle

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillSourceResolverTest {

    @Nested
    inner class Parser {

        @Test
        fun `absolute path is local`() {
            val spec = SkillSourceSpec.parse("/abs/does/not/exist")
            assertIs<SkillSourceSpec.Local>(spec)
        }

        @Test
        fun `relative dot path is local`() {
            val spec = SkillSourceSpec.parse("./nope")
            assertIs<SkillSourceSpec.Local>(spec)
        }

        @Test
        fun `existing local directory beats shorthand parsing`(@TempDir tmp: Path) {
            val dir = tmp.resolve("owner/repo")
            java.nio.file.Files.createDirectories(dir)
            val prevRoot = System.getProperty("user.dir")
            try {
                System.setProperty("user.dir", tmp.toString())
                // FraggleEnvironment.resolvePath uses FRAGGLE_ROOT, not user.dir,
                // so pass an absolute path to skip the ambiguity for this test.
                val spec = SkillSourceSpec.parse(dir.toString())
                assertIs<SkillSourceSpec.Local>(spec)
            } finally {
                System.setProperty("user.dir", prevRoot)
            }
        }

        @Test
        fun `owner slash repo shorthand is GitHub`() {
            val spec = SkillSourceSpec.parse("vercel-labs/skills")
            assertIs<SkillSourceSpec.GitHub>(spec)
            assertEquals("vercel-labs", spec.owner)
            assertEquals("skills", spec.repo)
            assertNull(spec.ref)
            assertNull(spec.subpath)
        }

        @Test
        fun `owner slash repo at ref shorthand sets ref`() {
            val spec = SkillSourceSpec.parse("acme/tools@main")
            assertIs<SkillSourceSpec.GitHub>(spec)
            assertEquals("acme", spec.owner)
            assertEquals("tools", spec.repo)
            assertEquals("main", spec.ref)
            assertNull(spec.subpath)
        }

        @Test
        fun `shorthand with subpath`() {
            val spec = SkillSourceSpec.parse("acme/tools/skills/code-review")
            assertIs<SkillSourceSpec.GitHub>(spec)
            assertEquals("skills/code-review", spec.subpath)
            assertNull(spec.ref)
        }

        @Test
        fun `shorthand with subpath and ref (last at wins)`() {
            val spec = SkillSourceSpec.parse("acme/tools/skills/code-review@dev")
            assertIs<SkillSourceSpec.GitHub>(spec)
            assertEquals("skills/code-review", spec.subpath)
            assertEquals("dev", spec.ref)
        }

        @Test
        fun `github tree URL parses ref and subpath`() {
            val spec = SkillSourceSpec.parse("https://github.com/acme/tools/tree/main/skills/foo")
            assertIs<SkillSourceSpec.GitHub>(spec)
            assertEquals("acme", spec.owner)
            assertEquals("tools", spec.repo)
            assertEquals("main", spec.ref)
            assertEquals("skills/foo", spec.subpath)
        }

        @Test
        fun `github blob URL parses same as tree`() {
            val spec = SkillSourceSpec.parse("https://github.com/acme/tools/blob/main/SKILL.md")
            assertIs<SkillSourceSpec.GitHub>(spec)
            assertEquals("main", spec.ref)
            assertEquals("SKILL.md", spec.subpath)
        }

        @Test
        fun `github root URL has no ref or subpath`() {
            val spec = SkillSourceSpec.parse("https://github.com/acme/tools")
            assertIs<SkillSourceSpec.GitHub>(spec)
            assertNull(spec.ref)
            assertNull(spec.subpath)
        }

        @Test
        fun `non-github https URL is GitUrl`() {
            val spec = SkillSourceSpec.parse("https://gitlab.com/foo/bar.git")
            assertIs<SkillSourceSpec.GitUrl>(spec)
        }

        @Test
        fun `ssh git URL is GitUrl`() {
            val spec = SkillSourceSpec.parse("git@gitlab.com:foo/bar.git")
            assertIs<SkillSourceSpec.GitUrl>(spec)
        }

        @Test
        fun `single segment is not a valid spec`() {
            assertNull(SkillSourceSpec.parse("just-one-thing"))
        }

        @Test
        fun `empty string is rejected`() {
            assertNull(SkillSourceSpec.parse(""))
            assertNull(SkillSourceSpec.parse("   "))
        }

        @Test
        fun `label round-trips ref and subpath`() {
            val spec = SkillSourceSpec.GitHub("acme", "tools", "main", "skills/foo")
            assertEquals("github:acme/tools@main#skills/foo", spec.label())
        }
    }

    @Nested
    inner class LabelParser {

        private fun rt(spec: SkillSourceSpec, label: String) {
            assertEquals(label, when (spec) {
                is SkillSourceSpec.Local -> spec.label()
                is SkillSourceSpec.GitHub -> spec.label()
                is SkillSourceSpec.GitUrl -> spec.label()
            })
            assertEquals(spec, SkillSourceSpec.parseLabel(label))
        }

        @Test
        fun `local label round-trips`() {
            rt(SkillSourceSpec.Local(java.nio.file.Paths.get("/abs/path")), "local:/abs/path")
        }

        @Test
        fun `github bare label round-trips`() {
            rt(SkillSourceSpec.GitHub("acme", "tools", null, null), "github:acme/tools")
        }

        @Test
        fun `github with ref round-trips`() {
            rt(SkillSourceSpec.GitHub("acme", "tools", "main", null), "github:acme/tools@main")
        }

        @Test
        fun `github with subpath round-trips`() {
            rt(
                SkillSourceSpec.GitHub("acme", "tools", null, "skills/foo"),
                "github:acme/tools#skills/foo",
            )
        }

        @Test
        fun `github with ref and subpath round-trips`() {
            rt(
                SkillSourceSpec.GitHub("acme", "tools", "dev", "skills/foo"),
                "github:acme/tools@dev#skills/foo",
            )
        }

        @Test
        fun `git https url round-trips`() {
            rt(
                SkillSourceSpec.GitUrl("https://gitlab.com/foo/bar.git", null, null),
                "git:https://gitlab.com/foo/bar.git",
            )
        }

        @Test
        fun `git https url with ref and subpath round-trips`() {
            rt(
                SkillSourceSpec.GitUrl("https://gitlab.com/foo/bar.git", "main", "skills/x"),
                "git:https://gitlab.com/foo/bar.git@main#skills/x",
            )
        }

        @Test
        fun `git ssh url round-trips with no ref`() {
            // SSH URLs contain `@` inside the URL itself. The parser must NOT
            // interpret that as a ref delimiter.
            rt(
                SkillSourceSpec.GitUrl("git@gitlab.com:foo/bar.git", null, null),
                "git:git@gitlab.com:foo/bar.git",
            )
        }

        @Test
        fun `git ssh url with ref round-trips`() {
            rt(
                SkillSourceSpec.GitUrl("git@gitlab.com:foo/bar.git", "main", null),
                "git:git@gitlab.com:foo/bar.git@main",
            )
        }

        @Test
        fun `git https url with auth user in url round-trips`() {
            // The `@` in `user:token@host` is inside the URL, not a ref delimiter.
            rt(
                SkillSourceSpec.GitUrl("https://user:tok@github.com/foo/bar.git", null, null),
                "git:https://user:tok@github.com/foo/bar.git",
            )
        }

        @Test
        fun `git https url with auth and ref round-trips`() {
            rt(
                SkillSourceSpec.GitUrl("https://user:tok@github.com/foo/bar.git", "dev", null),
                "git:https://user:tok@github.com/foo/bar.git@dev",
            )
        }

        @Test
        fun `unrecognized label returns null`() {
            kotlin.test.assertNull(SkillSourceSpec.parseLabel("something else"))
            kotlin.test.assertNull(SkillSourceSpec.parseLabel("local:"))
            kotlin.test.assertNull(SkillSourceSpec.parseLabel("github:justone"))
            kotlin.test.assertNull(SkillSourceSpec.parseLabel("git:"))
        }
    }

    @Nested
    inner class GitHubFetch {

        private fun buildZip(entries: Map<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((path, content) in entries) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        private fun skillContent(name: String, desc: String = "Test skill") =
            "---\nname: $name\ndescription: $desc\n---\n\n# $name\n"

        private fun mockHttp(bytes: ByteArray, status: HttpStatusCode = HttpStatusCode.OK): HttpClient {
            val engine = MockEngine { _: HttpRequestData ->
                if (status == HttpStatusCode.OK) {
                    respond(
                        content = bytes,
                        status = status,
                        headers = headersOf("Content-Type", "application/zip"),
                    )
                } else {
                    respondError(status)
                }
            }
            return HttpClient(engine)
        }

        @Test
        fun `installs a single skill from a github repo`() = runTest {
            val bytes = buildZip(
                mapOf(
                    "skills-abc1234/" to "",
                    "skills-abc1234/code-review/" to "",
                    "skills-abc1234/code-review/SKILL.md" to skillContent("code-review"),
                ),
            )
            val client = mockHttp(bytes)
            val resolver = SkillSourceResolver(httpClient = client)
            val spec = SkillSourceSpec.GitHub("vercel-labs", "skills", ref = null, subpath = null)

            val staged = resolver.resolve(spec)

            try {
                assertTrue(staged.path.resolve("code-review/SKILL.md").exists())
                assertEquals("github:vercel-labs/skills", staged.label)
            } finally {
                staged.cleanup()
                assertFalse(staged.path.exists(), "cleanup should remove the temp dir")
            }
        }

        @Test
        fun `slices to a subpath`() = runTest {
            val bytes = buildZip(
                mapOf(
                    "repo-sha/" to "",
                    "repo-sha/skills/" to "",
                    "repo-sha/skills/a/" to "",
                    "repo-sha/skills/a/SKILL.md" to skillContent("a"),
                    "repo-sha/skills/b/" to "",
                    "repo-sha/skills/b/SKILL.md" to skillContent("b"),
                    "repo-sha/README.md" to "# readme",
                ),
            )
            val client = mockHttp(bytes)
            val resolver = SkillSourceResolver(httpClient = client)
            val spec = SkillSourceSpec.GitHub("acme", "repo", ref = null, subpath = "skills/b")

            val staged = resolver.resolve(spec)

            try {
                assertTrue(staged.path.resolve("SKILL.md").exists())
                assertEquals("b", staged.path.fileName.toString())
                // README from the repo root must not be visible through the sliced path.
                assertFalse(staged.path.resolve("README.md").exists())
            } finally {
                staged.cleanup()
            }
        }

        @Test
        fun `missing subpath is reported cleanly`() = runTest {
            val bytes = buildZip(
                mapOf(
                    "repo-sha/" to "",
                    "repo-sha/SKILL.md" to skillContent("root-skill"),
                ),
            )
            val resolver = SkillSourceResolver(httpClient = mockHttp(bytes))
            val spec = SkillSourceSpec.GitHub("acme", "repo", ref = null, subpath = "does/not/exist")
            val ex = assertFails { resolver.resolve(spec) }
            assertTrue("subpath not found" in (ex.message ?: ""))
        }

        @Test
        fun `404 is surfaced as an IOException`() = runTest {
            val resolver = SkillSourceResolver(httpClient = mockHttp(ByteArray(0), HttpStatusCode.NotFound))
            val spec = SkillSourceSpec.GitHub("nope", "nope", ref = null, subpath = null)
            val ex = assertFails { resolver.resolve(spec) }
            assertTrue("GitHub download failed" in (ex.message ?: ""))
            assertTrue("404" in (ex.message ?: ""))
        }

        @Test
        fun `zip slip entries are skipped not written`() = runTest {
            val bytes = buildZip(
                mapOf(
                    "repo-sha/" to "",
                    "../escape.txt" to "malicious",
                    "repo-sha/safe/" to "",
                    "repo-sha/safe/SKILL.md" to skillContent("safe"),
                ),
            )
            val resolver = SkillSourceResolver(httpClient = mockHttp(bytes))
            val spec = SkillSourceSpec.GitHub("acme", "repo", ref = null, subpath = null)

            val staged = resolver.resolve(spec)

            try {
                // Dangerous entry must not exist.
                assertFalse(staged.path.resolve("../escape.txt").normalize().exists())
                // Safe entry survives.
                assertTrue(staged.path.resolve("safe/SKILL.md").exists())
            } finally {
                staged.cleanup()
            }
        }

        @Test
        fun `uses the configured ref in the URL`() = runTest {
            val bytes = buildZip(
                mapOf(
                    "repo-sha/" to "",
                    "repo-sha/SKILL.md" to skillContent("root"),
                ),
            )
            var capturedUrl: String? = null
            val engine = MockEngine { req: HttpRequestData ->
                capturedUrl = req.url.toString()
                respond(bytes, HttpStatusCode.OK, headersOf("Content-Type", "application/zip"))
            }
            val resolver = SkillSourceResolver(
                httpClient = HttpClient(engine),
                githubBaseUrl = "https://codeload.example",
            )
            val spec = SkillSourceSpec.GitHub("acme", "repo", ref = "dev", subpath = null)

            val staged = resolver.resolve(spec)
            try {
                assertEquals("https://codeload.example/acme/repo/zip/dev", capturedUrl)
            } finally {
                staged.cleanup()
            }
        }
    }

    @Nested
    inner class GitUrlFetch {

        @Test
        fun `delegates to GitRunner and slices to subpath`() = runTest {
            // Fake runner: writes a minimal skill tree into the target dir.
            val runner = GitRunner { _, _, target ->
                val skillDir = target.resolve("skills/only")
                java.nio.file.Files.createDirectories(skillDir)
                skillDir.resolve("SKILL.md").writeText("---\nname: only\ndescription: From fake git.\n---\n")
                Result.success(Unit)
            }
            val resolver = SkillSourceResolver(
                httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
                gitRunner = runner,
            )
            val spec = SkillSourceSpec.GitUrl(
                url = "https://gitlab.com/acme/tools.git",
                ref = "main",
                subpath = "skills/only",
            )

            val staged = resolver.resolve(spec)

            try {
                assertEquals("only", staged.path.fileName.toString())
                assertTrue(staged.path.resolve("SKILL.md").exists())
                assertTrue(staged.label.startsWith("git:"))
            } finally {
                staged.cleanup()
            }
        }

        @Test
        fun `GitRunner failure propagates and cleans up`() = runTest {
            val runner = GitRunner { _, _, _ -> Result.failure(java.io.IOException("git missing")) }
            val resolver = SkillSourceResolver(
                httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }),
                gitRunner = runner,
            )
            val spec = SkillSourceSpec.GitUrl(
                url = "https://gitlab.com/acme/tools.git",
                ref = null,
                subpath = null,
            )

            val ex = assertFails { resolver.resolve(spec) }
            assertTrue("git missing" in (ex.message ?: ""))
        }
    }
}
