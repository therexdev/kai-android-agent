package com.kai.agent.connections.mcp
import org.json.JSONObject
/** Fail closed on unsupported schema features; never fetch external references or trust readOnlyHint. */
object McpSchema {
    private val keys=setOf("type","properties","required","additionalProperties","items","enum","minimum","maximum","minItems","maxItems","minLength","maxLength","description","title","default","\$schema")
    fun requireSupported(s: JSONObject, depth: Int=0) {
        require(depth<=12 && s.toString().length<=16000)
        require(s.keys().asSequence().all { it in keys }) { "Unsupported MCP tool schema feature." }
        s.optString("\$schema").takeIf { it.isNotBlank() }?.let { require(it in setOf("https://json-schema.org/draft/2020-12/schema","http://json-schema.org/draft-07/schema#")) }
        val type=s.opt("type")
        val supported=setOf("object","array","string","number","integer","boolean","null")
        if(type is String) require(type in supported)
        if(type is org.json.JSONArray) require(type.length() in 1..7 && (0 until type.length()).all { type.optString(it) in supported })
        s.optJSONObject("properties")?.let { p -> require(p.length()<=100);p.keys().asSequence().forEach { requireSupported(p.getJSONObject(it),depth+1) } }
        s.optJSONObject("items")?.let { requireSupported(it,depth+1) }
        s.optJSONObject("additionalProperties")?.let { requireSupported(it,depth+1) }
    }
}
