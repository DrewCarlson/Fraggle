package external

import org.w3c.dom.HTMLElement


private var initialized = false

/**
 * Initialize highlight.js with the YAML language.
 * Safe to call multiple times - only initializes once.
 */
fun initHighlightJs() {
    if (!initialized) {
        hljs.registerLanguage("yaml", yamlLanguage)
        initialized = true
    }
}

/**
 * Highlight a code element using highlight.js.
 */
fun highlightElement(element: HTMLElement) {
    initHighlightJs()
    hljs.highlightElement(element)
}
