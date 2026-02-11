package fraggle.models

import kotlinx.serialization.Serializable

/**
 * Summary of a chat bridge for list views.
 */
@Serializable
data class BridgeInfo(
    val name: String,
    val platform: String,
    val connected: Boolean,
    val initialized: Boolean,
    /** When true, setup action is always available (e.g., to get OAuth link) */
    val persistentActivation: Boolean = false,
)

/**
 * Detailed bridge information.
 */
@Serializable
data class BridgeDetail(
    val name: String,
    val platform: String,
    val connected: Boolean,
    val initialized: Boolean,
    val supportsAttachments: Boolean,
    val supportsInlineImages: Boolean,
)
