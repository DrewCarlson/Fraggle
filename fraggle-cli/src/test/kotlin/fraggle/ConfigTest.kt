package fraggle

import fraggle.models.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.*

class ConfigTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    inner class DefaultValues {

        @Test
        fun `FraggleConfig has sensible defaults`() {
            val config = FraggleConfig()

            assertEquals(ProviderType.LMSTUDIO, config.fraggle.provider.type)
            assertEquals("http://localhost:1234/v1", config.fraggle.provider.url)
            assertEquals("", config.fraggle.provider.model)
        }

        @Test
        fun `AgentConfig has sensible defaults`() {
            val settings = AgentConfig()

            assertEquals(0.7, settings.temperature)
            assertEquals(4096, settings.maxTokens)
            assertEquals(10, settings.maxIterations)
            assertEquals(20, settings.maxHistoryMessages)
        }

        @Test
        fun `ExecutorConfig defaults to local`() {
            val settings = ExecutorConfig()

            assertEquals(ExecutorType.LOCAL, settings.type)
            assertEquals("./data/workspace", settings.workDir)
            assertEquals(SupervisionMode.NONE, settings.supervision)
            assertEquals(emptyList(), settings.toolPolicies)
        }

        @Test
        fun `PromptsConfig has sensible defaults`() {
            val settings = PromptsConfig()

            assertEquals("./config/prompts", settings.promptsDir)
            assertEquals(20_000, settings.maxFileChars)
            assertTrue(settings.autoCreateMissing)
        }

        @Test
        fun `MemoryConfig has sensible defaults`() {
            val settings = MemoryConfig()

            assertEquals("./data/memory", settings.baseDir)
        }

        @Test
        fun `SignalBridgeConfig has sensible defaults`() {
            val config = SignalBridgeConfig()

            assertEquals("", config.phone)
            assertFalse(config.enabled) // Empty phone means disabled
            assertEquals("./config/app/signal", config.configDir)
            assertEquals("@fraggle", config.trigger)
            assertTrue(config.respondToDirectMessages)
            assertTrue(config.showTypingIndicator)
        }

