package fraggle

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Parsed representation of a `fraggle skills add <source>` argument. Separating
 * parsing from resolution keeps the grammar testable in isolation and lets the
 * resolver focus on network / filesystem work.
 */
sealed class SkillSourceSpec {
    data class Local(val path: Path) : SkillSourceSpec() {
        fun label(): String = "local:$path"
    }

    /**
     * A GitHub repository reference, either from `owner/repo[@ref][/subpath]`
     * shorthand or from a `https://github.com/owner/repo(/tree/ref/path)?` URL.
     */
    data class GitHub(
        val owner: String,
        val repo: String,
        val ref: String?,
        val subpath: String?,
    ) : SkillSourceSpec() {
        fun label(): String = buildString {
            append("github:").append(owner).append('/').append(repo)
            if (ref != null) append('@').append(ref)
            if (subpath != null) append('#').append(subpath)
        }
    }

    /**
     * A generic git URL (anything not on github.com, or an ssh URL like
     * `git@gitlab.com:...`). Resolved via [GitRunner], which shells out to the
     * system `git` binary.
     */
    data class GitUrl(
        val url: String,
        val ref: String?,
        val subpath: String?,
    ) : SkillSourceSpec() {
        fun label(): String = buildString {
            append("git:").append(url)
            if (ref != null) append('@').append(ref)
            if (subpath != null) append('#').append(subpath)
        }
    }

    companion object {
        /**
         * Parse a source argument. Precedence, applied in order:
         *
         *  1. Anything matching a path that exists on disk → [Local]. This is
         *     the friendliest default — `fraggle skills add ./my-skill` and
         *     `fraggle skills add my-skill` both work if the directory exists,
         *     regardless of whether `my-skill` would also parse as shorthand.
         *  2. Explicit path prefixes (`/`, `./`, `../`, `~`) → [Local] even
         *     when the path doesn't exist (so the error message is clearer).
         *  3. `https://github.com/...` → [GitHub].
         *  4. `http(s)://...` or `*.git` or `git@host:…` → [GitUrl].
         *  5. `owner/repo[@ref][/subpath]` shorthand → [GitHub].
         */
        fun parse(raw: String): SkillSourceSpec? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            // Explicit paths: always treat as local, even if they don't exist yet.
            if (trimmed.startsWith('/') || trimmed.startsWith("./") || trimmed.startsWith("../") || trimmed.startsWith("~")) {
                return Local(FraggleEnvironment.resolvePath(trimmed))
            }

            // URL forms.
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                val uri = runCatching { URI.create(trimmed) }.getOrNull() ?: return null
                if (uri.host.equals("github.com", ignoreCase = true)) {
                    return parseGitHubUrl(uri) ?: GitUrl(trimmed, ref = null, subpath = null)
                }
                return GitUrl(trimmed, ref = null, subpath = null)
            }
            if (trimmed.endsWith(".git") || trimmed.startsWith("git@")) {
                return GitUrl(trimmed, ref = null, subpath = null)
            }

            // A string that already resolves to an existing filesystem entry
            // wins over shorthand parsing. Otherwise `fraggle skills add foo/bar`
            // might accidentally try to hit github when the user meant a local
            // `foo/bar` directory.
            val local = FraggleEnvironment.resolvePath(trimmed)
            if (local.exists()) return Local(local)

