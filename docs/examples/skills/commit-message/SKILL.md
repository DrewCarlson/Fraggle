---
name: commit-message
description: Write a conventional-commits style git commit message for staged changes. Use when the user asks to commit, write a commit message, or summarize what they changed.
license: MIT
---

# Conventional Commit Message

When the user asks you to write a commit message:

1. Run `git diff --cached` to see the staged changes. If nothing is staged, run `git diff` and note that the user has unstaged work.
2. Identify the dominant change kind: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `build`, `ci`, `perf`, `style`.
3. Pick a concise scope (the module, package, or feature area the change touches). Skip the scope if the change is repository-wide.
4. Write the subject line as `type(scope): summary` — imperative mood, ≤72 characters, no trailing period.
5. If the change is non-trivial, add a blank line and a short body (1–3 sentences) explaining the *why*, not the *what*.
6. If the change introduces a breaking change, add a `BREAKING CHANGE:` footer describing the migration.

Return only the commit message text — no preamble, no markdown fences.

## Example

```
feat(auth): add rotating refresh tokens

Refresh tokens are now rotated on every use and old tokens
are revoked, closing the replay window exploited in issue #412.
```
