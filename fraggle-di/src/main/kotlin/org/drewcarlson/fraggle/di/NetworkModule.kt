package org.drewcarlson.fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Provides shared network dependencies for the application.
 * Consolidates HTTP client creation to avoid duplicate instances.
 */
@ContributesTo(AppScope::class)
interface NetworkModule {
    companion object {
        /**
         * Provides a shared Json instance configured for the application.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }

        /**
         * Provides the default HTTP client for general-purpose use.
         * Configured with standard timeouts (30s request, 10s connect).
         */
        @Provides
        @SingleIn(AppScope::class)
        @DefaultHttpClient
        fun provideDefaultHttpClient(json: Json): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30.seconds.inWholeMilliseconds
                connectTimeoutMillis = 10.seconds.inWholeMilliseconds
            }
            Logging {
                level = LogLevel.ALL
                logger = Logger.SIMPLE
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, "Fraggle/1.0")
            }
        }

        /**
         * Provides an application-scoped CoroutineScope.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideAppScope(): CoroutineScope =
            CoroutineScope(Dispatchers.Default + SupervisorJob())

        /**
         * Provides the HTTP client configured for LLM API calls.
         * Has extended timeouts (5 minutes) for slow LLM responses.
         */
        @Provides
        @SingleIn(AppScope::class)
        @LlmHttpClient
        fun provideLlmHttpClient(json: Json): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            Logging {
                level = LogLevel.ALL
                logger = Logger.SIMPLE
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 5.minutes.inWholeMilliseconds
                connectTimeoutMillis = 10.seconds.inWholeMilliseconds
            }
            defaultRequest {
                contentType(ContentType.Application.Json)
            }
        }
    }
}