            return parseShorthand(trimmed)
        }

        private fun parseGitHubUrl(uri: URI): GitHub? {
            val segments = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            val owner = segments[0]
            val repo = segments[1].removeSuffix(".git")
            // /tree/<ref>/<path…> or /blob/<ref>/<path…>
            if (segments.size >= 4 && (segments[2] == "tree" || segments[2] == "blob")) {
                val ref = segments[3]
                val sub = segments.drop(4).joinToString("/").ifEmpty { null }
                return GitHub(owner, repo, ref, sub)
            }
            return GitHub(owner, repo, ref = null, subpath = null)
        }

        private fun parseShorthand(raw: String): GitHub? {
            // Split on the LAST `@` so paths containing `@` in the ref position work:
            // `owner/repo/path/to/skill@dev` → path=owner/repo/path/to/skill, ref=dev
            val atIndex = raw.lastIndexOf('@')
            val (pathPart, ref) = if (atIndex >= 0) {
                raw.substring(0, atIndex) to raw.substring(atIndex + 1).takeIf { it.isNotBlank() }
            } else {
                raw to null
            }
            val segments = pathPart.split('/').filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            if (!isValidOwnerOrRepo(segments[0]) || !isValidOwnerOrRepo(segments[1])) return null
            val owner = segments[0]
            val repo = segments[1]
            val subpath = segments.drop(2).joinToString("/").ifEmpty { null }
            return GitHub(owner, repo, ref, subpath)
        }

        private fun isValidOwnerOrRepo(segment: String): Boolean =
            segment.isNotEmpty() && segment.all { it.isLetterOrDigit() || it in "-._" } && !segment.startsWith('.')

        /**
         * Inverse of [Local.label] / [GitHub.label] / [GitUrl.label]. Parses a
         * manifest `source` string back into a spec so `fraggle skills update`
         * can re-resolve installations without re-asking the user.
         *
         * Grammar (prefixes are mandatory, distinguishing labels from user input):
         * ```
         * local:<absolute-path>
         * github:<owner>/<repo>[@<ref>][#<subpath>]
         * git:<url>[@<ref>][#<subpath>]
         * ```
         *
         * The `git:` form is the tricky one: `<url>` may itself contain `@`
         * (`git@host:foo/bar.git` ssh URLs, `https://user:tok@host/...` auth URLs).
         * We resolve the ambiguity by splitting on the LAST `@` and only treating
         * the suffix as a ref when it "looks like" one — non-empty, no `:`, no
         * `//`. Real git refs satisfy this; URL hosts never do.
         */
        fun parseLabel(label: String): SkillSourceSpec? {
            return when {
                label.startsWith("local:") -> {
                    val path = label.removePrefix("local:").trim()
                    if (path.isEmpty()) null else Local(java.nio.file.Paths.get(path))
                }
                label.startsWith("github:") -> parseGitHubLabel(label.removePrefix("github:"))
                label.startsWith("git:") -> parseGitLabel(label.removePrefix("git:"))
                else -> null
            }
        }

        private fun parseGitHubLabel(body: String): GitHub? {
            val hashIdx = body.indexOf('#')
            val beforeHash = if (hashIdx >= 0) body.substring(0, hashIdx) else body
            val subpath = if (hashIdx >= 0) body.substring(hashIdx + 1).ifEmpty { null } else null

            val atIdx = beforeHash.lastIndexOf('@')
            val pathPart: String
            val ref: String?
            if (atIdx >= 0) {
                pathPart = beforeHash.substring(0, atIdx)
                ref = beforeHash.substring(atIdx + 1).ifEmpty { null }
            } else {
                pathPart = beforeHash
                ref = null
            }
            val segments = pathPart.split('/').filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            if (!isValidOwnerOrRepo(segments[0]) || !isValidOwnerOrRepo(segments[1])) return null
            return GitHub(segments[0], segments[1], ref, subpath)
        }

        private fun parseGitLabel(body: String): GitUrl? {
            val hashIdx = body.indexOf('#')
            val beforeHash = if (hashIdx >= 0) body.substring(0, hashIdx) else body
            val subpath = if (hashIdx >= 0) body.substring(hashIdx + 1).ifEmpty { null } else null

            // Walk `@` positions from the right and pick the first split whose
            // left-hand side actually looks like a valid git URL. This handles
            // both `git@host:path` ssh shorthand and `https://user:tok@host/...`
            // auth URLs where the naive "last @" approach would mis-bucket the
            // embedded `@` as a ref delimiter.
            var searchTo = beforeHash.length
            while (true) {
                val atIdx = beforeHash.lastIndexOf('@', searchTo - 1)
                if (atIdx < 0) break
                val candidateUrl = beforeHash.substring(0, atIdx)
                val candidateRef = beforeHash.substring(atIdx + 1)
                if (candidateRef.isNotEmpty() && looksLikeGitUrl(candidateUrl)) {
                    return GitUrl(candidateUrl, candidateRef, subpath)
                }
                searchTo = atIdx
            }
            if (beforeHash.isEmpty()) return null
            return GitUrl(beforeHash, null, subpath)
        }

        /**
         * Heuristic for "is this string a plausible git URL?" used by the
         * label parser to disambiguate `@ref` suffixes from `@` characters
         * embedded inside URLs (ssh shorthand or `user:tok@host` auth URLs).
         *
         *  - Schemed URLs (`http(s)://`, `git://`, `ssh://`, `file://`) must
         *    have at least one `.` or `/` after the scheme — so `https://host.tld/...`
         *    matches but `https://user:tok` (which shows up when we naively
         *    split an auth URL on its embedded `@`) does not.
         *  - SSH shorthand (`user@host:path`) must have a hostname with a `.`.
         */
        private fun looksLikeGitUrl(s: String): Boolean {
            val schemes = listOf("http://", "https://", "git://", "ssh://", "file://")
            for (scheme in schemes) {
                if (s.startsWith(scheme)) {
                    val rest = s.substring(scheme.length)
                    return rest.isNotEmpty() && ('.' in rest || '/' in rest)
                }
            }
            val at = s.indexOf('@')
            if (at > 0) {
                val colon = s.indexOf(':', at + 1)
                if (colon > at + 1 && colon < s.length - 1) {
                    val host = s.substring(at + 1, colon)
                    if ('.' in host) return true
                }
            }
            return false
        }
    }
}

