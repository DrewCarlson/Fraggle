package fraggle.coding.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.NonInteractivePolicy
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text

/**
 * Spike entry point proving out the Mosaic + key event + Compose-state story
 * for the fraggle-coding-agent TUI.
 *
 * Run with:
 *   ./gradlew :fraggle-coding-agent:runSpike
 *
 * Exercises:
 *  - runMosaicBlocking { ... } renders a Composable to the terminal
 *  - Modifier.onKeyEvent { ... } receives typed characters, Enter, Arrow keys, Ctrl/Esc
 *  - Compose state (mutableStateOf, mutableStateListOf) drives recomposition
 *  - A minimal editor buffer: type to append, Backspace to delete, Enter to submit,
 *    Escape/Ctrl+C to exit.
 *
 * This is throwaway code for the coding-agent TUI spike. It is NOT the final
 * editor implementation; it only validates that Mosaic alone is sufficient.
 */
suspend fun main() {
    runMosaic(NonInteractivePolicy.Throw) {
        var buffer by remember { mutableStateOf("") }
        var lastKey by remember { mutableStateOf("<none>") }
        val submitted = remember { mutableStateListOf<String>() }
        var shouldExit by remember { mutableStateOf(false) }

        // A single focused Column with onKeyEvent. Returning `true` from the handler
        // marks the event as consumed; returning `false` lets it propagate.
        Column(
            modifier = Modifier.onKeyEvent { event ->
                lastKey = event.toString()
                when (event) {
                    KeyEvent("Escape"), KeyEvent("c", ctrl = true) -> {
                        shouldExit = true
                        true
                    }

                    KeyEvent("Enter") -> {
                        if (buffer.isNotEmpty()) {
                            submitted += buffer
                            buffer = ""
                        }
                        true
                    }

                    KeyEvent("Backspace") -> {
                        if (buffer.isNotEmpty()) buffer = buffer.dropLast(1)
                        true
                    }

                    else -> {
                        // Single-character printable keys have a one-char `key` string.
                        if (event.key.length == 1 && !event.ctrl && !event.alt) {
                            buffer += event.key
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        ) {
            Text("── fraggle coding spike ─────────────────────", color = Color.Cyan)
            Text("Type to edit, Enter to submit, Esc/Ctrl+C to exit")
            Text("")

            if (submitted.isEmpty()) {
                Text("(no submissions yet)", color = Color(128, 128, 128))
            } else {
                Text("Submissions:", color = Color.Green)
                submitted.forEachIndexed { idx, line ->
                    Text("  ${idx + 1}. $line")
                }
            }

            Text("")
            Row {
                Text("> ", color = Color.Yellow)
                Text(buffer)
                Text("_", color = Color(128, 128, 128)) // fake cursor
            }
            Text("")
            Text("last key: $lastKey", color = Color(128, 128, 128))

            if (shouldExit) {
                // Mosaic exits when the coroutine scope completes; stopping recomposition
                // isn't built-in here, but this proves we detected the exit intent.
                Text("", color = Color.Red)
                Text("(exit requested — press Ctrl+C to kill if it doesn't stop)", color = Color.Red)
            }
        }
    }
}
