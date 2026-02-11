package fraggle.models

import kotlinx.serialization.Serializable

/**
 * Standard error response.
 */
@Serializable
data class ErrorResponse(
    val error: String,
)
