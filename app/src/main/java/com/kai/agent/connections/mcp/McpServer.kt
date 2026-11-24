package com.kai.agent.connections.mcp
import com.kai.agent.data.MemoryRules
import com.kai.agent.web.PublicWebPolicy
import org.json.JSONObject
/** User-owned credentials. Never exported, logged or included in prompts. */
data class McpServer(val id: String,val name: String,val endpoint: String,val token: String = "",val protocol: String = "2026-07-28",val enabled: Boolean = true) {
    val accountId get()="mcp:$id"
    val generation get()=MemoryRules.digest("$id:$endpoint:$token:$protocol:$enabled")
    override fun toString()="McpServer(id=$id,credentials=<redacted>)"
    fun validate() {
        require(Regex("[a-zA-Z0-9_-]{1,64}").matches(id) && name.length in 1..100)
        val url=PublicWebPolicy.url(endpoint)
        require(url.query==null && url.fragment==null) { "Use an MCP endpoint without query credentials; put credentials in the token field." }
        require(token.length<=4096 && token.none { it=='\r' || it=='\n' })
        require(protocol in setOf("2026-07-28","2025-11-25"))
    }
    fun json(secrets: Boolean)=JSONObject().put("id",id).put("name",name).put("endpoint",endpoint)
        .put("token",if(secrets) token else "").put("protocol",protocol).put("enabled",secrets && enabled)
    companion object {
        fun read(o: JSONObject)=McpServer(o.getString("id"),o.getString("name"),o.getString("endpoint"),o.optString("token"),o.optString("protocol","2026-07-28"),o.optBoolean("enabled")).also { it.validate() }
    }
}
