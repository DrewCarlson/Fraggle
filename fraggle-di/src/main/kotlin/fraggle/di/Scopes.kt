package fraggle.di

import dev.zacsweers.metro.Scope

/**
 * Application-wide singleton scope.
 * Dependencies annotated with @SingleIn(AppScope::class) will be shared
 * across the entire application lifetime.
 */
abstract class AppScope private constructor()

/**
 * Per-session scope.
 * Dependencies annotated with @SingleIn(SessionScope::class) will be scoped
 * to a single chat session or request.
 */
abstract class SessionScope private constructor()
