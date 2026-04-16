plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Mosaic's lower-level TTY + event parsing. Gives us raw TTY access, raw mode
    // management, SIGWINCH + in-band resize events, and a parsed Event stream
    // (KeyboardEvent, ResizeEvent, etc.) — effectively ~2500 lines of already-solved
    // terminal I/O that would otherwise need to be hand-rolled.
    api(libs.mosaic.tty.terminal)

    // Markdown parsing for the Markdown → ANSI renderer. The parser produces a
    // tree; fraggle-tui ships its own ANSI emitter on top of it.
    implementation(libs.jetbrains.markdown)

    // Coroutines — StateFlow for terminal state, Channel for events
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
