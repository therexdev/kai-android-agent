package com.kai.agent.inference

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Endpoint is the OpenAI-compatible API base. A relay prefix is preserved. */
object EndpointPolicy {
    private val localHttpHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    fun base(endpoint: String): HttpUrl {
        val raw = endpoint.trim()
        val parsed = raw.toHttpUrlOrNull() ?: throw InferenceException("Enter a valid HTTPS API endpoint.")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
            throw InferenceException("The API endpoint must not contain credentials, a query or a fragment.")
        }
        if (!parsed.isHttps && parsed.host !in localHttpHosts) {
            throw InferenceException("Use HTTPS for a remote API. Plain HTTP is supported only for a local test endpoint.")
        }
        val path = parsed.encodedPath.trimEnd('/')
        val apiPath = when {
            path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions")
            path.endsWith("/models") -> path.removeSuffix("/models")
            path.endsWith("/v1") -> path
            path.isEmpty() -> "/v1"
            else -> path // Custom relay/provider bases may already include a version or route prefix.
        }
        return parsed.newBuilder().encodedPath("$apiPath/").build()
    }

    fun resource(endpoint: String, resource: String): HttpUrl =
        base(endpoint).newBuilder().addPathSegments(resource).build()

    fun validateRoute(config: ProviderConfig) {
        if(config.accountSession && config.endpoint != com.kai.agent.account.CREDIT_ORIGIN + "/platform/api") throw InferenceException("Account sessions can only be sent to Koinos AI.")
        if (config.provider == ProviderId.KOINOS && !config.accountSession && config.routing != RoutingMode.LOCAL_ONLY) {
            throw InferenceException("This Koinos AI API currently supports Local only. Network routing needs a verified API contract before it can be enabled.")
        }
    }
}
