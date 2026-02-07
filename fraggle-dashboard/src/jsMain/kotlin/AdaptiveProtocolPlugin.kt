import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLProtocol.Companion.HTTP
import io.ktor.http.URLProtocol.Companion.HTTPS
import io.ktor.http.URLProtocol.Companion.WS
import io.ktor.http.URLProtocol.Companion.WSS
import io.ktor.util.AttributeKey

/**
 * Contains the server url at the time the request was created.
 */
internal val ServerUrlAttribute = AttributeKey<String>("server_url")

/**
 * Upgrade or downgrade the requested protocol to match the
 * [ServerUrlAttribute]'s protocol security.
 *
 * This is required because the [io.ktor.client.plugins.DefaultRequest] plugin
 * cannot change a WebSocket protocol so all request call-sites must specify
 * the required protocol.  This plugin allows us to assume WSS in all call-sites
 * and downgrade to insecure automatically as required.
 *
 * HTTP protocol handling is applied here for completeness, but it would work
 * via the [io.ktor.client.plugins.DefaultRequest] plugin.
 */
internal val AdaptiveProtocolPlugin by lazy {
    createClientPlugin("AdaptiveProtocolPlugin") {
        onRequest { request, _ ->
            val serverUrl = request.attributes[ServerUrlAttribute]
            val secure = serverUrl.startsWith("https")
            request.url.protocol = when (request.url.protocol) {
                WSS, WS -> if (secure) WSS else WS
                HTTPS, HTTP -> if (secure) HTTPS else HTTP
                else -> request.url.protocol
            }
        }
    }
}