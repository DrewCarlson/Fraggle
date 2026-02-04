package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable

/**
 * Summary of a chat bridge for list views.
 */
@Serializable
data class BridgeInfo(
    val name: String,
    val platform: String,
    val connected: Boolean,
)

/**
 * Detailed bridge information.
 */
@Serializable
data class BridgeDetail(
    val name: String,
    val platform: String,
    val connected: Boolean,
    val supportsAttachments: Boolean,
    val supportsInlineImages: Boolean,
)