/**
 * Result of staging a source. The caller is responsible for invoking [cleanup]
 * once the staged content has been consumed, regardless of success or failure.
 */
data class StagedSource(
    val path: Path,
    val label: String,
    val cleanup: () -> Unit,
)

/**
 * Shells out to the system `git` binary. Extracted behind an interface so tests
 * can replace it without touching the resolver's main logic.
 */
fun interface GitRunner {
    fun clone(url: String, ref: String?, targetDir: Path): Result<Unit>

    companion object {
        /** Default implementation: runs `git clone --depth=1 [--branch ref] <url> <target>`. */
        val System: GitRunner = GitRunner { url, ref, targetDir ->
            val cmd = buildList {
                add("git")
                add("clone")
                add("--depth=1")
                if (ref != null) {
                    add("--branch")
                    add(ref)
                }
                add(url)
                add(targetDir.toString())
            }
            try {
                val proc = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
                val output = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit == 0) Result.success(Unit)
                else Result.failure(IOException("git clone failed (exit=$exit): ${output.trim()}"))
            } catch (e: Exception) {
                Result.failure(
                    IOException(
                        "failed to invoke git; install git or use a GitHub shorthand / URL " +
                            "(${e.message ?: e::class.simpleName})",
                        e,
                    ),
                )
            }
        }
    }
}

/**
 * Resolves a [SkillSourceSpec] to a [StagedSource] that [SkillInstaller] can
 * consume. Local sources pass through unchanged; remote sources are fetched,
 * extracted or cloned into a temp directory, and sliced to the requested
 * subpath.
 *
 * The [httpClient] is injectable so tests can plug in Ktor's MockEngine.
 */
