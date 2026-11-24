package com.kai.agent.runtime
import org.json.JSONArray
import org.json.JSONObject
/** Draft extraction never constructs arguments or grants authority; exact action arguments remain canonical. */
object DraftContent {
    fun extract(value: Any?, depth: Int = 0): String? {
        if(depth>12) return null
        return when(value) {
            is JSONObject -> value.keys().asSequence().mapNotNull { key ->
                val child=value.opt(key)
                if(key in setOf("text","content","markdown_text","markdown") && child is String && child.length>=40) child
                else if(child is JSONObject || child is JSONArray) extract(child,depth+1) else null
            }.maxByOrNull { it.length }?.take(48000)
            is JSONArray -> (0 until minOf(value.length(),1000)).mapNotNull { extract(value.opt(it),depth+1) }.maxByOrNull { it.length }
            else -> null
        }
    }
}
