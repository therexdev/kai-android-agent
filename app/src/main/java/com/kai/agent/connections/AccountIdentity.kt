package com.kai.agent.connections

import org.json.JSONObject

/** A small allowlisted projection, never a recursive scan of OAuth credential state. */
data class AccountIdentity(
    val providerDisplayName: String = "",
    val emailAddress: String = "",
    val source: String = ""
) {
    fun applyTo(account: ConnectionAccount): ConnectionAccount = account.copy(
        providerDisplayName = providerDisplayName,
        emailAddress = emailAddress,
        identitySource = source
    )

    companion object {
        const val SOURCE_ACCOUNT_DISPLAY_NAME = "composio_account_display_name"
        const val SOURCE_GMAIL_PROFILE = "gmail_profile"
        private const val MAX_DISPLAY_NAME = 256
        private const val MAX_PROFILE_JSON = 32_768
        private const val MAX_PROFILE_DEPTH = 4
        private const val MAX_PROFILE_NODES = 12
        private val mailbox = Regex("^[A-Za-z0-9.!#$%&'+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$")
        private val masked = Regex("\\*|[•●]{2,}|(?i)\\[?redacted\\]?|(?i)\\[?masked\\]?")

        /** Composio documents state.val.displayName as its provider identity label. */
        fun fromAccount(raw: JSONObject, toolkit: String): AccountIdentity? {
            val value = raw.optJSONObject("state")?.optJSONObject("val")?.opt("displayName")
            val display = safeDisplayName(value) ?: return null
            if (display == raw.optString("alias") || display == raw.optString("word_id")) return null
            return AccountIdentity(
                providerDisplayName = display,
                emailAddress = if (toolkit.equals("gmail", ignoreCase = true)) safeEmail(display).orEmpty() else "",
                source = SOURCE_ACCOUNT_DISPLAY_NAME
            )
        }

        /**
         * Accept only a successful GET_PROFILE envelope and its documented emailAddress field.
         * Bounded data/response wrappers allow SDK serialization without interpreting arbitrary
         * credential keys, message bodies, previews, or unverified aliases as mailbox identity.
         */
        fun fromGmailProfile(response: JSONObject): AccountIdentity? {
            if (response.opt("successful") != true || hasError(response) || response.toString().length > MAX_PROFILE_JSON) return null
            val values = linkedSetOf<String>()
            var nodes = 0
            var invalid = false
            fun visit(value: Any?, depth: Int) {
                if (value == null || value == JSONObject.NULL || invalid) return
                if (depth > MAX_PROFILE_DEPTH || ++nodes > MAX_PROFILE_NODES) { invalid = true; return }
                val node = when (value) {
                    is JSONObject -> value
                    is String -> {
                        if (value.length > MAX_PROFILE_JSON) { invalid = true; return }
                        try { JSONObject(value) } catch (_: Exception) { invalid = true; return }
                    }
                    else -> { invalid = true; return }
                }
                if (hasError(node) || (node.has("successful") && node.opt("successful") != true) ||
                    (node.has("success") && node.opt("success") != true)) { invalid = true; return }
                if (node.has("emailAddress")) {
                    val email = safeEmail(node.opt("emailAddress"))
                    if (email == null) { invalid = true; return }
                    values += email
                }
                for (key in listOf("data", "response", "response_data")) {
                    if (node.has(key)) visit(node.opt(key), depth + 1)
                }
            }
            visit(response.opt("data"), 0)
            if (invalid || values.size != 1) return null
            val email = values.single()
            return AccountIdentity(email, email, SOURCE_GMAIL_PROFILE)
        }

        internal fun safeDisplayName(value: Any?): String? {
            if (value !is String || value.length > MAX_DISPLAY_NAME || value.any {
                    Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() || it == '\u2028' || it == '\u2029'
                }) return null
            val text = value.trim()
            if (text.isEmpty() || masked.containsMatchIn(text)) return null
            return text
        }

        internal fun safeEmail(value: Any?): String? {
            val email = safeDisplayName(value) ?: return null
            if (email.length > 254 || !mailbox.matches(email)) return null
            val local = email.substringBefore('@')
            if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || ".." in local) return null
            if (email.substringAfter('@').split('.').any { it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
            return email
        }

        private fun hasError(value: JSONObject): Boolean = listOf("error", "errors").any { key ->
            val error = value.opt(key)
            error != null && error != JSONObject.NULL && error != ""
        }
    }
}
