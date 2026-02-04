# Skills

Skills are the tools available to the Fraggle agent. They enable the agent to interact with the file system, web, shell, and more.

## Built-in Skills

Fraggle includes several skill groups out of the box.

### Filesystem Skills

File operations within the sandbox:

| Skill | Description | Parameters |
|-------|-------------|------------|
| `read_file` | Read file contents | `path` (required), `max_lines` (default: 1000) |
| `write_file` | Write content to a file | `path` (required), `content` (required) |
| `append_file` | Append content to a file | `path` (required), `content` (required) |
| `list_files` | List directory contents | `path` (required), `recursive` (default: false) |
| `search_files` | Search for files by pattern | `path` (required), `pattern` (required, supports `*` and `?` wildcards) |
| `file_exists` | Check if a file or directory exists | `path` (required) |
| `delete_file` | Delete a file | `path` (required) |

### Web Skills

Web fetching and browser automation:

| Skill | Description | Parameters |
|-------|-------------|------------|
| `fetch_webpage` | Fetch content from a webpage | `url` (required) |
| `fetch_api` | Fetch data from an API endpoint | `url` (required), `method` (default: GET) |
| `send_image` | Download and send an image | `url` (required), `caption` (optional) |
| `screenshot_page` | Take a screenshot of a webpage | `url` (required), `full_page` (default: false), `caption` (optional) |

!!! note "Playwright Skills"
    The `fetch_webpage` skill uses Playwright for JavaScript rendering when configured. The `screenshot_page` skill requires Playwright configuration.

#### Playwright Setup

For JavaScript-heavy websites, configure Playwright:

**Option 1: Browserless (Docker)**
```bash
docker run -p 3000:3000 browserless/chrome
```

**Option 2: Playwright Server**
```bash
npx playwright run-server --port 3000
```

**Configuration:**
```yaml
fraggle:
  web:
    playwright:
      ws_endpoint: ws://localhost:3000
      navigation_timeout: 30000
      wait_after_load: 2000
```

### Shell Skills

Command execution in the sandbox:

| Skill | Description | Parameters |
|-------|-------------|------------|
| `execute_command` | Execute a shell command | `command` (required), `timeout_seconds` (default: 30) |

!!! warning "Security"
    Shell execution includes basic safeguards against dangerous commands (like `rm -rf /`), but the sandbox configuration determines the actual security level.

### Scheduling Skills

Task scheduling for deferred operations:

| Skill | Description | Parameters |
|-------|-------------|------------|
| `schedule_task` | Schedule a task for later | `name`, `action`, `delay_seconds`, `repeat_interval_seconds` (optional) |
| `list_tasks` | List all scheduled tasks | (none) |
| `get_task` | Get task details | `task_id` |
| `cancel_task` | Cancel a scheduled task | `task_id` |

## Skill DSL

Skills are defined using a Kotlin DSL:

```kotlin
skill("my_skill") {
    description = "A description of what this skill does"

    parameter<String>("input") {
        description = "Description of this parameter"
        required = true
    }

    parameter<Int>("count") {
        description = "Optional count parameter"
        default = 10
    }

    execute { params ->
        val input = params.get<String>("input")
        val count = params.getOrDefault("count", 10)

        // Do work...

        SkillResult.Success("Result message")
    }
}
```

### Parameter Types

Supported parameter types:

- `String` - Text values
- `Int` - Integer numbers
- `Long` - Long integers
- `Double` - Floating point numbers
- `Boolean` - True/false values

### Skill Results

Skills return one of these result types:

```kotlin
// Success with text output
SkillResult.Success("Operation completed")

// Error with message
SkillResult.Error("Something went wrong")

// Image attachment (for send_image, screenshot_page)
SkillResult.ImageAttachment(
    imageData = byteArray,
    mimeType = "image/png",
    caption = "Optional caption",
    output = "Description of what was done"
)
```

## Skill Registry

Skills are organized into a registry with optional grouping:

```kotlin
val registry = SkillRegistry {
    // Ungrouped skills
    install(mySkill)

    // Grouped skills
    group("filesystem", "File system operations") {
        install(readFile)
        install(writeFile)
    }
}
```

### Default Registry

The `DefaultSkills` object provides factory methods:

```kotlin
// Full registry with all skills
val registry = DefaultSkills.createRegistry(
    sandbox = sandbox,
    taskScheduler = taskScheduler,
    playwrightFetcher = playwrightFetcher  // optional
)

// Minimal registry (file + web only)
val registry = DefaultSkills.createMinimalRegistry(sandbox)

// Custom selection
val registry = DefaultSkills.createCustomRegistry(
    sandbox = sandbox,
    includeFile = true,
    includeWeb = true,
    includeShell = false,
    includeScheduling = false
)
```

## Custom Skills

Add custom skills by implementing the `Skill` interface or using the DSL:

```kotlin
// Using the DSL
val weatherSkill = skill("get_weather") {
    description = "Get current weather for a location"

    parameter<String>("location") {
        description = "City name or coordinates"
        required = true
    }

    execute { params ->
        val location = params.get<String>("location")
        // Call weather API...
        SkillResult.Success("Weather in $location: Sunny, 72°F")
    }
}

// Add to registry
val registry = SkillRegistry {
    DefaultSkills.createRegistry(sandbox).skills.forEach { install(it) }
    install(weatherSkill)
}
```

## Execution Context

Skills receive execution context with information about the current request:

```kotlin
execute { params ->
    val chatId = params.context?.chatId
    val userId = params.context?.userId

    // Use context for chat-specific behavior
    SkillResult.Success("Response for chat $chatId")
}
```

This context is used by skills like `schedule_task` to know which chat to send scheduled messages to.
