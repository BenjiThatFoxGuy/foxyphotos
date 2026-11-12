package gay.benjifox.foxyphotos.core.platform

/**
 * Named entry point for the Swift shell.
 *
 * A Kotlin `object` becomes a Swift class with a `shared` singleton, which is
 * more predictable across the Kotlin/Native boundary than a file facade class,
 * whose name is derived from the source file. iosApp/ContentView.swift reads
 * [currentTimeIso] to prove the shared core is linked and running on iOS.
 */
object PlatformInfo {
    /** Current wall-clock time as ISO-8601 UTC. */
    fun currentTimeIso(): String = formatCurrentTimeIso()
}
