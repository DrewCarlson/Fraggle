# Building

This guide covers building Fraggle from source for development purposes.

!!! note "For Users"
    If you just want to run Fraggle, download the [latest release](https://github.com/DrewCarlson/Fraggle/releases/latest) instead of building from source. See the [Getting Started](../installation/getting-started.md) guide.

## Prerequisites

- **JDK 21+** - Required for compilation and runtime
- **Gradle** - Wrapper included (no separate installation needed)

## Build Commands

### Build All Modules

```bash
./gradlew build
```

This compiles all modules and runs tests.

### Build Without Tests

```bash
./gradlew build -x test
```

### Build Specific Module

```bash
# Core framework
./gradlew :fraggle-assistant:build

# Signal integration
./gradlew :fraggle-signal:build

# Built-in tools
./gradlew :fraggle-tools:build

# CLI application
./gradlew :fraggle-cli:build
```

## Distribution

### Create Runnable Distribution

```bash
./gradlew :fraggle-cli:installDist
```

Creates a runnable distribution at `fraggle-cli/build/install/fraggle-cli/`.

Run with:
```bash
./fraggle-cli/build/install/fraggle-cli/bin/fraggle run
```

### Create Distribution Archives

```bash
./gradlew :fraggle-cli:distZip
./gradlew :fraggle-cli:distTar
```

Creates archives at `fraggle-cli/build/distributions/`.

### Build Fat JAR

```bash
./gradlew :fraggle-cli:shadowJar
```

Creates an executable JAR with all dependencies at `fraggle-cli/build/libs/fraggle.jar`.

Run with:
```bash
java -jar fraggle-cli/build/libs/fraggle.jar run
```

## Development Mode

When working on Fraggle itself, use the Gradle run task which configures `FRAGGLE_ROOT` for development:

```bash
# Clone the repo
git clone https://github.com/DrewCarlson/Fraggle.git
cd Fraggle

# Set up dev config
mkdir -p runtime-dev/config
cp config/fraggle.yaml runtime-dev/config/fraggle.yaml

# Run in dev mode (sets FRAGGLE_ROOT=runtime-dev/)
./gradlew :fraggle-cli:run --args="chat"
```

## Dashboard Build

The web dashboard uses Kotlin/JS with Compose for Web:

```bash
# Development build
./gradlew :fraggle-dashboard:jsBrowserDevelopmentDist

# Production build
./gradlew :fraggle-dashboard:jsBrowserProductionDist
```

Output is at `fraggle-dashboard/build/vite/js/productionExecutable/`.

## Clean Build

```bash
# Clean all build outputs
./gradlew clean

# Clean and rebuild
./gradlew clean build
```

## Dependency Updates

Check for dependency updates:

```bash
./gradlew dependencyUpdates
```

## IDE Setup

### IntelliJ IDEA

1. Open the project directory in IntelliJ
2. IntelliJ should automatically detect the Gradle project
3. Wait for the Gradle sync to complete
4. Use the Gradle tool window for build tasks

### VS Code

1. Install the Kotlin and Gradle extensions
2. Open the project directory
3. Use the Gradle extension for build tasks

## Troubleshooting

### "Unsupported class file major version"

You're using a JDK version lower than 21. Check with:
```bash
java -version
```

### Out of Memory

Increase Gradle's heap size in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g
```

### Gradle Daemon Issues

Kill existing daemons:
```bash
./gradlew --stop
```
