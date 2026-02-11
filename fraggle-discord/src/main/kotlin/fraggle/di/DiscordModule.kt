package fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import fraggle.discord.DiscordBridge
import fraggle.discord.DiscordBridgeInitializer
import fraggle.discord.DiscordConfig
import fraggle.models.DiscordBridgeConfig

/**
 * Provides Discord bridge services. All bindings are nullable —
 * when Discord is unconfigured, the entire chain resolves to null.
 */
@ContributesTo(AppScope::class)
interface DiscordModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideDiscordConfig(bridgeConfig: DiscordBridgeConfig?): DiscordConfig? {
            if (bridgeConfig == null || !bridgeConfig.enabled) return null
            return DiscordConfig(
                token = bridgeConfig.token,
                clientId = bridgeConfig.clientId,
                clientSecret = bridgeConfig.clientSecret,
                oauthRedirectUri = bridgeConfig.oauthRedirectUri,
                triggerPrefix = bridgeConfig.trigger,
                respondToDirectMessages = bridgeConfig.respondToDirectMessages,
                showTypingIndicator = bridgeConfig.showTypingIndicator,
                maxImagesPerMessage = bridgeConfig.maxImagesPerMessage,
                maxFileSizeBytes = bridgeConfig.maxFileSizeMb.toLong() * 1024 * 1024,
                allowedGuildIds = bridgeConfig.allowedGuildIds,
                allowedChannelIds = bridgeConfig.allowedChannelIds,
            )
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideDiscordBridge(
            config: DiscordConfig?,
            scope: CoroutineScope,
        ): DiscordBridge? {
            if (config == null) return null
            return DiscordBridge(config, scope)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideDiscordBridgeInitializer(
            config: DiscordConfig?,
            @DefaultHttpClient httpClient: HttpClient,
        ): DiscordBridgeInitializer? {
            if (config == null) return null
            return DiscordBridgeInitializer(config, httpClient)
        }
    }
}
