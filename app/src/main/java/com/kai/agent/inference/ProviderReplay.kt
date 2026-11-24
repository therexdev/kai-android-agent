package com.kai.agent.inference

import org.json.JSONArray
import org.json.JSONObject

/** Opaque gateway ciphertext. Never display, summarize, export or use as permission. */
@ConsistentCopyVisibility
data class ProviderReplay private constructor(val token: String, val route: String) {
    override fun toString() = "ProviderReplay(<private>)"
    companion object {
        fun read(message: JSONObject?, route: String? = null): ProviderReplay? {
            if (message == null || !message.has("kai_replay")) return null
            val value = message.get("kai_replay")
            require(value is String && value.length <= 34000 && value.matches(Regex("kr1\\.[A-Za-z0-9_-]+"))) { "Invalid provider continuation." }
            return ProviderReplay(value,route ?: message.optString("kai_replay_route"))
        }
        /** Strip opaque state even when an encrypted archive is represented as a JSON string. */
        fun publicCopy(value: Any?): Any = when(value) {
            is JSONObject -> JSONObject().also { out -> value.keys().forEach { k -> if(k !in setOf("kai_replay","kai_replay_route")) out.put(k,publicCopy(value.opt(k))) } }
            is JSONArray -> JSONArray().also { out -> for(i in 0 until value.length()) out.put(publicCopy(value.opt(i))) }
            is String -> {
                val nested = if(value.contains("kai_replay") && (value.trimStart().startsWith("{") || value.trimStart().startsWith("[")))
                    runCatching { org.json.JSONTokener(value).nextValue() }.getOrNull() else null
                if(nested is JSONObject || nested is JSONArray) publicCopy(nested).toString()
                else value.replace(Regex("kr1\\.[A-Za-z0-9_-]+"),"[provider continuation omitted]")
            }
            else -> value ?: JSONObject.NULL
        }
    }
}

/** Old booleans alone do not qualify a managed route. Expired evidence disables new turns. */
object NativeQualification {
    fun read(model: JSONObject, now: Long = System.currentTimeMillis()): JSONObject? {
        val q=model.optJSONObject("nativeQualification") ?: return null
        return q.takeIf { model.optInt("nativeTools")==1 && it.optInt("version")==2 && it.optString("replay")=="sealed-v1" &&
            it.optString("qualificationId").matches(Regex("[a-f0-9]{64}")) && it.optLong("checkedAt") in 1..now &&
            it.optLong("expiresAt")>now && it.optLong("expiresAt")-it.optLong("checkedAt") in 1..604800000 }
    }
}