        @Test
        fun `SignalBridgeConfig enabled when phone is set`() {
            val config = SignalBridgeConfig(phone = "+1234567890")

            assertTrue(config.enabled)
        }
    }

    @Nested
    inner class YamlParsing {

        @Test
        fun `loads minimal valid config`() {
            val configFile = tempDir.resolve("config.yaml")
            configFile.writeText("""
                fraggle:
                  provider:
                    type: lmstudio
                    url: http://localhost:1234/v1
            """.trimIndent())

            val config = ConfigLoader.load(configFile)

            assertEquals(ProviderType.LMSTUDIO, config.fraggle.provider.type)
        }

        @Test
        fun `loads full config with all sections`() {
            val configFile = tempDir.resolve("config.yaml")
            configFile.writeText("""
                fraggle:
                  provider:
                    type: lmstudio
                    url: http://custom:8080/v1
                    model: my-model
                  bridges:
                    signal:
                      phone: "+1234567890"
                      enabled: true
                      config_dir: /custom/signal
                      trigger: "@bot"
                      respond_to_direct_messages: false
                      show_typing_indicator: false
                  prompts:
                    prompts_dir: /custom/prompts
                    max_file_chars: 10000
                    auto_create_missing: false
                  memory:
                    base_dir: /custom/memory
                  executor:
                    type: local
                    work_dir: /custom/workspace
                  agent:
                    temperature: 0.5
                    max_tokens: 2048
                    max_iterations: 5
                    max_history_messages: 10
            """.trimIndent())

            val config = ConfigLoader.load(configFile)

            // Provider
            assertEquals("http://custom:8080/v1", config.fraggle.provider.url)
            assertEquals("my-model", config.fraggle.provider.model)

            // Signal bridge
            val signal = config.fraggle.bridges.signal
            assertNotNull(signal)
            assertEquals("+1234567890", signal.phone)
            assertTrue(signal.enabled)
            assertEquals("/custom/signal", signal.configDir)
            assertEquals("@bot", signal.trigger)
            assertFalse(signal.respondToDirectMessages)
            assertFalse(signal.showTypingIndicator)

            // Prompts
            assertEquals("/custom/prompts", config.fraggle.prompts.promptsDir)
            assertEquals(10000, config.fraggle.prompts.maxFileChars)
            assertFalse(config.fraggle.prompts.autoCreateMissing)

            // Memory
            assertEquals("/custom/memory", config.fraggle.memory.baseDir)

            // Executor (sandbox key)
            assertEquals("/custom/workspace", config.fraggle.executor.workDir)

            // Agent
            assertEquals(0.5, config.fraggle.agent.temperature)
            assertEquals(2048, config.fraggle.agent.maxTokens)
            assertEquals(5, config.fraggle.agent.maxIterations)
            assertEquals(10, config.fraggle.agent.maxHistoryMessages)
        }

        @Test
        fun `loads config with registered chats`() {
            val configFile = tempDir.resolve("config.yaml")
            configFile.writeText("""
                fraggle:
                  provider:
                    type: lmstudio
                  chats:
                    registered:
                      - id: "group:abc123"
                        name: "Dev Team"
                        trigger_override: "@devbot"
                        enabled: true
                      - id: "+1234567890"
                        name: "Personal"
            """.trimIndent())

            val config = ConfigLoader.load(configFile)

            assertEquals(2, config.fraggle.chats.registered.size)

            val devTeam = config.fraggle.chats.registered[0]
            assertEquals("group:abc123", devTeam.id)
            assertEquals("Dev Team", devTeam.name)
            assertEquals("@devbot", devTeam.triggerOverride)
            assertTrue(devTeam.enabled)

            val personal = config.fraggle.chats.registered[1]
            assertEquals("+1234567890", personal.id)
            assertEquals("Personal", personal.name)
            assertNull(personal.triggerOverride)
            assertTrue(personal.enabled) // default
        }

        @Test
        fun `loads config with playwright settings`() {
            val configFile = tempDir.resolve("config.yaml")
            configFile.writeText("""
                fraggle:
                  provider:
                    type: lmstudio
                  web:
                    playwright:
                      ws_endpoint: ws://localhost:3000
                      navigation_timeout: 60000
                      wait_after_load: 5000
                      viewport_width: 1920
                      viewport_height: 1080
                      user_agent: "CustomAgent/1.0"
            """.trimIndent())

            val config = ConfigLoader.load(configFile)

            val playwright = config.fraggle.web.playwright
            assertNotNull(playwright)
            assertEquals("ws://localhost:3000", playwright.wsEndpoint)
            assertEquals(60000L, playwright.navigationTimeout)
            assertEquals(5000L, playwright.waitAfterLoad)
            assertEquals(1920, playwright.viewportWidth)
            assertEquals(1080, playwright.viewportHeight)
            assertEquals("CustomAgent/1.0", playwright.userAgent)
        }

        @Test
        fun `parses all provider types`() {
            val types = listOf("lmstudio", "openai", "anthropic")
            val expected = listOf(ProviderType.LMSTUDIO, ProviderType.OPENAI, ProviderType.ANTHROPIC)

            for ((typeName, expectedType) in types.zip(expected)) {
                val configFile = tempDir.resolve("config-$typeName.yaml")
                configFile.writeText("""
                    fraggle:
                      provider:
                        type: $typeName
                """.trimIndent())

                val config = ConfigLoader.load(configFile)
                assertEquals(expectedType, config.fraggle.provider.type)
            }
        }

        @Test
        fun `parses all executor types`() {
            val types = listOf("local", "remote")
            val expected = listOf(ExecutorType.LOCAL, ExecutorType.REMOTE)

            for ((typeName, expectedType) in types.zip(expected)) {
                val configFile = tempDir.resolve("config-$typeName.yaml")
                configFile.writeText("""
                    fraggle:
                      executor:
                        type: $typeName
                """.trimIndent())

                val config = ConfigLoader.load(configFile)
                assertEquals(expectedType, config.fraggle.executor.type, "Failed for type: $typeName")
            }
        }

        @Test
        fun `parses tool_policies with object rules`() {
            val configFile = tempDir.resolve("config-rules.yaml")
            configFile.writeText("""
                fraggle:
                  executor:
                    supervision: supervised
                    tool_policies:
                      - tool: list_files
                      - tool: write_file
                        args:
                          - name: path
                            value:
                              - "/workspace/**"
            """.trimIndent())

            val config = ConfigLoader.load(configFile)
            assertEquals(2, config.fraggle.executor.toolPolicies.size)
            assertEquals("list_files", config.fraggle.executor.toolPolicies[0].tool)
            assertEquals(ApprovalPolicy.ALLOW, config.fraggle.executor.toolPolicies[0].policy)
            assertTrue(config.fraggle.executor.toolPolicies[0].args.isEmpty())
            assertEquals("write_file", config.fraggle.executor.toolPolicies[1].tool)
            assertEquals(1, config.fraggle.executor.toolPolicies[1].args.size)
            assertEquals("path", config.fraggle.executor.toolPolicies[1].args[0].name)
            assertEquals(listOf("/workspace/**"), config.fraggle.executor.toolPolicies[1].args[0].value)
            assertNull(config.fraggle.executor.toolPolicies[1].args[0].policy)
        }

        @Test
        fun `parses tool_policies with policy field`() {
            val configFile = tempDir.resolve("config-policy.yaml")
            configFile.writeText("""
                fraggle:
                  executor:
                    supervision: supervised
                    tool_policies:
                      - tool: read_file
                        policy: allow
                      - tool: write_file
                        policy: deny
                        args:
                          - name: path
                            value: ["/etc/**"]
                      - tool: execute_command
                        policy: ask
            """.trimIndent())

            val config = ConfigLoader.load(configFile)
            assertEquals(3, config.fraggle.executor.toolPolicies.size)

            assertEquals("read_file", config.fraggle.executor.toolPolicies[0].tool)
            assertEquals(ApprovalPolicy.ALLOW, config.fraggle.executor.toolPolicies[0].policy)

            assertEquals("write_file", config.fraggle.executor.toolPolicies[1].tool)
            assertEquals(ApprovalPolicy.DENY, config.fraggle.executor.toolPolicies[1].policy)

            assertEquals("execute_command", config.fraggle.executor.toolPolicies[2].tool)
            assertEquals(ApprovalPolicy.ASK, config.fraggle.executor.toolPolicies[2].policy)
        }

        @Test
        fun `parses arg-level policy`() {
            val configFile = tempDir.resolve("config-arg-policy.yaml")
            configFile.writeText("""
                fraggle:
                  executor:
                    supervision: supervised
                    tool_policies:
                      - tool: execute_command
                        policy: allow
                        args:
                          - name: command
                            value: ["ls"]
                          - name: working_dir
                            value: ["/sensitive/**"]
                            policy: deny
            """.trimIndent())

            val config = ConfigLoader.load(configFile)
            assertEquals(1, config.fraggle.executor.toolPolicies.size)

            val rule = config.fraggle.executor.toolPolicies[0]
            assertEquals("execute_command", rule.tool)
            assertEquals(ApprovalPolicy.ALLOW, rule.policy)
            assertEquals(2, rule.args.size)

            assertEquals("command", rule.args[0].name)
            assertEquals(listOf("ls"), rule.args[0].value)
            assertNull(rule.args[0].policy)

            assertEquals("working_dir", rule.args[1].name)
            assertEquals(listOf("/sensitive/**"), rule.args[1].value)
            assertEquals(ApprovalPolicy.DENY, rule.args[1].policy)
        }

        @Test
        fun `default policy is allow when omitted`() {
            val configFile = tempDir.resolve("config-default-policy.yaml")
            configFile.writeText("""
                fraggle:
                  executor:
                    supervision: supervised
                    tool_policies:
                      - tool: list_files
            """.trimIndent())

            val config = ConfigLoader.load(configFile)
            assertEquals(1, config.fraggle.executor.toolPolicies.size)
            assertEquals(ApprovalPolicy.ALLOW, config.fraggle.executor.toolPolicies[0].policy)
        }

        @Test
        fun `parses tool_policies with structured command patterns`() {
            val configFile = tempDir.resolve("config-cmd-patterns.yaml")
            configFile.writeText("""
                fraggle:
                  executor:
                    supervision: supervised
                    tool_policies:
                      - tool: execute_command
                        policy: allow
                        args:
                          - name: command
                            commands:
                              - command: cat
                                paths: ["/workspace/**"]
                              - command: grep
                                allow_flags: ["-r", "-i", "-n"]
                                paths: ["/workspace/**"]
                              - command: chmod
                                deny_flags: ["-R", "--recursive"]
                                args: ["*"]
                                paths: ["/workspace/**"]
                              - command: rm
                                deny_flags: ["-r", "-R", "-f"]
            """.trimIndent())

            val config = ConfigLoader.load(configFile)
            assertEquals(1, config.fraggle.executor.toolPolicies.size)

            val rule = config.fraggle.executor.toolPolicies[0]
            val matcher = rule.args[0]
            assertEquals("command", matcher.name)
            assertTrue(matcher.value.isEmpty())
            assertEquals(4, matcher.commands.size)

            // cat — paths only
            val cat = matcher.commands[0]
            assertEquals("cat", cat.command)
            assertNull(cat.allowFlags)
            assertTrue(cat.denyFlags.isEmpty())
            assertEquals(listOf("/workspace/**"), cat.paths)
            assertTrue(cat.args.isEmpty())

            // grep — allow_flags + paths
            val grep = matcher.commands[1]
            assertEquals("grep", grep.command)
            assertEquals(listOf("-r", "-i", "-n"), grep.allowFlags)
            assertTrue(grep.denyFlags.isEmpty())
            assertEquals(listOf("/workspace/**"), grep.paths)

            // chmod — deny_flags + args + paths
            val chmod = matcher.commands[2]
            assertEquals("chmod", chmod.command)
            assertNull(chmod.allowFlags)
            assertEquals(listOf("-R", "--recursive"), chmod.denyFlags)
            assertEquals(listOf("*"), chmod.args)
            assertEquals(listOf("/workspace/**"), chmod.paths)

            // rm — deny_flags only
            val rm = matcher.commands[3]
            assertEquals("rm", rm.command)
            assertEquals(listOf("-r", "-R", "-f"), rm.denyFlags)
            assertTrue(rm.paths.isEmpty())
            assertTrue(rm.args.isEmpty())
        }

        @Test
        fun `parses tool_policies with value list for shell commands`() {
            val configFile = tempDir.resolve("config-commands.yaml")
            configFile.writeText("""
                fraggle:
                  executor:
                    supervision: supervised
                    tool_policies:
                      - tool: execute_command
                        policy: allow
                        args:
                          - name: command
                            value:
                              - ls
                              - "cat /workspace/**"
                              - grep
            """.trimIndent())

            val config = ConfigLoader.load(configFile)
            assertEquals(1, config.fraggle.executor.toolPolicies.size)

            val rule = config.fraggle.executor.toolPolicies[0]
            assertEquals("execute_command", rule.tool)
            assertEquals(1, rule.args.size)

            val matcher = rule.args[0]
            assertEquals("command", matcher.name)
            assertEquals(listOf("ls", "cat /workspace/**", "grep"), matcher.value)
            assertNull(matcher.policy)
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `throws for missing config file`() {
            val nonExistent = tempDir.resolve("nonexistent.yaml")

            assertThrows<ConfigurationException> {
                ConfigLoader.load(nonExistent)
            }
        }

        @Test
        fun `throws for invalid yaml`() {
            val configFile = tempDir.resolve("config.yaml")
            configFile.writeText("this is not valid: yaml: content: [")

            assertThrows<ConfigurationException> {
                ConfigLoader.load(configFile)
            }
        }

        @Test
        fun `default() returns valid config`() {
            val config = ConfigLoader.default()

            assertNotNull(config)
            assertNotNull(config.fraggle)
        }
    }

    @Nested
    inner class LoadFromString {

        @Test
        fun `loadFromString parses yaml string`() {
            val yaml = """
                fraggle:
                  provider:
                    type: lmstudio
                    url: http://test:1234/v1
            """.trimIndent()

            val config = ConfigLoader.loadFromString(yaml)

            assertEquals("http://test:1234/v1", config.fraggle.provider.url)
        }

        @Test
        fun `loadFromString throws for invalid yaml`() {
            assertThrows<ConfigurationException> {
                ConfigLoader.loadFromString("invalid: yaml: [")
            }
        }
    }

    @Nested
    inner class RegisteredChatConversion {

        @Test
        fun `toRegisteredChat converts correctly`() {
            val configChat = RegisteredChatConfig(
                id = "chat123",
                name = "Test Chat",
                triggerOverride = "@custom",
                enabled = true,
            )

            val registeredChat = configChat.toRegisteredChat()

            assertEquals("chat123", registeredChat.id)
            assertEquals("Test Chat", registeredChat.name)
            assertEquals("@custom", registeredChat.triggerOverride)
            assertTrue(registeredChat.enabled)
        }
    }
}
