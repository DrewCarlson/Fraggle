package fraggle.coding.tui

import com.jakewharton.mosaic.runMosaicBlocking
import fraggle.coding.CodingAgent
import fraggle.coding.CodingAgentOptions

/**
 * Blocking entry point for the coding-agent TUI.
 *
 * This exists so consumers (the `fraggle-cli` module) can launch the TUI
 * without having to depend on Mosaic or apply the Compose compiler plugin
 * themselves. The Compose compiler plugin is only needed in modules that
 * contain `@Composable` functions; by wrapping `runMosaicBlocking` here,
 * fraggle-cli stays a plain Kotlin/JVM module.
 *
 * Blocks until the composition exits (user requested quit, or an
 * unhandled exception). Returns normally on clean exit; the caller is
 * responsible for any process-level shutdown like [kotlin.system.exitProcess].
 */
fun runCodingApp(
    agent: CodingAgent,
    options: CodingAgentOptions,
    header: HeaderInfo,
    onExitRequest: () -> Unit,
    permissionHandler: TuiToolPermissionHandler? = null,
) {
    runMosaicBlocking {
        CodingApp(
            agent = agent,
            options = options,
            header = header,
            onExitRequest = onExitRequest,
            permissionHandler = permissionHandler,
        )
    }
}
