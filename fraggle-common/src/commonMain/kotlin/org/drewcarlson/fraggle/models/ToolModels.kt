package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable

/**
 * Summary of a tool for list views.
 */
@Serializable
data class ToolInfo(
    val name: String,
    val description: String,
    val parameters: List<ParameterInfo>,
)

/**
 * Detailed tool information.
 */
@Serializable
data class ToolDetail(
    val name: String,
    val description: String,
    val parameters: List<ParameterInfo>,
)

/**
 * Information about a tool parameter.
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
)
