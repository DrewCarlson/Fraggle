package fraggle.di

import dev.zacsweers.metro.Scope

/**
 * Application-wide singleton scope.
 * Dependencies annotated with @SingleIn(AppScope::class) will be shared
 * across the entire application lifetime.
 */
@Scope
annotation class AppScope

/**
 * Per-session scope.
 * Dependencies annotated with @SingleIn(SessionScope::class) will be scoped
 * to a single chat session or request.
 */
@Scope
annotation class SessionScope
