# Agent Skills

Skills are self-contained capability bundles the Fraggle agent can discover and invoke. A skill packages a workflow — step-by-step instructions, scripts, reference material — into a directory containing a `SKILL.md` file with YAML frontmatter. Skills are portable: Fraggle follows the [agentskills.io](https://agentskills.io) specification, so a skill authored for Fraggle also works with other agent platforms that implement the spec (and vice versa).

## How they work

Skills use **progressive disclosure**: only skill metadata (name, description, path) is injected into the system prompt at startup. The model reads the full `SKILL.md` body on demand via the built-in `read_file` tool when a user request matches a skill's description. This keeps the context window small no matter how many skills you install.

The model never runs a skill in a sandbox — a skill is just a markdown document with instructions the model chooses to follow. Any side effects (running scripts, editing files, shell commands) happen through the normal tool system under the same supervision rules as any other tool call.

## Skill layout

A skill is a directory containing at minimum a `SKILL.md` file:

```
config/skills/
├── code-review/
│   ├── SKILL.md
│   ├── scripts/
│   │   └── check-style.sh
│   └── references/
│       └── style-guide.md
└── commit-message/
    └── SKILL.md
```

The directory name must match the `name` field in the frontmatter.

## SKILL.md format

```markdown
---
name: code-review
description: Review a pull request for correctness, style, and potential bugs.
license: MIT
---

# Code Review

When the user asks for a code review:

1. Read the diff with `git diff main...HEAD`.
2. For each changed file, read the full file to understand context.
3. Check for:
   - Missing error handling
   - Inconsistent naming
   - Tests for new behavior
4. Report findings as a bulleted list.

Style rules for this repo are in `references/style-guide.md` (relative to this skill).
```

### Required frontmatter fields

| Field         | Type   | Rules                                                                                     |
|---------------|--------|-------------------------------------------------------------------------------------------|
| `name`        | string | 1–64 chars, lowercase `a–z`, digits, hyphens only; must match parent directory name       |
| `description` | string | 1–1024 chars. Describes what the skill does *and when to use it*. Shown to the model.     |

### Optional frontmatter fields

| Field                       | Type        | Meaning                                                                                           |
|-----------------------------|-------------|---------------------------------------------------------------------------------------------------|
| `license`                   | string      | SPDX identifier. Metadata only.                                                                   |
| `compatibility`             | string      | Agent platform compatibility hint. Metadata only.                                                 |
| `allowed-tools`             | string list | Declared tool allowlist (parsed but not enforced in the current release).                         |
| `disable-model-invocation`  | boolean     | If `true`, hide the skill from the auto-catalog. Still reachable via `/skill:name` explicitly.    |

Validation is lenient: bad names or over-long descriptions produce a warning in the logs but the skill still loads. Missing `description` is the only hard failure — the skill is skipped.

## Discovery

At startup, Fraggle scans these locations in order and builds a combined registry:

| Source     | Path                                | Precedence (higher wins) |
|------------|-------------------------------------|--------------------------|
| `PACKAGE`  | Bundled skills (JAR resources)      | 1 (lowest)               |
| `GLOBAL`   | `~/.fraggle/skills/`                | 2                        |
| `PROJECT`  | `./config/skills/` (Fraggle root)   | 3                        |
| `EXPLICIT` | `extra_paths` from config           | 4 (highest)              |

When two sources define a skill with the same name, the higher-precedence source wins — so you can ship a default skill in `~/.fraggle/skills/` and override it per project in `./config/skills/` without editing the original.

**Discovery rule**: if a directory contains a `SKILL.md`, Fraggle treats it as a skill root and does **not** recurse into it. This means nested `SKILL.md` files under an existing skill are ignored — useful if a skill bundles reference material that itself contains markdown. For directories without a `SKILL.md`, Fraggle recurses looking for nested skill roots, so you can organise skills into categories:

```
config/skills/
├── review/
│   ├── code-review/SKILL.md
│   └── docs-review/SKILL.md
└── writing/
    └── commit-message/SKILL.md
```

## Invocation

### Automatic (model-initiated)

At startup, the catalog of visible skills is rendered into the system prompt as an XML block:

```xml
<available_skills>
  <skill>
    <name>code-review</name>
    <description>Review a pull request for correctness, style, and potential bugs.</description>
    <location>/home/you/.fraggle/skills/code-review/SKILL.md</location>
  </skill>
  ...
</available_skills>
```

The model is told to use the `read_file` tool to load a skill's body when the user's request matches one of the descriptions. After reading, it follows the instructions just like any other markdown guide. Relative paths inside a skill are resolved against the skill's directory.

### Explicit (`/skill:name`)

In chat bridges, users can invoke a skill directly:

```
/skill:code-review please look at the changes in main.kt
```

Fraggle parses the command, reads the skill body, strips the frontmatter, and rewrites the user message as:

```
<skill name="code-review" location="/…/code-review/SKILL.md">
References inside this skill are relative to /…/code-review.

# Code Review

When the user asks for a code review:
...
</skill>

please look at the changes in main.kt
```

That rewritten message then flows through the normal agent loop.

**Hidden skills** (those with `disable-model-invocation: true`) are *not* in the catalog, so the model won't pick them up automatically — but they remain reachable via explicit `/skill:name`. Use this for skills you want to keep off the default surface (e.g. destructive operations) while still having them available on request.

## Configuration

All skill behaviour is controlled from `config/fraggle.yaml`:

```yaml
fraggle:
  skills:
    enabled: true                        # Load and advertise skills
    skills_dir: ./config/skills          # Project-scoped directory (PROJECT precedence)
    global_dir: ~/.fraggle/skills        # Cross-project directory (null to disable)
    extra_paths: []                      # Additional files or directories (EXPLICIT precedence)
    enable_slash_commands: true          # Enable /skill:name in chat bridges
```

Setting `enabled: false` wires an empty registry — no catalog block is injected into the system prompt and `/skill:name` commands fall through as unknown. Setting `enable_slash_commands: false` keeps auto-invocation working but disables the explicit `/skill:name` path.

## Authoring tips

- **Write the description for the model, not for humans.** The description is the only part the model sees until it chooses to read the body, and it is what triggers automatic invocation. Include *what* the skill does and *when to use it* — keywords that will appear in user requests.
- **Keep SKILL.md skimmable.** The model will read the whole file; short numbered steps work better than long prose.
- **Use relative paths to bundled assets.** Scripts or reference docs shipped alongside `SKILL.md` can be referenced with plain relative paths — the expander tells the model they resolve against the skill's directory.
- **Test with `/skill:name` first.** Explicit invocation forces the skill body into context without having to rely on the model's automatic match. Once the workflow itself works, tweak the description until automatic invocation kicks in reliably.

## CLI reference

The `fraggle skills` subcommand family is a lightweight skill package manager modelled on [vercel-labs/skills](https://github.com/vercel-labs/skills), scoped to Fraggle's own skill directories. It **does not** write to other agents' directories (`.claude/skills`, `.cursor/skills`, …) — use the JS `npx skills` tool for cross-agent installs.

| Command | What it does |
|---|---|
| `fraggle skills list` | List installed skills from the configured sources, with source, description, and on-disk location. Flags: `-g`/`-p` to filter, `--all` to include hidden skills. |
| `fraggle skills find <terms…>` | Substring-match skills by name and description. Multiple terms are AND-matched, case-insensitive. |
| `fraggle skills validate [path]` | Validate SKILL.md files at a path (or the configured sources if omitted). Exits non-zero on any error; warnings don't fail. CI-friendly. |
| `fraggle skills init <name> [-d "…"]` | Scaffold a new `SKILL.md` under the target directory with a templated body. |
| `fraggle skills add <source>` | Install skills from a local path, a GitHub repo, or a git URL. |
| `fraggle skills update [<names…>]` | Re-fetch and reinstall tracked skills. Omit `<names>` to update everything tracked in the target's manifest. Supports `--dry-run`. |
| `fraggle skills remove <name…>` | Uninstall skills previously installed via `add`. Only touches entries tracked in the target's manifest. |

### Target resolution

`init`, `add`, and `remove` all share the same target resolution:

1. `--path <dir>` — explicit override.
2. `--global` — `SkillsConfig.globalDir` (default `~/.fraggle/skills`).
3. `--project` — `SkillsConfig.skillsDir` (default `./config/skills`).
4. Default: `--global` if configured, else `--project`.

### Source syntax for `add`

`fraggle skills add` accepts four source forms, evaluated in this order:

| Form | Example | Notes |
|---|---|---|
| Local path | `./my-skill`, `/abs/path/to/skill`, `~/skills/code-review` | A single `SKILL.md`, a skill directory, or a parent directory containing multiple skill dirs. Existing local paths always win over shorthand parsing. |
| GitHub shorthand | `vercel-labs/skills`, `acme/tools@main`, `acme/tools/skills/code-review@dev` | Fetched as a zipball from `codeload.github.com`. `@ref` selects a branch, tag, or commit SHA (defaults to `HEAD`). A path after the repo slices to a subdirectory. The **last** `@` in the string is treated as the ref delimiter, so subpath + ref combinations work. |
| github.com URL | `https://github.com/acme/tools/tree/main/skills/foo`, `https://github.com/acme/tools/blob/main/SKILL.md` | `tree/<ref>/<path>` and `blob/<ref>/<path>` forms are parsed into ref + subpath. |
| Git URL | `https://gitlab.com/foo/bar.git`, `git@github.com:foo/bar.git` | Requires `git` on `PATH`. Shells out to `git clone --depth=1 [--branch <ref>] …`. |

On install, `add`:

- Downloads (or clones) into a temp directory and runs `SkillLoader` against it, applying all the normal validation rules (name/description, `.gitignore`, dot-dir skip).
- Copies each discovered skill's directory into the target (or symlinks it, for local sources, with `--symlink`).
- Refuses to overwrite an existing destination — pass `--force` to replace it.
- Records each installation in `<target>/.fraggle-skills.json`. The manifest is what `remove` consults, so skills you drop into the target manually are **not** `remove`-able via the CLI and will be left alone.

### Examples

```bash
# Scaffold a new skill locally
fraggle skills init pr-review -d "Review a pull request for correctness, style, and tests."

# Install from GitHub shorthand
fraggle skills add vercel-labs/skills

# Install one skill from a subdirectory on a branch
fraggle skills add acme/tools/skills/code-review@dev

# Install from a local directory you're authoring, as a symlink
fraggle skills add --symlink ~/code/my-skill

# Install from a GitLab repo
fraggle skills add https://gitlab.com/foo/bar.git

# List everything loaded (including installed + manually-dropped)
fraggle skills list

# Search across loaded skills
fraggle skills find commit message

# Refresh every tracked skill from its original source
fraggle skills update

# Refresh specific skills, showing what would change without touching disk
fraggle skills update commit-message --dry-run

# Uninstall one or more skills
fraggle skills remove pr-review code-review

# Validate a directory before shipping (CI-friendly)
fraggle skills validate ./skills
```

### Notes on `update`

`update` re-resolves each tracked skill by parsing its manifest `source` label back into the original spec, fetches a fresh copy, and reinstalls over the existing directory with `force=true`. Per-entry rules:

- **Local sources installed by copy** — re-copies from the current source, so authoring changes propagate.
- **Local sources installed with `--symlink`** — skipped with a `= nothing to do` message. The symlink already points at live content, so re-copying would defeat the point.
- **GitHub and git URL sources** — always re-fetched. GitHub sources with no `@ref` pull the default branch; pinning `@ref` to a tag/SHA makes update idempotent.
- **Stale or unparseable manifest entries** — fail with a clear message and bump the command's exit code to `1` so scripts notice.

`update` is non-atomic: if the fetch succeeds but the install step fails partway, the old destination may already have been deleted. For pinned (`@ref`) installs this is essentially never seen; for `HEAD`-tracking installs the risk is the same as any other in-place package manager upgrade. If you need atomicity, run against a temporary target (`--path /tmp/staging`) and swap directories yourself.

### Exit codes

| Code | Meaning |
|---|---|
| `0` | Success (including idempotent `add` reruns that skip only on collision). |
| `1` | Validation or install failure — e.g. source had no valid skills, remote fetch failed, partial `remove`. |
| `2` | Usage error — unparseable source, missing local path, bad `--path`. |

## Example

A copyable reference skill lives at [`docs/examples/skills/commit-message/`](https://github.com/drewcarlson/Fraggle/tree/main/docs/examples/skills/commit-message). Copy the directory into `~/.fraggle/skills/` to install it globally, or into `./config/skills/` for a single project — or just run:

```bash
fraggle skills add docs/examples/skills/commit-message
```
