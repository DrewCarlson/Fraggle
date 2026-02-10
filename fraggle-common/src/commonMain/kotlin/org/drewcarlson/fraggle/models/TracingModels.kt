package org.drewcarlson.fraggle.models

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class TraceSession(
    val id: String,
    val chatId: String,
    val startTime: Instant,
    val endTime: Instant? = null,
    val eventCount: Int = 0,
    val status: String = "running",
)

@Serializable
data class TraceSessionDetail(
    val session: TraceSession,
    val events: List<TraceEventRecord>,
)

@Serializable
data class TraceEventRecord(
    val id: String,
    val sessionId: String,
    val timestamp: Instant,
    val eventType: String,
    val phase: String,
    val data: Map<String, String> = emptyMap(),
    val durationMs: Long? = null,
)
