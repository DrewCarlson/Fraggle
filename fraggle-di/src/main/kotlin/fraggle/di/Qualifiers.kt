package fraggle.di

import dev.zacsweers.metro.Qualifier

/**
 * Qualifier for the general-purpose HTTP client with standard timeouts.
 * Use this for most HTTP requests (Discord API, web fetching, etc.)
 */
@Qualifier
annotation class DefaultHttpClient

/**
 * Qualifier for the LLM HTTP client with extended timeouts.
 * Use this for LLM API calls that may take several minutes to complete.
 */
@Qualifier
annotation class LlmHttpClient

/**
 * Qualifier for the base tool registry containing only the generic tools
 * (filesystem, shell, web, time) provided by `fraggle-tools`. App-specific
 * modules (like the messenger assistant) compose their final registry by
 * decorating this one with additional app-flavored tools.
 */
@Qualifier
annotation class BaseFraggleToolRegistry
