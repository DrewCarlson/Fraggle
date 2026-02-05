@file:JsModule("highlight.js/lib/core")
@file:JsNonModule
package external

import org.w3c.dom.HTMLElement

@JsName("default")
external object hljs {
    fun highlightElement(element: HTMLElement)
    fun registerLanguage(name: String, language: dynamic)
}
