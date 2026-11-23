package com.kai.agent.connections

/** Safe to display: no keys, fingerprints, response bodies, provider tokens or account metadata. */
enum class ComposioAccessState { ALLOWED, AUTHENTICATION_OR_SCOPE_DENIED, PERMISSION_DENIED, UNAVAILABLE }

data class ComposioAccessCheck(
    val id: String,
    val label: String,
    val state: ComposioAccessState,
    val statusCode: Int?,
    val detail: String
)

data class ComposioDiagnostics(val checks: List<ComposioAccessCheck>) {
    val hasAnyAccess: Boolean get() = checks.any { it.state == ComposioAccessState.ALLOWED }
    val canReadAccounts: Boolean get() = checks.any { it.id == "connected_accounts" && it.state == ComposioAccessState.ALLOWED }
    val canBrowseApps: Boolean get() = checks.any { it.id == "toolkits" && it.state == ComposioAccessState.ALLOWED }
    val summary: String get() {
        val denied = checks.filter { it.state == ComposioAccessState.AUTHENTICATION_OR_SCOPE_DENIED || it.state == ComposioAccessState.PERMISSION_DENIED }
        return when {
            checks.isNotEmpty() && checks.all { it.state == ComposioAccessState.ALLOWED } ->
                "All four read checks passed. Write permissions and connected-app authorization have not been tested."
            hasAnyAccess && denied.isNotEmpty() ->
                "The key works for some Composio APIs, but access was denied for ${denied.joinToString { it.label }}. Check this project's key permissions."
            hasAnyAccess -> "Some read checks passed; others could not be completed. No write permissions were tested."
            denied.isNotEmpty() -> "Composio denied the access checks. This does not distinguish an invalid or revoked key from a key type or permission problem."
            else -> "Could not complete the access checks. Check connectivity and try again."
        }
    }

    companion object {
        const val API_BASE = "https://backend.composio.dev/api/v3.1"
        const val AUTH_HEADER = "x-api-key"
        const val PROJECT_KEY_HELP_URL = "https://docs.composio.dev/reference/authenticating-to-composio"
        const val PERMISSIONS_HELP_URL = "https://docs.composio.dev/reference/authenticating-to-composio/project-api-key-permissions"
    }
}
