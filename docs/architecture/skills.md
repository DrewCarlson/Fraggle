# Skills

!!! note "Work in Progress"
    This documentation is being written.

## Skill DSL

Skills are defined using a Kotlin DSL:

```kotlin
skill("my_skill") {
    description = "Does something useful"

    parameter<String>("input") {
        description = "The input value"
        required = true
    }

    execute { params ->
        val input = params.get<String>("input")
        SkillResult.Success("Processed: $input")
    }
}
```
