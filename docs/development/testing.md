# Testing

This guide covers running and writing tests for Fraggle.

## Running Tests

### All Tests

```bash
./gradlew test
```

### Specific Module

```bash
./gradlew :fraggle-agent:test
./gradlew :fraggle-tools:test
./gradlew :fraggle-signal:test
```

### Specific Test Class

```bash
./gradlew :fraggle-agent:test --tests="*FileMemoryStoreTest"
./gradlew :fraggle-tools:test --tests="*ToolsTest"
```

### Specific Test Method

```bash
./gradlew :fraggle-agent:test --tests="*FileMemoryStoreTest.UpdateFactTests*"
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
    private val mockSandbox = mockk<Sandbox>()

    @Test
    fun `test with mock`() = runTest {
        every { mockSandbox.readFile(any(), any()) } returns
            SandboxResult.Success("file contents")

        val tool = ReadFileTool(mockSandbox)
        val result = tool.execute(ReadFileTool.Args(path = "test.txt"))

        assertEquals("file contents", result)
        verify { mockSandbox.readFile("test.txt", 1000) }
    }
}
```

### Testing Tools

Tools extend Koog's `SimpleTool` and can be tested directly by calling `execute`:

```kotlin
class ToolTest {
    private val sandbox = mockk<Sandbox>()

    @Test
    fun `test read_file tool`() = runTest {
        every { sandbox.readFile(any(), any()) } returns
            SandboxResult.Success("file contents")

        val tool = ReadFileTool(sandbox)
        val result = tool.execute(ReadFileTool.Args(path = "test.txt"))

        assertEquals("file contents", result)
    }
}
```

## Test Categories

### Unit Tests

Test individual components in isolation:

```kotlin
// Located in src/test/kotlin/
class MemoryStoreTest {
    @Test
    fun `store should save and load facts`() = runTest {
        val store = FileMemoryStore(tempDir)
        store.append(MemoryScope.Global, Fact("Test fact"))

        val memory = store.load(MemoryScope.Global)
        assertEquals(1, memory.facts.size)
        assertEquals("Test fact", memory.facts[0].content)
    }
}
```

### Integration Tests

Test component interactions:

```kotlin
class ToolIntegrationTest {
    @Test
    fun `tool should execute within sandbox`() = runTest {
        val sandbox = PermissiveSandbox(tempDir)
        tempDir.resolve("test.txt").writeText("hello world")

        val tool = ReadFileTool(sandbox)
        val result = tool.execute(ReadFileTool.Args(path = "test.txt"))

        assertTrue(result.contains("hello world"))
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

### In-Memory Implementations

```kotlin
// InMemoryStore for testing memory without filesystem
val store = InMemoryStore()
store.append(MemoryScope.Global, Fact("Test fact"))
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
