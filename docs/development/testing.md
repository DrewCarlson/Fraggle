# Testing

!!! note "Work in Progress"
    This documentation is being written.

## Running Tests

```bash
# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :fraggle:test

# Run a specific test class
./gradlew :fraggle:test --tests="*SkillTest"
```

## Test Framework

Tests use JUnit 5 with MockK for mocking and kotlinx-coroutines-test for coroutine testing.
