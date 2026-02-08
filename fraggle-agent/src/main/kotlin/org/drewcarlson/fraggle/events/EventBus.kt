package org.drewcarlson.fraggle.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drewcarlson.fraggle.models.FraggleEvent

/**
 * Centralized event bus for broadcasting [FraggleEvent]s.
 * Breaks DI cycles by being created before the graph and passed as a factory parameter.
 */
class EventBus {
    private val _events = MutableSharedFlow<FraggleEvent>(replay = 1, extraBufferCapacity = 100)
    val events: SharedFlow<FraggleEvent> = _events.asSharedFlow()

    suspend fun emit(event: FraggleEvent) {
        _events.emit(event)
    }
}
