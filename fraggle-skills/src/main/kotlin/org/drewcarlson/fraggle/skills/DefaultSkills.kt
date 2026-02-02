package org.drewcarlson.fraggle.skills

import org.drewcarlson.fraggle.sandbox.Sandbox
import org.drewcarlson.fraggle.skill.SkillRegistry
import org.drewcarlson.fraggle.skills.file.FileSkills
import org.drewcarlson.fraggle.skills.scheduling.SchedulingSkills
import org.drewcarlson.fraggle.skills.scheduling.TaskScheduler
import org.drewcarlson.fraggle.skills.shell.ShellSkills
import org.drewcarlson.fraggle.skills.web.PlaywrightFetcher
import org.drewcarlson.fraggle.skills.web.WebSkills

/**
 * Factory for creating the default skill registry with all built-in skills.
 */
object DefaultSkills {

    /**
     * Create a skill registry with all built-in skills.
     *
     * @param sandbox The sandbox for file and shell operations
     * @param taskScheduler The task scheduler for scheduling skills
     * @param playwrightFetcher Optional Playwright fetcher for JavaScript-heavy web pages
     */
    fun createRegistry(
        sandbox: Sandbox,
        taskScheduler: TaskScheduler = TaskScheduler(),
        playwrightFetcher: PlaywrightFetcher? = null,
    ): SkillRegistry {
        return SkillRegistry {
            // File operations group
            group("filesystem", "File system operations") {
                FileSkills.create(sandbox).forEach { install(it) }
            }

            // Web operations group
            group("web", "Web fetch and search operations") {
                WebSkills.create(sandbox, playwrightFetcher).forEach { install(it) }
            }

            // Shell operations group
            group("shell", "Shell command execution") {
                ShellSkills.create(sandbox).forEach { install(it) }
            }

            // Scheduling operations group
            group("scheduling", "Task scheduling operations") {
                SchedulingSkills.create(taskScheduler).forEach { install(it) }
            }
        }
    }

    /**
     * Create a minimal skill registry with only file and web skills.
     *
     * @param sandbox The sandbox for operations
     * @param playwrightFetcher Optional Playwright fetcher for JavaScript-heavy web pages
     */
    fun createMinimalRegistry(
        sandbox: Sandbox,
        playwrightFetcher: PlaywrightFetcher? = null,
    ): SkillRegistry {
        return SkillRegistry {
            FileSkills.create(sandbox).forEach { install(it) }
            WebSkills.create(sandbox, playwrightFetcher).forEach { install(it) }
        }
    }

    /**
     * Create a skill registry with custom skill selection.
     *
     * @param sandbox The sandbox for operations
     * @param taskScheduler Optional task scheduler for scheduling skills
     * @param playwrightFetcher Optional Playwright fetcher for JavaScript-heavy web pages
     * @param includeFile Include file operation skills
     * @param includeWeb Include web operation skills
     * @param includeShell Include shell command skills
     * @param includeScheduling Include scheduling skills
     */
    fun createCustomRegistry(
        sandbox: Sandbox,
        taskScheduler: TaskScheduler? = null,
        playwrightFetcher: PlaywrightFetcher? = null,
        includeFile: Boolean = true,
        includeWeb: Boolean = true,
        includeShell: Boolean = false,
        includeScheduling: Boolean = false,
    ): SkillRegistry {
        return SkillRegistry {
            if (includeFile) {
                FileSkills.create(sandbox).forEach { install(it) }
            }

            if (includeWeb) {
                WebSkills.create(sandbox, playwrightFetcher).forEach { install(it) }
            }

            if (includeShell) {
                ShellSkills.create(sandbox).forEach { install(it) }
            }

            if (includeScheduling && taskScheduler != null) {
                SchedulingSkills.create(taskScheduler).forEach { install(it) }
            }
        }
    }
}
