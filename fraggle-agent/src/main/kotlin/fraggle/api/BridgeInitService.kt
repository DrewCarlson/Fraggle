package fraggle.api

/**
 * Service for managing interactive bridge initialization via WebSocket.
 *
 * Bridge initialization allows users to interactively set up chat bridges
 * (like Signal) that require multi-step verification flows.
 */
interface BridgeInitService {
    /**
     * Start initialization for a bridge.
     *
     * @param bridgeName The name of the bridge to initialize (e.g., "signal")
     * @return The session ID for tracking this initialization, or null if the bridge doesn't exist
     */
    suspend fun startInit(bridgeName: String): String?

    /**
     * Submit user input for an active initialization session.
     *
     * @param sessionId The session ID from [startInit]
     * @param input The user's input (e.g., captcha token, verification code)
     */
    suspend fun submitInput(sessionId: String, input: String)

    /**
     * Cancel an active initialization session.
     *
     * @param sessionId The session ID to cancel
     */
    fun cancelInit(sessionId: String)

    /**
     * Check if a bridge is initialized and ready to connect.
     *
     * @param bridgeName The name of the bridge to check
     * @return true if the bridge is initialized, false otherwise
     */
    suspend fun isInitialized(bridgeName: String): Boolean

    /**
     * Get all bridge names that can be initialized.
     *
     * @return Set of bridge names that have registered initializers
     */
    fun getInitializableBridges(): Set<String>
}
