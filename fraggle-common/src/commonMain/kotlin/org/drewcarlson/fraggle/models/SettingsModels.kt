package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable

/**
 * Response containing the configuration.
 */
@Serializable
data class ConfigResponse(
    /** Raw YAML content of the configuration file */
    val yaml: String,
    /** Structured configuration data (uses the shared config models) */
    val config: FraggleSettings,
)
