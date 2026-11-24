package dev.spendingtracker.shared.client

import dev.spendingtracker.shared.ApiJson
import dev.spendingtracker.shared.api.ApiError
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

/**
 * The API client mobile apps share. Sends the bearer token from [tokenStore] on every request, decodes non-2xx
 * bodies into a typed [ApiException], and clears the token on a 401 (the session is gone; log in again).
 *
 * Paths are absolute against [baseUrl] (e.g. "/api/auth/token"), so the same call works for any server the
 * user points the app at. [baseUrl] is the server's root (e.g. "http://10.0.2.2:8080"): a server mounted
 * under a sub-path would lose it, so front it at the root (as this app's own deploy does with its host).
 */
class ApiClient(
    val baseUrl: String,
    val tokenStore: TokenStore,
    engine: HttpClientEngine? = null,
) {
    internal val http: HttpClient = HttpClient(engine ?: defaultEngine()) {
        install(ContentNegotiation) { json(ApiJson) }
        // A hung server must surface as an error, not spin a spinner forever.
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
        defaultRequest { url(baseUrl) }
        install(createClientPlugin("BearerToken") {
            onRequest { request, _ ->
                tokenStore.get()?.let { request.header(HttpHeaders.Authorization, "Bearer $it") }
            }
        })
    }

    /** Runs [block], maps a non-2xx response to [ApiException] and clears the token on 401, then reads [body]. */
    @PublishedApi
    internal suspend fun <T> execute(block: suspend HttpClient.() -> HttpResponse, body: suspend (HttpResponse) -> T): T {
        val response = http.block()
        if (response.status == HttpStatusCode.Unauthorized) tokenStore.clear()
        if (!response.status.isSuccess()) throw ApiException(response.status.value, response.errorBody())
        return body(response)
    }

    /**
     * The error body as [ApiError]. Non-JSON bodies (a proxy's HTML error page, a plain-text 404) fall back to
     * `ApiError("http_<status>", text)` so the caller always gets a typed [ApiException].
     */
    private suspend fun HttpResponse.errorBody(): ApiError {
        val text = bodyAsText()
        return runCatching { ApiJson.decodeFromString<ApiError>(text) }
            .getOrElse { ApiError("http_${status.value}", text.take(200).ifEmpty { null }) }
    }

    suspend inline fun <reified T> get(path: String, crossinline config: HttpRequestBuilder.() -> Unit = {}): T =
        execute({ get(path, config) }) { it.body() }

    suspend inline fun <reified T> post(path: String, crossinline config: HttpRequestBuilder.() -> Unit = {}): T =
        execute({ post(path, config) }) { it.body() }

    suspend inline fun <reified T> put(path: String, crossinline config: HttpRequestBuilder.() -> Unit = {}): T =
        execute({ put(path, config) }) { it.body() }

    /** For DELETE endpoints with no response body (204). */
    suspend inline fun delete(path: String, crossinline config: HttpRequestBuilder.() -> Unit = {}) =
        execute({ delete(path, config) }) { }

    /** For POST endpoints with no response body (204), like logout. */
    suspend inline fun postNoContent(path: String, crossinline config: HttpRequestBuilder.() -> Unit = {}) =
        execute({ post(path, config) }) { }
}

/**
 * Sets a JSON request body (the standard content type for every API call that carries one). Reified, so the
 * serializer is chosen from the compile-time type — runtime-type guessing isn't guaranteed on Kotlin/Native.
 */
inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
    contentType(ContentType.Application.Json)
    setBody(value)
}

/** The platform's HTTP engine: OkHttp on Android, Darwin on iOS, Java on the JVM (tests). */
internal expect fun defaultEngine(): HttpClientEngine
