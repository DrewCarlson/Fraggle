package org.drewcarlson.fraggle.di

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import io.ktor.client.*
import org.drewcarlson.fraggle.models.FraggleConfig
import java.nio.file.Path

/**
 * Main application dependency graph.
 *
 * This graph provides all application-scoped dependencies including:
 * - HTTP clients (default and LLM-optimized)
 * - Configuration
 *
 * Usage:
 * ```
 * val graph = createAppGraph { create(config, configPath) }
 * ```
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
    /** General-purpose HTTP client for web requests */
    @get:DefaultHttpClient
    val defaultHttpClient: HttpClient

    /** HTTP client with extended timeouts for LLM API calls */
    @get:LlmHttpClient
    val llmHttpClient: HttpClient

    /** Application configuration */
    val config: FraggleConfig

    /** Path to the configuration file */
    val configPath: Path

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides config: FraggleConfig,
            @Provides configPath: Path,
        ): AppGraph
    }
}
