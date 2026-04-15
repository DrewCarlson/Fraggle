package fraggle.chat

/**
 * Result of a bridge initialization step.
 */
sealed class InitStepResult {
    /**
     * Step completed successfully, proceed to next step or finish.
     */
    data class Success(val message: String? = null) : InitStepResult()

    /**
     * Step requires user input before continuing.
     */
    data class PromptRequired(
        val prompt: String,
        val helpText: String? = null,
        val sensitive: Boolean = false,
    ) : InitStepResult()

    /**
     * Step failed with an error.
     */
    data class Error(val message: String, val recoverable: Boolean = true) : InitStepResult()

    /**
     * Initialization is complete.
     */
    data class Complete(val message: String) : InitStepResult()
}

/**
 * Interface for interactive bridge initialization.
 *
 * Some chat bridges require interactive setup steps beyond static configuration,
 * such as phone verification, OAuth flows, or QR code scanning.
 *
 * Implementations should be stateful, tracking progress through the initialization
 * flow and handling user input at each step.
 */
interface BridgeInitializer {
    /**
     * The name of the bridge being initialized.
     */
    val bridgeName: String

    /**
     * Human-readable description of what this initializer does.
     */
    val description: String

    /**
     * Check if the bridge is already initialized and ready to use.
     */
    suspend fun isInitialized(): Boolean

    /**
     * Start or continue the initialization process.
     *
     * Call this method repeatedly, providing user input when [InitStepResult.PromptRequired]
     * is returned, until [InitStepResult.Complete] or a non-recoverable error is returned.
     *
     * @param userInput The user's response to a previous prompt, or null to start/continue.
     * @return The result of this initialization step.
     */
    suspend fun initialize(userInput: String? = null): InitStepResult

    /**
     * Reset the initialization state to start over.
     */
    fun reset()
}

/**
 * Registry for bridge initializers.
 */
class BridgeInitializerRegistry {
    private val initializers = mutableMapOf<String, BridgeInitializer>()

    /**
     * Register an initializer for a bridge.
     */
    fun register(name: String, initializer: BridgeInitializer) {
        initializers[name.lowercase()] = initializer
    }

    /**
     * Get an initializer by bridge name.
     */
    fun get(name: String): BridgeInitializer? = initializers[name.lowercase()]

    /**
     * Get all registered initializer names.
     */
    fun names(): Set<String> = initializers.keys.toSet()

    /**
     * Get all registered initializers.
     */
    fun all(): Collection<BridgeInitializer> = initializers.values
}
