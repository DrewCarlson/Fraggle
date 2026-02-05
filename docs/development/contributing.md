# Contributing

Thank you for your interest in contributing to Fraggle.

## Development Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/DrewCarlson/Fraggle.git
   cd Fraggle
   ```

2. **Verify the build works**
   ```bash
   ./gradlew build
   ```

3. **Set up your IDE**
   - IntelliJ IDEA is recommended
   - Open the project directory and let it sync

4. **Create a development config**
   ```bash
   mkdir -p runtime-dev/config
   cp config/fraggle.yaml runtime-dev/config/fraggle.yaml
   ```

5. **Run in development mode**
   ```bash
   ./gradlew :fraggle-cli:run --args="chat"
   ```

## Code Style

The project follows standard Kotlin coding conventions:

- Use 4 spaces for indentation
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Keep functions focused and small
- Write descriptive names for classes, functions, and variables
- Document public APIs with KDoc

## Making Changes

### 1. Create a Branch

```bash
git checkout -b feature/my-feature
# or
git checkout -b fix/bug-description
```

### 2. Make Your Changes

- Write code
- Add/update tests
- Update documentation if needed

### 3. Run Tests

```bash
./gradlew test
```

### 4. Commit

Write clear commit messages:

```
Add weather skill for fetching forecasts

- Implement WeatherSkill with location parameter
- Add tests for various weather conditions
- Document skill in skills.md
```

### 5. Push and Create PR

```bash
git push origin feature/my-feature
```

Then create a pull request on GitHub.

## Pull Request Guidelines

- Provide a clear description of the changes
- Reference any related issues
- Ensure tests pass
- Keep PRs focused - one feature/fix per PR
- Be responsive to feedback

## Areas to Contribute

### Good First Issues

Look for issues labeled `good first issue` on GitHub.

### Skills

Add new skills for:

- External APIs (weather, news, etc.)
- System operations
- Integrations with other services

### Documentation

- Improve existing docs
- Add examples
- Fix typos

### Testing

- Increase test coverage
- Add integration tests
- Improve test utilities

### Performance

- Profile and optimize hot paths
- Reduce memory usage
- Improve startup time

## Architecture Notes

### Adding a New Skill

1. Create skill in `fraggle-skills/src/main/kotlin/`
2. Add to appropriate skill group (or create new group)
3. Register in `DefaultSkills.kt`
4. Add tests
5. Document in `docs/architecture/skills.md`

### Adding a New Provider

1. Implement `LLMProvider` interface
2. Add to provider factory
3. Add configuration options
4. Add tests
5. Document setup

### Adding a New Bridge

1. Implement bridge interface
2. Add configuration in `ConfigModels.kt`
3. Wire up in service orchestrator
4. Add documentation

## Questions?

- Open an issue for questions or discussion
- Check existing issues before opening new ones
- Be patient and respectful in discussions

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
