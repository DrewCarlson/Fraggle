package fraggle.tracing

import fraggle.models.TraceEventRecord
import fraggle.models.TraceSession
import fraggle.models.TraceSessionDetail
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

class TraceStore(private val maxSessions: Int = 100) {
    private val sessions = ConcurrentHashMap<String, TraceSession>()
    private val events = ConcurrentHashMap<String, MutableList<TraceEventRecord>>()
    private val sessionOrder = mutableListOf<String>()

    fun startSession(id: String, chatId: String): TraceSession {
        evictIfNeeded()
        val session = TraceSession(
            id = id,
            chatId = chatId,
            startTime = Clock.System.now(),
        )
        synchronized(sessionOrder) {
            sessionOrder.add(id)
        }
        sessions[id] = session
        events[id] = mutableListOf()
        return session
    }

    fun addEvent(sessionId: String, event: TraceEventRecord) {
        val eventList = events[sessionId] ?: return
        synchronized(eventList) {
            eventList.add(event)
        }
        sessions.computeIfPresent(sessionId) { _, session ->
            session.copy(eventCount = session.eventCount + 1)
        }
    }

    fun completeSession(id: String, status: String = "completed") {
        sessions.computeIfPresent(id) { _, session ->
            session.copy(endTime = Clock.System.now(), status = status)
        }
    }

    fun getSession(id: String): TraceSession? = sessions[id]

    fun getSessionEvents(id: String): List<TraceEventRecord> {
        val eventList = events[id] ?: return emptyList()
        synchronized(eventList) {
            return eventList.toList()
        }
    }

    fun listSessions(limit: Int = 50, offset: Int = 0): List<TraceSession> {
        val ordered = synchronized(sessionOrder) { sessionOrder.toList() }
        return ordered.asReversed()
            .drop(offset)
            .take(limit)
            .mapNotNull { sessions[it] }
    }

    fun getSessionDetail(id: String): TraceSessionDetail? {
        val session = sessions[id] ?: return null
        return TraceSessionDetail(
            session = session,
            events = getSessionEvents(id),
        )
    }

    private fun evictIfNeeded() {
        while (sessions.size >= maxSessions) {
            val oldest = synchronized(sessionOrder) {
                sessionOrder.removeFirstOrNull()
            } ?: break
            sessions.remove(oldest)
            events.remove(oldest)
        }
    }
}
