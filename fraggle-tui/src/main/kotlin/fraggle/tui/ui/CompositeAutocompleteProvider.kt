package fraggle.tui.ui

/**
 * An [AutocompleteProvider] that dispatches to the first delegate claiming
 * the incoming trigger character.
 *
 * This is the standard way to give the editor more than one completion
 * source — file-path completion for `@` plus slash-command completion for
 * `/`, for example. The delegates are tried in order, so list the more
 * specific ones first if two providers happen to claim the same trigger.
 *
 * @param providers Delegates in priority order. Constructor-copied, so later
 *   mutation to the source list has no effect.
 */
class CompositeAutocompleteProvider(
    providers: List<AutocompleteProvider>,
) : AutocompleteProvider {

    /** Convenience vararg constructor. */
    constructor(vararg providers: AutocompleteProvider) : this(providers.toList())

    private val providers: List<AutocompleteProvider> = providers.toList()

    override fun handlesTrigger(trigger: Char): Boolean =
        providers.any { it.handlesTrigger(trigger) }

    override fun suggest(trigger: Char, prefix: String, limit: Int): List<Autocompletion> {
        val delegate = providers.firstOrNull { it.handlesTrigger(trigger) } ?: return emptyList()
        return delegate.suggest(trigger, prefix, limit)
    }
}
