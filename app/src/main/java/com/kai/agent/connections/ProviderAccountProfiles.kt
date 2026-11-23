package com.kai.agent.connections

import org.json.JSONArray
import org.json.JSONObject

/** Fixed current-user reads only. Never accepts model/user URLs, IDs, headers, or credentials. */
internal object ProviderAccountProfiles {
    data class Read(val kind: String, val endpoint: String, val query: Map<String, String> = emptyMap(), val method: String = "GET") {
        fun request(accountId: String, ownerId: String): JSONObject = JSONObject()
            .put("connected_account_id", accountId).put("user_id", ownerId)
            .put("endpoint", endpoint).put("method", method)
            .put("parameters", JSONArray().apply { query.forEach { (key, value) ->
                put(JSONObject().put("name", key).put("value", value).put("in", "query"))
            } })
    }

    private val google = Read("google", "https://www.googleapis.com/oauth2/v2/userinfo")
    private val drive = Read("drive", "https://www.googleapis.com/drive/v3/about", mapOf("fields" to "user(displayName,emailAddress,me)"))
    fun reads(toolkit: String): List<Read> = when (toolkit.lowercase()) {
        "discord" -> listOf(Read("discord", "https://discord.com/api/v10/users/@me"))
        "youtube" -> listOf(Read("youtube", "https://www.googleapis.com/youtube/v3/channels",
            mapOf("part" to "snippet", "mine" to "true", "maxResults" to "2", "fields" to "items(id,snippet(title,customUrl)),nextPageToken")))
        "googledrive" -> listOf(drive, google)
        "googledocs", "googlesheets", "googlecalendar", "googletasks", "googlemeet", "googlephotos" -> listOf(google, drive)
        "github" -> listOf(Read("github", "https://api.github.com/user"))
        "outlook", "outlookcalendar", "onedrive", "microsoft_teams", "microsoftteams", "sharepoint" ->
            listOf(Read("microsoft", "https://graph.microsoft.com/v1.0/me", mapOf("\$select" to "id,displayName,mail,userPrincipalName")))
        "spotify" -> listOf(Read("spotify", "https://api.spotify.com/v1/me"))
        "slack" -> listOf(Read("slack", "https://slack.com/api/auth.test", method = "POST"))
        else -> emptyList()
    }

    fun displayName(toolkit: String): String = when (toolkit.lowercase()) {
        "youtube" -> "YouTube"
        "googledrive" -> "Google Drive"
        "googledocs" -> "Google Docs"
        "googlesheets" -> "Google Sheets"
        "googlecalendar" -> "Google Calendar"
        "googletasks" -> "Google Tasks"
        "googlemeet" -> "Google Meet"
        "googlephotos" -> "Google Photos"
        "github" -> "GitHub"
        "outlookcalendar" -> "Outlook Calendar"
        "onedrive" -> "OneDrive"
        "microsoft_teams", "microsoftteams" -> "Microsoft Teams"
        "sharepoint" -> "SharePoint"
        else -> toolkit.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }

    /** Proxy HTTP success alone is insufficient: require the provider's successful status too. */
    fun identity(kind: String, response: JSONObject): AccountIdentity? {
        if (response.toString().length > 32_768 || failed(response)) return null
        val envelope = if (response.has("status")) response else {
            if (response.opt("successful") != true) return null
            response.optJSONObject("data") ?: return null
        }
        if (failed(envelope) || envelope.opt("status") !is Number || envelope.optInt("status") != 200) return null
        val value = envelope.opt("data")
        val data = when (value) {
            is JSONObject -> value
            is String -> try { JSONObject(value) } catch (_: Exception) { return null }
            else -> return null
        }
        if (failed(data)) return null
        fun label(value: Any?) = AccountIdentity.safeDisplayName(value)
        fun email(value: Any?) = AccountIdentity.safeEmail(value)
        fun id(node: JSONObject, key: String = "id") = node.opt(key)?.takeIf { it != JSONObject.NULL }
            ?.toString()?.let { it.length in 1..200 && it.matches(Regex("[A-Za-z0-9_-]+")) } == true
        fun result(name: String?, mail: String? = null): AccountIdentity? {
            val safeName = label(name).orEmpty()
            val safeMail = email(mail).orEmpty()
            if (safeName.isBlank() && safeMail.isBlank()) return null
            return AccountIdentity(safeName.ifBlank { safeMail }, safeMail, "${kind}_profile")
        }
        return when (kind) {
            "discord" -> {
                if (!id(data)) return null
                val username = label(data.opt("username")) ?: return null
                val tag = label(data.opt("discriminator"))?.takeIf { it.matches(Regex("[0-9]{4}")) && it != "0000" }
                val handle = "@$username" + tag?.let { "#$it" }.orEmpty()
                val name = label(data.opt("global_name"))
                result((if (data.opt("bot") == true) "Bot · " else "") + if (name != null && name != username) "$name ($handle)" else handle)
            }
            "youtube" -> {
                val items = data.optJSONArray("items") ?: return null
                // More than one channel is ambiguous; never silently identify the first as the account.
                if (items.length() != 1 || !data.optString("nextPageToken").isNullOrBlank()) return null
                val channel = items.optJSONObject(0) ?: return null
                if (!id(channel)) return null
                val snippet = channel.optJSONObject("snippet") ?: return null
                val name = label(snippet.opt("title")) ?: return null
                val handle = label(snippet.opt("customUrl"))?.takeIf { it.startsWith('@') }
                result(if (handle != null && handle != name) "$name ($handle)" else name)
            }
            "drive" -> {
                val user = data.optJSONObject("user") ?: return null
                if (user.opt("me") != true) return null
                result(label(user.opt("displayName")), email(user.opt("emailAddress")))
            }
            "google" -> {
                if (!id(data)) return null
                result(label(data.opt("name")), if (data.opt("verified_email") == true) email(data.opt("email")) else null)
            }
            "github" -> if (id(data)) result(label(data.opt("login"))?.let { "@$it" }) else null
            "microsoft" -> if (id(data)) result(label(data.opt("displayName")), email(data.opt("mail")) ?: email(data.opt("userPrincipalName"))) else null
            "spotify" -> if (id(data)) result(label(data.opt("display_name")) ?: label(data.opt("id"))) else null
            "slack" -> {
                if (data.opt("ok") != true || !id(data, "user_id") || !id(data, "team_id")) return null
                val user = label(data.opt("user")) ?: return null
                val team = label(data.opt("team")) ?: return null
                result("$team · @$user")
            }
            else -> null
        }
    }

    private fun failed(value: JSONObject): Boolean =
        listOf("error", "errors").any { value.has(it) && value.opt(it) != JSONObject.NULL && value.opt(it) != "" } ||
            listOf("successful", "success").any { value.has(it) && value.opt(it) != true } ||
            value.optString("resultType") == "blocked"
}
