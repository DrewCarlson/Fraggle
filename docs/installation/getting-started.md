# Getting Started

!!! note "Work in Progress"
    This documentation is being written.

## Requirements

- JDK 21 or later
- signal-cli (for Signal integration)
- LM Studio or compatible LLM provider

## Installation

```bash
git clone https://github.com/DrewCarlson/Fraggle.git
cd Fraggle
./gradlew build
```

## Running

```bash
# Interactive chat mode (for testing)
./gradlew :app:run --args="chat"

# Full service with Signal
./gradlew :app:run --args="run"
```
