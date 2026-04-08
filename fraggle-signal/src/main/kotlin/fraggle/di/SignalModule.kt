package fraggle.di

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import fraggle.FraggleEnvironment
import fraggle.models.ChatsConfig
import fraggle.models.RegisteredChatConfig
import fraggle.models.SignalBridgeConfig
import fraggle.signal.*

/**
 * Provides Signal bridge services. All bindings are nullable —
 * when Signal is unconfigured, the entire chain resolves to null.
 */
@ContributesTo(AppScope::class)
interface SignalModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideSignalConfig(
            bridgeConfig: SignalBridgeConfig?,
            chatsConfig: ChatsConfig,
        ): SignalConfig? {
            if (bridgeConfig == null || !bridgeConfig.enabled) return null
            return SignalConfig(
                phoneNumber = bridgeConfig.phone,
                configDir = FraggleEnvironment.resolvePath(bridgeConfig.configDir).toString(),
                triggerPrefix = bridgeConfig.trigger,
                signalCliPath = bridgeConfig.signalCliPath,
                autoInstall = bridgeConfig.autoInstall,
                signalCliVersion = bridgeConfig.signalCliVersion,
                appsDir = FraggleEnvironment.dataDir.resolve("apps").toString(),
                profileName = bridgeConfig.profileName,
                respondToDirectMessages = bridgeConfig.respondToDirectMessages,
                showTypingIndicator = bridgeConfig.showTypingIndicator,
                registeredChats = chatsConfig.registered.map { it.toRegisteredChat() },
            )
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideSignalBridge(
            config: SignalConfig?,
            scope: CoroutineScope,
        ): SignalBridge? {
            if (config == null) return null
            return SignalBridge(config, scope)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideMessageRouter(config: SignalConfig?): MessageRouter? {
            if (config == null) return null
            return MessageRouter(config)
        }

        @Provides
        @SingleIn(AppScope::class)
        fun provideSignalBridgeInitializer(config: SignalConfig?): SignalBridgeInitializer? {
            if (config == null) return null
            return SignalBridgeInitializer(config)
        }

        private fun RegisteredChatConfig.toRegisteredChat(): RegisteredChat = RegisteredChat(
            id = id,
            name = name,
            triggerOverride = triggerOverride,
            enabled = enabled,
        )
    }
}
