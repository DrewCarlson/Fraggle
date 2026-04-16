package fraggle.tui.core

import com.jakewharton.mosaic.terminal.KeyboardEvent

/**
 * A [Component] that composites a list of children vertically. Each child's
 * render output is concatenated in order; no layout magic, no flex, no gaps.
 *
 * Use [Container] as the building block for stacking messages, chat history,
 * or any list-shaped region. For horizontal layout see [fraggle.tui.layout.Row].
 *
 * ## Thread safety
 *
 * [Container] is designed to be mutated from one coroutine (typically the
 * event-loop that consumes agent events and user input) while read from
 * another (the render-loop that produces frames). All mutations synchronize
 * on an internal lock, and every reader takes a snapshot before iterating.
 * Renders never see a partially-mutated child list.
 *
 * Key input is delegated to children in reverse order (last added gets first
 * chance). The first child to return `true` from [handleInput] consumes the
 * event.
 */
open class Container : Component {
    private val lock = Any()
    private val _children: MutableList<Component> = mutableListOf()

    /**
     * Snapshot of this container's current children. The returned list is
     * an immutable copy — iterating it is safe even if the container is
     * being mutated concurrently.
     */
    val children: List<Component>
        get() = synchronized(lock) { _children.toList() }

    /** Append [component] to the end of this container's child list. */
    fun addChild(component: Component) {
        synchronized(lock) { _children += component }
    }

    /** Remove [component] if present. No-op if it wasn't a child. */
    fun removeChild(component: Component) {
        synchronized(lock) { _children.remove(component) }
    }

    /** Remove all children. */
    fun clear() {
        synchronized(lock) { _children.clear() }
    }

    /** Swap the last child in place with [component]. No-op on empty containers. */
    fun replaceLast(component: Component) {
        synchronized(lock) {
            if (_children.isEmpty()) return
            _children[_children.lastIndex] = component
        }
    }

    override fun render(width: Int): List<String> {
        val snapshot = synchronized(lock) { _children.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        for (child in snapshot) {
            lines.addAll(child.render(width))
        }
        return lines
    }

    override fun handleInput(key: KeyboardEvent): Boolean {
        val snapshot = synchronized(lock) { _children.toList() }
        for (i in snapshot.indices.reversed()) {
            if (snapshot[i].handleInput(key)) return true
        }
        return false
    }

    override fun invalidate() {
        val snapshot = synchronized(lock) { _children.toList() }
        for (child in snapshot) child.invalidate()
    }
}
