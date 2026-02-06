package org.drewcarlson.fraggle.db

/**
 * A numbered migration that can execute arbitrary SQL or data transforms.
 *
 * Migrations are run inside a transaction. Use [migrate] for operations
 * that the auto-diff tool cannot infer (data transforms, column renames, drops).
 */
interface Migration {
    val version: Int
    val name: String
    fun migrate()
}
