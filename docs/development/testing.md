# Testing

This guide covers running and writing tests for Fraggle.

## Running Tests

### All Tests

```bash
./gradlew test
```

### Specific Module

```bash
./gradlew :fraggle:test
./gradlew :fraggle-skills:test
./gradlew :fraggle-signal:test
```

### Specific Test Class

```bash
./gradlew :fraggle:test --tests="*AgentTest"
./gradlew :fraggle-skills:test --tests="*FileSkillsTest"
```

### Specific Test Method

```bash
./gradlew :fraggle:test --tests="*AgentTest.testReActLoop"
```

### With Detailed Output

```bash
./gradlew test --info
```

## Test Framework

Tests use:

- **JUnit 5** - Test framework
- **MockK** - Kotlin-friendly mocking
- **kotlinx-coroutines-test** - Coroutine testing utilities
- **Kotest Assertions** - Fluent assertions (optional)

## Writing Tests

### Basic Test

```kotlin
class MyTest {
    @Test
    fun `test description`() {
        val result = someFunction()
        assertEquals(expected, result)
    }
}
```

### Coroutine Test

```kotlin
class CoroutineTest {
    @Test
    fun `test async operation`() = runTest {
        val result = suspendingFunction()
        assertEquals(expected, result)
    }
}
```

### Mocking with MockK

```kotlin
class MockTest {
    private val mockProvider = mockk<LLMProvider>()

    @Test
    fun `test with mock`() = runTest {
        coEvery { mockProvider.complete(any()) } returns "mocked response"

        val agent = FraggleAgent(mockProvider)
        val result = agent.process("input")

        assertEquals("mocked response", result)
        coVerify { mockProvider.complete(any()) }
    }
}
```

### Testing Skills

```kotlin
class SkillTest {
    private val sandbox = mockk<Sandbox>()

    @Test
    fun `test read_file skill`() = runTest {
        every { sandbox.readFile(any(), any()) } returns
            SandboxResult.Success("file contents")

        val skill = FileSkills.readFile(sandbox)
        val params = SkillParams(mapOf("path" to "test.txt"))
        val result = skill.execute(params)

        assertTrue(result is SkillResult.Success)
        assertEquals("file contents", (result as SkillResult.Success).output)
    }
}
```

## Test Categories

### Unit Tests

Test individual components in isolation:

```kotlin
// Located in src/test/kotlin/
class SkillRegistryTest {
    @Test
    fun `registry should find skill by name`() {
        val skill = skill("test") { execute { SkillResult.Success("ok") } }
        val registry = SkillRegistry { install(skill) }

        assertNotNull(registry.find("test"))
        assertNull(registry.find("nonexistent"))
    }
}
```

### Integration Tests

Test component interactions:

```kotlin
class AgentIntegrationTest {
    @Test
    fun `agent should execute skills and return response`() = runTest {
        val provider = FakeLLMProvider()
        val sandbox = PermissiveSandbox()
        val registry = DefaultSkills.createRegistry(sandbox)

        val agent = FraggleAgent(provider, registry)
        val result = agent.process("read file test.txt")

        // Verify the agent called the read_file skill
        assertTrue(result.contains("file contents"))
    }
}
```

## Test Fixtures

### Sample Configurations

```kotlin
object TestFixtures {
    val sampleConfig = FraggleConfig(
        fraggle = FraggleSettings(
            provider = ProviderConfig(
                type = ProviderType.LMSTUDIO,
                url = "http://localhost:1234/v1"
            )
        )
    )
}
```

### Fake Implementations

```kotlin
class FakeLLMProvider : LLMProvider {
    var responses = mutableListOf<String>()
    var callCount = 0

    override suspend fun complete(request: Request): String {
        return responses.getOrElse(callCount++) { "default response" }
    }
}
```

## Coverage

Generate coverage reports:

```bash
./gradlew test jacocoTestReport
```

Reports are at `build/reports/jacoco/`.

## Continuous Integration

Tests run automatically on:

- Pull requests
- Pushes to main branch

Check `.github/workflows/` for CI configuration.

## Best Practices

1. **Test behavior, not implementation** - Focus on what the code does, not how
2. **Use descriptive test names** - Use backticks for readable names: `` `should return error when file not found` ``
3. **Arrange-Act-Assert** - Structure tests clearly
4. **One assertion per test** - When practical, test one thing at a time
5. **Mock external dependencies** - Don't make real network calls in unit tests