class SkillSourceResolver(
    private val httpClient: HttpClient,
    private val gitRunner: GitRunner = GitRunner.System,
    private val githubBaseUrl: String = "https://codeload.github.com",
) {

    suspend fun resolve(spec: SkillSourceSpec): StagedSource = when (spec) {
        is SkillSourceSpec.Local -> {
            if (!spec.path.exists()) {
                throw IOException("source does not exist: ${spec.path}")
            }
            StagedSource(spec.path, spec.label(), cleanup = {})
        }
        is SkillSourceSpec.GitHub -> resolveGitHub(spec)
        is SkillSourceSpec.GitUrl -> resolveGitUrl(spec)
    }

    // ---- GitHub ----

    private suspend fun resolveGitHub(spec: SkillSourceSpec.GitHub): StagedSource {
        val ref = spec.ref ?: DEFAULT_REF
        val url = "$githubBaseUrl/${spec.owner}/${spec.repo}/zip/$ref"
        val tempDir = Files.createTempDirectory("fraggle-skills-gh-")
        val cleanup = { deleteRecursively(tempDir) }

        try {
            val response: HttpResponse = httpClient.get(url)
            if (!response.status.isSuccess()) {
                throw IOException(
                    "GitHub download failed (${response.status.value} ${response.status.description}) " +
                        "for ${spec.owner}/${spec.repo}@$ref",
                )
            }
            val bytes = response.bodyAsChannel().toInputStream().use { it.readBytes() }
            val extracted = extractZip(bytes, tempDir)
            val stagedRoot = stripSingleRoot(extracted)
            val sliced = applySubpath(stagedRoot, spec.subpath)
            return StagedSource(sliced, spec.label(), cleanup)
        } catch (t: Throwable) {
            cleanup()
            throw t
        }
    }

    // ---- Git URL ----

    private fun resolveGitUrl(spec: SkillSourceSpec.GitUrl): StagedSource {
        val tempDir = Files.createTempDirectory("fraggle-skills-git-")
        val cleanup = { deleteRecursively(tempDir) }
        try {
            val cloneDir = tempDir.resolve("clone")
            gitRunner.clone(spec.url, spec.ref, cloneDir).getOrThrow()
            val sliced = applySubpath(cloneDir, spec.subpath)
            return StagedSource(sliced, spec.label(), cleanup)
        } catch (t: Throwable) {
            cleanup()
            throw t
        }
    }

    // ---- zip helpers ----

    private fun extractZip(bytes: ByteArray, target: Path): Path {
        target.createDirectories()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val safeName = sanitizeZipEntryName(entry.name)
                if (safeName == null) {
                    zip.closeEntry()
                    continue
                }
                val out = target.resolve(safeName).normalize()
                if (!out.startsWith(target)) {
                    // Zip slip — skip anything that would escape the target dir.
                    zip.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    out.createDirectories()
                } else {
                    out.parent?.createDirectories()
                    Files.newOutputStream(out).use { os -> zip.copyTo(os) }
                }
                zip.closeEntry()
            }
        }
        return target
    }

    private fun sanitizeZipEntryName(name: String): String? {
        val normalized = name.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return null
        // Skip absolute paths and anything that backtracks.
        if (normalized.startsWith('/') || normalized.split('/').any { it == ".." }) return null
        return normalized
    }

    /**
     * GitHub zipballs contain a single top-level directory of the form
     * `<repo>-<sha>/...`. Peel it off so consumers see a clean skill tree.
     */
    private fun stripSingleRoot(dir: Path): Path {
        val entries = Files.list(dir).use { it.toList() }
        if (entries.size == 1 && entries.single().isDirectory()) {
            return entries.single()
        }
        return dir
    }

    private fun applySubpath(root: Path, subpath: String?): Path {
        if (subpath == null) return root
        val target = root.resolve(subpath).normalize()
        if (!target.startsWith(root)) {
            throw IOException("subpath escapes the source root: $subpath")
        }
        if (!target.exists()) {
            throw IOException("subpath not found in source: $subpath")
        }
        return target
    }

    // ---- cleanup ----

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach {
                runCatching { Files.delete(it) }
            }
        }
    }

    companion object {
        private const val DEFAULT_REF = "HEAD"
    }
}
