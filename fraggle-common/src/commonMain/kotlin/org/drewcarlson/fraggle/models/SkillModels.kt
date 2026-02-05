package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable

/**
 * Summary of a skill for list views.
 */
@Serializable
data class SkillInfo(
    val name: String,
    val description: String,
    val parameters: List<ParameterInfo>,
)

/**
 * Detailed skill information.
 */
@Serializable
data class SkillDetail(
    val name: String,
    val description: String,
    val parameters: List<ParameterInfo>,
)

/**
 * Information about a skill parameter.
 */
@Serializable
data class ParameterInfo(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
)
