package fraggle.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import fraggle.agent.loop.ThinkingController
import fraggle.agent.skill.InMemorySkillRegistry
import fraggle.agent.skill.SkillCommandExpander
import fraggle.agent.skill.SkillLoader
import fraggle.agent.skill.SkillSource
import fraggle.events.EventBus
import fraggle.models.FraggleEvent
import fraggle.provider.ThinkingLevel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatCommandProcessorTest {

    private val eventBus = EventBus()
    private val processor = ChatCommandProcessor(eventBus)

    @Nested
    inner class IsCommand {
        @Test
        fun `slash prefix is detected as command`() {
            assertTrue(processor.isCommand("/approve"))
            assertTrue(processor.isCommand("/deny"))
            assertTrue(processor.isCommand("/unknown"))
        }

        @Test
        fun `regular messages are not commands`() {
            assertFalse(processor.isCommand("hello"))
            assertFalse(processor.isCommand("approve"))
            assertFalse(processor.isCommand(""))
        }
    }

    @Nested
    inner class HandleCommand {
        @Test
        fun `approve resolves pending permission`() = runTest {
            processor.trackPermissionRequest("chat1", "req-1")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-1", granted.requestId)
                assertTrue(granted.approved)
            }

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.Approved>(result)
            eventJob.join()
        }

        @Test
        fun `deny resolves pending permission`() = runTest {
            processor.trackPermissionRequest("chat1", "req-2")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-2", granted.requestId)
                assertFalse(granted.approved)
            }

            val result = processor.handleCommand("chat1", "/deny")
            assertIs<CommandResult.Denied>(result)
            eventJob.join()
        }

        @Test
        fun `approve with no pending request returns NoPermissionPending`() = runTest {
            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.NoPermissionPending>(result)
        }

        @Test
        fun `deny with no pending request returns NoPermissionPending`() = runTest {
            val result = processor.handleCommand("chat1", "/deny")
            assertIs<CommandResult.NoPermissionPending>(result)
        }

        @Test
        fun `unknown command returns Unknown with command name`() = runTest {
            val result = processor.handleCommand("chat1", "/foobar arg1")
            assertIs<CommandResult.Unknown>(result)
            assertEquals("/foobar", result.command)
        }

        @Test
        fun `approve is case-insensitive`() = runTest {
            processor.trackPermissionRequest("chat1", "req-3")
            val result = processor.handleCommand("chat1", "/Approve")
            assertIs<CommandResult.Approved>(result)
        }

        @Test
        fun `latest request overwrites previous for same chat`() = runTest {
            processor.trackPermissionRequest("chat1", "req-old")
            processor.trackPermissionRequest("chat1", "req-new")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-new", granted.requestId)
            }

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.Approved>(result)
            eventJob.join()
        }

        @Test
        fun `different chats have independent pending requests`() = runTest {
            processor.trackPermissionRequest("chat1", "req-a")
            processor.trackPermissionRequest("chat2", "req-b")

            val eventJob = launch {
                val event = eventBus.events.first { it is FraggleEvent.ToolPermissionGranted }
                val granted = event as FraggleEvent.ToolPermissionGranted
                assertEquals("req-a", granted.requestId)
            }

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.Approved>(result)
            eventJob.join()
        }
    }

    @Nested
    inner class SkillCommands {

        private fun writeSkill(tmp: Path, name: String, description: String, body: String): Path {
            val dir = tmp.resolve(name)
            dir.createDirectories()
            val file = dir.resolve("SKILL.md")
            file.writeText("---\nname: $name\ndescription: $description\n---\n\n$body\n")
            return file
        }

        private fun buildProcessor(tmp: Path, enabled: Boolean = true): ChatCommandProcessor {
            writeSkill(tmp, "code-review", "Review code changes.", "# Code Review\n\nStep 1. Read the diff.")
            val loaded = SkillLoader().loadFromDirectory(tmp, SkillSource.PROJECT)
            val registry = InMemorySkillRegistry(loaded.skills)
            return ChatCommandProcessor(
                eventBus = eventBus,
                skillExpander = SkillCommandExpander { registry },
                skillCommandsEnabled = enabled,
            )
        }

        @Test
        fun `skill command returns Expanded with rewritten text`(@TempDir tmp: Path) = runTest {
            val processor = buildProcessor(tmp)
            val result = processor.handleCommand("chat1", "/skill:code-review please look at main.kt")
            assertIs<CommandResult.Expanded>(result)
            assertEquals("code-review", result.skillName)
            assertTrue("Step 1. Read the diff." in result.newText)
            assertTrue("please look at main.kt" in result.newText)
        }

        @Test
        fun `unknown skill returns UnknownSkill`(@TempDir tmp: Path) = runTest {
            val processor = buildProcessor(tmp)
            val result = processor.handleCommand("chat1", "/skill:missing")
            assertIs<CommandResult.UnknownSkill>(result)
            assertEquals("missing", result.name)
        }

        @Test
        fun `bare skill prefix returns MalformedSkill`(@TempDir tmp: Path) = runTest {
            val processor = buildProcessor(tmp)
            val result = processor.handleCommand("chat1", "/skill:")
            assertIs<CommandResult.MalformedSkill>(result)
        }

        @Test
        fun `skill commands disabled falls through to Unknown`(@TempDir tmp: Path) = runTest {
            val processor = buildProcessor(tmp, enabled = false)
            val result = processor.handleCommand("chat1", "/skill:code-review")
            assertIs<CommandResult.Unknown>(result)
        }

        @Test
        fun `approve still works with skill expander wired`(@TempDir tmp: Path) = runTest {
            val processor = buildProcessor(tmp)
            processor.trackPermissionRequest("chat1", "req-x")
            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.Approved>(result)
        }
    }

    @Nested
    inner class ThinkCommand {
        @Test
        fun `think with no controller falls through to Unknown`() = runTest {
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = null)
            val result = processor.handleCommand("chat1", "/think high")
            assertIs<CommandResult.Unknown>(result)
            assertEquals("/think", result.command)
        }

        @Test
        fun `think with valid level mutates controller and returns ThinkingSet`() = runTest {
            val controller = ThinkingController()
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = controller)
            val result = processor.handleCommand("chat1", "/think high")
            assertIs<CommandResult.ThinkingSet>(result)
            assertEquals("high", result.level)
            assertEquals(ThinkingLevel.HIGH, controller.level)
        }

        @Test
        fun `think off sets OFF level`() = runTest {
            val controller = ThinkingController(ThinkingLevel.HIGH)
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = controller)
            val result = processor.handleCommand("chat1", "/think off")
            assertIs<CommandResult.ThinkingSet>(result)
            assertEquals("off", result.level)
            assertEquals(ThinkingLevel.OFF, controller.level)
        }

        @Test
        fun `think on sets ON level`() = runTest {
            val controller = ThinkingController()
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = controller)
            val result = processor.handleCommand("chat1", "/think on")
            assertIs<CommandResult.ThinkingSet>(result)
            assertEquals("on", result.level)
            assertEquals(ThinkingLevel.ON, controller.level)
        }

        @Test
        fun `think default clears the override`() = runTest {
            val controller = ThinkingController(ThinkingLevel.HIGH)
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = controller)
            val result = processor.handleCommand("chat1", "/think default")
            assertIs<CommandResult.ThinkingSet>(result)
            assertEquals("default", result.level)
            assertNull(controller.level)
        }

        @Test
        fun `think with no argument clears the override`() = runTest {
            val controller = ThinkingController(ThinkingLevel.LOW)
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = controller)
            val result = processor.handleCommand("chat1", "/think")
            assertIs<CommandResult.ThinkingSet>(result)
            assertEquals("default", result.level)
            assertNull(controller.level)
        }

        @Test
        fun `think with bogus level returns ThinkingInvalid and leaves controller unchanged`() = runTest {
            val controller = ThinkingController(ThinkingLevel.MEDIUM)
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = controller)
            val result = processor.handleCommand("chat1", "/think turbo")
            assertIs<CommandResult.ThinkingInvalid>(result)
            assertEquals("turbo", result.raw)
            assertEquals(ThinkingLevel.MEDIUM, controller.level)
        }

        @Test
        fun `think is case insensitive`() = runTest {
            val controller = ThinkingController()
            val processor = ChatCommandProcessor(eventBus = eventBus, thinkingController = controller)
            val result = processor.handleCommand("chat1", "/Think HIGH")
            assertIs<CommandResult.ThinkingSet>(result)
            assertEquals(ThinkingLevel.HIGH, controller.level)
        }
    }

    @Nested
    inner class ClearPermissionRequest {
        @Test
        fun `clearing a request makes approve return NoPermissionPending`() = runTest {
            processor.trackPermissionRequest("chat1", "req-1")
            processor.clearPermissionRequest("req-1")

            val result = processor.handleCommand("chat1", "/approve")
            assertIs<CommandResult.NoPermissionPending>(result)
        }

        @Test
        fun `clearing a non-existent request is a no-op`() {
            processor.clearPermissionRequest("does-not-exist")
        }
    }
}
